package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.model.DatabaseEngine;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Generates human-friendly display names; immutable resource IDs remain authoritative. */
@Component
public class FriendlyNameGenerator {
    private static final char[] HANDLE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
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

    /**
     * Produces a short, human-readable database handle for UI display. The
     * immutable database ID remains the infrastructure identity.
     */
    public String nextDatabaseName(DatabaseEngine engine) {
        return databasePrefix(engine) + "-" + next() + "-" + nextShortSuffix();
    }

    /** A compact, non-ambiguous suffix used when a requested name is taken. */
    public String nextShortSuffix() {
        StringBuilder suffix = new StringBuilder(4);
        for (int index = 0; index < 4; index++) {
            suffix.append(HANDLE_ALPHABET[random.nextInt(HANDLE_ALPHABET.length)]);
        }
        return suffix.toString();
    }

    private String databasePrefix(DatabaseEngine engine) {
        if (engine == null) return "db";
        return switch (engine) {
            case POSTGRESQL -> "pg";
            case MYSQL -> "mysql";
            case MONGODB -> "mongo";
        };
    }
}
