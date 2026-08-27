ALTER TABLE databases
    ADD COLUMN desired_status VARCHAR(32),
    ADD COLUMN kubeblocks_phase VARCHAR(32),
    ADD COLUMN expected_replicas INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN observed_ready_replicas INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN observed_service_ready BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN last_observed_at TIMESTAMPTZ,
    ADD COLUMN missing_since TIMESTAMPTZ,
    ADD COLUMN degraded_since TIMESTAMPTZ,
    ADD COLUMN delete_requested_at TIMESTAMPTZ,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN sync_message VARCHAR(4000);

UPDATE databases
SET desired_status = CASE
        WHEN status = 'DELETING' THEN 'DELETED'
        ELSE 'RUNNING'
    END,
    expected_replicas = COALESCE(NULLIF(replicas, 0), 1),
    observed_ready_replicas = 0,
    observed_service_ready = FALSE
WHERE desired_status IS NULL;

ALTER TABLE databases
    ALTER COLUMN desired_status SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_databases_reconciliation_status
    ON databases(status, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_databases_last_observed
    ON databases(last_observed_at);
