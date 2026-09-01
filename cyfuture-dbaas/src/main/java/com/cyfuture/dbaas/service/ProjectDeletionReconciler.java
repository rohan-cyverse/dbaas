package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.entity.ProjectMetadata;
import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.repository.ProjectMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectDeletionReconciler {

    private final ProjectMetadataRepository projectRepository;
    private final KubeBlocksClient kubeBlocksClient;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${dbaas.project-reconcile-ms:5000}")
    public void reconcile() {

        for (ProjectMetadata project :
                projectRepository.findByStatusOrderByCreatedAtAsc(
                        ResourceStatus.DELETING)) {

            reconcileProject(project);
        }
    }

    private void reconcileProject(ProjectMetadata project) {

        try {

            String namespace = project.getNamespaceName();

            /*
             * Namespace already gone.
             */
            if (!kubeBlocksClient.namespaceExists(namespace)) {

                project.setStatus(ResourceStatus.DELETED);
                project.setUpdatedAt(Instant.now());

                projectRepository.save(project);

                log.info(
                        "Project {} deletion completed",
                        project.getProjectName()
                );

                return;
            }

            /*
             * Idempotent.
             * Calling delete repeatedly on a Terminating
             * namespace is safe.
             */
            kubeBlocksClient.requestNamespaceDelete(namespace);

        } catch (Exception exception) {

            log.warn(
                    "Project {} namespace cleanup will retry: {}",
                    project.getProjectName(),
                    exception.getMessage()
            );
        }
    }
}