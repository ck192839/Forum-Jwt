package com.example.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.entity.dto.Activity;
import com.example.mapper.ActivityMapper;
import com.example.utils.Const;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 抢活动库存对账任务：以 DB 为唯一基准（expected = total_stock - grabbed），
 * 回收 Redis 库存中泄漏的名额——回补丢失、死信未补偿、MQ 途中等造成的"少卖"，
 * 最迟一个对账周期被回收。
 *
 * 单方向纠偏原则：只回收（Redis > expected 时 DECRBY，只会变小），绝不放大
 * （Redis < expected 可能只是预扣在途，自动加回去会凭空多放名额导致超卖），只告警。
 * 键不存在说明库存懒加载还没发生，跳过。单活动异常不中断整轮。
 */
@Slf4j
@Component
public class ActivityStockReconciler {

    private final ActivityMapper activityMapper;
    private final StringRedisTemplate template;

    public ActivityStockReconciler(ActivityMapper activityMapper, StringRedisTemplate template) {
        this.activityMapper = activityMapper;
        this.template = template;
    }

    @Scheduled(fixedDelay = 30_000)
    public void reconcile() {
        List<Activity> activities;
        try {
            activities = activityMapper.selectList(Wrappers.<Activity>query()
                    .le("grab_start_time", new Date())
                    .ge("grab_end_time", new Date()));
        } catch (DataAccessException e) {
            log.error("对账任务查询活动失败,本轮跳过", e);
            return;
        }
        for (Activity activity : activities) {
            try {
                reconcileOne(activity);
            } catch (Exception e) {
                log.error("活动库存对账异常, activity={}", activity.getId(), e);
            }
        }
    }

    private void reconcileOne(Activity activity) {
        String stockKey = Const.ACTIVITY_STOCK + activity.getId();
        String value = template.opsForValue().get(stockKey);
        if (value == null) return; // 懒加载未发生，无对账对象
        long current = Long.parseLong(value);
        long expected = Math.max(activity.getTotalStock() - activity.getGrabbed(), 0);
        if (current > expected) {
            // DECRBY 而非 SET：原子且只减不增，与在途预扣 DECR 并发时也不会放大库存
            template.opsForValue().decrement(stockKey, current - expected);
            log.warn("对账回收泄漏名额, activity={}, redis={}, db基准={}", activity.getId(), current, expected);
        } else if (current < expected) {
            log.warn("Redis 库存低于 DB 基准,可能为预扣在途,持续出现需人工核查, activity={}, redis={}, db基准={}",
                    activity.getId(), current, expected);
        }
    }
}
