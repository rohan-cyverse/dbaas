package com.cyfuture.dbaas.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SizePlan {
//    C1G1("1", "1Gi"),
//    C1G2("1", "2Gi"),
//    C2G4("2", "4Gi"),
//    C4G8("4", "8Gi"),
//    C4G16("4", "16Gi"),
//    C8G16("8", "16Gi"),
//    C8G32("8", "32Gi");

//    private final String cpu;
//    private final String memory;
//    public String cpu() { return cpu; }
//    public String memory() { return memory; }

    C1G1("250m", "1", "1Gi", "1Gi"),
    C1G2("500m", "1", "2Gi", "2Gi"),
    C2G4("1", "2", "4Gi", "4Gi"),
    C4G8("2", "4", "8Gi", "8Gi"),
    C4G16("2", "4", "16Gi", "16Gi"),
    C8G16("4", "8", "16Gi", "16Gi"),
    C8G32("4", "8", "32Gi", "32Gi");


    private final String cpuRequest;
    private final String cpuLimit;
    private final String memoryRequest;
    private final String memoryLimit;

}
