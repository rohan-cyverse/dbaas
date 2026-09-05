package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.client.KubeBlocksClient;
import com.cyfuture.dbaas.client.DatabaseObservation;
import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.OperationResponse;
import com.cyfuture.dbaas.entity.DatabaseMetadata;
import com.cyfuture.dbaas.entity.OperationMetadata;
import com.cyfuture.dbaas.exception.ApiException;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseStatus;
import com.cyfuture.dbaas.model.DesiredState;
import com.cyfuture.dbaas.model.OperationStatus;
import com.cyfuture.dbaas.model.OperationType;
import com.cyfuture.dbaas.mapper.OperationMapper;
import com.cyfuture.dbaas.repository.OperationMetadataRepository;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1EnvVarSource;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobCondition;
import io.kubernetes.client.openapi.models.V1JobSpec;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretKeySelector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class CredentialLifecycleService {
    private static final String STATUS = "dbaas.cyfuture.com/credential-status";
    private static final String GENERATION = "dbaas.cyfuture.com/credential-generation";
    private static final String OPERATION_ID = "dbaas.cyfuture.com/credential-operation-id";
    private static final String READY = "READY";
    private static final String PENDING = "PENDING";
    private static final String FAILED = "FAILED";
    private static final String MANAGED_BY = "app.kubernetes.io/managed-by";
    private static final String MANAGED_BY_VALUE = "cyfuture-dbaas";
    private static final String DATABASE_ID = "dbaas.cyfuture.com/database-id";
    private static final String PROJECT_ID = "dbaas.cyfuture.com/project-id";
    private static final String HELPER = "dbaas.cyfuture.com/credential-helper";
    private static final char[] PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();

    private final KubeBlocksClient kubeBlocksClient;
    private final DatabaseProperties properties;
    private final OperationMetadataRepository operationRepository;
    private final OperationMapper operationMapper;
    private final CoreV1Api coreV1Api;
    private final BatchV1Api batchV1Api;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public CredentialLifecycleService(KubeBlocksClient kubeBlocksClient,
                                      DatabaseProperties properties,
                                      OperationMetadataRepository operationRepository,
                                      OperationMapper operationMapper,
                                      ApiClient apiClient) {
        this(kubeBlocksClient, properties, operationRepository, operationMapper,
                new CoreV1Api(apiClient), new BatchV1Api(apiClient));
    }

    CredentialLifecycleService(KubeBlocksClient kubeBlocksClient,
                               DatabaseProperties properties,
                               OperationMetadataRepository operationRepository,
                               OperationMapper operationMapper,
                               CoreV1Api coreV1Api,
                               BatchV1Api batchV1Api) {
        this.kubeBlocksClient = kubeBlocksClient;
        this.properties = properties;
        this.operationRepository = operationRepository;
        this.operationMapper = operationMapper;
        this.coreV1Api = coreV1Api;
        this.batchV1Api = batchV1Api;
    }

    public void reconcile(DatabaseMetadata metadata) {
        try {
            if (metadata.getDesiredState() == DesiredState.DELETED
                    || metadata.getStatus() == DatabaseStatus.DELETING
                    || metadata.getStatus() == DatabaseStatus.DELETED) {
                return;
            }
            DatabaseObservation database = kubeBlocksClient.get(
                    metadata.getNamespaceName(), metadata.getDatabaseId());
            if (database.status() != DatabaseStatus.RUNNING
                    || !database.serviceReady()) {
                return;
            }

            V1Secret secret = readOrCreateSecret(metadata);
            Map<String, String> annotations = annotations(secret);
            if (READY.equals(annotations.get(STATUS))) {
                deleteFinishedCredentialResources(metadata);
                return;
            }

            int generation = Integer.parseInt(annotations.getOrDefault(GENERATION, "1"));
            String jobName = jobName(metadata.getDatabaseId(), generation);
            V1Job job = readJob(metadata.getNamespaceName(), jobName);
            if (job == null) {
                String adminSecret = kubeBlocksClient.adminCredentialSecretName(
                        metadata.getNamespaceName(), metadata.getDatabaseId(), metadata.getEngine());
                createJob(metadata, database, secret.getMetadata().getName(),
                        adminSecret, generation);
                markOperation(annotations.get(OPERATION_ID), OperationStatus.RUNNING,
                        "Updating managed database credentials", false);
                return;
            }

            if (succeeded(job)) {
                String operationId = annotations.get(OPERATION_ID);
                annotations.put(STATUS, READY);
                annotations.remove(OPERATION_ID);
                if (secret.getData() != null) secret.getData().remove("previous-password");
                replaceSecret(metadata.getNamespaceName(), secret);
                markOperation(operationId, OperationStatus.SUCCEEDED,
                        "Managed database credentials are ready", true);
                deleteJob(metadata.getNamespaceName(), jobName);
                deleteFinishedCredentialResources(metadata);
            } else if (failed(job)) {
                String operationId = annotations.get(OPERATION_ID);
                byte[] previousPassword = secret.getData() == null
                        ? null : secret.getData().remove("previous-password");
                if (previousPassword != null) {
                    secret.getData().put("password", previousPassword);
                    annotations.put(STATUS, READY);
                    annotations.remove(OPERATION_ID);
                } else {
                    annotations.put(STATUS, FAILED);
                }
                replaceSecret(metadata.getNamespaceName(), secret);
                markOperation(operationId, OperationStatus.FAILED,
                        "Credential update failed; the previous password remains active", true);
                deleteJob(metadata.getNamespaceName(), jobName);
                deleteFinishedCredentialResources(metadata);
            }
        } catch (Exception exception) {
            // Provisioning is eventually consistent. The scheduled reconciler retries.
            log.debug("Credential reconciliation for {} will retry: {}",
                    metadata.getDatabaseId(), exception.getMessage());
        }
    }

    public ManagedCredential credentials(DatabaseMetadata metadata) {
        reconcile(metadata);
        try {
            V1Secret secret = coreV1Api.readNamespacedSecret(
                    secretName(metadata.getDatabaseId()), metadata.getNamespaceName()).execute();
            if (FAILED.equals(annotations(secret).get(STATUS))) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Managed credential provisioning failed; inspect the credential Job");
            }
            if (!READY.equals(annotations(secret).get(STATUS))) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Managed credentials are being prepared; retry shortly");
            }
            return new ManagedCredential(value(secret, "username"), value(secret, "password"),
                    value(secret, "database"));
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Managed credentials are being prepared; retry shortly");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not read managed credentials: " + exception.getMessage());
        }
    }

    /** Returns false while the managed database user is still being created. */
    public boolean ready(DatabaseMetadata metadata) {
        reconcile(metadata);
        try {
            V1Secret secret = coreV1Api.readNamespacedSecret(
                    secretName(metadata.getDatabaseId()), metadata.getNamespaceName()).execute();
            if (FAILED.equals(annotations(secret).get(STATUS))) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Managed credential provisioning failed; inspect the credential Job");
            }
            return READY.equals(annotations(secret).get(STATUS));
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) return false;
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not read managed credential status: " + exception.getMessage());
        }
    }

    public OperationResponse rotate(DatabaseMetadata metadata) {
        try {
            V1Secret secret = coreV1Api.readNamespacedSecret(
                    secretName(metadata.getDatabaseId()), metadata.getNamespaceName()).execute();
            Map<String, String> annotations = annotations(secret);
            if (!READY.equals(annotations.get(STATUS))) {
                if (PENDING.equals(annotations.get(STATUS))) {
                    return inProgressRotation(annotations);
                }
                throw new ApiException(HttpStatus.CONFLICT,
                        "Credentials are not ready for rotation");
            }

            String operationId = "op-" + shortId();
            OperationMetadata operation = operation(metadata, operationId);
            operationRepository.save(operation);

            int generation = Integer.parseInt(annotations.getOrDefault(GENERATION, "1")) + 1;
            annotations.put(GENERATION, String.valueOf(generation));
            annotations.put(STATUS, PENDING);
            annotations.put(OPERATION_ID, operationId);
            Map<String, byte[]> data = secret.getData() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(secret.getData());
            byte[] currentPassword = data.get("password");
            if (currentPassword == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Managed credential Secret is missing password");
            }
            data.put("previous-password", currentPassword);
            data.put("password", randomPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            secret.setData(data);
            replaceSecret(metadata.getNamespaceName(), secret);
            reconcile(metadata);
            return response(operation);
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Managed credentials are not ready for rotation");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not start credential rotation: " + exception.getMessage());
        }
    }

    private V1Secret readOrCreateSecret(DatabaseMetadata metadata)
            throws io.kubernetes.client.openapi.ApiException {
        String name = secretName(metadata.getDatabaseId());
        try {
            return ensureSecretMetadata(metadata,
                    coreV1Api.readNamespacedSecret(name, metadata.getNamespaceName()).execute());
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() != 404) throw exception;
            String suffix = metadata.getDatabaseId().substring(3).replace("-", "");
            V1Secret secret = new V1Secret()
                    .metadata(new V1ObjectMeta()
                            .name(name)
                            .namespace(metadata.getNamespaceName())
                            .labels(helperLabels(metadata))
                            .ownerReferences(ownerReferences(metadata))
                            .annotations(new LinkedHashMap<>(Map.of(
                                    STATUS, PENDING,
                                    GENERATION, "1"))))
                    .type("Opaque")
                    .stringData(Map.of(
                            "username", "dbaas_" + suffix,
                            "password", randomPassword(),
                            "database", "appdb_" + suffix));
            return coreV1Api.createNamespacedSecret(metadata.getNamespaceName(), secret).execute();
        }
    }

    private void createJob(DatabaseMetadata metadata, DatabaseObservation database,
                           String managedSecret, String adminSecret, int generation)
            throws io.kubernetes.client.openapi.ApiException {
        DatabaseProperties.EngineSettings settings = properties.engine(metadata.getEngine());
        if (settings.getCredentialImage() == null || settings.getCredentialImage().isBlank()) {
            throw new IllegalStateException("Credential Job image is not configured for "
                    + metadata.getEngine());
        }

        V1Container container = new V1Container()
                .name("credential-manager")
                .image(settings.getCredentialImage())
                .imagePullPolicy("IfNotPresent")
                .command(List.of("sh", "-ec"))
                .args(List.of(script(metadata.getEngine())))
                .env(List.of(
                        value("DB_HOST", database.privateHost()),
                        secret("ADMIN_USERNAME", adminSecret, "username"),
                        secret("ADMIN_PASSWORD", adminSecret, "password"),
                        secret("MANAGED_USERNAME", managedSecret, "username"),
                        secret("MANAGED_PASSWORD", managedSecret, "password"),
                        secret("MANAGED_DATABASE", managedSecret, "database")));

        String name = jobName(metadata.getDatabaseId(), generation);
        V1Job job = new V1Job()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(new V1ObjectMeta()
                        .name(name)
                        .namespace(metadata.getNamespaceName())
                        .labels(helperLabels(metadata))
                        .ownerReferences(ownerReferences(metadata)))
                .spec(new V1JobSpec()
                        .backoffLimit(2)
                        .activeDeadlineSeconds(300L)
                        .ttlSecondsAfterFinished(60)
                        .template(new V1PodTemplateSpec()
                                .metadata(new V1ObjectMeta().labels(jobPodLabels(metadata, name)))
                                .spec(new V1PodSpec()
                                        .automountServiceAccountToken(false)
                                        .restartPolicy("Never")
                                        .containers(List.of(container)))));
        batchV1Api.createNamespacedJob(metadata.getNamespaceName(), job).execute();
    }

    /**
     * Removes only DB-specific credential resources. Cluster-scoped/shared
     * credential services are deliberately out of scope because they do not
     * carry this database's helper labels or deterministic helper names.
     */
    public CredentialCleanupObservation cleanupDatabaseResources(DatabaseMetadata metadata) {
        String namespace = metadata.getNamespaceName();
        try {
            for (V1Job job : batchV1Api.listNamespacedJob(namespace).execute().getItems()) {
                if (isCredentialHelper(job.getMetadata(), metadata)) {
                    deleteJob(namespace, job.getMetadata().getName());
                }
            }
            for (io.kubernetes.client.openapi.models.V1Pod pod : coreV1Api
                    .listNamespacedPod(namespace).execute().getItems()) {
                if (isCredentialHelper(pod.getMetadata(), metadata)) {
                    deletePod(namespace, pod.getMetadata().getName());
                }
            }
            for (V1Secret secret : coreV1Api.listNamespacedSecret(namespace).execute().getItems()) {
                if (isCredentialHelper(secret.getMetadata(), metadata)) {
                    deleteSecret(namespace, secret.getMetadata().getName());
                }
            }
            return databaseResourcesGone(metadata);
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) return CredentialCleanupObservation.gone();
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not clean database credential helper resources: " + exception.getMessage());
        }
    }

    private OperationResponse inProgressRotation(Map<String, String> annotations) {
        String operationId = annotations.get(OPERATION_ID);
        if (operationId == null || operationId.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Credentials are still being prepared");
        }
        return operationRepository.findById(operationId)
                .map(this::response)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "Credential rotation is already in progress"));
    }

    /** Verifies helper Jobs, Pods and managed credential Secrets have actually disappeared. */
    public CredentialCleanupObservation databaseResourcesGone(DatabaseMetadata metadata) {
        String namespace = metadata.getNamespaceName();
        try {
            int jobs = (int) batchV1Api.listNamespacedJob(namespace).execute().getItems().stream()
                    .filter(job -> isCredentialHelper(job.getMetadata(), metadata)).count();
            int pods = (int) coreV1Api.listNamespacedPod(namespace).execute().getItems().stream()
                    .filter(pod -> isCredentialHelper(pod.getMetadata(), metadata)).count();
            int secrets = (int) coreV1Api.listNamespacedSecret(namespace).execute().getItems().stream()
                    .filter(secret -> isCredentialHelper(secret.getMetadata(), metadata)).count();
            return new CredentialCleanupObservation(jobs == 0 && pods == 0 && secrets == 0,
                    jobs, pods, secrets,
                    jobs == 0 && pods == 0 && secrets == 0
                            ? "No database-specific credential helper resources remain"
                            : "Credential cleanup is waiting for " + jobs + " Job(s), " + pods
                            + " Pod(s), and " + secrets + " Secret(s)");
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) return CredentialCleanupObservation.gone();
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Could not verify database credential helper cleanup: " + exception.getMessage());
        }
    }

    private V1Secret ensureSecretMetadata(DatabaseMetadata metadata, V1Secret secret)
            throws io.kubernetes.client.openapi.ApiException {
        Map<String, String> labels = secret.getMetadata().getLabels() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(secret.getMetadata().getLabels());
        boolean changed = false;
        for (Map.Entry<String, String> entry : helperLabels(metadata).entrySet()) {
            if (!entry.getValue().equals(labels.put(entry.getKey(), entry.getValue()))) changed = true;
        }
        if (!hasClusterOwner(secret.getMetadata())) {
            secret.getMetadata().setOwnerReferences(ownerReferences(metadata));
            changed = true;
        }
        if (!changed) return secret;
        secret.getMetadata().setLabels(labels);
        replaceSecret(metadata.getNamespaceName(), secret);
        return secret;
    }

    private Map<String, String> helperLabels(DatabaseMetadata metadata) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(MANAGED_BY, MANAGED_BY_VALUE);
        labels.put(DATABASE_ID, metadata.getDatabaseId());
        labels.put(PROJECT_ID, metadata.getProjectName());
        // Short aliases make audit/cleanup selectors easy for cluster operators.
        labels.put("database-id", metadata.getDatabaseId());
        labels.put("project-id", metadata.getProjectName());
        labels.put(HELPER, "true");
        return labels;
    }

    private Map<String, String> jobPodLabels(DatabaseMetadata metadata, String jobName) {
        Map<String, String> labels = helperLabels(metadata);
        labels.put("app", jobName);
        return labels;
    }

    private List<V1OwnerReference> ownerReferences(DatabaseMetadata metadata) {
        return List.of(kubeBlocksClient.clusterOwnerReference(metadata.getNamespaceName(),
                metadata.getDatabaseId()));
    }

    private boolean hasClusterOwner(V1ObjectMeta metadata) {
        return metadata.getOwnerReferences() != null && metadata.getOwnerReferences().stream()
                .anyMatch(owner -> "Cluster".equals(owner.getKind()));
    }

    private boolean isCredentialHelper(V1ObjectMeta resource, DatabaseMetadata metadata) {
        if (resource == null || resource.getName() == null) return false;
        Map<String, String> labels = resource.getLabels() == null ? Map.of() : resource.getLabels();
        boolean labelled = metadata.getDatabaseId().equals(labels.get(DATABASE_ID))
                && (metadata.getProjectName().equals(labels.get(PROJECT_ID))
                || labels.get(PROJECT_ID) == null);
        if (labelled && "true".equals(labels.get(HELPER))) return true;
        // Backward-compatible cleanup for helper resources created before the
        // project/helper labels were introduced. Names are DB-ID specific.
        return resource.getName().equals(secretName(metadata.getDatabaseId()))
                || resource.getName().startsWith(metadata.getDatabaseId() + "-credentials-");
    }

    private void deleteFinishedCredentialResources(DatabaseMetadata metadata) {
        try {
            for (V1Job job : batchV1Api.listNamespacedJob(metadata.getNamespaceName()).execute().getItems()) {
                if (isCredentialHelper(job.getMetadata(), metadata)
                        && (succeeded(job) || failed(job))) {
                    deleteJob(metadata.getNamespaceName(), job.getMetadata().getName());
                }
            }
            for (io.kubernetes.client.openapi.models.V1Pod pod : coreV1Api
                    .listNamespacedPod(metadata.getNamespaceName()).execute().getItems()) {
                if (isCredentialHelper(pod.getMetadata(), metadata) && terminal(pod)) {
                    deletePod(metadata.getNamespaceName(), pod.getMetadata().getName());
                }
            }
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() != 404) {
                log.debug("Credential helper cleanup for {} will retry: {}", metadata.getDatabaseId(),
                        exception.getMessage());
            }
        }
    }

    private boolean terminal(io.kubernetes.client.openapi.models.V1Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getPhase() == null) return false;
        return "Succeeded".equalsIgnoreCase(pod.getStatus().getPhase())
                || "Failed".equalsIgnoreCase(pod.getStatus().getPhase());
    }

    private void deleteJob(String namespace, String name) throws io.kubernetes.client.openapi.ApiException {
        try {
            batchV1Api.deleteNamespacedJob(name, namespace).execute();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() != 404) throw exception;
        }
    }

    private void deletePod(String namespace, String name) throws io.kubernetes.client.openapi.ApiException {
        try {
            coreV1Api.deleteNamespacedPod(name, namespace).execute();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() != 404) throw exception;
        }
    }

    private void deleteSecret(String namespace, String name) throws io.kubernetes.client.openapi.ApiException {
        try {
            coreV1Api.deleteNamespacedSecret(name, namespace).execute();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() != 404) throw exception;
        }
    }

    public record CredentialCleanupObservation(boolean complete, int jobs, int pods, int secrets,
                                               String message) {
        static CredentialCleanupObservation gone() {
            return new CredentialCleanupObservation(true, 0, 0, 0,
                    "No database-specific credential helper resources remain");
        }
    }

    private String script(DatabaseEngine engine) {
        return switch (engine) {
            case POSTGRESQL -> """
                    export PGPASSWORD="$ADMIN_PASSWORD"
                    if psql -h "$DB_HOST" -U "$ADMIN_USERNAME" -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='$MANAGED_USERNAME'" | grep -q 1; then
                      psql -v ON_ERROR_STOP=1 -h "$DB_HOST" -U "$ADMIN_USERNAME" -d postgres -c "ALTER ROLE \"$MANAGED_USERNAME\" WITH LOGIN PASSWORD '$MANAGED_PASSWORD'"
                    else
                      psql -v ON_ERROR_STOP=1 -h "$DB_HOST" -U "$ADMIN_USERNAME" -d postgres -c "CREATE ROLE \"$MANAGED_USERNAME\" WITH LOGIN PASSWORD '$MANAGED_PASSWORD'"
                    fi
                    if ! psql -h "$DB_HOST" -U "$ADMIN_USERNAME" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$MANAGED_DATABASE'" | grep -q 1; then
                      psql -v ON_ERROR_STOP=1 -h "$DB_HOST" -U "$ADMIN_USERNAME" -d postgres -c "CREATE DATABASE \"$MANAGED_DATABASE\" OWNER \"$MANAGED_USERNAME\""
                    fi
                    psql -v ON_ERROR_STOP=1 -h "$DB_HOST" -U "$ADMIN_USERNAME" -d "$MANAGED_DATABASE" -c "GRANT ALL ON SCHEMA public TO \"$MANAGED_USERNAME\""
                    """;
            case MYSQL -> """
                    mysql --protocol=TCP -h "$DB_HOST" -u"$ADMIN_USERNAME" -p"$ADMIN_PASSWORD" -e "CREATE DATABASE IF NOT EXISTS $MANAGED_DATABASE; CREATE USER IF NOT EXISTS '$MANAGED_USERNAME'@'%' IDENTIFIED BY '$MANAGED_PASSWORD'; ALTER USER '$MANAGED_USERNAME'@'%' IDENTIFIED BY '$MANAGED_PASSWORD'; GRANT ALL PRIVILEGES ON $MANAGED_DATABASE.* TO '$MANAGED_USERNAME'@'%'; FLUSH PRIVILEGES;"
                    """;
            case MONGODB -> """
                    mongosh --quiet --host "$DB_HOST" --username "$ADMIN_USERNAME" --password "$ADMIN_PASSWORD" --authenticationDatabase admin --eval "const target=db.getSiblingDB('$MANAGED_DATABASE'); if (target.getUser('$MANAGED_USERNAME')) { target.updateUser('$MANAGED_USERNAME',{pwd:'$MANAGED_PASSWORD',roles:[{role:'readWrite',db:'$MANAGED_DATABASE'}]}); } else { target.createUser({user:'$MANAGED_USERNAME',pwd:'$MANAGED_PASSWORD',roles:[{role:'readWrite',db:'$MANAGED_DATABASE'}]}); }"
                    """;
        };
    }

    private V1EnvVar value(String name, String value) {
        return new V1EnvVar().name(name).value(value);
    }

    private V1EnvVar secret(String name, String secretName, String key) {
        return new V1EnvVar().name(name).valueFrom(new V1EnvVarSource()
                .secretKeyRef(new V1SecretKeySelector().name(secretName).key(key)));
    }

    private V1Job readJob(String namespace, String name)
            throws io.kubernetes.client.openapi.ApiException {
        try {
            return batchV1Api.readNamespacedJob(name, namespace).execute();
        } catch (io.kubernetes.client.openapi.ApiException exception) {
            if (exception.getCode() == 404) return null;
            throw exception;
        }
    }

    private boolean succeeded(V1Job job) {
        return job.getStatus() != null && job.getStatus().getSucceeded() != null
                && job.getStatus().getSucceeded() > 0;
    }

    private boolean failed(V1Job job) {
        if (job.getStatus() == null || job.getStatus().getConditions() == null) return false;
        return job.getStatus().getConditions().stream().anyMatch(this::failedCondition);
    }

    private boolean failedCondition(V1JobCondition condition) {
        return "Failed".equals(condition.getType()) && "True".equals(condition.getStatus());
    }

    private Map<String, String> annotations(V1Secret secret) {
        if (secret.getMetadata().getAnnotations() == null) {
            secret.getMetadata().setAnnotations(new LinkedHashMap<>());
        }
        return secret.getMetadata().getAnnotations();
    }

    private void replaceSecret(String namespace, V1Secret secret)
            throws io.kubernetes.client.openapi.ApiException {
        coreV1Api.replaceNamespacedSecret(secret.getMetadata().getName(), namespace, secret).execute();
    }

    private String value(V1Secret secret, String key) {
        if (secret.getData() == null || secret.getData().get(key) == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Managed credential Secret is missing " + key);
        }
        return new String(secret.getData().get(key), java.nio.charset.StandardCharsets.UTF_8);
    }

    private void markOperation(String operationId, OperationStatus status,
                               String message, boolean completed) {
        if (operationId == null || operationId.isBlank()) return;
        operationRepository.findById(operationId).ifPresent(operation -> {
            operation.setStatus(status);
            operation.setMessage(message);
            if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
            if (completed) operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
        });
    }

    private OperationMetadata operation(DatabaseMetadata database, String operationId) {
        return OperationMetadata.builder()
                .operationId(operationId)
                .databaseId(database.getDatabaseId())
                .projectName(database.getProjectName())
                .type(OperationType.ROTATE_CREDENTIALS)
                .status(OperationStatus.PENDING)
                .provisioningStage(com.cyfuture.dbaas.model.ProvisioningStage.QUEUED)
                .progress(0)
                .message("Credential rotation queued")
                .createdAt(Instant.now())
                .build();
    }

    private OperationResponse response(OperationMetadata operation) {
        return operationMapper.toResponse(operation);
    }

    private String secretName(String databaseId) {
        return databaseId + "-managed-credentials";
    }

    private String jobName(String databaseId, int generation) {
        return databaseId + "-credentials-" + generation;
    }

    private String randomPassword() {
        StringBuilder password = new StringBuilder(24);
        for (int index = 0; index < 24; index++) {
            password.append(PASSWORD_CHARS[secureRandom.nextInt(PASSWORD_CHARS.length)]);
        }
        return password.toString();
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
