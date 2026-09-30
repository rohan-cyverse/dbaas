-- Keep the stable database/Cluster identity after an in-place restore. The
-- first restore is a validation cluster; the second materializes the same
-- backup under the original Cluster name, after which validation resources
-- are deleted.
ALTER TABLE restores
    ADD COLUMN final_kubernetes_ops_request_name VARCHAR(63) NULL AFTER kubernetes_restore_name,
    ADD COLUMN final_kubernetes_restore_name VARCHAR(63) NULL AFTER final_kubernetes_ops_request_name,
    ADD COLUMN temporary_cluster_delete_at DATETIME(6) NULL AFTER old_cluster_deleted_at,
    ADD COLUMN temporary_cluster_deleted_at DATETIME(6) NULL AFTER temporary_cluster_delete_at,
    ADD INDEX idx_restores_temporary_cluster_cleanup
        (temporary_cluster_delete_at, temporary_cluster_deleted_at, status);
