-- Extends the v9 manual-backup model with scheduled policy desired state,
-- immutable history fields, and enough Kubernetes identity to reconcile after
-- the source database Cluster has been deleted. No repository credentials,
-- encryption material, or database passwords are stored here.

ALTER TABLE backup_policies
    MODIFY COLUMN kubernetes_policy_name VARCHAR(63) NULL,
    ADD COLUMN policy_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' AFTER observed_status,
    ADD COLUMN auto_backup_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER scheduling_enabled,
    ADD COLUMN retention_days INT NOT NULL DEFAULT 7 AFTER default_retention_period,
    ADD COLUMN retention_policy VARCHAR(32) NOT NULL DEFAULT 'RETAIN_ALL' AFTER retention_days,
    ADD COLUMN timezone VARCHAR(60) NOT NULL DEFAULT 'UTC' AFTER cron_expression,
    ADD COLUMN pitr_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER timezone,
    ADD COLUMN configuration_applied BOOLEAN NOT NULL DEFAULT FALSE AFTER pitr_enabled,
    ADD COLUMN kubernetes_schedule_name VARCHAR(63) NULL AFTER kubernetes_policy_name,
    ADD COLUMN policy_update_operation_id VARCHAR(32) NULL AFTER kubernetes_schedule_name,
    ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER policy_update_operation_id,
    ADD COLUMN request_hash VARCHAR(64) NULL AFTER idempotency_key,
    ADD COLUMN failure_code VARCHAR(64) NULL AFTER request_hash,
    ADD COLUMN failure_message VARCHAR(1000) NULL AFTER failure_code,
    ADD COLUMN last_observed_at DATETIME(6) NULL AFTER updated_at,
    ADD INDEX idx_backup_policies_status (policy_status, updated_at);

ALTER TABLE backups
    ADD COLUMN backup_method VARCHAR(63) NULL AFTER backup_type,
    ADD COLUMN trigger_method VARCHAR(32) NOT NULL DEFAULT 'MANUAL' AFTER backup_method,
    ADD COLUMN retention_policy VARCHAR(32) NOT NULL DEFAULT 'RETAIN_ALL' AFTER retention_period,
    ADD COLUMN deletion_mode VARCHAR(32) NULL AFTER retention_policy,
    ADD COLUMN delete_idempotency_key VARCHAR(128) NULL AFTER deletion_mode,
    ADD COLUMN delete_request_hash VARCHAR(64) NULL AFTER delete_idempotency_key,
    ADD COLUMN kubernetes_namespace VARCHAR(63) NULL AFTER kubernetes_backup_name,
    ADD COLUMN kubernetes_uid VARCHAR(63) NULL AFTER kubernetes_namespace,
    ADD COLUMN source_logical_database_name VARCHAR(128) NULL AFTER source_database_version,
    ADD COLUMN expires_at DATETIME(6) NULL AFTER completed_at,
    ADD COLUMN purged_at DATETIME(6) NULL AFTER deleted_at,
    DROP INDEX uk_backups_kubernetes_name,
    ADD CONSTRAINT uk_backups_kubernetes_identity
        UNIQUE (kubernetes_namespace, kubernetes_backup_name),
    ADD INDEX idx_backups_history_filters
        (project_name, database_id, engine, status, trigger_method, backup_type, created_at),
    ADD INDEX idx_backups_expiration (status, expires_at);

-- v9 backed only full DBaaS backups. Preserve their actual engine method so
-- history and restore eligibility remain explicit after the schema upgrade.
UPDATE backups
SET backup_method = CASE engine
    WHEN 'POSTGRESQL' THEN 'pg-basebackup'
    WHEN 'MYSQL' THEN 'xtrabackup'
    WHEN 'MONGODB' THEN 'dump'
    ELSE 'unknown'
END
WHERE backup_method IS NULL;

ALTER TABLE backups
    MODIFY COLUMN backup_method VARCHAR(63) NOT NULL;

ALTER TABLE restore_requests
    ADD COLUMN source_kubernetes_backup_name VARCHAR(63) NULL AFTER source_backup_id,
    ADD COLUMN source_backup_namespace VARCHAR(63) NULL AFTER source_kubernetes_backup_name,
    ADD COLUMN kubernetes_restore_name VARCHAR(63) NULL AFTER kubernetes_ops_request_name,
    ADD COLUMN restored_database_name VARCHAR(32) NULL AFTER restored_database_id,
    ADD COLUMN public_host VARCHAR(255) NULL AFTER restored_database_name,
    ADD COLUMN public_port INT NULL AFTER public_host,
    ADD INDEX idx_restore_history_filters (project_name, engine, status, created_at);
