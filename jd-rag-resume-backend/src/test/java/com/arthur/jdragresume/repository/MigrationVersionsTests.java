package com.arthur.jdragresume.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway refuses to start when two migrations share a version, but the MySQL tests that would show it only run
 * where Docker is available. Two branches that each add "the next" migration collide this way, so the check runs
 * here, without a database.
 */
class MigrationVersionsTests {
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    private static final Pattern NAME = Pattern.compile("V(\\d+)__[a-z0-9_]+\\.sql");

    @Test
    void everyMigrationHasItsOwnVersionAndTheyLeaveNoGaps() throws IOException {
        Map<Integer, String> byVersion = new TreeMap<>();
        List<String> names;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            names = files.map(file -> file.getFileName().toString()).sorted().toList();
        }
        for (String name : names) {
            Matcher matcher = NAME.matcher(name);
            assertTrue(matcher.matches(), "unexpected migration file name: " + name);
            String previous = byVersion.put(Integer.parseInt(matcher.group(1)), name);
            assertEquals(null, previous, "two migrations share a version: " + previous + " and " + name);
        }
        assertEquals(byVersion.size(), byVersion.keySet().stream().mapToInt(Integer::intValue).max().orElse(0),
                "migration versions should run 1..N without gaps: " + byVersion.keySet());
    }
}
