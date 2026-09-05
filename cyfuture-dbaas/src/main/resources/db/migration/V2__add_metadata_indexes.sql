CREATE INDEX idx_databases_project_created ON `databases`(project_name, created_at);
CREATE INDEX idx_databases_status_created ON `databases`(status, created_at);
CREATE INDEX idx_operations_database_created ON operations(database_id, created_at);
CREATE INDEX idx_operations_project_database_created ON operations(project_name, database_id, created_at);
CREATE INDEX idx_operations_status ON operations(status);
