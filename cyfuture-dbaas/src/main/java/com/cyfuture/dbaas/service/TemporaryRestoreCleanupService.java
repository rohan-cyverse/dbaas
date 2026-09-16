package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.model.RestoreStatus;
import com.cyfuture.dbaas.repository.RestoreRequestMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TemporaryRestoreCleanupService {
    private final RestoreRequestMetadataRepository restoreRepository;
    private final RestoreService restoreService;

    @Scheduled(fixedDelayString = "${dbaas.restore.cleanup-ms:300000}")
    public void cleanupExpiredTemporaryRestores() {
        for (var restore : restoreRepository
                .findByTemporaryTrueAndPromotedAtIsNullAndDeletedAtIsNullAndExpiresAtBeforeAndStatusInOrderByExpiresAtAsc(
                        Instant.now(), List.of(RestoreStatus.PENDING, RestoreStatus.RUNNING,
                                RestoreStatus.READY, RestoreStatus.COMPLETED, RestoreStatus.FAILED))) {
            try {
                restoreService.expireTemporaryRestore(restore);
            } catch (Exception exception) {
                log.debug("Expired temporary restore cleanup for {} will retry: {}",
                        restore.getRestoreId(),
                        BackupRestoreSafety.safeMessage(exception, "Temporary restore cleanup will retry."));
            }
        }
    }
}
