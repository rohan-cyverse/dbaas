SET @created_by_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'projects'
      AND column_name = 'created_by'
);

SET @add_created_by_column := IF(
    @created_by_column_exists = 0,
    'ALTER TABLE projects ADD COLUMN created_by VARCHAR(255)',
    'SELECT 1'
);

PREPARE add_created_by_column_statement FROM @add_created_by_column;
EXECUTE add_created_by_column_statement;
DEALLOCATE PREPARE add_created_by_column_statement;
