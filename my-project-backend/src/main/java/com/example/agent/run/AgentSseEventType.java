package com.example.agent.run;

public enum AgentSseEventType {
    RUN_STARTED("run_started"),
    MESSAGE_DELTA("message_delta"),
    TOOL_STARTED("tool_started"),
    TOOL_COMPLETED("tool_completed"),
    CITATION("citation"),
    QUESTION("question"),
    DRAFT_READY("draft_ready"),
    RUN_COMPLETED("run_completed"),
    ERROR("error");

    private final String wireName;

    AgentSseEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static AgentSseEventType fromWireName(String wireName) {
        for (AgentSseEventType type : values()) {
            if (type.wireName.equals(wireName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown Agent event type: " + wireName);
    }
}
