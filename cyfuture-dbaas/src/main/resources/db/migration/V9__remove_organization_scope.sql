-- The public API and domain model now use Project -> Databases directly.
-- Project names therefore become globally unique within this DBaaS control plane.
--
-- Older releases allowed the same project_name in different organizations. Keep
-- the most-used project name unchanged and deterministically rename only the
-- additional conflicting projects. The project_id suffix is stable and avoids
-- losing or merging any existing project/database metadata.
ALTER TABLE databases DROP CONSTRAINT IF EXISTS fk_database_project;
ALTER TABLE projects DROP CONSTRAINT IF EXISTS fk_project_organization;
ALTER TABLE projects DROP CONSTRAINT IF EXISTS uk_project_organization_name;
ALTER TABLE databases DROP CONSTRAINT IF EXISTS uk_database_org_project_idempotency;

CREATE TEMP TABLE project_name_migration_v9 ON COMMIT DROP AS
WITH ranked_projects AS (
    SELECT p.project_id,
           p.organization_name,
           p.project_name AS old_project_name,
           COUNT(d.database_id) AS database_count,
           ROW_NUMBER() OVER (
               PARTITION BY p.project_name
               ORDER BY COUNT(d.database_id) DESC,
                        p.created_at ASC,
                        p.project_id ASC
           ) AS duplicate_rank
    FROM projects p
    LEFT JOIN databases d
      ON d.organization_name = p.organization_name
     AND d.project_name = p.project_name
    GROUP BY p.project_id,
             p.organization_name,
             p.project_name,
             p.created_at
)
SELECT project_id,
       organization_name,
       old_project_name,
       CASE
           WHEN duplicate_rank = 1 THEN old_project_name
           ELSE LEFT(old_project_name, 17)
                || '-'
                || SUBSTRING(MD5(project_id), 1, 12)
       END AS new_project_name
FROM ranked_projects;

UPDATE databases d
SET project_name = m.new_project_name
FROM project_name_migration_v9 m
WHERE d.organization_name = m.organization_name
  AND d.project_name = m.old_project_name
  AND m.new_project_name <> m.old_project_name;

UPDATE operations o
SET project_name = m.new_project_name
FROM project_name_migration_v9 m
WHERE o.organization_name = m.organization_name
  AND o.project_name = m.old_project_name
  AND m.new_project_name <> m.old_project_name;

UPDATE projects p
SET project_name = m.new_project_name,
    updated_at = CURRENT_TIMESTAMP
FROM project_name_migration_v9 m
WHERE p.project_id = m.project_id
  AND m.new_project_name <> m.old_project_name;

DROP INDEX IF EXISTS idx_projects_organization_created;
DROP INDEX IF EXISTS idx_databases_org_project_created;
DROP INDEX IF EXISTS idx_databases_org_project_id;
DROP INDEX IF EXISTS idx_operations_org_project_database_created;
DROP INDEX IF EXISTS idx_operations_org_project_database_id;

ALTER TABLE operations DROP COLUMN IF EXISTS organization_name;
ALTER TABLE databases DROP COLUMN IF EXISTS organization_name;
ALTER TABLE projects DROP COLUMN IF EXISTS organization_name;

DROP TABLE IF EXISTS organizations;

ALTER TABLE projects
    ADD CONSTRAINT uk_project_name UNIQUE (project_name);

ALTER TABLE databases
    ADD CONSTRAINT uk_database_project_idempotency
    UNIQUE (project_name, idempotency_key);

ALTER TABLE databases
    ADD CONSTRAINT fk_database_project_name
    FOREIGN KEY (project_name)
    REFERENCES projects(project_name);

CREATE INDEX IF NOT EXISTS idx_projects_created
    ON projects(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_databases_project_created_v9
    ON databases(project_name, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_operations_project_database_created_v9
    ON operations(project_name, database_id, created_at DESC);
