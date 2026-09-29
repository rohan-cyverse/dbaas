-- Seed each newly-enabled automatic backup policy with a full backup.
ALTER TABLE backup_settings
    ADD COLUMN initial_backup_required BIT(1) NOT NULL DEFAULT b'0',
    ADD COLUMN initial_backup_id VARCHAR(32) NULL;

ALTER TABLE backup_settings
    ADD CONSTRAINT fk_backup_settings_initial_backup
        FOREIGN KEY (initial_backup_id) REFERENCES backups (backup_id)
        ON DELETE SET NULL;
