package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Keeps older managed Cluster CRs on the safe PreferInPlace policy. */
@Service
@RequiredArgsConstructor
@Slf4j
public class PodUpdatePolicyReconciler {
    private final KubeBlocksClient kubeBlocksClient;

    @EventListener(ApplicationReadyEvent.class)
    public void migrateOnStartup() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${dbaas.pod-update-policy-reconcile-ms:30000}")
    public void reconcile() {
        try {
            int migrated = kubeBlocksClient.migrateManagedClustersToPreferInPlace();
            if (migrated > 0) log.info("Migrated {} managed Cluster CR(s) to PreferInPlace", migrated);
        } catch (Exception exception) {
            log.warn("Managed Cluster pod update policy migration will retry: {}", exception.getMessage());
        }
    }
}
