# Cyfuture DBaaS control plane

Spring Boot control-plane API for provisioning PostgreSQL, MySQL and MongoDB through KubeBlocks, including asynchronous manual full backup and restore-to-a-new-database workflows.

## Resource model

The application intentionally uses a simple hierarchy:

```text
Backend-managed Organization
  -> Projects
     -> Databases
```

The organization is a backend-managed logical tenant boundary, not a Kubernetes namespace. DBaaS creates the default organization with an immutable `org-xxxx` ID and a friendly display name such as `amber-river`. Clients cannot create, select, or delete organizations; they may update only its display name and description. Kubernetes resource identity never depends on either field.

`GET /api/v1/organization` returns the immutable `organizationId`. Every project response includes that same `organizationId`, so a UI can always render the ownership path: **Organization → Project → Database**. The server assigns the organization; callers must not submit or trust a client-chosen organization ID.

Each project receives one Kubernetes namespace. New projects use `dbaas-p-<projectId>`. Namespaces belonging to projects created by an older release are retained, so existing databases are not moved or recreated.

## Included features

- Project create, list, get, update and guarded delete
- PostgreSQL standalone and replication
- MySQL standalone and replication
- MongoDB standalone, replica set and sharding
- Asynchronous provisioning with idempotency keys
- Persistent MySQL control-plane metadata managed by Flyway migrations and Hibernate validation
- Status, progress and operation polling
- Automatic least-privilege database and user creation
- Unique per-database password stored in a dedicated Kubernetes Secret
- Credential rotation
- Public connection endpoint only; Kubernetes service and pod details remain internal
- Permanent shared HAProxy/OpenStack LoadBalancer gateway
- Automatic caller-IP CIDR selection in local development
- Deletion protection
- Asynchronous, idempotent KubeBlocks full backups using the platform-owned BackupRepo
- Restore of a completed backup into a new database and public gateway route
- Restart-safe backup and restore reconciliation with operation polling
- Swagger UI and an updated Postman collection

## API routes

### Organization

```text
GET    /api/v1/organization
PUT    /api/v1/organization
```

### Projects

```text

POST   /api/v1/projects
GET    /api/v1/projects
GET    /api/v1/projects/{projectId}
PUT    /api/v1/projects/{projectId}
DELETE /api/v1/projects/{projectId}
```

Deleting a project immediately clears DB-level deletion protection and requests deletion of its
KubeBlocks Clusters. Once their finalizers have completed, DBaaS automatically requests deletion
of its DBaaS-owned Kubernetes namespace. Empty projects request namespace deletion immediately.
The delete endpoint returns `202 Accepted` with `projectId`, `status`, and a user-facing message
while cleanup is in progress; it returns `200 OK` with `status: DELETED` once cleanup is complete.

Create request:

```json
{
  "displayName": "Test Project",
  "description": "Development databases"
}
```

The create response includes the immutable `projectId` and a `Location` header with its canonical
route. Use that `projectId`—not the editable `displayName`—for every `{projectId}` path segment.

### Databases

```text
GET    /api/v1/projects/{projectId}/databases/options
POST   /api/v1/projects/{projectId}/databases
GET    /api/v1/projects/{projectId}/databases
GET    /api/v1/projects/{projectId}/databases/{databaseId}
GET    /api/v1/projects/{projectId}/databases/{databaseId}/operations
GET    /api/v1/projects/{projectId}/databases/{databaseId}/operations/{operationId}
GET    /api/v1/projects/{projectId}/databases/{databaseId}/connection
POST   /api/v1/projects/{projectId}/databases/{databaseId}/credentials/rotate
PUT    /api/v1/projects/{projectId}/databases/{databaseId}/deletion-protection?enabled=false
DELETE /api/v1/projects/{projectId}/databases/{databaseId}
GET    /api/v1/operations/{operationId}
```

Database creation requires an `Idempotency-Key` header. Example:

```json
{
  "remark": "Orders database",
  "engine": "POSTGRESQL",
  "mode": "STANDALONE",
  "version": "17.5.0",
  "size": "C1G2",
  "storageGi": 20,
  "replicas": 1,
  "shards": 0,
  "timezone": "Asia/Kolkata",
  "deletionProtection": true,
  "tags": {
    "environment": "test"
  }
}
```

`name` is optional. When omitted, DBaaS returns a memorable, unique, engine-prefixed
display handle such as `pg-silver-orchid-k7f9`. If a caller supplies a name already used
inside that project, DBaaS keeps the requested base and appends a short suffix instead.
The create response includes the final `name` alongside `databaseId` and `operationId`, so
the UI can show the selected handle immediately.

Public access is automatic. `allowedCidrs` may be omitted. In local development the API can discover the caller's public egress address. Behind Cyfuture.ai, disable that fallback and forward trusted proxy headers.

If deletion protection is enabled, deleting the database returns `409 Conflict` with
`code: DELETION_PROTECTION_ENABLED` and tells the caller to disable deletion protection first.

The restart endpoint always restarts the full database and its KubeBlocks components. It accepts
no request body; component-level restarts are not exposed by this API.

### Backups and restore

```text
POST   /api/v1/projects/{projectId}/databases/{databaseId}/backups
GET    /api/v1/projects/{projectId}/databases/{databaseId}/backups
GET    /api/v1/projects/{projectId}/databases/{databaseId}/backups/{backupId}
DELETE /api/v1/projects/{projectId}/databases/{databaseId}/backups/{backupId}

POST   /api/v1/projects/{projectId}/databases/{databaseId}/backups/{backupId}/restore
GET    /api/v1/operations/{operationId}
```

The current release supports manual FULL backups only. Every create or restore
request requires an Idempotency-Key header of 8-128 letters, numbers, period,
underscore, colon, or hyphen. Keep the exact key when retrying the same
request; use a new key for a new backup or restore. Reusing a key with a
different request returns IDEMPOTENCY_KEY_REUSED.

Create a PostgreSQL full backup:

```http
POST /api/v1/projects/prj-123/databases/db-456/backups
Idempotency-Key: backup-orders-20260907-001
Content-Type: application/json

{
  "type": "FULL",
  "retention": "7d"
}
```

Retention is optional and uses a KubeBlocks duration, for example 7d, 24h,
or 1mo7d. Omitting the request body uses the configured default of 7d.
The server returns 202 Accepted, Location, Operation-Location, and Retry-After
headers before it submits the KubeBlocks Backup resource:

```json
{
  "operationId": "op-7d4cba9f4bd2",
  "backupId": "bkp-0eb83c49ab21",
  "status": "PENDING",
  "statusUrl": "/api/v1/projects/prj-123/databases/db-456/backups/bkp-0eb83c49ab21",
  "pollAfterSeconds": 5
}
```

Poll statusUrl until status is COMPLETED or FAILED. A normal backup status is
safe to display in a UI:

```json
{
  "backupId": "bkp-0eb83c49ab21",
  "operationId": "op-7d4cba9f4bd2",
  "databaseId": "db-456",
  "engine": "POSTGRESQL",
  "type": "FULL",
  "parentBackupId": null,
  "backupChainId": "bkp-0eb83c49ab21",
  "status": "COMPLETED",
  "retention": "7d",
  "sizeBytes": 3707917,
  "message": "Backup completed."
}
```

Create a restore only after the backup is COMPLETED:

```http
POST /api/v1/projects/prj-123/databases/db-456/backups/bkp-0eb83c49ab21/restore
Idempotency-Key: restore-orders-20260907-001
Content-Type: application/json

{
  "name": "orders-restore"
}
```

The restore never overwrites db-456; it allocates a new databaseId and returns:

```json
{
  "restoreId": "rst-4059d1a1f560",
  "operationId": "op-5340ccaa2f58",
  "databaseId": "db-7a011c19ca1b",
  "status": "PENDING",
  "statusUrl": "/api/v1/operations/op-5340ccaa2f58",
  "pollAfterSeconds": 5
}
```

Poll the global operation route. A restore reaches SUCCEEDED only after the
KubeBlocks restore operation succeeds, the restored Cluster is healthy, managed
credentials are ready, and the shared public gateway route is ready. Fetch the
new database through its normal database route and obtain connection details
only through its existing /connection endpoint.

restoreTime is reserved for a future point-in-time restore request. It is
rejected with PITR_NOT_AVAILABLE unless continuous backups are enabled; this
release does not claim PITR support.

#### Backup infrastructure and lifecycle safety

DBaaS treats MySQL metadata as desired state and KubeBlocks resources as the
observed infrastructure state. Backup objects are stored in the existing
S3-compatible repository; DBaaS uses the existing Ready
cyfuture-dbaas-backuprepo and only reads its status. It never creates, patches,
or replaces that BackupRepo or its encryption configuration.

The generated KubeBlocks BackupPolicy must expose the manual method appropriate
to the engine:

| Engine | Available manual full method | Reserved future methods |
| --- | --- | --- |
| PostgreSQL | pg-basebackup | wal-g-incremental, archive-wal |
| MySQL | xtrabackup | xtrabackup-inc, archive-binlog |
| MongoDB | dump | pbm-physical, archive-oplog, pbm-pitr |

The reconciler independently resumes pending or running backups and restores
after an application restart. It records PENDING, RUNNING, COMPLETED, FAILED,
DELETING, and DELETED status without claiming backup completion until KubeBlocks
reports success.

Database deletion is blocked while a backup or restore is active. Completed and
failed retained backups can survive source database deletion. Project namespace
cleanup is blocked while retained backups exist. DELETE
/backups/{backupId} is an explicit asynchronous purge: it deletes only the
known DBaaS-owned KubeBlocks Backup resource using its delete policy, which
removes its associated object-storage data. Unknown or orphan Kubernetes backup
resources are never deleted automatically.

Backup and restore responses never contain S3 credentials, encryption
passphrases, Kubernetes Secrets, database passwords, or private endpoints.

#### PowerShell: one PostgreSQL full backup and restore

Replace the sample IDs with a running PostgreSQL database. The commands only
call the DBaaS API; they do not use kubectl or kbcli.

```powershell
$baseUrl = "http://localhost:8080"
$projectId = "prj-123"
$sourceDatabaseId = "db-456"

$backupHeaders = @{
  "Content-Type" = "application/json"
  "Idempotency-Key" = "backup-orders-20260907-001"
}
$backup = Invoke-RestMethod -Method POST -Uri "$baseUrl/api/v1/projects/$projectId/databases/$sourceDatabaseId/backups" -Headers $backupHeaders -Body '{"type":"FULL","retention":"7d"}'

do {
  Start-Sleep -Seconds $backup.pollAfterSeconds
  $backupState = Invoke-RestMethod -Method GET -Uri "$baseUrl$($backup.statusUrl)"
} while ($backupState.status -notin @("COMPLETED", "FAILED"))

if ($backupState.status -ne "COMPLETED") {
  throw "Backup did not complete: $($backupState.message)"
}

$restoreHeaders = @{
  "Content-Type" = "application/json"
  "Idempotency-Key" = "restore-orders-20260907-001"
}
$restore = Invoke-RestMethod -Method POST -Uri "$baseUrl/api/v1/projects/$projectId/databases/$sourceDatabaseId/backups/$($backup.backupId)/restore" -Headers $restoreHeaders -Body '{"name":"orders-restore"}'

do {
  Start-Sleep -Seconds $restore.pollAfterSeconds
  $restoreState = Invoke-RestMethod -Method GET -Uri "$baseUrl$($restore.statusUrl)"
} while ($restoreState.status -notin @("SUCCEEDED", "FAILED"))

if ($restoreState.status -ne "SUCCEEDED") {
  throw "Restore did not complete: $($restoreState.message)"
}

Invoke-RestMethod -Method GET -Uri "$baseUrl/api/v1/projects/$projectId/databases/$($restore.databaseId)"
```

Before enabling incremental backup, schedules, or PITR, configure and validate
the corresponding KubeBlocks BackupPolicy methods and repository retention
policy. The persisted policy fields and engine strategy abstraction are ready
for that expansion, but no scheduler, incremental chain, WAL/binlog/oplog
archive, or point-in-time recovery is enabled by this release.

### Response boundary

Public responses contain only client-useful IDs, configuration, lifecycle state,
public endpoint details, and short status messages. They never expose Kubernetes
namespace names, service/pod names, ClusterIP addresses, or `.svc.cluster.local`
hosts. Create requests return `202 Accepted` with concise JSON plus `Location`
and `Operation-Location` headers for polling. Project responses expose `organizationId`;
they still never expose namespace identity.

Connection details are generated when requested and are never persisted in the
metadata database. The connection endpoint is always the public gateway route.

## Run locally

Create the local environment file:

```powershell
Copy-Item .env.example .env
notepad .env
```

Set `DBAAS_KUBECONFIG` to the real kubeconfig path, then run:

```powershell
.\run-local.ps1
```

If port `8080` is already occupied, stop the existing application or set
`SERVER_PORT=8081` in `.env` before running the script.

### Central VM metadata database

The VM, Laptop A, and Laptop B must use one MySQL schema hosted on the VM.
There is no local metadata-database fallback: startup requires all three
environment variables below, and their values must identify the same schema.

| Instance | `METADATA_DB_URL` | `DBAAS_GATEWAY_RECONCILE_ENABLED` |
| --- | --- | --- |
| VM production service | `jdbc:mysql://<VM_MYSQL_HOST>:3306/<shared-schema>?useSSL=...` | `true` |
| Laptop A / Laptop B | The same direct URL, or a local SSH-tunnel URL that forwards to that exact schema | `false` |

Every instance must set:

```text
METADATA_DB_URL=jdbc:mysql://<configurable-host>:<port>/<shared-schema>?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
METADATA_DB_USERNAME=<central-metadata-user>
METADATA_DB_PASSWORD=<central-metadata-password>
```

Use the VM's DNS name, private address, or other routable MySQL host in the
direct URL. The host is supplied in `METADATA_DB_URL`; it is not hardcoded by
the application. If a laptop cannot reach MySQL directly, it may use an SSH
tunnel while still targeting the same VM schema:

```dotenv
# .env values for the tunnel; replace every placeholder.
METADATA_TUNNEL_VM_HOST=<vm-ssh-host>
METADATA_TUNNEL_SSH_PORT=<vm-ssh-port>
METADATA_TUNNEL_BIND_HOST=localhost
METADATA_DB_TUNNEL_PORT=3307
METADATA_DB_HOST=<mysql-host-as-seen-from-the-vm>
METADATA_DB_PORT=3306

# The laptop URL points only at its local tunnel; the tunnel's target is still
# the central VM database above.
METADATA_DB_URL=jdbc:mysql://localhost:3307/<shared-schema>?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
```

Then open the tunnel before starting the local application:

```powershell
.\open-vm-metadata-tunnel.ps1
```

The script prompts for SSH authentication and keeps the tunnel open; do not
store an SSH password in `.env` or source control. Set
`METADATA_DB_TUNNEL_PORT` only when a tunnel is required; `run-local.ps1`
otherwise connects directly to the configured central URL.

Flyway owns the metadata schema and Hibernate only validates it. A fresh
database runs migrations `V1` through `V9` automatically. Migration `V9` is
additive: it creates `backup_policies`, `backups`, and `restore_requests` for
backup desired state, restore tracking, idempotency, retention, and safe
failure metadata. It does not modify any already-applied migration. Existing
installations apply it in the normal Flyway sequence. For a one-time upgrade
from the older pre-lifecycle table layout, back up the metadata database and
set `FLYWAY_BASELINE_VERSION=2` for that migration run.

Swagger UI:

```text
http://localhost:8080/swagger-ui/index.html
```

## Shared public gateway

The permanent LoadBalancer and HAProxy deployment are environment infrastructure and should be installed once per Kubernetes cluster. The Spring application allocates an existing warm port and updates only HAProxy routing; it does not create a new cloud LoadBalancer for every database.

Expected configuration:

```properties
dbaas.gateway.namespace=dbaas-gateway
dbaas.gateway.service-name=dbaas-public-gateway
dbaas.gateway.config-map-name=dbaas-public-gateway-config
dbaas.gateway.deployment-name=dbaas-public-gateway
dbaas.gateway.reconcile-enabled=false
dbaas.gateway.port-start=31000
dbaas.gateway.port-end=31009
```

HAProxy must accept OpenStack Proxy Protocol v2 on public database listeners. CIDR enforcement belongs on the LoadBalancer `loadBalancerSourceRanges`, not HAProxy source ACLs, because NodePort forwarding may translate the source visible to HAProxy.

Only the designated VM production service should set
`DBAAS_GATEWAY_RECONCILE_ENABLED=true`. Laptop instances must set it to
`false`. When false, DBaaS never writes the HAProxy ConfigMap, Deployment,
Service, checksum, routes, or rollout state. Normal database APIs continue to
use shared metadata; the enabled VM instance observes that metadata and applies
the gateway route.

When enabled, reconciliation acquires the shared MySQL named lock with
`GET_LOCK` and releases it with `RELEASE_LOCK` on the same JDBC connection.
This serializes VM writers against the central metadata database. Route ordering
and rendered `haproxy.cfg` are deterministic; unchanged configuration does not
update the ConfigMap, patch the Deployment, or trigger a rollout.

To verify the intended writer:

1. Confirm the VM systemd environment has
   `DBAAS_GATEWAY_RECONCILE_ENABLED=true` and each laptop `.env` has
   `DBAAS_GATEWAY_RECONCILE_ENABLED=false`.
2. Record the gateway ConfigMap resource version and Deployment generation,
   then run a laptop DBaaS operation while the VM reconciliation is temporarily
   stopped. Neither value should change.
3. Start the VM reconciler again. For a metadata change that changes a route,
   it alone updates the ConfigMap/checksum; repeating reconciliation without a
   metadata change leaves both resources unchanged.

## Postman

Import:

```text
postman/Cyfuture DBaaS.postman_collection.json
```

The collection contains backend-managed organization settings, direct project creation,
all supported PostgreSQL, MySQL and MongoDB lifecycle requests, and a
**Backup and Restore** folder. Run the backup requests in this order: create a
full backup, poll its status until COMPLETED, restore it, then poll the global
restore operation. The folder stores returned IDs in collection variables and
intentionally omits any request that would expose a password, private endpoint,
repository credential, or encryption value.
