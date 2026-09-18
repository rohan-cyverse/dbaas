DROP INDEX uk_restore_target_database ON restores;

ALTER TABLE database_instances
    ADD COLUMN active_cluster_name VARCHAR(63) NULL AFTER display_name;

UPDATE database_instances
SET active_cluster_name = database_id
WHERE active_cluster_name IS NULL;

ALTER TABLE restores
    ADD COLUMN temporary_cluster_name VARCHAR(63) NULL AFTER restored_database_id,
    ADD COLUMN old_cluster_name VARCHAR(63) NULL AFTER temporary_cluster_name,
    ADD COLUMN old_cluster_delete_at DATETIME(6) NULL AFTER deleted_at,
    ADD COLUMN old_cluster_deleted_at DATETIME(6) NULL AFTER old_cluster_delete_at,
    ADD INDEX idx_restores_old_cluster_cleanup (old_cluster_delete_at, old_cluster_deleted_at, status);
