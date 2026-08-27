CREATE TABLE organizations (
    organization_name VARCHAR(30) PRIMARY KEY,
    display_name VARCHAR(64) NOT NULL,
    description VARCHAR(250),
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE projects (
    project_id VARCHAR(32) PRIMARY KEY,
    organization_name VARCHAR(30) NOT NULL,
    project_name VARCHAR(30) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    description VARCHAR(250),
    namespace_name VARCHAR(63) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_project_organization_name
        UNIQUE (organization_name, project_name),
    CONSTRAINT fk_project_organization
        FOREIGN KEY (organization_name)
        REFERENCES organizations(organization_name)
);

-- Preserve databases created by earlier versions by registering their
-- existing organization and project scopes.
INSERT INTO organizations (
    organization_name, display_name, description, status, created_at, updated_at
)
SELECT organization_name,
       INITCAP(REPLACE(organization_name, '-', ' ')),
       'Migrated from existing database metadata',
       'ACTIVE',
       MIN(created_at),
       MAX(updated_at)
FROM databases
GROUP BY organization_name;

INSERT INTO projects (
    project_id, organization_name, project_name, display_name, description,
    namespace_name, status, created_at, updated_at
)
SELECT 'prj-' || SUBSTRING(MD5(organization_name || ':' || project_name), 1, 12),
       organization_name,
       project_name,
       INITCAP(REPLACE(project_name, '-', ' ')),
       'Migrated from existing database metadata',
       MIN(namespace_name),
       'ACTIVE',
       MIN(created_at),
       MAX(updated_at)
FROM databases
GROUP BY organization_name, project_name;

ALTER TABLE databases
    ADD CONSTRAINT fk_database_project
    FOREIGN KEY (organization_name, project_name)
    REFERENCES projects(organization_name, project_name);

CREATE INDEX idx_projects_organization_created
    ON projects(organization_name, created_at DESC);
