package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MongoDbBackupEngineStrategy implements BackupEngineStrategy {
    @Override public DatabaseEngine engine() { return DatabaseEngine.MONGODB; }
    @Override public String manualFullMethod() { return "dump"; }
    @Override public String continuousMethod() { return "archive-oplog"; }
    @Override public List<String> futureIncrementalMethods() { return List.of("pbm-physical"); }
    @Override public List<String> futureContinuousMethods() { return List.of("archive-oplog", "pbm-pitr"); }
    @Override public boolean supportsTopology(DatabaseMode mode) {
        // KubeBlocks' logical dump method is available for both standalone and
        // replica-set MongoDB clusters. Sharded backups need a topology-aware
        // method and remain explicitly unsupported here.
        return mode == DatabaseMode.STANDALONE || mode == DatabaseMode.REPLICA_SET;
    }

    @Override public boolean supportsPitrTopology(DatabaseMode mode) {
        // The installed archive-oplog template is supported only for a replica set.
        return mode == DatabaseMode.REPLICA_SET;
    }
}
