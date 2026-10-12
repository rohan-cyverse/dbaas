ALTER TABLE database_instances
    ADD COLUMN master_slave_connection_enabled BOOLEAN NOT NULL DEFAULT FALSE;
