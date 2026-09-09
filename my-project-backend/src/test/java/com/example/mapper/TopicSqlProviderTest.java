package com.example.mapper;

import com.example.entity.dto.Interact;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicSqlProviderTest {

    @Test
    void buildsInsertSqlFromAnEnumMappedTableName() {
        String sql = TopicSqlProvider.addInteract(Map.of(
                "interacts", List.of(new Interact(1, 2, new Date(), "like")),
                "type", InteractType.LIKE));

        assertTrue(sql.contains("db_topic_interact_like"));
        assertFalse(sql.contains("${"));
    }

    @Test
    void buildsCountSqlFromAnEnumMappedTableName() {
        String sql = TopicSqlProvider.interactCount(Map.of("tid", 1, "type", InteractType.COLLECT));

        assertTrue(sql.contains("db_topic_interact_collect"));
        assertFalse(sql.contains("${"));
    }

    @Test
    void rejectsRawTableNameValuesEvenWhenProviderIsCalledDirectly() {
        assertThrows(IllegalArgumentException.class,
                () -> TopicSqlProvider.interactCount(Map.of("tid", 1, "type", "like")));
    }
}
