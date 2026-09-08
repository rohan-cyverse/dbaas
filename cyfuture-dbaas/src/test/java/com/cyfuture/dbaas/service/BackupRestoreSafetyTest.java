package com.cyfuture.dbaas.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BackupRestoreSafetyTest {

    @Test
    void stripsSensitiveKubernetesDiagnosticsFromPersistedFailureMessages() {
        String message = BackupRestoreSafety.safeMessage(null,
                "Secret object-store-creds password=hunter2 at 10.10.4.12");

        assertEquals("KubeBlocks reported a backup or restore lifecycle failure. Check platform logs.", message);
        assertFalse(message.toLowerCase().contains("secret"));
        assertFalse(message.toLowerCase().contains("password"));
    }

    @Test
    void keepsAUsefulNonSensitiveFallbackForRetryableErrors() {
        String message = BackupRestoreSafety.safeMessage(
                new RuntimeException("temporarily unable to contact KubeBlocks"),
                "Backup submission will retry when Kubernetes is available.");

        assertEquals("temporarily unable to contact KubeBlocks", message);
    }
}
