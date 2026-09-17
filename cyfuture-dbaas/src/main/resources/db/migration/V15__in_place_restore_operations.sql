ALTER TABLE operations
    ADD COLUMN last_heartbeat_at DATETIME(6) NULL AFTER completed_at,
    ADD COLUMN timeout_at DATETIME(6) NULL AFTER last_heartbeat_at,
    ADD COLUMN blocking_operation_id VARCHAR(32) NULL AFTER timeout_at,
    ADD INDEX idx_operations_database_active (project_name, database_id, status);

ALTER TABLE restores
    ADD COLUMN safety_backup_id VARCHAR(32) NULL AFTER last_observed_at,
    ADD COLUMN data_replacement_started BOOLEAN NOT NULL DEFAULT FALSE AFTER safety_backup_id,
    ADD COLUMN rollback_attempted BOOLEAN NOT NULL DEFAULT FALSE AFTER data_replacement_started;
