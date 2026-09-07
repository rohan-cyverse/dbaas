package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.model.DatabaseEngine;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MongoDbBackupEngineStrategy implements BackupEngineStrategy {
    @Override public DatabaseEngine engine() { return DatabaseEngine.MONGODB; }
    @Override public String manualFullMethod() { return "dump"; }
    @Override public List<String> futureIncrementalMethods() { return List.of("pbm-physical"); }
    @Override public List<String> futureContinuousMethods() { return List.of("archive-oplog", "pbm-pitr"); }
}
