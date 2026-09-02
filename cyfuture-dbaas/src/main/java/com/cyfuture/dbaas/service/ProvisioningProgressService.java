package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.ProvisioningStage;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Keeps database and create-operation progress consistent. */
@Service
@RequiredArgsConstructor
public class ProvisioningProgressService {
    private final DatabaseMetadataRepository databaseRepository;
    private final OperationMetadataRepository operationRepository;

    public void update(DatabaseMetadata database, ProvisioningStage stage,
                       int progress, String message) {
        if (database.getDesiredStatus() == DatabaseStatus.DELETED
                || database.getStatus() == DatabaseStatus.DELETING
                || database.getStatus() == DatabaseStatus.DELETED) {
            return;
        }
        int safeProgress = Math.max(0, Math.min(progress, 100));
        database.setProvisioningStage(stage);
        database.setProgress(safeProgress);
        database.setMessage(message);
        database.setUpdatedAt(Instant.now());
        databaseRepository.save(database);

        operationRepository.findById(database.getOperationId()).ifPresent(operation -> {
            if (operation.getStatus() == OperationStatus.SUCCEEDED
                    || operation.getStatus() == OperationStatus.FAILED) {
                return;
            }
            operation.setStatus(OperationStatus.RUNNING);
            operation.setProvisioningStage(stage);
            operation.setProgress(safeProgress);
            operation.setMessage(message);
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            operation.setUpdatedAt(Instant.now());
            operationRepository.save(operation);
        });
    }

    public void ready(DatabaseMetadata database) {
        if (database.getDesiredStatus() == DatabaseStatus.DELETED
                || database.getStatus() == DatabaseStatus.DELETING
                || database.getStatus() == DatabaseStatus.DELETED) {
            return;
        }
        database.setStatus(DatabaseStatus.RUNNING);
        update(database, ProvisioningStage.READY, 100,
                "Database is ready to accept connections");
        operationRepository.findById(database.getOperationId()).ifPresent(operation -> {
            operation.setStatus(OperationStatus.SUCCEEDED);
            operation.setProvisioningStage(ProvisioningStage.READY);
            operation.setProgress(100);
            operation.setMessage("Database is ready to accept connections");
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            operation.setCompletedAt(Instant.now());
            operation.setUpdatedAt(Instant.now());
            operationRepository.save(operation);
        });
    }

    public void failed(DatabaseMetadata database, String message) {
        if (database.getDesiredStatus() == DatabaseStatus.DELETED
                || database.getStatus() == DatabaseStatus.DELETING
                || database.getStatus() == DatabaseStatus.DELETED) {
            return;
        }
        database.setStatus(DatabaseStatus.FAILED);
        update(database, ProvisioningStage.FAILED, 100, message);
        operationRepository.findById(database.getOperationId()).ifPresent(operation -> {
            operation.setStatus(OperationStatus.FAILED);
            operation.setProvisioningStage(ProvisioningStage.FAILED);
            operation.setProgress(100);
            operation.setMessage(message);
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            operation.setCompletedAt(Instant.now());
            operation.setUpdatedAt(Instant.now());
            operationRepository.save(operation);
        });
    }
}
