ALTER TABLE restores
    ADD COLUMN target_database_name VARCHAR(32) NULL AFTER restored_database_id,
    ADD COLUMN temporary BIT NOT NULL DEFAULT b'1' AFTER restore_time,
    ADD COLUMN expires_after_hours INT NULL AFTER temporary,
    ADD COLUMN expires_at DATETIME(6) NULL AFTER expires_after_hours,
    ADD COLUMN access_mode VARCHAR(32) NOT NULL DEFAULT 'PRIVATE' AFTER expires_at,
    ADD COLUMN promoted_at DATETIME(6) NULL AFTER completed_at,
    ADD COLUMN deleted_at DATETIME(6) NULL AFTER promoted_at,
    ADD INDEX idx_restores_active_temporary
        (project_name, source_database_id, temporary, status, expires_at),
    ADD INDEX idx_restores_expiry
        (temporary, expires_at, status);

UPDATE restores r
JOIN database_instances d ON d.database_id = r.restored_database_id
SET r.target_database_name = d.display_name
WHERE r.target_database_name IS NULL;

UPDATE restores
SET target_database_name = restored_database_id
WHERE target_database_name IS NULL;

ALTER TABLE restores
    MODIFY target_database_name VARCHAR(32) NOT NULL;
