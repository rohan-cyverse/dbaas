ALTER TABLE database_instances
    ADD COLUMN master_slave_enabled BOOLEAN NOT NULL DEFAULT FALSE;
