package com.cyfuture.dbaas.model;

/** User intent. Runtime failures and drift belong to the observed status. */
public enum DesiredState { RUNNING, STOPPED, DELETED }
