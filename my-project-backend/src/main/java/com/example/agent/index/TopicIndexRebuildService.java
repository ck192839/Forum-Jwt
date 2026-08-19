package com.example.agent.index;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.entity.dto.Topic;
import com.example.mapper.TopicMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 索引全量重建服务：遍历所有公开帖子，重新生成向量索引。
 *
 * 用途：向量索引损坏/版本升级后，后台管理入口（AgentIndexAdminController）触发全量重建。
 *
 * 并发安全：AtomicBoolean running 保证同一时刻只有一个重建任务；
 * status 是 volatile 记录，供外部随时查询进度（后台管理页面展示）。
 * 单帖失败不影响整体：失败计数，继续处理下一帖。
 */
@Slf4j
public class TopicIndexRebuildService {
    private final TopicMapper topicMapper; // 帖子查询
    private final TopicVectorIndexer indexer; // 向量索引写入
    private final Executor executor; // 重建执行器（单线程，串行执行）
    private final AtomicBoolean running = new AtomicBoolean(false); // 是否正在重建
    private volatile TopicIndexRebuildStatus status = TopicIndexRebuildStatus.idle(); // 进度状态

    public TopicIndexRebuildService(TopicMapper topicMapper, TopicVectorIndexer indexer, Executor executor) {
        this.topicMapper = topicMapper;
        this.indexer = indexer;
        this.executor = executor;
    }

    /**
     * 启动重建。
     * 
     * @return 是否成功启动（已有任务在跑则返回 false）
     */
    public boolean start() {
        // CAS 保证互斥：已有任务时直接拒绝
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        Instant startedAt = Instant.now();
        status = new TopicIndexRebuildStatus(true, 0, 0, 0, startedAt, null);
        try {
            executor.execute(() -> rebuild(startedAt));
        } catch (RuntimeException exception) {
            // 提交失败：复位状态并抛给调用方
            running.set(false);
            status = new TopicIndexRebuildStatus(false, 0, 0, 1, startedAt, Instant.now());
            throw exception;
        }
        return true;
    }

    /** 查询当前重建状态（管理页面轮询用）。 */
    public TopicIndexRebuildStatus status() {
        return status;
    }

    /** 重建主逻辑（在 executor 线程上异步执行）。 */
    private void rebuild(Instant startedAt) {
        int processed = 0; // 成功处理数
        int failed = 0; // 失败数
        try {
            // 只重建公开帖子（invisible = 0）
            List<Topic> topics = topicMapper.selectList(Wrappers.<Topic>query().eq("invisible", 0));
            status = new TopicIndexRebuildStatus(true, topics.size(), 0, 0, startedAt, null);
            // 先清空旧索引，再全量写入
            indexer.clear();
            for (Topic topic : topics) {
                try {
                    indexer.index(topic);
                    processed++;
                } catch (RuntimeException exception) {
                    // 单帖失败不中断整体重建
                    failed++;
                    log.error("Unable to rebuild vector index for topic {}", topic.getId(), exception);
                }
                status = new TopicIndexRebuildStatus(
                        true, topics.size(), processed, failed, startedAt, null);
            }
            status = new TopicIndexRebuildStatus(
                    false, topics.size(), processed, failed, startedAt, Instant.now());
        } catch (RuntimeException exception) {
            // 连帖子都加载不出来：整体失败
            status = new TopicIndexRebuildStatus(false, 0, processed, failed + 1, startedAt, Instant.now());
            log.error("Unable to load topics for vector index rebuild", exception);
        } finally {
            running.set(false);
        }
    }
}
