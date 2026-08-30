package com.tradinganalytics.research.v5;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import java.io.PrintStream;

/** Exact process-boundary adapter for {@code tools/strategy-research-v5.mjs}. */
public final class StrategyResearchV5CommandAdapter {
    public static final String COMMANDS = "data-backfill|data-raw-replay|feature-build|metadata-build|"
            + "opportunity-envelope|artifact-build|research-init|experiment-freeze|search-genetic|"
            + "research-run|overfit-audit|prospective-runner|readiness-audit|deployment-audit|validate|index";
    public static final String USAGE = "usage: strategy-research-v5.mjs " + COMMANDS;
    public static final String HELP_USAGE = USAGE + " [options]";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private StrategyResearchV5CommandAdapter() {}

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream stdout, PrintStream stderr) {
        String command = args == null || args.length == 0 || args[0] == null ? "" : args[0];
        ObjectNode options = flags(args, 1);
        try {
            if (command.isEmpty() || "--help".equals(command) || "-h".equals(command)
                    || options.path("help").isBoolean() && options.path("help").booleanValue()
                    || options.path("h").isBoolean() && options.path("h").booleanValue()) {
                stdout.println(HELP_USAGE); return 0;
            }
            JsonNode result = StrategyResearchV5.runAuthoritativeV5Cli(command, options);
            if (result != null) {
                stdout.print(NodePrettyJson.write(result)); return 0;
            }
            stderr.println("unknown strategy-research-v5 command: " + command);
            stderr.println(USAGE); return 1;
        } catch (RuntimeException error) {
            stderr.println(message(error)); return 1;
        }
    }

    static ObjectNode flags(String[] args, int start) {
        ObjectNode options = JSON.objectNode();
        if (args == null) return options;
        for (int index = Math.max(0, start); index < args.length; index++) {
            String argument = args[index];
            if (argument == null || !argument.startsWith("--")) continue;
            String rawKey = argument.substring(2); JsonNode value;
            if (index + 1 >= args.length || args[index + 1] != null && args[index + 1].startsWith("--")) {
                value = JSON.booleanNode(true);
            } else value = JSON.textNode(args[++index] == null ? "" : args[index]);
            options.set(rawKey, value); options.set(rawKey.replace('-', '_'), value);
        }
        return options;
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isEmpty() ? error.getClass().getSimpleName() : value;
    }
}
