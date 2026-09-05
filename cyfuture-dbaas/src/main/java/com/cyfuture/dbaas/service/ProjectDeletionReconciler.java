package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.model.ResourceStatus;
import com.cyfuture.dbaas.repository.ProjectMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Completes namespace removal after KubeBlocks finishes project database finalizers. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectDeletionReconciler {
    private final ProjectMetadataRepository projectRepository;
    private final ProjectService projectService;

    @Scheduled(fixedDelayString = "${dbaas.project-delete-reconcile-ms:5000}")
    public void reconcile() {
        for (var project : projectRepository.findByStatusOrderByCreatedAtAsc(ResourceStatus.DELETING)) {
            try {
                projectService.reconcileDeletion(project);
            } catch (Exception exception) {
                log.debug("Project deletion reconciliation for {} will retry: {}",
                        project.getProjectId(), exception.getMessage());
            }
        }
    }
}
