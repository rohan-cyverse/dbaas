-- PITR state is durable metadata only. No repository, Secret, encryption, or
-- object-store values are copied into DBaaS tables.

ALTER TABLE backup_policies
    ADD COLUMN continuous_backup_method VARCHAR(63) NULL AFTER default_backup_method,
    ADD COLUMN pitr_status VARCHAR(32) NOT NULL DEFAULT 'DISABLED' AFTER pitr_enabled,
    ADD COLUMN pitr_message VARCHAR(1000) NULL AFTER pitr_status,
    ADD COLUMN recoverable_from DATETIME(6) NULL AFTER pitr_message,
    ADD COLUMN recoverable_until DATETIME(6) NULL AFTER recoverable_from,
    ADD COLUMN pitr_observed_at DATETIME(6) NULL AFTER recoverable_until;

ALTER TABLE backups
    ADD COLUMN base_backup_id VARCHAR(32) NULL AFTER parent_backup_id,
    ADD COLUMN parent_kubernetes_backup_name VARCHAR(63) NULL AFTER base_backup_id,
    ADD COLUMN base_kubernetes_backup_name VARCHAR(63) NULL AFTER parent_kubernetes_backup_name,
    ADD COLUMN coverage_start DATETIME(6) NULL AFTER purged_at,
    ADD COLUMN coverage_end DATETIME(6) NULL AFTER coverage_start,
    ADD INDEX idx_backups_pitr_chain (project_name, database_id, backup_type, base_backup_id, completed_at);

UPDATE backups
SET base_backup_id = backup_id,
    base_kubernetes_backup_name = kubernetes_backup_name
WHERE backup_type = 'FULL';

ALTER TABLE restore_requests
    ADD COLUMN restore_mode VARCHAR(32) NOT NULL DEFAULT 'FULL' AFTER source_backup_id,
    ADD COLUMN continuous_backup_id VARCHAR(32) NULL AFTER restore_mode,
    ADD CONSTRAINT uk_restore_project_source_database_idempotency
        UNIQUE (project_name, source_database_id, idempotency_key);
