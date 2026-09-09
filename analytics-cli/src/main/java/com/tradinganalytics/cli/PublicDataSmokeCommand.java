package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.contracts.json.NodePrettyJson;
import com.tradinganalytics.marketdata.PublicDataSmokeService;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Spring/Picocli replacement for {@code tools/strategy-public-smoke.mjs}. */
@Component
@Command(name = "strategy-public-smoke", description = "Check all public crypto data routes")
public class PublicDataSmokeCommand implements Callable<Integer> {
    @Option(names = "--network", description = "Fetch one completed spot and USD-M bar per asset")
    private boolean network;

    @Spec
    private CommandSpec spec;

    private final PublicDataSmokeService service;

    public PublicDataSmokeCommand() {
        this(PublicDataSmokeService.production());
    }

    PublicDataSmokeCommand(PublicDataSmokeService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public Integer call() {
        ObjectNode result = service.run(network);
        spec.commandLine().getOut().print(NodePrettyJson.write(result));
        return network && !result.path("pass").asBoolean() ? 1 : 0;
    }
}
