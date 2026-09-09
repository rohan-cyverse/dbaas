package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseEngine;
import com.cyfuture.dbaas.model.DatabaseMode;

import java.util.List;

/** Engine-specific KubeBlocks method selection, intentionally small and extensible. */
public interface BackupEngineStrategy {
    DatabaseEngine engine();

    /** Installed method used when a manual full backup is requested. */
    String manualFullMethod();

    /** Installed continuous-log method used only when PITR is explicitly enabled. */
    String continuousMethod();

    /** Installed method candidates used when a manual incremental backup is requested. */
    List<String> futureIncrementalMethods();

    List<String> futureContinuousMethods();

    /** The topology must be one backed by the installed KubeBlocks template. */
    boolean supportsTopology(DatabaseMode mode);

    /** PITR is more restrictive than ordinary full-backup support for some engines. */
    default boolean supportsPitrTopology(DatabaseMode mode) {
        return supportsTopology(mode);
    }

    default String manualIncrementalMethod() {
        return futureIncrementalMethods().stream().findFirst().orElse(null);
    }

    default String manualMethod(BackupType type) {
        return switch (type) {
            case FULL -> manualFullMethod();
            case INCREMENTAL -> manualIncrementalMethod();
            case CONTINUOUS -> null;
        };
    }

    default boolean supportsNow(BackupType type) {
        return type == BackupType.FULL
                || (type == BackupType.INCREMENTAL && manualIncrementalMethod() != null);
    }
}
