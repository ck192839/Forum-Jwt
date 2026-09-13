package com.example.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlowUtilsTest {

    @Mock
    StringRedisTemplate template;

    FlowUtils flowUtils;

    @BeforeEach
    void setUp() {
        flowUtils = new FlowUtils();
        org.springframework.test.util.ReflectionTestUtils.setField(flowUtils, "template", template);
    }

    @SuppressWarnings("unchecked")
    private void stubExecute(Boolean result) {
        lenient().when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(result);
    }

    @Test
    void limitOnceCheckPassesThroughScriptResult() {
        stubExecute(true);
        assertTrue(flowUtils.limitOnceCheck("k", 3));
        verify(template).execute(any(RedisScript.class), eq(List.of("k")), eq("3"), eq("1"));

        stubExecute(false);
        assertFalse(flowUtils.limitOnceCheck("k", 3));
    }

    @Test
    void limitPeriodCheckSendsCounterBlockKeysAndThreeArgs() {
        stubExecute(false);
        assertFalse(flowUtils.limitPeriodCheck("counter", "block", 30, 20, 10));
        verify(template).execute(any(RedisScript.class),
                eq(List.of("counter", "block")), eq("30"), eq("10"), eq("20"));
    }

    @Test
    void limitPeriodCounterCheckSendsPeriodAndFrequency() {
        stubExecute(true);
        assertTrue(flowUtils.limitPeriodCounterCheck("counter", 3, 3600));
        verify(template).execute(any(RedisScript.class),
                eq(List.of("counter")), eq("3600"), eq("3"));
    }

    @Test
    void limitOnceUpgradeCheckSendsBaseFrequencyUpgrade() {
        stubExecute(true);
        assertTrue(flowUtils.limitOnceUpgradeCheck("k", 5, 10, 60));
        verify(template).execute(any(RedisScript.class),
                eq(List.of("k")), eq("10"), eq("5"), eq("60"));
    }

    @Test
    void nullScriptResultIsTreatedAsDenied() {
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        assertFalse(flowUtils.limitOnceCheck("k", 3));
    }
}
