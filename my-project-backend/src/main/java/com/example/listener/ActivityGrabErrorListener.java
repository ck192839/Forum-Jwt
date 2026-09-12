package com.example.listener;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.entity.dto.ActivityGrabEvent;
import com.example.entity.dto.ActivityOrder;
import com.example.mapper.ActivityMapper;
import com.example.mapper.ActivityOrderMapper;
import com.example.utils.Const;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 报名死信消费者：消息重试 3 次耗尽后落在本队列。能走到这里说明事务已回滚
 * （订单未落、grabbed 未加），但 Redis 已预扣、幂等键已占——不补偿的话名额
 * 泄漏且用户被幂等键挡住无法重试。
 *
 * 补偿 = 库存 INCR + 释放幂等键，让用户可以重新报名；订单已存在（极端时序）
 * 则跳过补偿，防止凭空放名额。
 *
 * 注意：用的是无 recoverer 的独立容器工厂 activityGrabErrorRabbitListenerContainerFactory，
 * 且本方法内部吞掉所有异常——否则重试耗尽会被 recoverer 重新投递回本队列造成死循环；
 * 补偿失败的缺口由 ActivityStockReconciler 对账任务回收。
 */
@Slf4j
@Component
public class ActivityGrabErrorListener {

    private final ActivityMapper activityMapper;
    private final ActivityOrderMapper activityOrderMapper;
    private final StringRedisTemplate template;

    public ActivityGrabErrorListener(ActivityMapper activityMapper,
                                     ActivityOrderMapper activityOrderMapper,
                                     StringRedisTemplate template) {
        this.activityMapper = activityMapper;
        this.activityOrderMapper = activityOrderMapper;
        this.template = template;
    }

    @RabbitListener(queues = Const.MQ_ACTIVITY_GRAB_ERROR,
            containerFactory = "activityGrabErrorRabbitListenerContainerFactory")
    public void handle(ActivityGrabEvent event) {
        try {
            Long count = activityOrderMapper.selectCount(Wrappers.<ActivityOrder>query()
                    .eq("activity_id", event.activityId())
                    .eq("uid", event.uid()));
            if (count != null && count > 0) {
                log.warn("死信对应的报名已落单,跳过补偿, activity={}, uid={}", event.activityId(), event.uid());
                return;
            }
            template.opsForValue().increment(Const.ACTIVITY_STOCK + event.activityId());
            template.delete(Const.ACTIVITY_GRABBED + event.activityId() + ":" + event.uid());
            log.error("报名消息重试耗尽进死信,已回补库存并释放幂等键(用户可重试), activity={}, uid={}",
                    event.activityId(), event.uid());
        } catch (Exception e) {
            log.error("死信补偿失败,等待库存对账任务回收名额, activity={}, uid={}",
                    event.activityId(), event.uid(), e);
        }
    }
}
