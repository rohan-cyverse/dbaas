-- These columns are legacy duplicates or transient diagnostics. Desired state,
-- observed status, lifecycle timestamps and operation records remain the source
-- of truth for reconciliation.
SET @metadata_cleanup_sql = (
    SELECT IF(COUNT(*) > 0,
        'ALTER TABLE database_instances DROP COLUMN desired_status',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'database_instances'
      AND column_name = 'desired_status');
PREPARE metadata_cleanup_statement FROM @metadata_cleanup_sql;
EXECUTE metadata_cleanup_statement;
DEALLOCATE PREPARE metadata_cleanup_statement;

SET @metadata_cleanup_sql = (
    SELECT IF(COUNT(*) > 0,
        'ALTER TABLE database_instances DROP COLUMN kubeblocks_phase',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'database_instances'
      AND column_name = 'kubeblocks_phase');
PREPARE metadata_cleanup_statement FROM @metadata_cleanup_sql;
EXECUTE metadata_cleanup_statement;
DEALLOCATE PREPARE metadata_cleanup_statement;

SET @metadata_cleanup_sql = (
    SELECT IF(COUNT(*) > 0,
        'ALTER TABLE database_instances DROP COLUMN sync_message',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'database_instances'
      AND column_name = 'sync_message');
PREPARE metadata_cleanup_statement FROM @metadata_cleanup_sql;
EXECUTE metadata_cleanup_statement;
DEALLOCATE PREPARE metadata_cleanup_statement;

-- project_name always duplicated the immutable primary key project_id in the
-- projects table. Child project ownership columns are deliberately retained:
-- they are required by idempotency, operation recovery and reconciliation.
SET @metadata_cleanup_sql = (
    SELECT IF(COUNT(*) > 0,
        'ALTER TABLE projects DROP INDEX uk_project_name',
        'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'projects'
      AND index_name = 'uk_project_name');
PREPARE metadata_cleanup_statement FROM @metadata_cleanup_sql;
EXECUTE metadata_cleanup_statement;
DEALLOCATE PREPARE metadata_cleanup_statement;

SET @metadata_cleanup_sql = (
    SELECT IF(COUNT(*) > 0,
        'ALTER TABLE projects DROP COLUMN project_name',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'projects'
      AND column_name = 'project_name');
PREPARE metadata_cleanup_statement FROM @metadata_cleanup_sql;
EXECUTE metadata_cleanup_statement;
DEALLOCATE PREPARE metadata_cleanup_statement;
