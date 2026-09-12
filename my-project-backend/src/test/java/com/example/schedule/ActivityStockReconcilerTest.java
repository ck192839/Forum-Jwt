package com.example.schedule;

import com.example.entity.dto.Activity;
import com.example.mapper.ActivityMapper;
import com.example.utils.Const;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityStockReconcilerTest {

    private static final String STOCK_KEY = Const.ACTIVITY_STOCK + 1;

    @Mock
    ActivityMapper activityMapper;
    @Mock
    StringRedisTemplate template;
    @Mock
    ValueOperations<String, String> valueOperations;

    ActivityStockReconciler reconciler;

    @BeforeEach
    void setUp() {
        lenient().when(template.opsForValue()).thenReturn(valueOperations);
        reconciler = new ActivityStockReconciler(activityMapper, template);
    }

    private Activity activeActivity(int totalStock, int grabbed) {
        Activity activity = new Activity();
        activity.setId(1);
        activity.setTotalStock(totalStock);
        activity.setGrabbed(grabbed);
        activity.setGrabStartTime(new Date(System.currentTimeMillis() - 3600_000));
        activity.setGrabEndTime(new Date(System.currentTimeMillis() + 3600_000));
        return activity;
    }

    private void stubActivities(Activity... activities) {
        when(activityMapper.selectList(any())).thenReturn(List.of(activities));
    }

    @Test
    void leakedStockIsRecycledDownToDatabaseBaseline() {
        stubActivities(activeActivity(20, 3)); // DB 基准 17
        when(valueOperations.get(STOCK_KEY)).thenReturn("20"); // Redis 泄漏 3 个

        reconciler.reconcile();

        verify(valueOperations).decrement(STOCK_KEY, 3L);
    }

    @Test
    void stockBelowBaselineIsOnlyWarnedNotInflated() {
        stubActivities(activeActivity(20, 3));
        when(valueOperations.get(STOCK_KEY)).thenReturn("10"); // 疑似在途预扣

        reconciler.reconcile();

        verify(valueOperations, never()).decrement(any(String.class), any(Long.class));
    }

    @Test
    void missingStockKeyIsSkipped() {
        stubActivities(activeActivity(20, 3));
        when(valueOperations.get(STOCK_KEY)).thenReturn(null); // 懒加载未发生

        reconciler.reconcile();

        verify(valueOperations, never()).decrement(any(String.class), any(Long.class));
    }

    @Test
    void databaseFailureDoesNotThrowOutOfScheduledTask() {
        when(activityMapper.selectList(any())).thenThrow(new QueryTimeoutException("db down"));

        assertDoesNotThrow(reconciler::reconcile);
    }

    @Test
    void redisFailureOnOneActivityDoesNotStopTheRest() {
        Activity first = activeActivity(20, 3);
        first.setId(1);
        Activity second = activeActivity(20, 3);
        second.setId(2);
        when(activityMapper.selectList(any())).thenReturn(List.of(first, second));
        when(valueOperations.get(Const.ACTIVITY_STOCK + 1)).thenThrow(new QueryTimeoutException("redis down"));
        when(valueOperations.get(Const.ACTIVITY_STOCK + 2)).thenReturn("20");

        assertDoesNotThrow(reconciler::reconcile);
        verify(valueOperations).decrement(Const.ACTIVITY_STOCK + 2, 3L);
    }
}
