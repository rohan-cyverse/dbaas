package com.cyfuture.dbaas.service;

import com.cyfuture.dbaas.model.DatabaseEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendlyNameGeneratorTest {
    private final FriendlyNameGenerator generator = new FriendlyNameGenerator();

    @Test
    void databaseHandlesAreHumanReadableEnginePrefixedAndWithinTheApiLimit() {
        String name = generator.nextDatabaseName(DatabaseEngine.POSTGRESQL);

        assertTrue(name.matches("^pg-[a-z]+-[a-z]+-[abcdefghjkmnpqrstuvwxyz23456789]{4}$"));
        assertTrue(name.length() <= 32);
    }
}
