# Cyfuture DBaaS control plane

Spring Boot control-plane API for provisioning PostgreSQL, MySQL and MongoDB through KubeBlocks.

## Resource model

The application intentionally uses a simple hierarchy:

```text
Project
└── Databases
```

There is no organization resource or organization segment in API URLs. Project names are globally unique in this control-plane deployment. Authentication and ownership are expected to be supplied later by the parent Cyfuture.ai platform.

Each project receives one Kubernetes namespace. New projects use `dbaas-{project}`. Namespaces belonging to projects created by an older release are retained, so existing databases are not moved or recreated.

## Included features

- Project create, list, get, update and guarded delete
- PostgreSQL standalone and replication
- MySQL standalone and replication
- MongoDB standalone, replica set and sharding
- Asynchronous provisioning with idempotency keys
- Persistent MySQL control-plane metadata managed by JPA/Hibernate
- Status, progress and operation polling
- Automatic least-privilege database and user creation
- Unique per-database password stored in a dedicated Kubernetes Secret
- Credential rotation
- Internal Kubernetes services and frontend-safe public connection details
- Permanent shared HAProxy/OpenStack LoadBalancer gateway
- Automatic caller-IP CIDR selection in local development
- Deletion protection
- Swagger UI and an updated Postman collection

## API routes

### Projects

```text
POST   /api/v1/projects
GET    /api/v1/projects
GET    /api/v1/projects/{project}
PUT    /api/v1/projects/{project}
DELETE /api/v1/projects/{project}
```

Create request:

```json
{
  "name": "test-project",
  "displayName": "Test Project",
  "description": "Development databases"
}
```

### Databases

```text
GET    /api/v1/projects/{project}/databases/options
POST   /api/v1/projects/{project}/databases
GET    /api/v1/projects/{project}/databases
GET    /api/v1/projects/{project}/databases/{databaseId}
GET    /api/v1/projects/{project}/databases/{databaseId}/operations
GET    /api/v1/projects/{project}/databases/{databaseId}/operations/{operationId}
GET    /api/v1/projects/{project}/databases/{databaseId}/connection
POST   /api/v1/projects/{project}/databases/{databaseId}/credentials/rotate
PUT    /api/v1/projects/{project}/databases/{databaseId}/deletion-protection?enabled=false
DELETE /api/v1/projects/{project}/databases/{databaseId}
```

Database creation requires an `Idempotency-Key` header. Example:

```json
{
  "name": "orders-postgres",
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

Public access is automatic. The frontend does not need to send allowlist CIDRs; the API derives the caller IP. In local development the API can discover the caller's public egress address. Behind Cyfuture.ai, disable that fallback and forward trusted proxy headers.

List endpoints return a page envelope:

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalItems": 0,
  "totalPages": 0
}
```

Database and operation listing support only `page` and `size`. Results are returned newest-first.

### Async operations and polling

Provisioning, scaling, restart, storage expansion, credential rotation and deletion return `202 Accepted`. The response includes `operationId`, `databaseId`, operation `status`, operation `statusUrl` and `suggestedPollingIntervalSeconds`. The same URL is also sent in `Location` and `Operation-Location`; `Retry-After` mirrors the suggested interval.

Example:

```json
{
  "operationId": "op-a1b2c3d4e5f6",
  "databaseId": "db-a1b2c3d4e5f6",
  "project": "test-project",
  "type": "RESTART",
  "status": "PENDING",
  "terminal": false,
  "message": "Database restart request queued",
  "failureReason": null,
  "statusUrl": "/api/v1/projects/test-project/databases/db-a1b2c3d4e5f6/operations/op-a1b2c3d4e5f6",
  "suggestedPollingIntervalSeconds": 5,
  "createdAt": "2026-09-01T05:55:00Z",
  "startedAt": null,
  "completedAt": null
}
```

Poll operation status with `GET statusUrl` until `terminal=true` and `status` is `SUCCEEDED` or `FAILED`. Operation polling is read-only: it reads MySQL metadata only and does not submit provisioning, roll gateway pods, or update allowlists. After a browser refresh, recover by listing `/operations?page=0&size=20` for the database and polling the latest non-terminal operation. Keep database health separate by reading `GET /api/v1/projects/{project}/databases/{databaseId}`.

Creation responses return the operation polling URL in `statusUrl`.

### Frontend response changes

Frontend-facing JSON is intentionally small. Database status/list responses omit `privateEndpoint`, `namespace`, backend readiness counters and gateway allowlist CIDRs. Operation responses omit `project`, `stage` and `progress`. The connection endpoint omits `privateEndpoint` and `privateConnectionUri`. Internal Kubernetes service discovery and shared-gateway routing still use those backend-only values.

`publicEndpoint.ready` is true only when the database has reached `RUNNING` and `READY` and the shared gateway route is configured and rolled out. A public host or port alone is not enough.

Credentials and passwords are returned only by `GET /api/v1/projects/{project}/databases/{databaseId}/connection`, with `Cache-Control: no-store`. List and status APIs never return passwords.

Errors use a stable shape and include `X-Request-Id`:

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Invalid request",
  "fieldErrors": [
    {"field": "storageGi", "message": "must be greater than or equal to 10"}
  ],
  "requestId": "7d570e3e-8f7d-4f22-89f5-9a913bb750f5",
  "status": 400,
  "retryable": false,
  "timestamp": "2026-09-01T05:55:00Z"
}
```

Retry `409 DATABASE_NOT_READY`, `502` and `503` responses with backoff. Do not retry validation failures without changing the request. Reuse the same `Idempotency-Key` for the same create or lifecycle retry; use a new key for a different request body.

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

Spring Boot also imports `.env` and `/etc/cyfuture-dbaas.env` automatically.
`.env.example` is only a template; do not use it as the runtime env file.
On the VM, run the service with systemd; direct `./mvnw spring-boot:run`
will read `/etc/cyfuture-dbaas.env` when needed.

The metadata database is MySQL. Create the local database/user before startup:

```sql
CREATE DATABASE IF NOT EXISTS dbaas_metadata CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'dbaas'@'localhost' IDENTIFIED BY 'change-me';
ALTER USER 'dbaas'@'localhost' IDENTIFIED BY 'change-me';
GRANT ALL PRIVILEGES ON dbaas_metadata.* TO 'dbaas'@'localhost';
FLUSH PRIVILEGES;
```

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
dbaas.gateway.port-start=31000
dbaas.gateway.port-end=31009
```

HAProxy must accept OpenStack Proxy Protocol v2 on public database listeners. CIDR enforcement belongs on the LoadBalancer `loadBalancerSourceRanges`, not HAProxy source ACLs, because NodePort forwarding may translate the source visible to HAProxy.

## Postman

Import:

```text
postman/cyfuture-dbaas.postman_collection.json
```

The collection contains project management and all supported PostgreSQL, MySQL and MongoDB lifecycle requests. It no longer contains an organization variable or organization requests.
