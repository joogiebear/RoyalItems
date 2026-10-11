package com.mystipixel.royalitems;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Every key the code reads must ship in messages.yml, so a server file that predates a key still shows text.
class MessageDefaultsTest {

    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Pattern MESSAGE_CALL =
            Pattern.compile("\\bmessages\\s*\\.\\s*(get|send)\\s*\\(\\s*(?:[\\w.()]+\\s*,\\s*)?(\"[^\"]*\"|[^,)]+)");

    @Test
    void everyKeyReadInCodeShipsInMessagesYml() throws IOException {
        YamlConfiguration shipped = load();
        List<String> problems = new ArrayList<>();
        int found = 0;
        for (Path file : sources()) {
            String source = Files.readString(file);
            Matcher m = MESSAGE_CALL.matcher(source);
            while (m.find()) {
                found++;
                String where = file.getFileName() + ":" + lineOf(source, m.start());
                String key = m.group(2).strip();
                if (!key.startsWith("\"")) {
                    problems.add(where + ": message key must be a string literal, not " + key);
                    continue;
                }
                key = key.substring(1, key.length() - 1);
                if (!shipped.isString(key)) {
                    problems.add(where + ": " + key + " is not in messages.yml");
                }
            }
        }
        assertTrue(found > 45, "found only " + found + " message reads; is the scan still matching?");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void bundledYamlHasNoDuplicateKeys() throws IOException {
        assertNoDuplicateKeys("/messages.yml");
        assertNoDuplicateKeys("/config.yml");
    }

    // YAML keeps only the last of two equal keys, silently dropping the first
    private static void assertNoDuplicateKeys(String name) throws IOException {
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (String line : resource(name).split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            path.add(trimmed.substring(0, colon));
            indents.add(indent);
            String key = String.join(".", path);
            if (!seen.add(key)) {
                duplicates.add(key);
            }
        }
        assertTrue(duplicates.isEmpty(), "duplicate keys in " + name + ": " + duplicates);
    }

    @Test
    void aPlaceholderValueIsNotExpandedAgain() {
        assertEquals("a %b% c", MessageManager.fill("a %x% c", "x", "%b%", "b", "no"));
        assertEquals("100% done", MessageManager.fill("100% done", "x", "y"));
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.endsWith("MessageManager.java"))
                    .toList();
        }
    }

    private static int lineOf(String source, int index) {
        return (int) source.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = MessageDefaultsTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static YamlConfiguration load() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(resource("/messages.yml"));
        } catch (InvalidConfigurationException e) {
            throw new IOException(e);
        }
        return yaml;
    }
}
