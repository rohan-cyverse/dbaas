package com.cyfuture.dbaas.model;

/** A restore always creates a new database resource. */
public enum RestoreMode {
    FULL,
    POINT_IN_TIME
}
