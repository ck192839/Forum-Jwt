package com.example.mapper;

/**
 * Supported interaction types and their fixed physical table names.
 * The table name is never accepted directly from request input.
 */
public enum InteractType {
    LIKE("like", "db_topic_interact_like"),
    COLLECT("collect", "db_topic_interact_collect");

    private final String value;
    private final String tableName;

    InteractType(String value, String tableName) {
        this.value = value;
        this.tableName = tableName;
    }

    public String tableName() {
        return tableName;
    }

    public static InteractType parse(String value) {
        for (InteractType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported interaction type: " + value);
    }
}
