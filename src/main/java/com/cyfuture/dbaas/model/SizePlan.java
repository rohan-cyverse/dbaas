package com.cyfuture.dbaas.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SizePlan {
    C1G1("1", "1Gi"),
    C1G2("1", "2Gi"),
    C2G4("2", "4Gi"),
    C4G8("4", "8Gi"),
    C4G16("4", "16Gi"),
    C8G16("8", "16Gi"),
    C8G32("8", "32Gi");

    private final String cpu;
    private final String memory;
    public String cpu() { return cpu; }
    public String memory() { return memory; }
}
