/**
 * Copyright 2016 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.keyvalue.cassandra;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.cassandra.thrift.CASResult;
import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Longs;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.cassandra.CassandraKeyValueServiceConfigManager;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.common.base.FunctionCheckedException;
import com.palantir.common.base.Throwables;

final class SchemaMutationLock {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMutationLock.class);

    private static final String GLOBAL_DDL_LOCK_FORMAT = "%1$d_%2$d";
    private static final long GLOBAL_DDL_LOCK_CLEARED_ID = Long.MAX_VALUE;
    private static final String GLOBAL_DDL_LOCK_CLEARED_VALUE =
            lockValueFromIdAndHeartbeat(GLOBAL_DDL_LOCK_CLEARED_ID, 0);

    private final boolean supportsCas;
    private final CassandraKeyValueServiceConfigManager configManager;
    private final CassandraClientPool clientPool;
    private final TracingQueryRunner queryRunner;
    private final ConsistencyLevel writeConsistency;
    private final UniqueSchemaMutationLockTable lockTable;
    private final ReentrantLock schemaMutationLockForEarlierVersionsOfCassandra = new ReentrantLock(true);
    private final HeartbeatService heartbeatService;

    SchemaMutationLock(
            boolean supportsCas,
            CassandraKeyValueServiceConfigManager configManager,
            CassandraClientPool clientPool,
            TracingQueryRunner queryRunner,
            ConsistencyLevel writeConsistency,
            UniqueSchemaMutationLockTable lockTable,
            HeartbeatService heartbeatService) {
        this.supportsCas = supportsCas;
        this.configManager = configManager;
        this.clientPool = clientPool;
        this.queryRunner = queryRunner;
        this.writeConsistency = writeConsistency;
        this.lockTable = lockTable;
        this.heartbeatService = heartbeatService;
    }

    public interface Action {
        void execute() throws Exception;
    }

    void runWithLock(Action action) {
        if (!supportsCas) {
            runWithLockWithoutCas(action);
            return;
        }

        long lockId = waitForSchemaMutationLock();
        heartbeatService.startBeatingForLock(lockId);
        try {
            action.execute();
        } catch (Exception e) {
            throw Throwables.throwUncheckedException(e);
        } finally {
            heartbeatService.stopBeating();
            schemaMutationUnlock(lockId);
        }
    }

    private void runWithLockWithoutCas(Action action) {
        LOGGER.info("Because your version of Cassandra does not support check and set,"
                + " we will use a java level lock to synchronise schema mutations."
                + " If this is a clustered service, this could lead to corruption.");
        try {
            waitForSchemaMutationLockWithoutCas();
        } catch (TimeoutException e) {
            throw Throwables.throwUncheckedException(e);
        }

        try {
            action.execute();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Throwables.throwUncheckedException(e);
        } catch (Exception e) {
            throw Throwables.throwUncheckedException(e);
        } finally {
            schemaMutationUnlockWithoutCas();
        }

    }

    /**
     * Each locker generates a random ID per operation and uses a CAS operation as a DB-side lock.
     *
     * There are two special ID values used for book-keeping
     * - one representing the lock being cleared
     * - one representing a remote ID that is guaranteed to never be generated
     * This is required because Cassandra CAS does not treat setting to null / empty as a delete,
     * (though there is a to-do in their code to possibly add this)
     * though it accepts an empty expected column to mean that non-existance was expected
     * (see putUnlessExists for example, which has the luck of not having to ever deal with deleted values)
     *
     * I can't hold this against them though, because Atlas has a semi-similar problem with empty byte[] values
     * meaning deleted internally.
     *
     * @return an ID to be passed into a subsequent unlock call
     */
    private long waitForSchemaMutationLock() {
        final long perOperationNodeId = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE - 2);

        try {
            clientPool.runWithRetry((FunctionCheckedException<Cassandra.Client, Void, Exception>) client -> {
                Column ourUpdate = lockColumnFromIdAndHeartbeat(perOperationNodeId, 0);

                List<Column> expected = ImmutableList.of(lockColumnWithValue(GLOBAL_DDL_LOCK_CLEARED_VALUE));

                CASResult casResult = writeDdlLockWithCas(client, expected, ourUpdate);

                Column lastSeenColumn = null;
                int timesAttempted = 0;

                // We use schemaMutationTimeoutMillis to wait for schema mutations to agree as well as
                // to specify the timeout period before we give up trying to acquire the schema mutation lock
                int mutationTimeoutMillis = configManager.getConfig().schemaMutationTimeoutMillis()
                        * CassandraConstants.SCHEMA_MUTATION_LOCK_TIMEOUT_MULTIPLIER;
                Stopwatch stopwatch = Stopwatch.createStarted();

                // could have a timeout controlling this level, confusing for users to set both timeouts though
                while (!casResult.isSuccess()) {
                    if (casResult.getCurrent_valuesSize() == 0) { // never has been an existing lock
                        // special case, no one has ever made a lock ever before
                        // this becomes analogous to putUnlessExists now
                        expected = ImmutableList.of();
                    } else {
                        Column existingColumn = Iterables.getOnlyElement(casResult.getCurrent_values(), null);
                        if (existingColumn == null) {
                            throw new IllegalStateException("Something is wrong with underlying locks."
                                    + " Contact support for guidance on manually examining and clearing"
                                    + " locks from " + lockTable.getOnlyTable() + " table.");
                        }
                        if (lastSeenColumn == null || !existingColumn.equals(lastSeenColumn)) {
                            LOGGER.debug("Heartbeat alive, will retry.");
                            lastSeenColumn = existingColumn;
                        } else {
                            // dead heartbeat
                            throw Throwables.rewrapAndThrowUncheckedException(generateDeadHeartbeatException());
                        }

                        expected = getExpectedCasResult(existingColumn);
                    }

                    // lock holder taking unreasonable amount of time, signal something's wrong
                    if (stopwatch.elapsed(TimeUnit.MILLISECONDS) > mutationTimeoutMillis) {
                        TimeoutException schemaLockTimeoutError = generateSchemaLockTimeoutException(stopwatch);
                        LOGGER.error(schemaLockTimeoutError.getMessage(), schemaLockTimeoutError);
                        throw Throwables.rewrapAndThrowUncheckedException(schemaLockTimeoutError);
                    }

                    long timeToSleep = CassandraConstants.TIME_BETWEEN_LOCK_ATTEMPT_ROUNDS_MILLIS
                            * (long) Math.pow(2, timesAttempted++);
                    Thread.sleep(timeToSleep);
                    casResult = writeDdlLockWithCas(client, expected, ourUpdate);
                }

                // we won the lock!
                LOGGER.info("Successfully acquired schema mutation lock.");
                return null;
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Throwables.throwUncheckedException(e);
        } catch (Exception e) {
            throw Throwables.throwUncheckedException(e);
        }
        return perOperationNodeId;
    }

    private List<Column> getExpectedCasResult(Column existingColumn) {
        // handle old (pre-heartbeat) cleared lock values encountered during migrations
        if (Longs.fromByteArray(existingColumn.getValue()) == GLOBAL_DDL_LOCK_CLEARED_ID) {
            return ImmutableList.of(lockColumnWithValue(
                    Longs.toByteArray(GLOBAL_DDL_LOCK_CLEARED_ID)));
        } else {
            return ImmutableList.of(lockColumnWithValue(GLOBAL_DDL_LOCK_CLEARED_VALUE));
        }
    }

    private RuntimeException generateDeadHeartbeatException() {
        return new RuntimeException("The current lock holder has failed to update its heartbeat."
                + " We suspect that this might be due to a node crashing while holding the"
                + " schema mutation lock. If this is indeed the case, run the clean-cass-locks-state"
                + " cli command.");
    }

    private TimeoutException generateSchemaLockTimeoutException(Stopwatch stopwatch) {
        return new TimeoutException(
                String.format("We have timed out waiting on the current"
                                + " schema mutation lock holder. We have tried to grab the lock for %d milliseconds"
                                + " unsuccessfully. This indicates that the current lock holder has died without"
                                + " releasing the lock and will require manual intervention. Shut down all AtlasDB"
                                + " clients operating on the %s keyspace and then run the clean-cass-locks-state"
                                + " cli command.",
                        stopwatch.elapsed(TimeUnit.MILLISECONDS),
                        configManager.getConfig().keyspace()));
    }

    private void waitForSchemaMutationLockWithoutCas() throws TimeoutException {
        String message = "AtlasDB was unable to get a lock on Cassandra system schema mutations"
                + " for your cluster. Likely cause: Service(s) performing heavy schema mutations"
                + " in parallel, or extremely heavy Cassandra cluster load.";
        try {
            if (!schemaMutationLockForEarlierVersionsOfCassandra.tryLock(
                    configManager.getConfig().schemaMutationTimeoutMillis(),
                    TimeUnit.MILLISECONDS)) {
                throw new TimeoutException(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TimeoutException(message);
        }
    }

    private void schemaMutationUnlock(long perOperationNodeId) {
        try {
            clientPool.runWithRetry((FunctionCheckedException<Cassandra.Client, Void, TException>) client -> {
                int heartbeatCount = heartbeatService.getCurrentHeartbeatCount();
                Column lockColumn = lockColumnFromIdAndHeartbeat(perOperationNodeId, heartbeatCount);
                List<Column> ourExpectedLock = ImmutableList.of(lockColumn);
                Column clearedLock = lockColumnWithValue(GLOBAL_DDL_LOCK_CLEARED_VALUE);
                CASResult casResult = writeDdlLockWithCas(client, ourExpectedLock, clearedLock);

                if (!casResult.isSuccess()) {
                    handleForcedLockClear(casResult, perOperationNodeId, heartbeatCount);
                }
                LOGGER.info("Successfully released schema mutation lock.");
                return null;
            });
        } catch (TException e) {
            throw Throwables.throwUncheckedException(e);
        }
    }

    private void schemaMutationUnlockWithoutCas() {
        schemaMutationLockForEarlierVersionsOfCassandra.unlock();
    }

    private CASResult writeDdlLockWithCas(
            Cassandra.Client client,
            List<Column> expectedLockValue,
            Column newLockValue) throws TException {
        TableReference lockTableRef = lockTable.getOnlyTable();
        return queryRunner.run(client, lockTableRef,
                () -> client.cas(
                        getGlobalDdlLockRowName(),
                        lockTableRef.getQualifiedName(),
                        expectedLockValue,
                        ImmutableList.of(newLockValue),
                        ConsistencyLevel.SERIAL,
                        writeConsistency));
    }

    static void handleForcedLockClear(CASResult casResult, long perOperationNodeId, int heartbeatCount) {
        Preconditions.checkState(casResult.getCurrent_valuesSize() == 1,
                "Something is wrong with the underlying locks. Contact support for guidance.");

        String remoteLock = "(unknown)";
        Column column = Iterables.getOnlyElement(casResult.getCurrent_values(), null);
        if (column != null) {
            String remoteValue = new String(column.getValue(), StandardCharsets.UTF_8);
            long remoteId = Long.parseLong(remoteValue.split("_")[0]);
            remoteLock = (remoteId == GLOBAL_DDL_LOCK_CLEARED_ID)
                    ? "(Cleared Value)"
                    : remoteValue;
        }

        String expectedLock = lockValueFromIdAndHeartbeat(perOperationNodeId, heartbeatCount);
        throw new IllegalStateException(String.format("Another process cleared our schema mutation lock from"
                + " underneath us. Our ID, which we expected, was %s, the value we saw in the database"
                + " was instead %s.", expectedLock, remoteLock));
    }

    private static Column lockColumnWithValue(byte[] value) {
        return new Column()
                .setName(CassandraKeyValueServices.makeCompositeBuffer(
                        CassandraConstants.GLOBAL_DDL_LOCK_COLUMN_NAME.getBytes(StandardCharsets.UTF_8),
                        AtlasDbConstants.TRANSACTION_TS).array())
                .setValue(value) // expected previous
                .setTimestamp(AtlasDbConstants.TRANSACTION_TS);
    }

    static Column lockColumnWithValue(String strValue) {
        return lockColumnWithValue(strValue.getBytes(StandardCharsets.UTF_8));
    }

    static String lockValueFromIdAndHeartbeat(long id, int heartbeatCount) {
        return String.format(GLOBAL_DDL_LOCK_FORMAT, id, heartbeatCount);
    }

    static ByteBuffer getGlobalDdlLockRowName() {
        return ByteBuffer.wrap(CassandraConstants.GLOBAL_DDL_LOCK_ROW_NAME.getBytes(StandardCharsets.UTF_8));
    }

    static Column lockColumnFromIdAndHeartbeat(long id, int heartbeatCount) {
        return lockColumnWithValue(lockValueFromIdAndHeartbeat(id, heartbeatCount));
    }
}
