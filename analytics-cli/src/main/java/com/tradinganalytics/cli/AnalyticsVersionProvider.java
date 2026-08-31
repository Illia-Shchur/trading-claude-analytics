package com.tradinganalytics.cli;

import picocli.CommandLine.IVersionProvider;

public final class AnalyticsVersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() {
        String version = AnalyticsVersionProvider.class.getPackage().getImplementationVersion();
        return new String[]{"trading-analytics " + (version == null ? "development" : version)};
    }
}
