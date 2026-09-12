package com.example.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.entity.dto.Activity;
import com.example.entity.dto.ActivityGrabEvent;
import com.example.entity.dto.ActivityOrder;
import com.example.entity.vo.response.ActivityOrderVO;
import com.example.entity.vo.response.ActivityVO;
import com.example.mapper.ActivityMapper;
import com.example.mapper.ActivityOrderMapper;
import com.example.service.ActivityService;
import com.example.utils.CacheUtils;
import com.example.utils.Const;
import com.example.utils.FlowUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 抢活动服务：报名洪峰的多层削减都在这一条链路上——
 * 全局 FlowLimitingFilter（每 IP）→ 单用户防连点 → SETNX 幂等 → Redis DECR 原子预扣
 * → MQ 异步落单（削峰）→ 消费端 DB 行锁兜底防超卖。
 * 任一层拒绝都不触达下一层；MQ 投递失败会本地回补库存并释放幂等键，用户可重试。
 */
@Slf4j
@Service
public class ActivityServiceImpl implements ActivityService {

    private static final int GRAB_LIMIT_COOLDOWN_SECONDS = 3; // 单用户防连点冷却
    private static final long LIST_CACHE_EXPIRE_SECONDS = 5;  // 活动列表短期缓存（名额数允许秒级滞后）
    private static final long SEND_CONFIRM_TIMEOUT_SECONDS = 5; // MQ publisher confirm 超时

    @Resource
    StringRedisTemplate template;

    @Resource
    FlowUtils flowUtils;

    @Resource
    CacheUtils cacheUtils;

    @Resource
    ActivityMapper activityMapper;

    @Resource
    ActivityOrderMapper activityOrderMapper;

    @Resource
    RabbitTemplate rabbitTemplate;

    @Override
    public List<ActivityVO> list() {
        List<ActivityVO> cached = cacheUtils.takeListFromCache(Const.ACTIVITY_LIST_CACHE, ActivityVO.class);
        if (cached != null) return cached;
        List<ActivityVO> list = activityMapper.selectList(Wrappers.<Activity>query()
                        .orderByAsc("grab_end_time")).stream()
                .map(this::toVO)
                .toList();
        cacheUtils.saveListToCache(Const.ACTIVITY_LIST_CACHE, list, LIST_CACHE_EXPIRE_SECONDS);
        return list;
    }

    @Override
    public GrabResult grab(int uid, int activityId) {
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            return GrabResult.reject(400, "活动不存在");
        }
        Date now = new Date();
        if (now.before(activity.getGrabStartTime()) || now.after(activity.getGrabEndTime())) {
            return GrabResult.reject(400, "当前不在报名时间范围内");
        }
        // 单用户防连点：3 秒冷却，把重复点击挡在最前面
        if (!flowUtils.limitOnceCheck(Const.ACTIVITY_GRAB_LIMIT + activityId + ":" + uid, GRAB_LIMIT_COOLDOWN_SECONDS)) {
            return GrabResult.reject(429, "操作过于频繁，请稍后再试");
        }
        // 用户维度幂等：一人一活动只有第一次请求会走到预扣
        String idempotentKey = Const.ACTIVITY_GRABBED + activityId + ":" + uid;
        if (!tryAcquireIdempotentKey(idempotentKey, activity)) {
            return duplicateResult(activityId, uid);
        }
        // Redis 原子预扣库存：负数即售罄，INCR 回补计数（Redis 故障 fail-close，拒绝而非放行）
        Long stock = decrementStock(activity);
        if (stock == null) {
            releaseIdempotentKey(idempotentKey);
            return GrabResult.reject(500, "系统繁忙，请稍后再试");
        }
        if (stock < 0) {
            template.opsForValue().increment(Const.ACTIVITY_STOCK + activityId);
            releaseIdempotentKey(idempotentKey);
            return GrabResult.reject(400, "名额已抢完");
        }
        // 异步落单：MQ 确认失败时本地回补，用户可重试
        if (!sendGrabEvent(new ActivityGrabEvent(activityId, uid))) {
            template.opsForValue().increment(Const.ACTIVITY_STOCK + activityId);
            releaseIdempotentKey(idempotentKey);
            return GrabResult.reject(500, "系统繁忙，请稍后再试");
        }
        return GrabResult.queued();
    }

    @Override
    public List<ActivityOrderVO> myOrders(int uid) {
        List<ActivityOrder> orders = activityOrderMapper.selectList(Wrappers.<ActivityOrder>query()
                .eq("uid", uid)
                .orderByDesc("id"));
        if (orders.isEmpty()) return List.of();
        List<Integer> activityIds = orders.stream().map(ActivityOrder::getActivityId).distinct().toList();
        var titles = activityMapper.selectBatchIds(activityIds).stream()
                .collect(java.util.stream.Collectors.toMap(Activity::getId, Activity::getTitle));
        return orders.stream().map(order -> {
            ActivityOrderVO vo = new ActivityOrderVO();
            vo.setId(order.getId());
            vo.setActivityId(order.getActivityId());
            vo.setActivityTitle(titles.get(order.getActivityId()));
            vo.setStatus(order.getStatus());
            vo.setCreateTime(order.getCreateTime());
            return vo;
        }).toList();
    }

    /** 重复报名的幂等响应：返回已有订单的当前状态，而不是报错。 */
    private GrabResult duplicateResult(int activityId, int uid) {
        ActivityOrder order = activityOrderMapper.selectOne(Wrappers.<ActivityOrder>query()
                .eq("activity_id", activityId)
                .eq("uid", uid)
                .last("LIMIT 1"));
        if (order == null) {
            // 幂等键已存在但订单还没落库（MQ 在途）——同样按处理中对待
            return GrabResult.duplicate("报名处理中，请稍后在「我的报名」查看");
        }
        return switch (order.getStatus()) {
            case ActivityOrder.STATUS_SUCCESS -> GrabResult.duplicate("已报名成功，无需重复报名");
            case ActivityOrder.STATUS_FAILED -> GrabResult.duplicate("报名未成功，名额已被抢完");
            default -> GrabResult.duplicate("报名处理中，请稍后在「我的报名」查看");
        };
    }

    /** SETNX 幂等键，TTL 到报名截止后 1 小时自动过期（截止后窗口校验本身会拒绝）。 */
    private boolean tryAcquireIdempotentKey(String key, Activity activity) {
        long ttlSeconds = Math.max(60, (activity.getGrabEndTime().getTime() - System.currentTimeMillis()) / 1000 + 3600);
        Boolean first = template.opsForValue().setIfAbsent(key, "1", ttlSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(first);
    }

    private void releaseIdempotentKey(String key) {
        try {
            template.delete(key);
        } catch (DataAccessException e) {
            log.error("释放报名幂等键失败: {}", key, e);
        }
    }

    /** 库存键懒加载（基准 = DB 已抢数与总名额之差），随后 DECR。Redis 故障返回 null。 */
    private Long decrementStock(Activity activity) {
        String stockKey = Const.ACTIVITY_STOCK + activity.getId();
        try {
            if (Boolean.FALSE.equals(template.hasKey(stockKey))) {
                int remain = activity.getTotalStock() - activity.getGrabbed();
                template.opsForValue().setIfAbsent(stockKey, String.valueOf(Math.max(remain, 0)));
            }
            return template.opsForValue().decrement(stockKey);
        } catch (DataAccessException e) {
            log.error("Redis 预扣库存失败, activity={}", activity.getId(), e);
            return null;
        }
    }

    /** 发送报名事件并等待 publisher confirm（5 秒），确认失败视为投递失败。 */
    private boolean sendGrabEvent(ActivityGrabEvent event) {
        try {
            CorrelationData correlation = new CorrelationData();
            rabbitTemplate.convertAndSend(Const.MQ_ACTIVITY_GRAB, event, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(SEND_CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return confirm != null && confirm.isAck();
        } catch (Exception e) {
            log.error("报名事件投递失败, activity={}, uid={}", event.activityId(), event.uid(), e);
            return false;
        }
    }

    private ActivityVO toVO(Activity activity) {
        ActivityVO vo = new ActivityVO();
        vo.setId(activity.getId());
        vo.setTitle(activity.getTitle());
        vo.setDescription(activity.getDescription());
        vo.setLocation(activity.getLocation());
        vo.setActivityTime(activity.getActivityTime());
        vo.setTotalStock(activity.getTotalStock());
        vo.setGrabbed(activity.getGrabbed());
        vo.setGrabStartTime(activity.getGrabStartTime());
        vo.setGrabEndTime(activity.getGrabEndTime());
        return vo;
    }
}
