#!/usr/bin/env bash

set -x

function checkDocsBuild {
  cd docs/
  make html
}

# Container 1
CONTAINER_1=(':atlasdb-cassandra-integration-tests:check -D:atlasdb-cassandra-integration-tests:test.single=CassandraSuite' ':atlasdb-tests-shared:check')

# Container 2
CONTAINER_2=(':atlasdb-ete-tests:check' ':atlasdb-config:check')

#Container 3
CONTAINER_3=(':atlasdb-cassandra-integration-tests:check -D:atlasdb-cassandra-integration-tests:test.single=CassandraTransactionsSuite' ':lock-impl:check' ':atlasdb-dbkvs-tests:check')

CONTAINER_4=(':atlasdb-dropwizard-tests:check' ':atlasdb-cassandra:check' ':atlasdb-ete-test-utils:check')

CONTAINER_5=(':atlasdb-dbkvs:check' ':atlasdb-cassandra-multinode-tests:check' ':atlasdb-impl-shared:check' ':atlasdb-dropwizard-bundle:check' ':atlasdb-api:check')

# Container 0 - runs tasks not found in the below containers
CONTAINER_0_EXCLUDE=("${CONTAINER_1[@]}" "${CONTAINER_2[@]}" "${CONTAINER_3[@]}" "${CONTAINER_4[@]}"  "${CONTAINER_5[@]}")

for task in "${CONTAINER_0_EXCLUDE[@]}"
do
    CONTAINER_0_EXCLUDE_ARGS="$CONTAINER_0_EXCLUDE_ARGS -x $task"
done

case $CIRCLE_NODE_INDEX in
    0) ./gradlew --profile --continue check $CONTAINER_0_EXCLUDE_ARGS ;;
    1) ./gradlew --profile --continue --parallel ${CONTAINER_1[@]} ;;
    2) ./gradlew --profile --continue --parallel ${CONTAINER_2[@]} ;;
    3) ./gradlew --profile --continue ${CONTAINER_3[@]} && checkDocsBuild ;;
    4) ./gradlew --profile --continue --parallel ${CONTAINER_4[@]} ;;
    5) ./gradlew --profile --continue --parallel ${CONTAINER_5[@]} ;;
esac
