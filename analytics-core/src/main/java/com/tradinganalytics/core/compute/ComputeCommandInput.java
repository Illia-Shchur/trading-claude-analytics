package com.tradinganalytics.core.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Command input boundary: argv parsing and workspace-relative JSON loading. */
final class ComputeCommandInput {

    private ComputeCommandInput() {
    }

    static Parsed parse(String[] argv) {
        String command = argv.length == 0 ? null : argv[0];
        List<String> args = new ArrayList<>();
        LinkedHashMap<String, Object> flags = new LinkedHashMap<>();
        for (int index = 1; index < argv.length; index++) {
            String token = argv[index];
            if (token.startsWith("--")) {
                String key = token.substring(2);
                if (index + 1 < argv.length && !argv[index + 1].startsWith("--")) {
                    flags.put(key, argv[++index]);
                } else {
                    flags.put(key, true);
                }
            } else {
                args.add(token);
            }
        }
        return new Parsed(command, args, flags);
    }

    static JsonNode readJson(Object input, ObjectMapper json, Path workspaceRoot) throws IOException {
        String text = string(input);
        if (text.startsWith("@")) {
            Path path = workspaceRoot.resolve(text.substring(1)).normalize();
            text = Files.readString(path, StandardCharsets.UTF_8);
        }
        return json.readTree(text);
    }

    static JsonNode readDataFile(String name, ObjectMapper json, Path workspaceRoot) throws IOException {
        Path path = workspaceRoot.resolve("tools").resolve(name).normalize();
        return json.readTree(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static String string(Object value) {
        if (value == null) return "";
        if (value instanceof Boolean bool) return Boolean.toString(bool);
        return String.valueOf(value);
    }

    record Parsed(String command, List<String> args, Map<String, Object> flags) {
    }
}
