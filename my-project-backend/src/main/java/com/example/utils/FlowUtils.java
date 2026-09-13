package com.example.utils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 限流通用工具
 * 针对于不同的情况进行限流操作，支持限流升级
 *
 * 原实现为 JVM 内 synchronized + 2~4 次 Redis 往返的 check-then-act，
 * 高并发同 IP 场景下退化为全局锁（压测 200 并发卡在 ~245 QPS，等于 1÷临界区耗时）。
 * 现将检查与计数合并为单次 Lua 原子脚本：一次往返、天然串行化于 Redis 单线程，
 * 无需任何外部锁；限流状态在 Redis 中，天然支持多实例部署。
 */
@Slf4j
@Component
public class FlowUtils {

    @Resource
    StringRedisTemplate template;

    /**
     * 计数限流脚本：INCR 计数，首个请求为计数键设 TTL，超过频率即拒绝。
     * KEYS[1]=计数键，ARGV[1]=计数周期(秒)，ARGV[2]=频率上限。返回 1=放行 0=拒绝。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final DefaultRedisScript<Boolean> COUNTER_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            if n > tonumber(ARGV[2]) then
                return 0
            end
            return 1
            """, (Class) Boolean.class);

    /**
     * 周期限流脚本：封禁键存在直接拒绝（不计数）；计数超频时设封禁键。
     * KEYS[1]=计数键，KEYS[2]=封禁键，ARGV[1]=封禁时间(秒)，ARGV[2]=计数周期(秒)，ARGV[3]=频率上限。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final DefaultRedisScript<Boolean> PERIOD_BLOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 0
            end
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if n > tonumber(ARGV[3]) then
                redis.call('SET', KEYS[2], '', 'EX', ARGV[1])
                return 0
            end
            return 1
            """, (Class) Boolean.class);

    /**
     * 升级惩罚脚本：超频后以更长的升级时间重置同一计数键的 TTL。
     * KEYS[1]=计数键，ARGV[1]=基础限制时间(秒)，ARGV[2]=频率上限，ARGV[3]=升级限制时间(秒)。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final DefaultRedisScript<Boolean> UPGRADE_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
                return 1
            end
            if n > tonumber(ARGV[2]) then
                redis.call('SET', KEYS[1], '1', 'EX', ARGV[3])
                return 0
            end
            return 1
            """, (Class) Boolean.class);

    /**
     * 针对于单次频率限制，请求成功后，在冷却时间内不得再次进行请求，如3秒内不能再次发起请求
     * @param key 键
     * @param blockTime 限制时间
     * @return 是否通过限流检查
     */
    public boolean limitOnceCheck(String key, int blockTime){
        return Boolean.TRUE.equals(template.execute(COUNTER_LIMIT_SCRIPT, List.of(key),
                String.valueOf(blockTime), "1"));
    }

    /**
     * 针对于单次频率限制，请求成功后，在冷却时间内不得再次进行请求
     * 如3秒内不能再次发起请求，如果不听劝阻继续发起请求，将限制更长时间
     * @param key 键
     * @param frequency 请求频率
     * @param baseTime 基础限制时间
     * @param upgradeTime 升级限制时间
     * @return 是否通过限流检查
     */
    public boolean limitOnceUpgradeCheck(String key, int frequency, int baseTime, int upgradeTime){
        return Boolean.TRUE.equals(template.execute(UPGRADE_LIMIT_SCRIPT, List.of(key),
                String.valueOf(baseTime), String.valueOf(frequency), String.valueOf(upgradeTime)));
    }

    /**
     * 针对于在时间段内多次请求限制，如3秒内限制请求20次，超出频率则封禁一段时间
     * @param counterKey 计数键
     * @param blockKey 封禁键
     * @param blockTime 封禁时间
     * @param frequency 请求频率
     * @param period 计数周期
     * @return 是否通过限流检查
     */
    public boolean limitPeriodCheck(String counterKey, String blockKey, int blockTime, int frequency, int period){
        return Boolean.TRUE.equals(template.execute(PERIOD_BLOCK_SCRIPT, List.of(counterKey, blockKey),
                String.valueOf(blockTime), String.valueOf(period), String.valueOf(frequency)));
    }

    /**
     * 针对于在时间段内多次请求限制，如3秒内20次请求
     * @param counterKey 计数键
     * @param frequency 请求频率
     * @param period 计数周期
     * @return 是否通过限流检查
     */
    public boolean limitPeriodCounterCheck(String counterKey, int frequency, int period){
        return Boolean.TRUE.equals(template.execute(COUNTER_LIMIT_SCRIPT, List.of(counterKey),
                String.valueOf(period), String.valueOf(frequency)));
    }
}
