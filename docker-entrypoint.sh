#!/bin/bash
set -e

# Write the node ID to myid file
if [ -n "$ZOO_MY_ID" ]; then
    echo "$ZOO_MY_ID" > "$ZOO_DATA_DIR/myid"
fi

# Write zoo.cfg configuration file
cat <<EOF > "$ZOO_HOME/conf/zoo.cfg"
tickTime=2000
initLimit=10
syncLimit=5
dataDir=$ZOO_DATA_DIR
dataLogDir=$ZOO_LOG_DIR
clientPort=2181
admin.enableServer=true
EOF

# Append ensemble servers if provided
if [ -n "$ZOO_SERVERS" ]; then
    for server in $ZOO_SERVERS; do
        echo "$server" >> "$ZOO_HOME/conf/zoo.cfg"
    done
fi

exec "$@"