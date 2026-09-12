package com.example.service.impl;

import com.example.entity.dto.Interact;
import com.example.mapper.InteractType;
import com.example.mapper.TopicMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicInteractFlushTest {

    private static final String TYPE = "like";

    @Mock
    TopicMapper topicMapper;
    @Mock
    StringRedisTemplate template;
    @Mock
    HashOperations<String, Object, Object> hashOperations;

    TopicServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(template.opsForHash()).thenReturn(hashOperations);
        service = new TopicServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", topicMapper);
        ReflectionTestUtils.setField(service, "template", template);
    }

    @SuppressWarnings("unchecked")
    private void stubPop(String... flat) {
        when(template.execute(any(RedisScript.class), anyList())).thenReturn(List.of(flat));
    }

    @Test
    void flushWritesCheckAndUncheckBatchesFromPoppedEntries() {
        stubPop("1:5", "true", "1:6", "false");

        ReflectionTestUtils.invokeMethod(service, "saveInteract", TYPE);

        ArgumentCaptor<List<Interact>> check = ArgumentCaptor.forClass(List.class);
        verify(topicMapper).addInteract(check.capture(), eq(InteractType.LIKE));
        assertEquals(1, check.getValue().size());
        assertEquals(5, check.getValue().get(0).getUid());
        ArgumentCaptor<List<Interact>> uncheck = ArgumentCaptor.forClass(List.class);
        verify(topicMapper).deleteInteract(uncheck.capture(), eq(InteractType.LIKE));
        assertEquals(6, uncheck.getValue().get(0).getUid());
        // 原子弹出后不再整键删除
        verify(template, never()).delete(TYPE);
    }

    @Test
    void emptyPopSkipsDatabaseWrites() {
        stubPop();

        ReflectionTestUtils.invokeMethod(service, "saveInteract", TYPE);

        verify(topicMapper, never()).addInteract(any(), any());
        verify(topicMapper, never()).deleteInteract(any(), any());
    }

    @Test
    void databaseFailureRestoresPoppedEntriesForRetry() {
        stubPop("1:5", "true");
        doThrow(new RuntimeException("db down")).when(topicMapper).addInteract(any(), any());

        ReflectionTestUtils.invokeMethod(service, "saveInteract", TYPE);

        verify(hashOperations).putAll(eq(TYPE), eq(Map.of("1:5", "true")));
    }

    @Test
    void hasInteractReadsSingleFieldBeforeFallingBackToDatabase() {
        when(hashOperations.get(TYPE, "1:5")).thenReturn("true");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "hasInteract", 1, 5, TYPE));
        verify(topicMapper, never()).userInteractCount(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any());

        when(hashOperations.get(TYPE, "1:7")).thenReturn(null);
        when(topicMapper.userInteractCount(1, 7, InteractType.LIKE)).thenReturn(3);
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service, "hasInteract", 1, 7, TYPE));

        when(hashOperations.get(TYPE, "1:8")).thenReturn("false");
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(service, "hasInteract", 1, 8, TYPE));
    }
}
