package com.example.service.impl;

import com.alibaba.fastjson2.JSON;
import com.example.entity.dto.Activity;
import com.example.entity.dto.ActivityGrabEvent;
import com.example.entity.dto.ActivityOrder;
import com.example.entity.vo.request.ActivityAdminSaveVO;
import com.example.mapper.ActivityMapper;
import com.example.mapper.ActivityOrderMapper;
import com.example.service.ActivityService.GrabResult;
import com.example.utils.CacheUtils;
import com.example.utils.Const;
import com.example.utils.FlowUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.Serializable;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityServiceImplTest {

    private static final int ACTIVITY_ID = 1;
    private static final int UID = 42;
    private static final String STOCK_KEY = "activity:stock:1";
    private static final String IDEMPOTENT_KEY = "activity:grabbed:1:42";

    @Mock
    StringRedisTemplate template;
    @Mock
    ValueOperations<String, String> valueOperations;
    @Mock
    FlowUtils flowUtils;
    @Mock
    CacheUtils cacheUtils;
    @Mock
    ActivityMapper activityMapper;
    @Mock
    ActivityOrderMapper activityOrderMapper;
    @Mock
    RabbitTemplate rabbitTemplate;

    ActivityServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(template.opsForValue()).thenReturn(valueOperations);
        service = new ActivityServiceImpl();
        inject("template", template);
        inject("flowUtils", flowUtils);
        inject("cacheUtils", cacheUtils);
        inject("activityMapper", activityMapper);
        inject("activityOrderMapper", activityOrderMapper);
        inject("rabbitTemplate", rabbitTemplate);
    }

    private void inject(String field, Object value) {
        try {
            var target = ActivityServiceImpl.class.getDeclaredField(field);
            target.setAccessible(true);
            target.set(service, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Activity openActivity() {
        Activity activity = new Activity();
        activity.setId(ACTIVITY_ID);
        activity.setTitle("校园技术沙龙");
        activity.setTotalStock(20);
        activity.setGrabbed(3);
        activity.setGrabStartTime(new Date(System.currentTimeMillis() - 3600_000));
        activity.setGrabEndTime(new Date(System.currentTimeMillis() + 3600_000 * 24));
        activity.setStatus(Activity.STATUS_ON);
        return activity;
    }

    private void stubGrabPathAccepted(boolean idempotentKeyAcquired, Long stockAfterDecrement) {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(openActivity());
        when(flowUtils.limitOnceCheck(anyString(), eq(3))).thenReturn(true);
        when(valueOperations.setIfAbsent(eq(IDEMPOTENT_KEY), eq("1"), anyLong(), any(TimeUnit.class)))
                .thenReturn(idempotentKeyAcquired);
        lenient().when(template.hasKey(STOCK_KEY)).thenReturn(true);
        lenient().when(valueOperations.decrement(STOCK_KEY)).thenReturn(stockAfterDecrement);
    }

    /** grab 同步等待 confirm，因此必须在 convertAndSend 调用时立即完成 future。 */
    private void stubPublishConfirm(boolean ack) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(2);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), any(ActivityGrabEvent.class), any(CorrelationData.class));
    }

    private void assertEventPublished() {
        verify(rabbitTemplate).convertAndSend(eq("activity-grab"), any(ActivityGrabEvent.class), any(CorrelationData.class));
    }

    @Test
    void acceptedGrabPublishesEventAndReturnsQueued() {
        stubGrabPathAccepted(true, 5L);
        stubPublishConfirm(true);

        GrabResult result = service.grab(UID, ACTIVITY_ID);

        assertEquals(200, result.code());
        assertTrue(result.message().contains("处理中"));
        assertEventPublished();
    }

    @Test
    void duplicateGrabReturnsExistingOrderStatusInsteadOfError() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(openActivity());
        when(flowUtils.limitOnceCheck(anyString(), eq(3))).thenReturn(true);
        when(valueOperations.setIfAbsent(eq(IDEMPOTENT_KEY), eq("1"), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);
        ActivityOrder order = new ActivityOrder();
        order.setStatus(ActivityOrder.STATUS_SUCCESS);
        when(activityOrderMapper.selectOne(any())).thenReturn(order);

        GrabResult result = service.grab(UID, ACTIVITY_ID);

        assertEquals(200, result.code());
        assertTrue(result.message().contains("已报名成功"));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), any(Object.class), any(CorrelationData.class));
    }

    @Test
    void soldOutInRedisRollsBackCountAndIdempotentKey() {
        stubGrabPathAccepted(true, -1L);

        GrabResult result = service.grab(UID, ACTIVITY_ID);

        assertEquals(400, result.code());
        assertTrue(result.message().contains("名额已抢完"));
        verify(valueOperations).increment(STOCK_KEY);
        verify(template).delete(IDEMPOTENT_KEY);
        verify(rabbitTemplate, never()).convertAndSend(anyString(), any(Object.class), any(CorrelationData.class));
    }

    @Test
    void grabOutsideWindowIsRejectedBeforeTouchingRedis() {
        Activity activity = openActivity();
        activity.setGrabStartTime(new Date(System.currentTimeMillis() + 3600_000));
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity);

        GrabResult result = service.grab(UID, ACTIVITY_ID);

        assertEquals(400, result.code());
        verify(template, never()).hasKey(anyString());
    }

    @Test
    void redisFailureFailsClosedInsteadOfOverselling() {
        stubGrabPathAccepted(true, null);
        when(valueOperations.decrement(STOCK_KEY)).thenThrow(new QueryTimeoutException("redis down"));

        GrabResult result = service.grab(UID, ACTIVITY_ID);

        assertEquals(500, result.code());
        verify(template).delete(IDEMPOTENT_KEY);
        verify(rabbitTemplate, never()).convertAndSend(anyString(), any(Object.class), any(CorrelationData.class));
    }

    @Test
    void failedMqPublishCompensatesStockSoUserCanRetry() {
        stubGrabPathAccepted(true, 5L);
        stubPublishConfirm(false);

        GrabResult result = service.grab(UID, ACTIVITY_ID);

        assertEquals(500, result.code());
        verify(valueOperations).increment(STOCK_KEY);
        verify(template).delete(IDEMPOTENT_KEY);
    }

    @Test
    void stockKeyIsLazilySeededFromDatabaseWhenMissing() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(openActivity());
        when(flowUtils.limitOnceCheck(anyString(), eq(3))).thenReturn(true);
        when(valueOperations.setIfAbsent(eq(IDEMPOTENT_KEY), eq("1"), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        when(template.hasKey(STOCK_KEY)).thenReturn(false);
        when(valueOperations.setIfAbsent(eq(STOCK_KEY), eq("17"))).thenReturn(true);
        when(valueOperations.decrement(STOCK_KEY)).thenReturn(16L);
        stubPublishConfirm(true);

        GrabResult result = service.grab(UID, ACTIVITY_ID);

        assertEquals(200, result.code());
        verify(valueOperations).setIfAbsent(STOCK_KEY, "17");
    }

    @Test
    void cachedActivityConfigServesGrabPathWithoutDbQuery() {
        when(valueOperations.get(Const.ACTIVITY_CONFIG_CACHE + ACTIVITY_ID))
                .thenReturn(JSON.toJSONString(openActivity()));
        when(flowUtils.limitOnceCheck(anyString(), eq(3))).thenReturn(true);
        when(valueOperations.setIfAbsent(eq(IDEMPOTENT_KEY), eq("1"), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        lenient().when(template.hasKey(STOCK_KEY)).thenReturn(true);
        lenient().when(valueOperations.decrement(STOCK_KEY)).thenReturn(5L);
        stubPublishConfirm(true);

        GrabResult result = service.grab(UID, ACTIVITY_ID);

        assertEquals(200, result.code());
        assertEventPublished();
        verify(activityMapper, never()).selectById(ACTIVITY_ID);
    }

    @Test
    void configCacheReadFailureFallsBackToDatabase() {
        when(valueOperations.get(Const.ACTIVITY_CONFIG_CACHE + ACTIVITY_ID))
                .thenThrow(new QueryTimeoutException("redis down"));
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(openActivity());
        when(flowUtils.limitOnceCheck(anyString(), eq(3))).thenReturn(true);
        when(valueOperations.setIfAbsent(eq(IDEMPOTENT_KEY), eq("1"), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
        lenient().when(template.hasKey(STOCK_KEY)).thenReturn(true);
        lenient().when(valueOperations.decrement(STOCK_KEY)).thenReturn(5L);
        stubPublishConfirm(true);

        GrabResult result = service.grab(UID, ACTIVITY_ID);

        assertEquals(200, result.code());
        assertEventPublished();
        verify(activityMapper).selectById(ACTIVITY_ID);
    }


    @Test
    void offShelfActivityIsRejectedBeforeAnythingElse() {
        Activity activity = openActivity();
        activity.setStatus(Activity.STATUS_OFF);
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity);

        GrabResult result = service.grab(UID, ACTIVITY_ID);

        assertEquals(400, result.code());
        assertTrue(result.message().contains("已下架"));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), any(Object.class), any(CorrelationData.class));
    }

    @Test
    void adminSaveRejectsInvertedGrabWindow() {
        ActivityAdminSaveVO vo = new ActivityAdminSaveVO();
        vo.setTitle("活动");
        vo.setDescription("描述");
        vo.setLocation("地点");
        vo.setActivityTime(new Date(System.currentTimeMillis() + 86400_000));
        vo.setTotalStock(10);
        vo.setGrabStartTime(new Date(System.currentTimeMillis() + 7200_000));
        vo.setGrabEndTime(new Date(System.currentTimeMillis() + 3600_000));

        String error = service.adminSave(vo);

        assertTrue(error != null && error.contains("早于"));
        verify(activityMapper, never()).insert(any(Activity.class));
    }

    @Test
    void adminSaveCreatesActivityAndInvalidatesCaches() {
        ActivityAdminSaveVO vo = new ActivityAdminSaveVO();
        vo.setTitle("活动");
        vo.setDescription("描述");
        vo.setLocation("地点");
        vo.setActivityTime(new Date(System.currentTimeMillis() + 86400_000));
        vo.setTotalStock(10);
        vo.setGrabStartTime(new Date(System.currentTimeMillis() - 3600_000));
        vo.setGrabEndTime(new Date(System.currentTimeMillis() + 3600_000));

        // 模拟数据库自增 id 回填（MyBatis-Plus insert 后实体 id 非空）
        doAnswer(invocation -> {
            invocation.getArgument(0, Activity.class).setId(5);
            return 1;
        }).when(activityMapper).insert(any(Activity.class));

        String error = service.adminSave(vo);

        assertNull(error);
        verify(activityMapper).insert(any(Activity.class));
        verify(template).delete(Const.ACTIVITY_CONFIG_CACHE + 5);
        verify(template).delete(Const.ACTIVITY_STOCK + 5);
    }

    @Test
    void adminDeleteIsBlockedWhenOrdersExist() {
        when(activityOrderMapper.selectCount(any())).thenReturn(3L);

        String error = service.adminDelete(ACTIVITY_ID);

        assertTrue(error != null && error.contains("不能删除"));
        verify(activityMapper, never()).deleteById(any(Serializable.class));
    }
}
