package com.pipemend.pipeline.triage;

import com.pipemend.pipeline.triage.contract.TriageRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.StreamSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reads contracts/fixtures; no fixture is written or regenerated. A test changes only its own copy of the tree. */
final class TriageResponseFixtures {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private TriageResponseFixtures() {}

    static String text(String name) {
        try {
            return Files.readString(OpenApiShape.CONTRACTS.resolve("fixtures").resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ObjectNode tree(String name) {
        return (ObjectNode) JSON.readTree(text(name));
    }

    static List<JsonNode> responses() {
        return StreamSupport.stream(JSON.readTree(text("index.json")).spliterator(), false)
                .filter(entry -> entry.get("kind").asString().equals("response"))
                .toList();
    }

    static TriageRequest requestOf(String response) {
        String request = responses().stream()
                .filter(entry -> entry.get("fixture").asString().equals(response))
                .findFirst()
                .orElseThrow()
                .get("request")
                .asString();
        return JSON.readValue(text(request), TriageRequest.class);
    }
}
