package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.DatabaseEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class BackupEngineStrategies {
    private final Map<DatabaseEngine, BackupEngineStrategy> strategies;

    public BackupEngineStrategies(List<BackupEngineStrategy> candidates) {
        Map<DatabaseEngine, BackupEngineStrategy> selected = new EnumMap<>(DatabaseEngine.class);
        for (BackupEngineStrategy candidate : candidates) {
            if (selected.put(candidate.engine(), candidate) != null) {
                throw new IllegalStateException("Duplicate backup strategy for " + candidate.engine());
            }
        }
        this.strategies = Map.copyOf(selected);
    }

    public BackupEngineStrategy require(DatabaseEngine engine) {
        BackupEngineStrategy strategy = strategies.get(engine);
        if (strategy == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "BACKUP_ENGINE_UNSUPPORTED", false,
                    "Backups are not supported for this database engine.");
        }
        return strategy;
    }
}
