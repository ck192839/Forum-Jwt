package com.example.agent.index;

import com.example.utils.Const;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpMessageReturnedException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 索引事件发布器：把帖子索引事件发送到 RabbitMQ。
 *
 * 关键设计——事务同步（防止索引与业务不一致）：
 * - 若当前有活动事务，不立即发送，而是把事件暂存在事务资源里，
 * 在事务 afterCommit 后再真正发送（事务回滚则不发送）。
 * - 没有事务时直接发送。
 *
 * 可靠投递：使用 publisher confirms，5 秒内等 RabbitMQ ack，
 * 失败（nack / 消息被退回 / 超时）抛 AmqpException，由业务层决定是否重试。
 */
@Slf4j
@Component
public class TopicIndexEventPublisher {
    private static final long CONFIRM_TIMEOUT_SECONDS = 5; // 等待 ack 的超时

    private final RabbitTemplate rabbitTemplate;

    public TopicIndexEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** 发「写入/更新」索引事件（新增/编辑帖子后调用）。 */
    public void upsert(int topicId) {
        publish(new TopicIndexEvent(topicId, TopicIndexAction.UPSERT));
    }

    /** 发「删除」索引事件（删除帖子后调用）。 */
    public void delete(int topicId) {
        publish(new TopicIndexEvent(topicId, TopicIndexAction.DELETE));
    }

    /**
     * 发布入口：有活动事务则延迟到提交后发，否则立即发。
     */
    private void publish(TopicIndexEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            // 已有挂起的批量事件 → 追加进同一批
            PendingEvents pending = (PendingEvents) TransactionSynchronizationManager.getResource(this);
            if (pending != null) {
                pending.events.add(event);
                return;
            }
            // 首次：创建挂起集合并注册事务同步回调
            PendingEvents newPending = new PendingEvents();
            newPending.events.add(event);
            TransactionSynchronizationManager.bindResource(this, newPending);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 事务提交成功后才真正发送（保证索引与业务一致）
                    sendAll(newPending.events);
                }

                @Override
                public void afterCompletion(int status) {
                    // 清理事务资源（提交/回滚都会走到）
                    TransactionSynchronizationManager.unbindResourceIfPossible(TopicIndexEventPublisher.this);
                }
            });
            return;
        }
        // 无事务：直接发送
        send(event);
    }

    /** 批量发送（事务提交后），累积首个失败并抛出。 */
    private void sendAll(List<TopicIndexEvent> events) {
        AmqpException firstFailure = null;
        for (TopicIndexEvent event : events) {
            try {
                send(event);
            } catch (AmqpException exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                } else {
                    firstFailure.addSuppressed(exception);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /** 发送单个事件（失败记录日志后重新抛出）。 */
    private void send(TopicIndexEvent event) {
        try {
            sendConfirmed(event);
        } catch (AmqpException exception) {
            log.error("Unable to enqueue topic index event for topic {}", event.topicId(), exception);
            throw exception;
        }
    }

    /**
     * 带 publisher confirm 的可靠发送：
     * - convertAndSend 到 Const.MQ_TOPIC_INDEX 队列
     * - 等 confirm future（5s 超时）
     * - 消息被退回（路由失败）或 nack 都抛异常
     */
    private void sendConfirmed(TopicIndexEvent event) {
        CorrelationData correlation = new CorrelationData();
        rabbitTemplate.convertAndSend(Const.MQ_TOPIC_INDEX, event, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (correlation.getReturned() != null) {
                throw new AmqpMessageReturnedException(
                        "Topic index event was returned by RabbitMQ",
                        correlation.getReturned());
            }
            if (!confirm.isAck()) {
                throw new AmqpException("RabbitMQ rejected topic index event: " + confirm.getReason());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Interrupted while waiting for RabbitMQ publisher confirmation", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AmqpException("Unable to confirm topic index event delivery", exception);
        }
    }

    /** 事务内挂起的事件集合。 */
    private static final class PendingEvents {
        private final List<TopicIndexEvent> events = new ArrayList<>();
    }
}
