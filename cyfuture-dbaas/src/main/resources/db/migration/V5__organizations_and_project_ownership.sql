-- Organizations are logical tenancy boundaries only; they are never Kubernetes namespaces.
CREATE TABLE organizations (
    organization_id VARCHAR(32) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    description VARCHAR(250) NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (organization_id)
) ENGINE=InnoDB;

-- Existing projects remain accessible after the tenancy model is introduced.
INSERT INTO organizations (organization_id, display_name, description, status, created_at, updated_at)
VALUES ('org-legacy', 'legacy-harbor', 'Automatically created for projects from before organization support',
        'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

ALTER TABLE projects ADD COLUMN organization_id VARCHAR(32) NULL;
UPDATE projects SET organization_id = 'org-legacy' WHERE organization_id IS NULL;
ALTER TABLE projects MODIFY COLUMN organization_id VARCHAR(32) NOT NULL;
ALTER TABLE projects ADD INDEX idx_projects_organization_id (organization_id);
ALTER TABLE projects ADD CONSTRAINT fk_projects_organization
    FOREIGN KEY (organization_id) REFERENCES organizations (organization_id);
