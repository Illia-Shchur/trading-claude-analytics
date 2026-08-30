package com.tradinganalytics.cli;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine;
import picocli.spring.PicocliSpringFactory;

public final class TradingAnalyticsApplication {
    private TradingAnalyticsApplication() {
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(TradingAnalyticsConfiguration.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);

        int exitCode;
        try (ConfigurableApplicationContext context = application.run()) {
            var factory = new PicocliSpringFactory(context);
            var command = context.getBean(AnalyticsCommand.class);
            exitCode = new CommandLine(command, factory).execute(args);
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
