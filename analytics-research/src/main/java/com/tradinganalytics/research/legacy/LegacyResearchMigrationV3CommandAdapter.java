package com.tradinganalytics.research.legacy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;

import java.io.PrintStream;
import java.nio.file.Path;

/** Unregistered CLI boundary for {@code migrate-research-v3.mjs}. */
public final class LegacyResearchMigrationV3CommandAdapter {
    private LegacyResearchMigrationV3CommandAdapter() {}

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        Path root = Path.of(args.length > 0 ? args[0] : "strategy-research")
                .toAbsolutePath().normalize();
        Path output = Path.of(args.length > 1
                ? args[1] : ".research-run/v3-migration-inventory.json")
                .toAbsolutePath().normalize();
        try {
            ObjectNode result = LegacyResearchMigrationV3.migrate(root, output);
            out.print(NodePrettyJson.write(result));
            return 0;
        } catch (RuntimeException error) {
            err.println(error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
            return 1;
        }
    }
}
