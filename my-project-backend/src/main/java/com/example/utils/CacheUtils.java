package com.example.utils;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class CacheUtils {
    private static final long DOUBLE_DELETE_DELAY_MILLIS = 500;

    @Resource
    StringRedisTemplate template;

    private final ScheduledExecutorService scheduler;

    public CacheUtils() {
        this(Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cache-double-delete");
            thread.setDaemon(true);
            return thread;
        }));
    }

    CacheUtils(StringRedisTemplate template, ScheduledExecutorService scheduler) {
        this.template = template;
        this.scheduler = scheduler;
    }

    private CacheUtils(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    public <T> T takeFromCache(String key, Class<T> dataType) {
        String s=template.opsForValue().get(key);
        if(s==null) return null;
        return JSONObject.parseObject(s).to(dataType);
    }

    public <T> List<T> takeListFromCache(String key, Class<T> itemType) {
        String s=template.opsForValue().get(key);
        if(s==null) return null;
        return JSONArray.parseArray(s).toList(itemType);
    }
    public <T> void saveToCache(String key, T data, long expire) {
        template.opsForValue().set(key, JSONObject.from(data).toJSONString(), expire, TimeUnit.SECONDS);
    }
    public <T> void saveListToCache(String key,List<T> list, long expire) {
        template.opsForValue().set(key, JSONArray.from(list).toJSONString(), expire, TimeUnit.SECONDS);
    }

    public void deleteCachePattern(String key) {
        Set<String> keys= Optional.ofNullable(template.keys(key)).orElse(Collections.emptySet());
        template.delete(keys);
    }

    public void deleteCache(String key) {//tip:Redis 的 delete 命令不支持通配符模式(*)匹配！ 它只能删除精确匹配的 key
        template.delete(key);
    }

    public void deleteCacheAfterCommit(String key) {
        deleteAfterCommit(() -> deleteCache(key));
    }

    public void deleteCachePatternAfterCommit(String pattern) {
        deleteAfterCommit(() -> deleteCachePattern(pattern));
    }

    private void deleteAfterCommit(Runnable deleteAction) {
        Runnable doubleDelete = () -> {
            deleteAction.run();
            scheduler.schedule(deleteAction, DOUBLE_DELETE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doubleDelete.run();
                }
            });
        } else {
            doubleDelete.run();
        }
    }

    @PreDestroy
    void shutdownScheduler() {
        scheduler.shutdown();
    }

}
