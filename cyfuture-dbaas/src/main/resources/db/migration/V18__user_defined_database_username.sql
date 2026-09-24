ALTER TABLE database_instances
    ADD COLUMN logical_username VARCHAR(32) NULL AFTER logical_database_name;

UPDATE database_instances
SET logical_username = CONCAT('dbaas_', REPLACE(SUBSTRING(database_id, 4), '-', ''))
WHERE logical_username IS NULL OR logical_username = '';

ALTER TABLE database_instances
    MODIFY logical_username VARCHAR(32) NOT NULL,
    ADD CONSTRAINT uk_database_project_logical_name
        UNIQUE (project_name, logical_database_name),
    ADD CONSTRAINT uk_database_project_logical_username
        UNIQUE (project_name, logical_username);
