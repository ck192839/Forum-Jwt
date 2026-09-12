package com.example.listener;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.entity.dto.ActivityGrabEvent;
import com.example.entity.dto.ActivityOrder;
import com.example.mapper.ActivityMapper;
import com.example.mapper.ActivityOrderMapper;
import com.example.utils.Const;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 报名事件消费者：MQ 异步落单（削峰后串行化写库）。
 *
 * 处理规则：
 * - 已有订单（重放/重复投递）→ 直接 ack，保证幂等
 * - DB 行锁 grabOnce 原子占位：成功 → 落「报名成功」；失败（名额已完，Redis 与 DB
 *   极端不一致的兜底）→ 落「报名失败」并回补 Redis 库存、释放幂等键
 * - 补偿的 Redis 操作在事务 afterCommit 后执行：事务回滚不补偿，避免凭空回补名额
 *
 * 注意：监听用的是独立容器工厂 activityGrabRabbitListenerContainerFactory
 * （独立线程、独立重试配置，重试耗尽后进死信队列）。
 */
@Slf4j
@Component
public class ActivityGrabListener {

    private final ActivityMapper activityMapper;
    private final ActivityOrderMapper activityOrderMapper;
    private final StringRedisTemplate template;

    public ActivityGrabListener(ActivityMapper activityMapper,
                                ActivityOrderMapper activityOrderMapper,
                                StringRedisTemplate template) {
        this.activityMapper = activityMapper;
        this.activityOrderMapper = activityOrderMapper;
        this.template = template;
    }

    @RabbitListener(queues = Const.MQ_ACTIVITY_GRAB, containerFactory = "activityGrabRabbitListenerContainerFactory")
    @Transactional
    public void handle(ActivityGrabEvent event) {
        // 幂等：重放/重复投递时订单已存在，直接结束
        Long count = activityOrderMapper.selectCount(Wrappers.<ActivityOrder>query()
                .eq("activity_id", event.activityId())
                .eq("uid", event.uid()));
        if (count != null && count > 0) return;

        boolean grabbed = activityMapper.grabOnce(event.activityId()) == 1;
        ActivityOrder order = new ActivityOrder();
        order.setActivityId(event.activityId());
        order.setUid(event.uid());
        order.setStatus(grabbed ? ActivityOrder.STATUS_SUCCESS : ActivityOrder.STATUS_FAILED);
        try {
            activityOrderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 并发下唯一键兜底：视为已处理（容器重试重放时也会走到这里）。
            // 此时 grabOnce 可能已多占一个名额（本条投递没有对应订单），保守回补
            if (grabbed) {
                registerStockCompensation(event.activityId(), event.uid());
            }
            return;
        }
        if (!grabbed) {
            registerStockCompensation(event.activityId(), event.uid());
        }
    }

    /**
     * DB 兜底失败后的回补：库存 +1、释放幂等键，事务提交后执行。
     * 回补失败的缺口由 ActivityStockReconciler 对账任务以 DB 为基准回收。
     */
    private void registerStockCompensation(int activityId, int uid) {
        Runnable compensate = () -> {
            try {
                template.opsForValue().increment(Const.ACTIVITY_STOCK + activityId);
                template.delete(Const.ACTIVITY_GRABBED + activityId + ":" + uid);
            } catch (Exception e) {
                log.error("报名失败回补 Redis 库存异常, activity={}, uid={}", activityId, uid, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    compensate.run();
                }
            });
        } else {
            compensate.run();
        }
    }
}
