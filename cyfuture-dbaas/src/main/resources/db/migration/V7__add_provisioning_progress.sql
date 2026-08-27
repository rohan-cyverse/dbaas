ALTER TABLE databases
    ADD COLUMN provisioning_stage VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    ADD COLUMN progress INTEGER NOT NULL DEFAULT 0;

ALTER TABLE operations
    ADD COLUMN provisioning_stage VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    ADD COLUMN progress INTEGER NOT NULL DEFAULT 0;

UPDATE databases
SET provisioning_stage = CASE
        WHEN status = 'RUNNING' THEN 'READY'
        WHEN status = 'FAILED' THEN 'FAILED'
        ELSE 'WAITING_FOR_REPLICAS'
    END,
    progress = CASE
        WHEN status IN ('RUNNING', 'FAILED') THEN 100
        ELSE 40
    END;

UPDATE operations
SET provisioning_stage = CASE
        WHEN status = 'SUCCEEDED' THEN 'READY'
        WHEN status = 'FAILED' THEN 'FAILED'
        ELSE 'WAITING_FOR_REPLICAS'
    END,
    progress = CASE
        WHEN status IN ('SUCCEEDED', 'FAILED') THEN 100
        ELSE 40
    END;

-- Earlier application versions marked a database RUNNING as soon as the
-- KubeBlocks pods were ready, even if the managed user or public endpoint was
-- still pending. Revalidate those deployments once after this upgrade so the
-- API never advertises an unusable connection as READY.
UPDATE databases
SET status = 'PROVISIONING',
    provisioning_stage = 'WAITING_FOR_REPLICAS',
    progress = 45,
    message = 'Revalidating credentials and network after workflow upgrade'
WHERE status = 'RUNNING';

-- Old CREATE operations could also be marked SUCCEEDED too early. Put their
-- operation back into the resumable workflow while the related database is
-- being revalidated.
UPDATE operations operation
SET status = 'RUNNING',
    provisioning_stage = database.provisioning_stage,
    progress = database.progress,
    completed_at = NULL,
    message = 'Continuing provisioning after workflow upgrade'
FROM databases database
WHERE operation.database_id = database.database_id
  AND operation.type = 'CREATE'
  AND database.status = 'PROVISIONING';
