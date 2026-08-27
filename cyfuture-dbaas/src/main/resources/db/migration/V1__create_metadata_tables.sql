CREATE TABLE databases (
    database_id VARCHAR(32) PRIMARY KEY,
    operation_id VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    project_name VARCHAR(30) NOT NULL,
    namespace_name VARCHAR(63) NOT NULL,
    display_name VARCHAR(32) NOT NULL,
    remark VARCHAR(64),
    engine VARCHAR(32) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    database_version VARCHAR(32) NOT NULL,
    size_plan VARCHAR(32) NOT NULL,
    storage_gi INTEGER NOT NULL,
    access_mode VARCHAR(16) NOT NULL,
    deletion_protection BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    replicas INTEGER NOT NULL,
    shards INTEGER NOT NULL,
    scheduling_policy VARCHAR(32) NOT NULL,
    timezone VARCHAR(60),
    case_sensitive BOOLEAN,
    allowed_cidrs VARCHAR(1000),
    tags VARCHAR(2000),
    message VARCHAR(4000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_database_project_idempotency UNIQUE (project_name, idempotency_key)
);

CREATE TABLE operations (
    operation_id VARCHAR(32) PRIMARY KEY,
    database_id VARCHAR(32) NOT NULL,
    project_name VARCHAR(30) NOT NULL,
    type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    message VARCHAR(4000),
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_operation_database FOREIGN KEY (database_id)
        REFERENCES databases(database_id)
);

CREATE INDEX idx_databases_project_created
    ON databases(project_name, created_at DESC);

CREATE INDEX idx_operations_database_created
    ON operations(database_id, created_at DESC);

CREATE INDEX idx_operations_status
    ON operations(status);
