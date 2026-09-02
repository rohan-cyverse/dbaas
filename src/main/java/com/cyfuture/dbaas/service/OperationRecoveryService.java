package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.repository.DatabaseMetadataRepository;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.LinkedHashMap;

@Service
@RequiredArgsConstructor
public class OperationRecoveryService {
    private final OperationMetadataRepository operationRepository;
    private final DatabaseMetadataRepository databaseRepository;
    private final AsyncProvisioningService provisioningService;
    private final KubeBlocksOperationSubmitter operationSubmitter;

    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterruptedOperations() {
        List<OperationMetadata> interrupted = operationRepository.findByStatusIn(
                List.of(OperationStatus.PENDING, OperationStatus.RUNNING));
        for (OperationMetadata operation : interrupted) {
            databaseRepository
                    .findByDatabaseIdAndProjectName(
                            operation.getDatabaseId(), operation.getProjectName())
                    .ifPresent(database -> {
                        if (operation.getType() == OperationType.CREATE
                                && database.getStatus() == DatabaseStatus.PROVISIONING) {
                            operation.setStatus(OperationStatus.PENDING);
                            operation.setMessage("Resuming database provisioning after application restart");
                            operationRepository.save(operation);
                            provisioningService.provision(operation.getOperationId(),
                                    database.getDatabaseId(), database.getProjectName(),
                                    database.getNamespaceName(),
                                    request(database));
                        } else if (operation.getType() != OperationType.CREATE) {
                            operation.setStatus(OperationStatus.PENDING);
                            operation.setMessage("Resuming KubeBlocks operation after application restart");
                            operationRepository.save(operation);
                            operationSubmitter.submit(operation.getOperationId());
                        }
                    });
        }
    }

    private CreateDatabaseRequest request(DatabaseMetadata database) {
        return new CreateDatabaseRequest(database.getDisplayName(), database.getRemark(),
                database.getEngine(), database.getMode(), database.getDatabaseVersion(),
                database.getSizePlan(), database.getStorageGi(), database.getReplicas(),
                database.getShards(), database.getTimezone(), cidrs(database.getAllowedCidrs()),
                database.isDeletionProtection(), tags(database.getTags()));
    }

    private List<String> cidrs(String stored) {
        if (stored == null || stored.isBlank() || "[]".equals(stored.trim())) return List.of();
        String value = stored.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private Map<String, String> tags(String stored) {
        if (stored == null || stored.isBlank() || "{}".equals(stored.trim())
                || "[]".equals(stored.trim())) return Map.of();
        String value = stored.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (String entry : value.split(",")) {
            String[] pair = entry.trim().split("=", 2);
            if (pair.length == 2 && !pair[0].isBlank()) {
                tags.put(pair[0].trim(), pair[1].trim());
            }
        }
        return tags;
    }
}
