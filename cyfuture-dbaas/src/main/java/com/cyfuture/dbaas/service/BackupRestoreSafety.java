package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.exception.ApiException;

import java.util.regex.Pattern;

/** Prevents Kubernetes/object-storage diagnostic text from crossing the API boundary. */
final class BackupRestoreSafety {
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)(password|passwd|pwd|token|secret|access[_-]?key|credential|passphrase)"
                    + "\\s*[:=]\\s*[^\\s,;\\\"']+");

    private BackupRestoreSafety() {}

    static String safeMessage(Throwable exception, String fallback) {
        String value = exception == null ? null : exception.getMessage();
        if (value == null || value.isBlank()) return fallback;
        return SECRET_VALUE.matcher(value).replaceAll("$1=******");
    }

    static String failureCode(Throwable exception, String fallback) {
        return exception instanceof ApiException api ? api.getCode() : fallback;
    }

    static boolean retryable(Throwable exception) {
        return exception instanceof ApiException api && api.isRetryable();
    }
}
