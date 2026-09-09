package com.cyfuture.dbaas.controller;

import com.cyfuture.dbaas.dto.CreateBackupRequest;
import com.cyfuture.dbaas.dto.CreateRestoreRequest;
import com.cyfuture.dbaas.dto.RestoreResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackupRecoveryApiContractTest {
    private static final String DATABASE = "/api/v1/projects/{projectId}/databases/{databaseId}";

    @Test
    void exposesOnlyTheTenDatabaseScopedBackupAndRecoveryOperations() {
        Set<String> expected = Set.of(
                "PUT " + DATABASE + "/backup-settings",
                "GET " + DATABASE + "/backup-settings",
                "GET " + DATABASE + "/recovery-window",
                "POST " + DATABASE + "/backups",
                "GET " + DATABASE + "/backups",
                "GET " + DATABASE + "/backups/{backupId}",
                "DELETE " + DATABASE + "/backups/{backupId}",
                "POST " + DATABASE + "/restores",
                "GET " + DATABASE + "/restores",
                "GET " + DATABASE + "/restores/{restoreId}");

        Set<String> actual = new LinkedHashSet<>();
        actual.addAll(routes(BackupSettingsController.class));
        actual.addAll(routes(RecoveryWindowController.class));
        actual.addAll(routes(BackupController.class));
        actual.addAll(routes(RestoreController.class));

        assertEquals(expected, actual);
    }

    @Test
    void usesExplicitTypeAndModePayloadFields() {
        assertEquals(Set.of("type", "retentionDays"), componentNames(CreateBackupRequest.class));
        assertEquals(Set.of("mode", "backupId", "restoreTime"), componentNames(CreateRestoreRequest.class));
        assertEquals(Set.of("restoreId", "databaseId", "mode", "backupId", "restoreTime", "status",
                "createdAt", "startedAt", "completedAt", "errorCode", "errorMessage"),
                componentNames(RestoreResponse.class));
    }

    private Set<String> routes(Class<?> controller) {
        RequestMapping baseMapping = controller.getAnnotation(RequestMapping.class);
        String basePath = baseMapping.value()[0];
        Set<String> routes = new LinkedHashSet<>();
        for (Method method : controller.getDeclaredMethods()) {
            add(routes, "GET", basePath, method.getAnnotation(GetMapping.class));
            add(routes, "POST", basePath, method.getAnnotation(PostMapping.class));
            add(routes, "PUT", basePath, method.getAnnotation(PutMapping.class));
            add(routes, "DELETE", basePath, method.getAnnotation(DeleteMapping.class));
        }
        return routes;
    }

    private void add(Set<String> routes, String method, String basePath, GetMapping mapping) {
        if (mapping != null) add(routes, method, basePath, mapping.value());
    }

    private void add(Set<String> routes, String method, String basePath, PostMapping mapping) {
        if (mapping != null) add(routes, method, basePath, mapping.value());
    }

    private void add(Set<String> routes, String method, String basePath, PutMapping mapping) {
        if (mapping != null) add(routes, method, basePath, mapping.value());
    }

    private void add(Set<String> routes, String method, String basePath, DeleteMapping mapping) {
        if (mapping != null) add(routes, method, basePath, mapping.value());
    }

    private void add(Set<String> routes, String method, String basePath, String[] paths) {
        if (paths.length == 0) {
            routes.add(method + " " + basePath);
            return;
        }
        for (String path : paths) routes.add(method + " " + basePath + path);
    }

    private Set<String> componentNames(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (RecordComponent component : type.getRecordComponents()) names.add(component.getName());
        return names;
    }
}
