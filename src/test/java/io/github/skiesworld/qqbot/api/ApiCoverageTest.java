package io.github.skiesworld.qqbot.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.skiesworld.qqbot.http.Endpoint;
import io.github.skiesworld.qqbot.util.Json;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Completeness gate: every operation the official docs describe (as extracted into
 * tools/docgen/spec.json) must be reachable through a generated {@link Endpoint} constant, so a docs
 * update that adds an endpoint fails this test until the SDK covers it.
 */
class ApiCoverageTest {

    private static final Path SPEC = Path.of("tools", "docgen", "spec.json");

    @Test
    void everyDocumentedTemplatedOperationHasAnEndpoint() throws Exception {
        JsonObject spec = Json.parseLenient(Files.readString(SPEC, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray documented = spec.getAsJsonArray("endpoints");
        assertFalse(documented.size() == 0, "spec.json is empty; regenerate tools/docgen");

        Set<String> generated = generatedKeys();
        Set<String> missing = new TreeSet<>();
        int templated = 0;
        for (var element : documented) {
            JsonObject e = element.getAsJsonObject();
            String path = e.get("path").getAsString();
            if (!path.contains("{")) {
                continue;
            }
            templated++;
            String key = e.get("method").getAsString() + " " + path;
            if (!generated.contains(key)) {
                missing.add(key);
            }
        }
        assertTrue(templated >= 80,
                "expected the docs to describe at least 80 templated operations, saw " + templated);
        assertTrue(missing.isEmpty(), "operations without an Endpoint constant: " + missing);
    }

    /**
     * Two operations may share one path (guild-wide mute vs batch member mute), so coverage is also
     * counted per path: a merge that silently swallows an operation fails here, not only in the path check.
     */
    @Test
    void everyDocumentedOperationCountIsRepresented() throws Exception {
        JsonObject spec = Json.parseLenient(Files.readString(SPEC, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, Integer> documented = new TreeMap<>();
        for (var element : spec.getAsJsonArray("endpoints")) {
            JsonObject e = element.getAsJsonObject();
            String path = e.get("path").getAsString();
            if (!path.contains("{")) {
                continue;
            }
            documented.merge(e.get("method").getAsString() + " " + path, 1, Integer::sum);
        }
        Map<String, Integer> generated = new TreeMap<>();
        for (Field field : io.github.skiesworld.qqbot.api.endpoint.Endpoints.class.getFields()) {
            if (!Endpoint.class.isAssignableFrom(field.getType())) {
                continue;
            }
            Endpoint<?> endpoint = (Endpoint<?>) field.get(null);
            generated.merge(endpoint.method().name() + " " + endpoint.pathTemplate(), 1, Integer::sum);
        }
        List<String> shortBy = new ArrayList<>();
        documented.forEach((key, want) -> {
            int have = generated.getOrDefault(key, 0);
            if (have < want) {
                shortBy.add(key + ": docs describe " + want + " operation(s), generated " + have);
            }
        });
        assertTrue(shortBy.isEmpty(), String.join("; ", shortBy));
        assertTrue(generated.values().stream().mapToInt(Integer::intValue).sum() >= 94,
                "expected at least 94 endpoint constants");
    }

    @Test
    void generatedEndpointsResolveTheirPlaceholders() throws Exception {
        int checked = 0;
        for (Field field : io.github.skiesworld.qqbot.api.endpoint.Endpoints.class.getFields()) {
            if (!Endpoint.class.isAssignableFrom(field.getType())) {
                continue;
            }
            Endpoint<?> endpoint = (Endpoint<?>) field.get(null);
            String template = endpoint.pathTemplate();
            assertTrue(template.startsWith("/"), field.getName() + " -> " + template);
            List<String> vars = java.util.regex.Pattern.compile("\\{(\\w+)}").matcher(template)
                    .results().map(m -> m.group(1)).toList();
            io.github.skiesworld.qqbot.http.Params params = io.github.skiesworld.qqbot.http.Params.of();
            vars.forEach(v -> params.pathValue(v, "X"));
            String resolved = endpoint.resolvePath(params);
            assertFalse(resolved.contains("{"), field.getName() + " -> " + resolved);
            checked++;
        }
        assertTrue(checked >= 80, "only " + checked + " endpoints were generated");
    }

    private static Set<String> generatedKeys() throws Exception {
        Set<String> out = new LinkedHashSet<>();
        for (Field field : io.github.skiesworld.qqbot.api.endpoint.Endpoints.class.getFields()) {
            if (!Endpoint.class.isAssignableFrom(field.getType())) {
                continue;
            }
            Endpoint<?> endpoint = (Endpoint<?>) field.get(null);
            out.add(endpoint.method().name() + " " + endpoint.pathTemplate());
        }
        return out;
    }
}
