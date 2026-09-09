package com.tradinganalytics.cli;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compatibility parser for the historical Node command flags.
 *
 * <p>The parser intentionally keeps both the literal kebab-case key and its
 * snake_case alias. Non-option arguments are ignored and a flag without a
 * following value is represented by {@link Boolean#TRUE}.</p>
 */
public final class LegacyFlagOptions {
    private LegacyFlagOptions() {
    }

    public static Map<String, Object> parse(String... arguments) {
        return parse(List.of(arguments));
    }

    public static Map<String, Object> parse(List<String> arguments) {
        var options = new LinkedHashMap<String, Object>();
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (!argument.startsWith("--")) {
                continue;
            }
            String rawKey = argument.substring(2);
            Object value;
            if (index + 1 >= arguments.size() || arguments.get(index + 1).startsWith("--")) {
                value = Boolean.TRUE;
            } else {
                value = arguments.get(++index);
            }
            options.put(rawKey, value);
            options.put(rawKey.replace('-', '_'), value);
        }
        return options;
    }
}
