package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupEngineStrategiesTest {
    private final BackupEngineStrategies strategies = new BackupEngineStrategies(List.of(
            new PostgreSqlBackupEngineStrategy(), new MySqlBackupEngineStrategy(), new MongoDbBackupEngineStrategy()));

    @Test
    void selectsInstalledFullBackupMethodsForAllSupportedEngines() {
        assertEquals("pg-basebackup", strategies.require(DatabaseEngine.POSTGRESQL).manualFullMethod());
        assertEquals("xtrabackup", strategies.require(DatabaseEngine.MYSQL).manualFullMethod());
        assertEquals("dump", strategies.require(DatabaseEngine.MONGODB).manualFullMethod());
    }

    @Test
    void allowsMongoStandaloneAndReplicaSetForDumpBackup() {
        var mongo = strategies.require(DatabaseEngine.MONGODB);
        assertTrue(mongo.supportsTopology(DatabaseMode.STANDALONE));
        assertTrue(mongo.supportsTopology(DatabaseMode.REPLICA_SET));
        assertFalse(mongo.supportsTopology(DatabaseMode.SHARDING));
    }
}
