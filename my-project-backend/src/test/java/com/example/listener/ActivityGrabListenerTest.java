package com.example.listener;

import com.example.entity.dto.ActivityGrabEvent;
import com.example.entity.dto.ActivityOrder;
import com.example.mapper.ActivityMapper;
import com.example.mapper.ActivityOrderMapper;
import com.example.utils.Const;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityGrabListenerTest {

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

    ActivityGrabListener listener;

    @BeforeEach
    void setUp() {
        lenient().when(template.opsForValue()).thenReturn(valueOperations);
        listener = new ActivityGrabListener(activityMapper, activityOrderMapper, template);
    }

    @Test
    void dbFallbackFailureCompensatesStockAfterCommit() {
        when(activityOrderMapper.selectCount(any())).thenReturn(0L);
        when(activityMapper.grabOnce(1)).thenReturn(0);
        when(activityOrderMapper.insert(any(ActivityOrder.class))).thenReturn(1);

        listener.handle(EVENT);

        verify(valueOperations).increment(STOCK_KEY);
        verify(template).delete(IDEMPOTENT_KEY);
    }

    @Test
    void duplicateInsertAfterSuccessfulGrabOnceAlsoCompensates() {
        // 重放投递：幂等检查与落单之间非原子，grabOnce 已多占名额但 insert 撞唯一键
        when(activityOrderMapper.selectCount(any())).thenReturn(0L);
        when(activityMapper.grabOnce(1)).thenReturn(1);
        when(activityOrderMapper.insert(any(ActivityOrder.class))).thenThrow(new DuplicateKeyException("dup"));

        listener.handle(EVENT);

        verify(valueOperations).increment(STOCK_KEY);
        verify(template).delete(IDEMPOTENT_KEY);
    }

    @Test
    void replayedDeliveryWithExistingOrderIsAckedWithoutCompensation() {
        when(activityOrderMapper.selectCount(any())).thenReturn(1L);

        listener.handle(EVENT);

        verify(valueOperations, never()).increment(STOCK_KEY);
    }

    @Test
    void successfulGrabDoesNotCompensate() {
        when(activityOrderMapper.selectCount(any())).thenReturn(0L);
        when(activityMapper.grabOnce(1)).thenReturn(1);
        when(activityOrderMapper.insert(any(ActivityOrder.class))).thenReturn(1);

        listener.handle(EVENT);

        verify(valueOperations, never()).increment(STOCK_KEY);
    }
}
