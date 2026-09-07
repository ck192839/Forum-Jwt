package com.example.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.Set;

import static org.mockito.Mockito.*;

class CacheUtilsTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final CacheUtils cacheUtils = new CacheUtils(template, scheduler);

    @AfterEach
    void stopScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void deletesAfterCommitAndDeletesAgainAfterDelay() {
        transactionTemplate().executeWithoutResult(status -> {
            cacheUtils.deleteCacheAfterCommit("topic:top");
            verifyNoInteractions(template);
        });

        verify(template, timeout(200).times(1)).delete("topic:top");
        verify(template, timeout(2000).times(2)).delete("topic:top");
    }

    @Test
    void doesNotDeleteWhenTransactionRollsBack() {
        transactionTemplate().executeWithoutResult(status -> {
            cacheUtils.deleteCacheAfterCommit("topic:top");
            status.setRollbackOnly();
        });

        verifyNoInteractions(template);
    }

    @Test
    void deletesImmediatelyAndAgainWhenThereIsNoTransaction() {
        cacheUtils.deleteCacheAfterCommit("topic:top");

        verify(template, timeout(200).times(1)).delete("topic:top");
        verify(template, timeout(2000).times(2)).delete("topic:top");
    }

    @Test
    void deletesMatchingKeysTwiceAfterCommit() {
        when(template.keys("topic:*")).thenReturn(Set.of("topic:1"));

        transactionTemplate().executeWithoutResult(status -> cacheUtils.deleteCachePatternAfterCommit("topic:*"));

        verify(template, timeout(200).times(1)).delete(Set.of("topic:1"));
        verify(template, timeout(2000).times(2)).delete(Set.of("topic:1"));
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(new TestTransactionManager());
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
