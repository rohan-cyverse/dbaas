package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MySqlBackupEngineStrategy implements BackupEngineStrategy {
    @Override public DatabaseEngine engine() { return DatabaseEngine.MYSQL; }
    @Override public String manualFullMethod() { return "xtrabackup"; }
    @Override public String continuousMethod() { return "archive-binlog"; }
    @Override public List<String> futureIncrementalMethods() { return List.of("xtrabackup-inc"); }
    @Override public List<String> futureContinuousMethods() { return List.of("archive-binlog"); }
    @Override public boolean supportsTopology(DatabaseMode mode) {
        return mode == DatabaseMode.STANDALONE || mode == DatabaseMode.REPLICATION;
    }
}
