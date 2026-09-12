# Cyfuture DBaaS control plane

Spring Boot 3 / Java 17 control-plane API for PostgreSQL, MySQL, and MongoDB.
It provisions databases through KubeBlocks and exposes product-level lifecycle,
backup, recovery, credentials, and public connection APIs.

## Resource model

```text
Projects -> Databases
```

Each project owns its database resources. Database IDs and project IDs are
immutable; display names are for users only.

## Backup and recovery

All backup/recovery routes use the `/api/v1` prefix.

```text
GET    /api/v1/projects/{projectId}/databases/{databaseId}/backup-settings
PUT    /api/v1/projects/{projectId}/databases/{databaseId}/backup-settings

POST   /api/v1/projects/{projectId}/databases/{databaseId}/backups
GET    /api/v1/projects/{projectId}/databases/{databaseId}/backups
GET    /api/v1/projects/{projectId}/databases/{databaseId}/backups/{backupId}
DELETE /api/v1/projects/{projectId}/databases/{databaseId}/backups/{backupId}

GET    /api/v1/projects/{projectId}/databases/{databaseId}/recovery-window

POST   /api/v1/projects/{projectId}/databases/{databaseId}/restores
GET    /api/v1/projects/{projectId}/databases/{databaseId}/restores
GET    /api/v1/projects/{projectId}/databases/{databaseId}/restores/{restoreId}
```

`POST /backups` accepts `type: FULL` or `type: INCREMENTAL`. An incremental
backup uses the most recent completed backup chain and therefore requires a
completed full backup first. `POST /backups` and `POST /restores` require an
`Idempotency-Key` header. Reuse the same key only when retrying the same
request.

Backup configuration is mandatory when creating a database: the customer must
explicitly choose scheduled backups, retention, timezone, and PITR. When
`scheduled` is `true`, `schedule` is also required. Use `scheduled: false` to
explicitly opt out of automatic backups. The `backup` field in the create
request uses this shape:

```json
{
  "scheduled": true,
  "retentionDays": 7,
  "schedule": "0 2 * * *",
  "timezone": "UTC",
  "pitrEnabled": true
}
```

When `scheduled` is `true`, DBaaS waits for KubeBlocks to generate the
database's `BackupSchedule`, then enables the selected full-backup entry with
the requested cron expression and retention. KubeBlocks owns the resulting
Kubernetes `CronJob`. When `scheduled` is `false`, that entry remains disabled
and no scheduled-backup CronJob is created. The schedule can appear a few
seconds after the database Cluster because it is controller-generated.

Database and project deletion are blocked while DBaaS metadata or KubeBlocks
reports an active backup or restore. Wait for that work to complete before
retrying deletion.

Create a full backup with an optional retention override:

```json
{
  "type": "FULL",
  "retentionDays": 7
}
```

Create an incremental backup through the same endpoint:

```json
{"type":"INCREMENTAL"}
```

Use `mode` to choose the restore flow. A full restore uses a backup ID:

```json
{"mode":"FULL","backupId":"bkp-0eb83c49ab21"}
```

A point-in-time restore uses a past UTC timestamp within the recovery window:

```json
{"mode":"POINT_IN_TIME","restoreTime":"2026-09-09T08:30:00Z"}
```

Every restore creates a new database. `GET /backups` and `GET /restores` are
the single status/history views for their respective resources. Backup and
restore responses contain only product-level IDs, type, status, timestamps,
size, expiry, and safe errors.

The application uses the platform-owned global BackupRepo internally. It never
creates, updates, deletes, or exposes that resource. Deleting a backup has one
meaning: remove the known backup and its retained data.

## Other API areas

- Projects: `/api/v1/projects`
- Databases: `/api/v1/projects/{projectId}/databases`
- Database connection and credentials: database-scoped connection and rotation routes
- General asynchronous database operations: `/api/v1/operations/{operationId}`

Swagger UI is available at `/swagger-ui.html`.

## Run locally

Create the local environment file, configure the metadata database and kubeconfig,
then run:

```powershell
Copy-Item .env.example .env
.\run-local.ps1
```

The metadata database is managed by Flyway. Hibernate runs in validation mode.
