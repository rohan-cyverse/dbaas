# Cyfuture DBaaS control plane

Spring Boot control-plane API for provisioning PostgreSQL, MySQL and MongoDB through KubeBlocks.

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
GET    /api/v1/projects/{project}
PUT    /api/v1/projects/{project}
DELETE /api/v1/projects/{project}
```

Deleting a project immediately clears DB-level deletion protection and requests deletion of its
KubeBlocks Clusters. Once their finalizers have completed, DBaaS automatically requests deletion
of its DBaaS-owned Kubernetes namespace. Empty projects request namespace deletion immediately.

Create request:

```json
{
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

The metadata database is MySQL. Create the local database/user before startup:

```sql
CREATE DATABASE IF NOT EXISTS dbaas_metadata CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'dbaas'@'localhost' IDENTIFIED BY 'change-me';
ALTER USER 'dbaas'@'localhost' IDENTIFIED BY 'change-me';
GRANT ALL PRIVILEGES ON dbaas_metadata.* TO 'dbaas'@'localhost';
FLUSH PRIVILEGES;
```

Flyway owns the metadata schema and Hibernate only validates it. A fresh
database runs migrations `V1` through `V7` automatically. An existing
installation already on the current pre-Flyway schema is safely baselined at
`V6`, then receives the targeted `V7` cleanup. For a one-time upgrade
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
dbaas.gateway.port-start=31000
dbaas.gateway.port-end=31009
```

HAProxy must accept OpenStack Proxy Protocol v2 on public database listeners. CIDR enforcement belongs on the LoadBalancer `loadBalancerSourceRanges`, not HAProxy source ACLs, because NodePort forwarding may translate the source visible to HAProxy.

## Postman

Import:

```text
postman/cyfuture-dbaas.postman_collection.json
```

The collection contains backend-managed organization settings, direct project creation, and all supported PostgreSQL, MySQL and MongoDB lifecycle requests. It intentionally has no organization-create or organization-selection request.
