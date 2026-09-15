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
        // Current KubeBlocks MongoDB addons publish dump and archive-oplog for
        // both the regular and sharded BackupPolicyTemplates. Runtime template
        // validation remains authoritative for the installed addon version.
        return mode == DatabaseMode.STANDALONE || mode == DatabaseMode.REPLICA_SET
                || mode == DatabaseMode.SHARDING;
    }

    @Override public boolean supportsPitrTopology(DatabaseMode mode) {
        return supportsTopology(mode);
    }
}
