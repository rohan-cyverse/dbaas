ALTER TABLE databases
    ADD COLUMN organization_name VARCHAR(30);

UPDATE databases
SET organization_name = 'default-org'
WHERE organization_name IS NULL;

ALTER TABLE databases
    ALTER COLUMN organization_name SET NOT NULL;

ALTER TABLE operations
    ADD COLUMN organization_name VARCHAR(30);

UPDATE operations o
SET organization_name = d.organization_name
FROM databases d
WHERE o.database_id = d.database_id
  AND o.organization_name IS NULL;

ALTER TABLE operations
    ALTER COLUMN organization_name SET NOT NULL;

ALTER TABLE databases
    DROP CONSTRAINT uk_database_project_idempotency;

ALTER TABLE databases
    ADD CONSTRAINT uk_database_org_project_idempotency
        UNIQUE (organization_name, project_name, idempotency_key);

CREATE INDEX idx_databases_org_project_created
    ON databases(organization_name, project_name, created_at DESC);

CREATE INDEX idx_databases_org_project_database
    ON databases(organization_name, project_name, database_id);

CREATE INDEX idx_operations_org_project_database_created
    ON operations(organization_name, project_name, database_id, created_at DESC);

CREATE INDEX idx_operations_org_project_database_operation
    ON operations(organization_name, project_name, database_id, operation_id);
