package com.example.listener;

import com.example.entity.dto.ActivityGrabEvent;
import com.example.mapper.ActivityMapper;
import com.example.mapper.ActivityOrderMapper;
import com.example.utils.Const;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityGrabErrorListenerTest {

    private static final ActivityGrabEvent EVENT = new ActivityGrabEvent(1, 42);
    private static final String STOCK_KEY = Const.ACTIVITY_STOCK + 1;
    private static final String IDEMPOTENT_KEY = Const.ACTIVITY_GRABBED + 1 + ":" + 42;

    @Mock
    ActivityMapper activityMapper;
    @Mock
    ActivityOrderMapper activityOrderMapper;
    @Mock
    StringRedisTemplate template;
    @Mock
    ValueOperations<String, String> valueOperations;

    ActivityGrabErrorListener listener;

    @BeforeEach
    void setUp() {
        lenient().when(template.opsForValue()).thenReturn(valueOperations);
        listener = new ActivityGrabErrorListener(activityMapper, activityOrderMapper, template);
    }

    @Test
    void compensatesStockAndIdempotentKeyWhenOrderMissing() {
        when(activityOrderMapper.selectCount(any())).thenReturn(0L);

        listener.handle(EVENT);

        verify(valueOperations).increment(STOCK_KEY);
        verify(template).delete(IDEMPOTENT_KEY);
    }

    @Test
    void skipsCompensationWhenOrderAlreadyExists() {
        when(activityOrderMapper.selectCount(any())).thenReturn(1L);

        listener.handle(EVENT);

        verify(valueOperations, never()).increment(STOCK_KEY);
        verify(template, never()).delete(IDEMPOTENT_KEY);
    }

    @Test
    void compensationFailureIsSwallowedSoMessageIsAcked() {
        when(activityOrderMapper.selectCount(any())).thenReturn(0L);
        when(valueOperations.increment(STOCK_KEY)).thenThrow(new QueryTimeoutException("redis down"));

        assertDoesNotThrow(() -> listener.handle(EVENT));
        verify(template, never()).delete(IDEMPOTENT_KEY);
    }
}
