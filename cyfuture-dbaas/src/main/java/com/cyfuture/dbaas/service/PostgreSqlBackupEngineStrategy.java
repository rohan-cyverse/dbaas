package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.model.DatabaseEngine;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PostgreSqlBackupEngineStrategy implements BackupEngineStrategy {
    @Override public DatabaseEngine engine() { return DatabaseEngine.POSTGRESQL; }
    @Override public String manualFullMethod() { return "pg-basebackup"; }
    @Override public List<String> futureIncrementalMethods() { return List.of("wal-g-incremental"); }
    @Override public List<String> futureContinuousMethods() { return List.of("archive-wal"); }
}
