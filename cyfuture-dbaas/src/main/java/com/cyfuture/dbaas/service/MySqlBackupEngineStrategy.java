package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.model.DatabaseEngine;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MySqlBackupEngineStrategy implements BackupEngineStrategy {
    @Override public DatabaseEngine engine() { return DatabaseEngine.MYSQL; }
    @Override public String manualFullMethod() { return "xtrabackup"; }
    @Override public List<String> futureIncrementalMethods() { return List.of("xtrabackup-inc"); }
    @Override public List<String> futureContinuousMethods() { return List.of("archive-binlog"); }
}
