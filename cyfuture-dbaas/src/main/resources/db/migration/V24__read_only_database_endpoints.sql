ALTER TABLE database_instances
    ADD COLUMN read_only_public_port INT NULL,
    ADD CONSTRAINT uk_database_read_only_public_port UNIQUE (read_only_public_port);
