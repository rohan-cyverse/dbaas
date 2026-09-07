package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.model.BackupType;
import com.cyfuture.dbaas.model.DatabaseEngine;

import java.util.List;

/** Engine-specific KubeBlocks method selection, intentionally small and extensible. */
public interface BackupEngineStrategy {
    DatabaseEngine engine();

    /** The only method enabled for manual backups in this release. */
    String manualFullMethod();

    /** Reserved method names are surfaced internally, never attempted prematurely. */
    List<String> futureIncrementalMethods();

    List<String> futureContinuousMethods();

    default boolean supportsNow(BackupType type) {
        return type == BackupType.FULL;
    }
}
