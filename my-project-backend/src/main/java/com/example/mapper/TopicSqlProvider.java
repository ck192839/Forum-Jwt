package com.example.mapper;

import java.util.Map;

/**
 * SQL provider for the two interaction tables.
 * Only an InteractType can select a table; arbitrary request strings never reach SQL.
 */
public final class TopicSqlProvider {
    private TopicSqlProvider() {
    }

    public static String addInteract(Map<String, Object> parameters) {
        return "<script>"
                + "insert ignore into " + tableName(parameters)
                + " values "
                + "<foreach collection='interacts' item='item' separator=','>"
                + "(#{item.tid}, #{item.uid}, #{item.time})"
                + "</foreach>"
                + "</script>";
    }

    public static String deleteInteract(Map<String, Object> parameters) {
        return "<script>"
                + "delete from " + tableName(parameters) + " where "
                + "<foreach collection='interacts' item='item' separator=' or '>"
                + "(tid = #{item.tid} and uid = #{item.uid})"
                + "</foreach>"
                + "</script>";
    }

    public static String interactCount(Map<String, Object> parameters) {
        return "select count(*) from " + tableName(parameters) + " where tid = #{tid}";
    }

    public static String interactCountBatch(Map<String, Object> parameters) {
        return "<script>"
                + "select tid, count(*) as total from " + tableName(parameters)
                + " where tid in "
                + "<foreach collection='tids' item='tid' open='(' separator=',' close=')'>#{tid}</foreach>"
                + " group by tid"
                + "</script>";
    }

    public static String userInteractCount(Map<String, Object> parameters) {
        return "select count(*) from " + tableName(parameters)
                + " where tid = #{tid} and uid = #{uid}";
    }

    private static String tableName(Map<String, Object> parameters) {
        Object value = parameters.get("type");
        if (!(value instanceof InteractType type)) {
            throw new IllegalArgumentException("Interaction type must be an InteractType");
        }
        return type.tableName();
    }
}
