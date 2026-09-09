package com.tradinganalytics.reporting;

import com.fasterxml.jackson.databind.JsonNode;

final class ReportSemanticValidator {
    private ReportSemanticValidator() {
    }

    static SemanticIssues semanticIssues2(JsonNode report, ReportContract.ValidationOptions options) {
        return ReportMachine2Semantics.validate(report, options.filename());
    }

    static SemanticIssues semanticIssues3(JsonNode report, ReportContract.ValidationOptions options) {
        return ReportMachine3Semantics.validate(report, options.filename());
    }
}
