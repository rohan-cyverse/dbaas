package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.exception.ApiException;

import java.util.regex.Pattern;

/** Prevents Kubernetes/object-storage diagnostic text from crossing the API boundary. */
final class BackupRestoreSafety {
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)(password|passwd|pwd|token|secret|access[_-]?key|credential|passphrase)"
                    + "\\s*[:=]\\s*[^\\s,;\\\"']+");
    private static final Pattern SENSITIVE_DIAGNOSTIC = Pattern.compile(
            "(?i)(\\b(secret|password|passwd|pwd|token|access[_-]?key|credential|passphrase|"
                    + "encryption|kms)\\b|s3://|\\.svc\\.cluster\\.local\\b|"
                    + "\\b(?:10|127)\\.(?:\\d{1,3}\\.){2}\\d{1,3}\\b|"
                    + "\\b192\\.168\\.(?:\\d{1,3}\\.)?\\d{1,3}\\b|"
                    + "\\b172\\.(?:1[6-9]|2\\d|3[01])\\.(?:\\d{1,3}\\.)?\\d{1,3}\\b|"
                    + "\\bnamespace\\b)");
    private static final int MAX_MESSAGE_LENGTH = 500;

    private BackupRestoreSafety() {}

    static String safeMessage(Throwable exception, String fallback) {
        if (exception == null) return safeFallback(fallback);
        String value = exception.getMessage();
        if (value == null || value.isBlank()) return safeFallback(fallback);
        String sanitized = SECRET_VALUE.matcher(value).replaceAll("$1=******");
        return SENSITIVE_DIAGNOSTIC.matcher(sanitized).find() ? safeFallback(fallback) : limit(sanitized);
    }

    private static String safeFallback(String fallback) {
        if (fallback == null || fallback.isBlank()) {
            return "KubeBlocks reported a backup or restore lifecycle failure.";
        }
        String sanitized = SECRET_VALUE.matcher(fallback).replaceAll("$1=******");
        if (SENSITIVE_DIAGNOSTIC.matcher(sanitized).find()) {
            return "KubeBlocks reported a backup or restore lifecycle failure. Check platform logs.";
        }
        return limit(sanitized);
    }

    private static String limit(String value) {
        String trimmed = value.trim();
        return trimmed.length() <= MAX_MESSAGE_LENGTH ? trimmed : trimmed.substring(0, MAX_MESSAGE_LENGTH);
    }

    static String failureCode(Throwable exception, String fallback) {
        return exception instanceof ApiException api ? api.getCode() : fallback;
    }

    static boolean retryable(Throwable exception) {
        return exception instanceof ApiException api && api.isRetryable();
    }
}
