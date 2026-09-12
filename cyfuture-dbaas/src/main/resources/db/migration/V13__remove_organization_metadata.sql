ALTER TABLE projects DROP FOREIGN KEY fk_projects_organization;
ALTER TABLE projects DROP INDEX idx_projects_organization_id;
ALTER TABLE projects DROP COLUMN organization_id;
DROP TABLE organizations;
