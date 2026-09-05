package com.cyfuture.dbaas.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Generates human-friendly display names; immutable resource IDs remain authoritative. */
@Component
public class FriendlyNameGenerator {
    private static final String[] ADJECTIVES = {
            "amber", "bright", "calm", "cobalt", "coral", "golden", "indigo", "jade",
            "lunar", "misty", "north", "quiet", "silver", "solar", "swift", "velvet"
    };
    private static final String[] NOUNS = {
            "birch", "comet", "harbor", "mango", "meadow", "orchid", "otter", "river",
            "summit", "tiger", "valley", "willow", "zephyr", "cedar", "lagoon", "maple"
    };

    private final SecureRandom random = new SecureRandom();

    public String next() {
        return ADJECTIVES[random.nextInt(ADJECTIVES.length)] + "-"
                + NOUNS[random.nextInt(NOUNS.length)];
    }
}
