-- Lifecycle operations use the active physical Cluster, which can differ from
-- the stable DBaaS database_id after a restore cutover. Persist the selected
-- target so async submission and reconciliation cannot drift to another name.
ALTER TABLE operations
    ADD COLUMN target_cluster_name VARCHAR(63) NULL AFTER ops_request_name;

UPDATE operations o
JOIN database_instances d ON d.database_id = o.database_id
SET o.target_cluster_name = COALESCE(NULLIF(d.active_cluster_name, ''), d.database_id)
WHERE o.target_cluster_name IS NULL;
