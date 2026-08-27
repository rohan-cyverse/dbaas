CREATE INDEX idx_databases_project_database
    ON databases(project_name, database_id);

CREATE INDEX idx_operations_project_database_created
    ON operations(project_name, database_id, created_at DESC);

CREATE INDEX idx_operations_project_database_operation
    ON operations(project_name, database_id, operation_id);
