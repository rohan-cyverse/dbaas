-- Preserve existing metadata while moving away from the MySQL-reserved table name.
RENAME TABLE `databases` TO database_instances;
ALTER TABLE database_instances ADD COLUMN desired_state VARCHAR(16) NULL;
UPDATE database_instances SET desired_state = CASE
  WHEN status IN ('DELETING','DELETED') THEN 'DELETED'
  ELSE 'RUNNING' END WHERE desired_state IS NULL;
ALTER TABLE database_instances MODIFY desired_state VARCHAR(16) NOT NULL;
