ALTER TABLE database_instances
    ADD COLUMN logical_database_name VARCHAR(63) NULL AFTER display_name;

UPDATE database_instances
SET logical_database_name = CONCAT('appdb_', REPLACE(SUBSTRING(database_id, 4), '-', ''))
WHERE logical_database_name IS NULL OR logical_database_name = '';

ALTER TABLE database_instances
    MODIFY logical_database_name VARCHAR(63) NOT NULL;
