-- Preserve historical rows while removing derived KubeBlocks and legacy API state.
-- The global BackupRepo is not a metadata-table resource and is never changed here.

UPDATE backups
SET trigger_method = 'SCHEDULED'
WHERE trigger_method = 'AUTOMATIC';

UPDATE operations
SET type = 'BACKUP'
WHERE type = 'BACKUP_DELETE';

ALTER TABLE backups
    DROP INDEX uk_backups_kubernetes_identity,
    DROP INDEX idx_backups_chain,
    DROP INDEX idx_backups_history_filters,
    DROP INDEX idx_backups_pitr_chain,
    DROP COLUMN delete_operation_id,
    DROP COLUMN source_display_name,
    DROP COLUMN backup_chain_id,
    DROP COLUMN backup_repository_name,
    DROP COLUMN retention_policy,
    DROP COLUMN deletion_mode,
    DROP COLUMN delete_idempotency_key,
    DROP COLUMN delete_request_hash,
    DROP COLUMN kubernetes_namespace,
    DROP COLUMN source_mode,
    DROP COLUMN source_database_version,
    DROP COLUMN source_logical_database_name,
    DROP COLUMN source_size_plan,
    DROP COLUMN source_storage_gi,
    DROP COLUMN source_replicas,
    DROP COLUMN source_shards,
    DROP COLUMN source_timezone,
    DROP COLUMN source_allowed_cidrs,
    DROP COLUMN source_tags,
    DROP COLUMN purged_at,
    ADD CONSTRAINT uk_backups_project_kubernetes_name
        UNIQUE (project_name, kubernetes_backup_name),
    ADD INDEX idx_backups_recovery_chain
        (project_name, database_id, backup_type, base_backup_id, completed_at);

RENAME TABLE backup_policies TO backup_settings;

ALTER TABLE backup_settings
    DROP COLUMN engine,
    DROP COLUMN backup_repository_name,
    DROP COLUMN default_backup_method,
    DROP COLUMN continuous_backup_method,
    DROP COLUMN encryption_configured,
    DROP COLUMN scheduling_enabled,
    DROP COLUMN default_retention_period,
    DROP COLUMN observed_status,
    DROP COLUMN retention_policy,
    DROP COLUMN pitr_status,
    DROP COLUMN pitr_message,
    DROP COLUMN recoverable_from,
    DROP COLUMN recoverable_until,
    DROP COLUMN pitr_observed_at,
    DROP COLUMN idempotency_key,
    DROP COLUMN request_hash;

ALTER TABLE restore_requests
    DROP INDEX idx_restore_history_filters,
    DROP INDEX idx_restore_project_source_backup,
    DROP INDEX uk_restore_project_backup_idempotency,
    DROP COLUMN continuous_backup_id,
    DROP COLUMN source_kubernetes_backup_name,
    DROP COLUMN source_backup_namespace,
    DROP COLUMN engine,
    DROP COLUMN kubernetes_cluster_name,
    DROP COLUMN restored_database_name,
    DROP COLUMN public_host,
    DROP COLUMN public_port,
    ADD INDEX idx_restores_source_created (project_name, source_database_id, created_at);

RENAME TABLE restore_requests TO restores;
