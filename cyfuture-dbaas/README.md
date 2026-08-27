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
- Private Kubernetes endpoint and public endpoint
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

Public access is automatic. `allowedCidrs` may be omitted. In local development the API can discover the caller's public egress address. Behind Cyfuture.ai, disable that fallback and forward trusted proxy headers.

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
