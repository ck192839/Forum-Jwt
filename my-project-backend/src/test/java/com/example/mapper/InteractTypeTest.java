package com.example.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InteractTypeTest {

    @Test
    void mapsSupportedTypesToFixedPhysicalTables() {
        assertEquals("db_topic_interact_like", InteractType.parse("like").tableName());
        assertEquals("db_topic_interact_collect", InteractType.parse("collect").tableName());
    }

    @Test
    void rejectsValuesThatAreNotSupportedInteractionTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> InteractType.parse("db_topic_interact_like where 1=1"));
        assertThrows(IllegalArgumentException.class, () -> InteractType.parse("LIKE"));
    }
}
