package com.cyfuture.dbaas.client;

import com.cyfuture.dbaas.config.DatabaseProperties;
import com.cyfuture.dbaas.dto.CreateDatabaseRequest;
import com.cyfuture.dbaas.dto.BackupSettingsRequest;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;
import com.cyfuture.dbaas.model.SizePlan;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.apis.StorageV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodStatus;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimList;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.custom.Quantity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubeBlocksClientTest {
    private CustomObjectsApi customObjectsApi;
    private CoreV1Api coreV1Api;
    private KubeBlocksClient client;

    @BeforeEach
    void setUp() throws Exception {
        customObjectsApi = mock(CustomObjectsApi.class, RETURNS_DEEP_STUBS);
        coreV1Api = mock(CoreV1Api.class, RETURNS_DEEP_STUBS);
        client = new KubeBlocksClient(new DatabaseProperties(), customObjectsApi,
                coreV1Api, mock(StorageV1Api.class));
        when(customObjectsApi.getClusterCustomObject("apiextensions.k8s.io", "v1",
                "customresourcedefinitions", "opsrequests.operations.kubeblocks.io")
                .execute()).thenReturn(opsRequestCrd());
        when(customObjectsApi.getClusterCustomObject("apiextensions.k8s.io", "v1",
                "customresourcedefinitions", "clusters.apps.kubeblocks.io")
                .execute()).thenReturn(clusterCrd());
        when(customObjectsApi.createNamespacedCustomObject(eq("operations.kubeblocks.io"),
                eq("v1alpha1"), eq("dbaas-orders"), eq("opsrequests"), any()).execute())
                .thenReturn(Map.of());
    }

    @Test
    void verticalScalingManifestMatchesInstalledOpsRequestSchema() throws Exception {
        client.createVerticalScalingOpsRequest("dbaas-orders", "db-orders0001",
                "op-scale0001", "postgresql",
                Map.of("cpu", "1", "memory", "2Gi"),
                Map.of("cpu", "2", "memory", "4Gi"));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).createNamespacedCustomObject(eq("operations.kubeblocks.io"),
                eq("v1alpha1"), eq("dbaas-orders"), eq("opsrequests"), body.capture());
        Map<?, ?> manifest = (Map<?, ?>) body.getValue();
        Map<?, ?> spec = (Map<?, ?>) manifest.get("spec");
        assertEquals("VerticalScaling", spec.get("type"));
        assertEquals("db-orders0001", spec.get("clusterName"));
        List<?> scaling = (List<?>) spec.get("verticalScaling");
        Map<?, ?> component = (Map<?, ?>) scaling.get(0);
        assertEquals("postgresql", component.get("componentName"));
        assertEquals(Map.of("cpu", "1", "memory", "2Gi"), component.get("requests"));
        assertEquals(Map.of("cpu", "2", "memory", "4Gi"), component.get("limits"));
    }

    @Test
    void horizontalScalingUsesReplicaChangeDelta() throws Exception {
        client.createHorizontalScalingOpsRequest("dbaas-orders", "db-orders0001",
                "op-scale0002", "postgresql", 2, 4);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).createNamespacedCustomObject(eq("operations.kubeblocks.io"),
                eq("v1alpha1"), eq("dbaas-orders"), eq("opsrequests"), body.capture());
        Map<?, ?> spec = (Map<?, ?>) ((Map<?, ?>) body.getValue()).get("spec");
        Map<?, ?> component = (Map<?, ?>) ((List<?>) spec.get("horizontalScaling")).get(0);
        assertEquals(Map.of("replicaChanges", 2), component.get("scaleOut"));
    }

    @Test
    void migratesStrictInPlacePolicyToPreferInPlaceBeforeVerticalScaling() throws Exception {
        when(customObjectsApi.getNamespacedCustomObject("apps.kubeblocks.io", "v1",
                "dbaas-orders", "clusters", "db-orders0001").execute())
                .thenReturn(clusterWithPolicy("StrictInPlace"));
        when(customObjectsApi.replaceNamespacedCustomObject(eq("apps.kubeblocks.io"),
                eq("v1"), eq("dbaas-orders"), eq("clusters"),
                eq("db-orders0001"), any()).execute()).thenReturn(Map.of());

        client.ensurePreferInPlacePodUpdatePolicy("dbaas-orders",
                "db-orders0001", "postgresql");

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).replaceNamespacedCustomObject(eq("apps.kubeblocks.io"),
                eq("v1"), eq("dbaas-orders"), eq("clusters"),
                eq("db-orders0001"), body.capture());
        Map<?, ?> spec = (Map<?, ?>) ((Map<?, ?>) body.getValue()).get("spec");
        Map<?, ?> component = (Map<?, ?>) ((List<?>) spec.get("componentSpecs")).get(0);
        assertEquals("PreferInPlace", component.get("podUpdatePolicy"));
    }

    @Test
    void extractsComponentsFromNormalAndShardedClusters() throws Exception {
        when(customObjectsApi.getNamespacedCustomObject("apps.kubeblocks.io", "v1",
                "dbaas-orders", "clusters", "db-orders0001").execute())
                .thenReturn(cluster());

        List<KubeBlocksClient.ClusterComponentInfo> components = client.components(
                "dbaas-orders", "db-orders0001");

        assertEquals(List.of("mongos", "config-server", "shard"),
                components.stream().map(KubeBlocksClient.ClusterComponentInfo::name).toList());
        assertTrue(components.get(2).sharding());
        assertEquals("20Gi", components.get(2).storage("data"));
        assertEquals("StrictInPlace", components.get(2).podUpdatePolicy());
    }

    @Test
    void createsPostgresqlMongoAndMysqlWithPreferInPlacePolicy() throws Exception {
        client.create("dbaas-orders", "prj-orders", "db-postgres0001",
                request(DatabaseEngine.POSTGRESQL, DatabaseMode.REPLICATION));
        client.create("dbaas-orders", "prj-orders", "db-mongo000001",
                request(DatabaseEngine.MONGODB, DatabaseMode.REPLICA_SET));
        client.create("dbaas-orders", "prj-orders", "db-mysql000001",
                request(DatabaseEngine.MYSQL, DatabaseMode.REPLICATION));

        ArgumentCaptor<Object> bodies = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi, times(3)).createNamespacedCustomObject(
                eq("apps.kubeblocks.io"), eq("v1"), eq("dbaas-orders"), eq("clusters"), bodies.capture());
        for (Object body : bodies.getAllValues()) {
            Map<?, ?> spec = (Map<?, ?>) ((Map<?, ?>) body).get("spec");
            for (Object component : (List<?>) spec.get("componentSpecs")) {
                assertEquals("PreferInPlace", ((Map<?, ?>) component).get("podUpdatePolicy"));
            }
        }
    }

    @Test
    void migratesEveryManagedStrictInPlaceComponentWithoutTouchingUnmanagedClusters() throws Exception {
        Map<String, Object> managed = clusterWithPolicy("StrictInPlace");
        managed.put("metadata", new java.util.LinkedHashMap<>(Map.of(
                "name", "db-orders0001", "namespace", "dbaas-orders",
                "labels", Map.of("app.kubernetes.io/managed-by", "cyfuture-dbaas"))));
        Map<String, Object> unmanaged = clusterWithPolicy("StrictInPlace");
        unmanaged.put("metadata", Map.of("name", "other", "namespace", "default", "labels", Map.of()));
        when(customObjectsApi.listClusterCustomObject("apps.kubeblocks.io", "v1", "clusters")
                .execute()).thenReturn(Map.of("items", List.of(managed, unmanaged)));
        when(customObjectsApi.replaceNamespacedCustomObject(eq("apps.kubeblocks.io"), eq("v1"),
                eq("dbaas-orders"), eq("clusters"), eq("db-orders0001"), any()).execute())
                .thenReturn(Map.of());

        assertEquals(1, client.migrateManagedClustersToPreferInPlace());
        Map<?, ?> component = (Map<?, ?>) ((List<?>) ((Map<?, ?>) managed.get("spec"))
                .get("componentSpecs")).get(0);
        assertEquals("PreferInPlace", component.get("podUpdatePolicy"));
    }

    @Test
    void skipsTerminatingClustersDuringPodUpdatePolicyMigration() throws Exception {
        Map<String, Object> terminating = clusterWithPolicy("StrictInPlace");
        terminating.put("metadata", new java.util.LinkedHashMap<>(Map.of(
                "name", "db-deleting0001", "namespace", "dbaas-deleting",
                "labels", Map.of("app.kubernetes.io/managed-by", "cyfuture-dbaas"),
                "deletionTimestamp", "2026-09-02T07:45:14Z")));
        when(customObjectsApi.listClusterCustomObject("apps.kubeblocks.io", "v1", "clusters")
                .execute()).thenReturn(Map.of("items", List.of(terminating)));

        assertEquals(0, client.migrateManagedClustersToPreferInPlace());
        verify(customObjectsApi, never()).replaceNamespacedCustomObject(
                eq("apps.kubeblocks.io"), eq("v1"), any(), eq("clusters"), any(), any());
        Map<?, ?> component = (Map<?, ?>) ((List<?>) ((Map<?, ?>) terminating.get("spec"))
                .get("componentSpecs")).get(0);
        assertEquals("StrictInPlace", component.get("podUpdatePolicy"));
    }

    @Test
    void ignoresAClusterThatDisappearsDuringPodUpdatePolicyMigration() throws Exception {
        Map<String, Object> vanished = managedCluster("db-vanished0001", "dbaas-removed");
        Map<String, Object> active = managedCluster("db-active00001", "dbaas-orders");
        when(customObjectsApi.listClusterCustomObject("apps.kubeblocks.io", "v1", "clusters")
                .execute()).thenReturn(Map.of("items", List.of(vanished, active)));
        when(customObjectsApi.replaceNamespacedCustomObject(eq("apps.kubeblocks.io"), eq("v1"),
                eq("dbaas-removed"), eq("clusters"), eq("db-vanished0001"), any()).execute())
                .thenThrow(new io.kubernetes.client.openapi.ApiException(404, "Not Found"));
        when(customObjectsApi.replaceNamespacedCustomObject(eq("apps.kubeblocks.io"), eq("v1"),
                eq("dbaas-orders"), eq("clusters"), eq("db-active00001"), any()).execute())
                .thenReturn(Map.of());

        assertEquals(1, client.migrateManagedClustersToPreferInPlace());
        verify(customObjectsApi).replaceNamespacedCustomObject(eq("apps.kubeblocks.io"), eq("v1"),
                eq("dbaas-orders"), eq("clusters"), eq("db-active00001"), any());
    }

    @Test
    void verifiesActualRequestedPodResourcesBeforeCompletingVerticalScaling() throws Exception {
        when(customObjectsApi.getNamespacedCustomObject("apps.kubeblocks.io", "v1",
                "dbaas-orders", "clusters", "db-orders0001").execute())
                .thenReturn(clusterWithPolicy("PreferInPlace"));
        V1ResourceRequirements resources = new V1ResourceRequirements()
                .requests(Map.of("cpu", new Quantity("1000m"), "memory", new Quantity("2048Mi")))
                .limits(Map.of("cpu", new Quantity("2"), "memory", new Quantity("4Gi")));
        V1Pod pod = new V1Pod()
                .metadata(new V1ObjectMeta().name("db-orders0001-postgresql-0").labels(Map.of(
                        "app.kubernetes.io/instance", "db-orders0001",
                        "apps.kubeblocks.io/component-name", "postgresql")))
                .spec(new V1PodSpec().containers(List.of(new V1Container()
                        .name("postgresql").resources(resources))))
                .status(new V1PodStatus().conditions(List.of(new V1PodCondition()
                        .type("Ready").status("True"))));
        when(coreV1Api.listNamespacedPod("dbaas-orders")
                .labelSelector("app.kubernetes.io/instance=db-orders0001").execute())
                .thenReturn(new V1PodList().items(List.of(pod, pod)));

        KubeBlocksClient.VerticalScalingObservation observation = client.observeVerticalScaling(
                "dbaas-orders", "db-orders0001", "postgresql",
                Map.of("cpu", "1", "memory", "2Gi"),
                Map.of("cpu", "2", "memory", "4Gi"));

        assertTrue(observation.complete());
    }

    @Test
    void updatesOnlyDeletionProtectionFields() throws Exception {
        when(customObjectsApi.getNamespacedCustomObject("apps.kubeblocks.io", "v1",
                "dbaas-orders", "clusters", "db-orders0001").execute())
                .thenReturn(observableCluster());
        when(coreV1Api.listNamespacedPod("dbaas-orders")
                .labelSelector("app.kubernetes.io/instance=db-orders0001").execute())
                .thenReturn(new V1PodList().items(List.of()));
        when(coreV1Api.listNamespacedPersistentVolumeClaim("dbaas-orders")
                .labelSelector("app.kubernetes.io/instance=db-orders0001").execute())
                .thenReturn(new V1PersistentVolumeClaimList().items(List.of()));

        client.setDeletionProtection("dbaas-orders", "db-orders0001", true);

        ArgumentCaptor<Object> replacement = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).replaceNamespacedCustomObject(eq("apps.kubeblocks.io"), eq("v1"),
                eq("dbaas-orders"), eq("clusters"), eq("db-orders0001"), replacement.capture());
        Map<?, ?> body = (Map<?, ?>) replacement.getValue();
        assertEquals("DoNotTerminate", ((Map<?, ?>) body.get("spec")).get("terminationPolicy"));
        Map<?, ?> metadata = (Map<?, ?>) body.get("metadata");
        Map<?, ?> annotations = (Map<?, ?>) metadata.get("annotations");
        assertEquals("true", annotations.get("dbaas.cyfuture.com/deletion-protection"));
    }

    @Test
    void deletesOnlyANamespaceOwnedByTheProject() throws Exception {
        when(coreV1Api.readNamespace("dbaas-p-prj-orders0001").execute()).thenReturn(
                new V1Namespace().metadata(new V1ObjectMeta().labels(Map.of(
                        "app.kubernetes.io/managed-by", "cyfuture-dbaas",
                        "dbaas.cyfuture.com/project", "prj-orders0001"))));

        client.deleteProjectNamespace("dbaas-p-prj-orders0001", "prj-orders0001");

        verify(coreV1Api).deleteNamespace("dbaas-p-prj-orders0001");
    }

    @Test
    void projectDeletionClearsProtectionBeforeDeletingTheCluster() throws Exception {
        when(customObjectsApi.getNamespacedCustomObject("apps.kubeblocks.io", "v1",
                "dbaas-orders", "clusters", "db-orders0001").execute())
                .thenReturn(observableCluster());

        client.prepareProjectDatabaseDeletion("dbaas-orders", "db-orders0001");

        ArgumentCaptor<Object> replacement = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).replaceNamespacedCustomObject(eq("apps.kubeblocks.io"), eq("v1"),
                eq("dbaas-orders"), eq("clusters"), eq("db-orders0001"), replacement.capture());
        Map<?, ?> body = (Map<?, ?>) replacement.getValue();
        assertEquals("WipeOut", ((Map<?, ?>) body.get("spec")).get("terminationPolicy"));
        Map<?, ?> annotations = (Map<?, ?>) ((Map<?, ?>) body.get("metadata"))
                .get("annotations");
        assertEquals("false", annotations.get("dbaas.cyfuture.com/deletion-protection"));
        verify(customObjectsApi).deleteNamespacedCustomObject(
                "apps.kubeblocks.io", "v1", "dbaas-orders", "clusters", "db-orders0001");
    }

    @Test
    void databaseDeletionUsesWipeOutSoClusterPvcsAreRemoved() throws Exception {
        when(customObjectsApi.getNamespacedCustomObject("apps.kubeblocks.io", "v1",
                "dbaas-orders", "clusters", "db-orders0001").execute())
                .thenReturn(observableCluster());

        client.requestDelete("dbaas-orders", "db-orders0001");

        ArgumentCaptor<Object> replacement = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).replaceNamespacedCustomObject(eq("apps.kubeblocks.io"), eq("v1"),
                eq("dbaas-orders"), eq("clusters"), eq("db-orders0001"), replacement.capture());
        Map<?, ?> body = (Map<?, ?>) replacement.getValue();
        assertEquals("WipeOut", ((Map<?, ?>) body.get("spec")).get("terminationPolicy"));
        verify(customObjectsApi).deleteNamespacedCustomObject(
                "apps.kubeblocks.io", "v1", "dbaas-orders", "clusters", "db-orders0001");
    }

    @Test
    void resolvesCurrentDefaultPolicyWhenApprovedRepositoryIsClusterDefault() throws Exception {
        readyBackupRepository(true);
        when(customObjectsApi.listNamespacedCustomObject("dataprotection.kubeblocks.io", "v1alpha1",
                "dbaas-orders", "backuppolicies").execute()).thenReturn(Map.of("items", List.of(
                backupPolicy("dataprotection.kubeblocks.io/is-default-policy", null))));

        KubeBlocksClient.BackupPolicyInfo policy = client.resolveReadyBackupPolicy(
                "dbaas-orders", "db-orders0001", DatabaseEngine.POSTGRESQL,
                "pg-basebackup", "cyfuture-dbaas-backuprepo");

        assertEquals("db-orders0001-postgresql-backup-policy", policy.policyName());
        assertEquals("cyfuture-dbaas-backuprepo", policy.repositoryName());
        assertEquals("pg-basebackup", policy.backupMethod());
    }

    @Test
    void rejectsAnExplicitPolicyRepositoryOtherThanTheApprovedOne() throws Exception {
        readyBackupRepository(true);
        when(customObjectsApi.listNamespacedCustomObject("dataprotection.kubeblocks.io", "v1alpha1",
                "dbaas-orders", "backuppolicies").execute()).thenReturn(Map.of("items", List.of(
                backupPolicy("dataprotection.kubeblocks.io/is-default-policy", "other-repository"))));

        com.cyfuture.dbaas.exception.ApiException exception = assertThrows(
                com.cyfuture.dbaas.exception.ApiException.class,
                () -> client.resolveReadyBackupPolicy("dbaas-orders", "db-orders0001",
                        DatabaseEngine.POSTGRESQL, "pg-basebackup", "cyfuture-dbaas-backuprepo"));

        assertEquals("BACKUP_REPOSITORY_MISMATCH", exception.getCode());
    }

    @Test
    void restoreManifestMatchesInstalledKubeBlocksSchema() throws Exception {
        client.createRestoreOpsRequest("dbaas-orders", "prj-orders", "db-restore0001",
                "rst-restore0001", "bkp-backup0001", null);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).createNamespacedCustomObject(eq("operations.kubeblocks.io"),
                eq("v1alpha1"), eq("dbaas-orders"), eq("opsrequests"), body.capture());
        Map<?, ?> spec = (Map<?, ?>) ((Map<?, ?>) body.getValue()).get("spec");
        assertEquals("Restore", spec.get("type"));
        assertEquals("db-restore0001", spec.get("clusterName"));
        Map<?, ?> restore = (Map<?, ?>) spec.get("restore");
        assertEquals("bkp-backup0001", restore.get("backupName"));
        assertEquals("Parallel", restore.get("volumeRestorePolicy"));
    }

    @Test
    void scheduledBackupConfigurationPatchesOnlyClusterSpecBackup() throws Exception {
        readyBackupRepository(true);
        Map<String, Object> cluster = new java.util.LinkedHashMap<>();
        cluster.put("metadata", Map.of("name", "db-orders0001", "labels", Map.of(
                "app.kubernetes.io/managed-by", "cyfuture-dbaas",
                "dbaas.cyfuture.com/project", "prj-orders",
                "dbaas.cyfuture.com/database-id", "db-orders0001")));
        cluster.put("spec", new java.util.LinkedHashMap<>(Map.of("componentSpecs", List.of())));
        when(customObjectsApi.getNamespacedCustomObject("apps.kubeblocks.io", "v1",
                "dbaas-orders", "clusters", "db-orders0001").execute()).thenReturn(cluster);
        client.configureScheduledBackup("dbaas-orders", "prj-orders", "db-orders0001",
                "pg-basebackup", "cyfuture-dbaas-backuprepo", "7d", "0 2 * * *", true);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).patchNamespacedCustomObject(eq("apps.kubeblocks.io"), eq("v1"),
                eq("dbaas-orders"), eq("clusters"), eq("db-orders0001"), body.capture());
        Map<?, ?> backup = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) body.getValue()).get("spec")).get("backup");
        assertEquals(true, backup.get("enabled"));
        assertEquals("pg-basebackup", backup.get("method"));
        assertEquals("cyfuture-dbaas-backuprepo", backup.get("repoName"));
        assertEquals("7d", backup.get("retentionPeriod"));
        assertEquals("0 2 * * *", backup.get("cronExpression"));
        assertEquals(false, backup.get("pitrEnabled"));
    }

    @Test
    void scheduledBackupConfigurationDoesNotRewriteAnUnchangedCluster() throws Exception {
        readyBackupRepository(true);
        Map<String, Object> cluster = new java.util.LinkedHashMap<>();
        cluster.put("metadata", Map.of("name", "db-orders0001", "labels", Map.of(
                "app.kubernetes.io/managed-by", "cyfuture-dbaas",
                "dbaas.cyfuture.com/project", "prj-orders",
                "dbaas.cyfuture.com/database-id", "db-orders0001")));
        cluster.put("spec", new java.util.LinkedHashMap<>(Map.of("backup", Map.of(
                "enabled", true, "method", "pg-basebackup", "repoName", "cyfuture-dbaas-backuprepo",
                "retentionPeriod", "7d", "cronExpression", "0 2 * * *", "pitrEnabled", false,
                "incrementalBackupEnabled", false))));
        when(customObjectsApi.getNamespacedCustomObject("apps.kubeblocks.io", "v1",
                "dbaas-orders", "clusters", "db-orders0001").execute()).thenReturn(cluster);

        client.configureScheduledBackup("dbaas-orders", "prj-orders", "db-orders0001",
                "pg-basebackup", "cyfuture-dbaas-backuprepo", "7d", "0 2 * * *", true);

        verify(customObjectsApi, never()).patchNamespacedCustomObject(eq("apps.kubeblocks.io"), eq("v1"),
                eq("dbaas-orders"), eq("clusters"), eq("db-orders0001"), any());
    }

    @Test
    void manualBackupManifestUsesManagedIdentityAndDeleteDeletionPolicy() throws Exception {
        when(customObjectsApi.createNamespacedCustomObject(eq("dataprotection.kubeblocks.io"),
                eq("v1alpha1"), eq("dbaas-orders"), eq("backups"), any()).execute()).thenReturn(Map.of());

        client.createBackup("dbaas-orders", "prj-orders", "db-orders0001", "bkp-orders0001",
                "db-orders-policy", "pg-basebackup", "7d", null,
                "bkp-orders0001", "op-orders0001");

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).createNamespacedCustomObject(eq("dataprotection.kubeblocks.io"),
                eq("v1alpha1"), eq("dbaas-orders"), eq("backups"), body.capture());
        Map<?, ?> manifest = (Map<?, ?>) body.getValue();
        assertEquals("Delete", ((Map<?, ?>) manifest.get("spec")).get("deletionPolicy"));
        Map<?, ?> labels = (Map<?, ?>) ((Map<?, ?>) manifest.get("metadata")).get("labels");
        assertEquals("prj-orders", labels.get("dbaas.cyfuture.com/project"));
        assertEquals("db-orders0001", labels.get("dbaas.cyfuture.com/database-id"));
        assertEquals("bkp-orders0001", labels.get("dbaas.cyfuture.com/backup-id"));
        assertEquals("op-orders0001", labels.get("dbaas.cyfuture.com/operation-id"));
    }

    @Test
    void discoversOnlyBackupScheduleOwnedBackupsForTheKnownPolicy() throws Exception {
        Map<String, Object> generated = Map.of(
                "metadata", Map.of("name", "scheduled-good", "uid", "uid-good", "labels", Map.of(),
                        "ownerReferences", List.of(Map.of("kind", "BackupSchedule"))),
                "spec", Map.of("backupPolicyName", "db-orders-policy", "backupMethod", "pg-basebackup",
                        "retentionPeriod", "7d"),
                "status", Map.of("phase", "Completed", "totalSize", 42L));
        Map<String, Object> unknown = Map.of(
                "metadata", Map.of("name", "manual-other", "uid", "uid-other", "labels", Map.of(
                        "app.kubernetes.io/instance", "db-orders0001")),
                "spec", Map.of("backupPolicyName", "db-orders-policy"), "status", Map.of());
        when(customObjectsApi.listNamespacedCustomObject("dataprotection.kubeblocks.io", "v1alpha1",
                "dbaas-orders", "backups").execute()).thenReturn(Map.of("items", List.of(generated, unknown)));

        var discovered = client.listScheduledBackups("dbaas-orders", "db-orders0001", "db-orders-policy");

        assertEquals(1, discovered.size());
        assertEquals("scheduled-good", discovered.get(0).backupName());
        assertEquals("uid-good", discovered.get(0).uid());
        assertEquals(42L, discovered.get(0).sizeBytes());
    }

    @Test
    void deletesAnImportedScheduledBackupOnlyWhenUidAndPolicyStillMatch() throws Exception {
        Map<String, Object> scheduled = Map.of(
                "metadata", Map.of("uid", "uid-scheduled-001", "labels", Map.of(
                        "app.kubernetes.io/instance", "db-orders0001"),
                        "ownerReferences", List.of(Map.of("kind", "BackupSchedule"))),
                "spec", Map.of("backupPolicyName", "db-orders-policy"));
        when(customObjectsApi.getNamespacedCustomObject("dataprotection.kubeblocks.io", "v1alpha1",
                "dbaas-orders", "backups", "scheduled-orders-001").execute()).thenReturn(scheduled);

        client.deleteManagedBackup("dbaas-orders", "prj-orders", "db-orders0001",
                "bkp-a-001", "op-a-001", "scheduled-orders-001", "uid-scheduled-001",
                "db-orders-policy");

        verify(customObjectsApi).deleteNamespacedCustomObject("dataprotection.kubeblocks.io", "v1alpha1",
                "dbaas-orders", "backups", "scheduled-orders-001");
    }

    @Test
    void refusesToDeleteANameReusedByAnotherScheduledBackup() throws Exception {
        Map<String, Object> replacement = Map.of(
                "metadata", Map.of("uid", "uid-new", "labels", Map.of(
                        "app.kubernetes.io/instance", "db-orders0001"),
                        "ownerReferences", List.of(Map.of("kind", "BackupSchedule"))),
                "spec", Map.of("backupPolicyName", "db-orders-policy"));
        when(customObjectsApi.getNamespacedCustomObject("dataprotection.kubeblocks.io", "v1alpha1",
                "dbaas-orders", "backups", "scheduled-orders-001").execute()).thenReturn(replacement);

        com.cyfuture.dbaas.exception.ApiException exception = assertThrows(
                com.cyfuture.dbaas.exception.ApiException.class,
                () -> client.deleteManagedBackup("dbaas-orders", "prj-orders", "db-orders0001",
                        "bkp-a-001", "op-a-001", "scheduled-orders-001", "uid-historic",
                        "db-orders-policy"));

        assertEquals("BACKUP_RESOURCE_NOT_MANAGED", exception.getCode());
        verify(customObjectsApi, never()).deleteNamespacedCustomObject(
                eq("dataprotection.kubeblocks.io"), eq("v1alpha1"), eq("dbaas-orders"),
                eq("backups"), eq("scheduled-orders-001"));
    }

    @Test
    void backupObservationConvertsKubernetesQuantityToBytes() throws Exception {
        when(customObjectsApi.getNamespacedCustomObject("dataprotection.kubeblocks.io", "v1alpha1",
                "dbaas-orders", "backups", "bkp-orders0001").execute()).thenReturn(Map.of(
                "metadata", Map.of("uid", "backup-uid"),
                "status", Map.of("phase", "Completed", "totalSize", "1.5Gi",
                        "startTimestamp", "2026-09-08T10:00:00Z",
                        "completionTimestamp", "2026-09-08T10:01:00Z",
                        "expiration", "2026-09-15T10:01:00Z")));

        KubeBlocksClient.BackupObservation observed = client.observeBackup(
                "dbaas-orders", "bkp-orders0001");

        assertEquals(1_610_612_736L, observed.sizeBytes());
        assertEquals(Instant.parse("2026-09-15T10:01:00Z"), observed.expiration());
    }

    @Test
    void backupObservationDoesNotExposeSecretOrPrivateEndpointDiagnostics() throws Exception {
        when(customObjectsApi.getNamespacedCustomObject("dataprotection.kubeblocks.io", "v1alpha1",
                "dbaas-orders", "backups", "bkp-orders0001").execute()).thenReturn(Map.of(
                "metadata", Map.of("uid", "backup-uid"),
                "status", Map.of("phase", "Failed", "failureReason",
                        "Secret backup-s3 password=not-for-api at db.orders.svc.cluster.local")));

        KubeBlocksClient.BackupObservation observed = client.observeBackup(
                "dbaas-orders", "bkp-orders0001");

        assertFalse(observed.message().toLowerCase().contains("secret"));
        assertFalse(observed.message().toLowerCase().contains("password"));
        assertFalse(observed.message().contains("svc.cluster.local"));
    }

    @Test
    void observesOnlyRestoreOwnedByTheKnownOpsRequest() throws Exception {
        Map<String, Object> restore = Map.of(
                "metadata", Map.of("name", "restore-orders", "ownerReferences", List.of(
                        Map.of("kind", "OpsRequest", "name", "rst-orders0001"))),
                "status", Map.of("phase", "Completed", "startTimestamp", "2026-09-08T10:00:00Z",
                        "completionTimestamp", "2026-09-08T10:02:00Z"));
        when(customObjectsApi.listNamespacedCustomObject("dataprotection.kubeblocks.io", "v1alpha1",
                "dbaas-orders", "restores").execute()).thenReturn(Map.of("items", List.of(restore)));

        KubeBlocksClient.RestoreObservation observed = client.observeRestore(
                "dbaas-orders", "rst-orders0001", null);

        assertTrue(observed.exists());
        assertEquals("restore-orders", observed.restoreName());
        assertEquals("Completed", observed.phase());
    }

    @Test
    void validatesLinkedTemplateFromTheV102DataProtectionApi() throws Exception {
        readyBackupRepository(true);
        Map<String, Object> policy = new java.util.LinkedHashMap<>(backupPolicy(
                "dataprotection.kubeblocks.io/is-default-policy", null));
        Map<String, Object> metadata = new java.util.LinkedHashMap<>((Map<String, Object>) policy.get("metadata"));
        metadata.put("annotations", Map.of(
                "dataprotection.kubeblocks.io/is-default-policy", "true",
                "dataprotection.kubeblocks.io/backup-policy-template", "postgres-template"));
        policy.put("metadata", metadata);
        when(customObjectsApi.listNamespacedCustomObject("dataprotection.kubeblocks.io", "v1alpha1",
                "dbaas-orders", "backuppolicies").execute()).thenReturn(Map.of("items", List.of(policy)));
        when(customObjectsApi.getClusterCustomObject("dataprotection.kubeblocks.io", "v1alpha1",
                "backuppolicytemplates", "postgres-template").execute()).thenReturn(Map.of(
                "spec", Map.of("backupMethods", List.of(Map.of("name", "pg-basebackup"))),
                "status", Map.of("phase", "Available")));
        org.mockito.Mockito.clearInvocations(customObjectsApi);

        client.resolveReadyBackupPolicy("dbaas-orders", "db-orders0001", DatabaseEngine.POSTGRESQL,
                "pg-basebackup", "cyfuture-dbaas-backuprepo");

        verify(customObjectsApi).getClusterCustomObject("dataprotection.kubeblocks.io", "v1alpha1",
                "backuppolicytemplates", "postgres-template");
    }

    @Test
    void databaseCreationCarriesNormalizedBackupSpecWhenConfigured() throws Exception {
        CreateDatabaseRequest backupRequest = new CreateDatabaseRequest("orders-db", null,
                DatabaseEngine.POSTGRESQL, DatabaseMode.REPLICATION, "test-version", SizePlan.C1G1,
                10, 2, 0, null, List.of(), false, Map.of(),
                new BackupSettingsRequest(true, 7, "30 20 * * *", "Asia/Kolkata", false));

        client.create("dbaas-orders", "prj-orders", "db-postgres0002", backupRequest);

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(customObjectsApi).createNamespacedCustomObject(eq("apps.kubeblocks.io"), eq("v1"),
                eq("dbaas-orders"), eq("clusters"), body.capture());
        Map<?, ?> backup = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) body.getValue()).get("spec")).get("backup");
        assertEquals("pg-basebackup", backup.get("method"));
        assertEquals("30 20 * * *", backup.get("cronExpression"));
        assertEquals(false, backup.get("pitrEnabled"));
    }

    private void readyBackupRepository(boolean defaultRepository) throws Exception {
        when(customObjectsApi.getClusterCustomObject("dataprotection.kubeblocks.io", "v1alpha1",
                "backuprepos", "cyfuture-dbaas-backuprepo").execute()).thenReturn(Map.of("status", Map.of(
                "phase", "Ready", "isDefault", defaultRepository)));
    }

    private Map<String, Object> backupPolicy(String defaultAnnotation, String repositoryName) {
        Map<String, Object> spec = new java.util.LinkedHashMap<>();
        spec.put("backupMethods", List.of(Map.of("name", "pg-basebackup")));
        if (repositoryName != null) spec.put("backupRepoName", repositoryName);
        return Map.of(
                "metadata", Map.of(
                        "name", "db-orders0001-postgresql-backup-policy",
                        "labels", Map.of("app.kubernetes.io/instance", "db-orders0001"),
                        "annotations", Map.of(defaultAnnotation, "true")),
                "spec", spec,
                "status", Map.of("phase", "Available"));
    }

    private CreateDatabaseRequest request(DatabaseEngine engine, DatabaseMode mode) {
        return new CreateDatabaseRequest("orders-db", null, engine, mode, "test-version",
                SizePlan.C1G1, 10, 2, 0, null, List.of(), false, Map.of());
    }

    private Map<String, Object> opsRequestCrd() {
        Map<String, Object> restore = Map.of(
                "backupName", Map.of("type", "string"),
                "volumeRestorePolicy", Map.of("type", "string"),
                "restorePointInTime", Map.of("type", "string"));
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("clusterName", Map.of("type", "string"));
        properties.put("type", Map.of("type", "string"));
        properties.put("verticalScaling", Map.of("type", "array"));
        properties.put("horizontalScaling", Map.of("type", "array"));
        properties.put("volumeExpansion", Map.of("type", "array"));
        properties.put("restart", Map.of("type", "array"));
        properties.put("restore", Map.of("type", "object", "properties", restore));
        return Map.of("spec", Map.of("versions", List.of(Map.of(
                "name", "v1alpha1",
                "schema", Map.of("openAPIV3Schema", Map.of("properties", Map.of(
                        "spec", Map.of("properties", properties))))))));
    }

    private Map<String, Object> clusterCrd() {
        Map<String, Object> backup = new java.util.LinkedHashMap<>();
        backup.put("enabled", Map.of("type", "boolean"));
        backup.put("method", Map.of("type", "string"));
        backup.put("continuousMethod", Map.of("type", "string"));
        backup.put("repoName", Map.of("type", "string"));
        backup.put("retentionPeriod", Map.of("type", "string"));
        backup.put("cronExpression", Map.of("type", "string"));
        backup.put("pitrEnabled", Map.of("type", "boolean"));
        backup.put("incrementalBackupEnabled", Map.of("type", "boolean"));
        return Map.of("spec", Map.of("versions", List.of(Map.of(
                "name", "v1",
                "schema", Map.of("openAPIV3Schema", Map.of("properties", Map.of(
                        "spec", Map.of("properties", Map.of(
                                "backup", Map.of("properties", backup))))))))));
    }

    private Map<String, Object> cluster() {
        return Map.of("spec", Map.of(
                "componentSpecs", List.of(
                        component("mongos", 2, Map.of()),
                        component("config-server", 3, Map.of("data", "10Gi"))),
                "shardings", List.of(Map.of(
                        "name", "shard",
                        "shards", 2,
                        "template", component("shard", 3, Map.of("data", "20Gi"))))));
    }

    private Map<String, Object> clusterWithPolicy(String policy) {
        Map<String, Object> component = new java.util.LinkedHashMap<>();
        component.put("name", "postgresql");
        component.put("replicas", 2);
        component.put("podUpdatePolicy", policy);
        component.put("volumeClaimTemplates", List.of());
        Map<String, Object> spec = new java.util.LinkedHashMap<>();
        spec.put("componentSpecs", List.of(component));
        Map<String, Object> cluster = new java.util.LinkedHashMap<>();
        cluster.put("spec", spec);
        return cluster;
    }

    private Map<String, Object> managedCluster(String name, String namespace) {
        Map<String, Object> cluster = clusterWithPolicy("StrictInPlace");
        cluster.put("metadata", new java.util.LinkedHashMap<>(Map.of(
                "name", name, "namespace", namespace,
                "labels", Map.of("app.kubernetes.io/managed-by", "cyfuture-dbaas"))));
        return cluster;
    }

    private Map<String, Object> observableCluster() {
        return Map.of(
                "metadata", Map.of("name", "db-orders0001", "annotations", Map.of(
                        "dbaas.cyfuture.com/engine", "POSTGRESQL",
                        "dbaas.cyfuture.com/mode", "STANDALONE",
                        "dbaas.cyfuture.com/version", "17.5.0",
                        "dbaas.cyfuture.com/size", "C1G1",
                        "dbaas.cyfuture.com/storage-gi", "10")),
                "spec", Map.of("componentSpecs", List.of(Map.of(
                        "name", "postgresql", "replicas", 1))),
                "status", Map.of("phase", "Running"));
    }

    private Map<String, Object> component(String name, int replicas, Map<String, String> volumes) {
        return Map.of(
                "name", name,
                "replicas", replicas,
                "podUpdatePolicy", "StrictInPlace",
                "volumeClaimTemplates", volumes.entrySet().stream()
                        .map(entry -> Map.of("name", entry.getKey(), "spec", Map.of(
                                "resources", Map.of("requests", Map.of("storage", entry.getValue())))))
                        .toList());
    }
}
