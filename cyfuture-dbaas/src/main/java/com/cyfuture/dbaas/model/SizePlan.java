package com.cyfuture.dbaas.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SizePlan {

    C1G1("250m", "1", "1Gi", "1Gi"),
    C1G2("500m", "1", "2Gi", "2Gi"),
    C2G4("1", "2", "4Gi", "4Gi"),
    C4G8("2", "4", "8Gi", "8Gi");

    private final String cpuRequest;
    private final String cpuLimit;
    private final String memoryRequest;
    private final String memoryLimit;
}
