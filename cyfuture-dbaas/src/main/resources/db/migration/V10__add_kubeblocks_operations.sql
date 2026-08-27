ALTER TABLE operations
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN request_hash VARCHAR(64),
    ADD COLUMN ops_request_name VARCHAR(63),
    ADD COLUMN component_name VARCHAR(63),
    ADD COLUMN target_replicas INTEGER,
    ADD COLUMN target_storage_size VARCHAR(32),
    ADD COLUMN volume_name VARCHAR(32),
    ADD COLUMN cpu_request VARCHAR(32),
    ADD COLUMN memory_request VARCHAR(32),
    ADD COLUMN cpu_limit VARCHAR(32),
    ADD COLUMN memory_limit VARCHAR(32);

CREATE UNIQUE INDEX IF NOT EXISTS uk_operations_project_database_idempotency
    ON operations(project_name, database_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
