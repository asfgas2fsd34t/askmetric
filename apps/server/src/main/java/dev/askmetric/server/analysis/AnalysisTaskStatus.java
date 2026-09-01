package dev.askmetric.server.analysis;

import com.fasterxml.jackson.annotation.JsonValue;

public enum AnalysisTaskStatus {
    ACTIVE("active"),
    WAITING_FOR_INPUT("waiting_for_input"),
    WAITING_FOR_APPROVAL("waiting_for_approval"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String wireValue;

    AnalysisTaskStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
