package com.example.agent.index;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.entity.dto.Topic;
import com.example.mapper.TopicMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class TopicIndexRebuildService {
    private final TopicMapper topicMapper;
    private final TopicVectorIndexer indexer;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile TopicIndexRebuildStatus status = TopicIndexRebuildStatus.idle();

    public TopicIndexRebuildService(TopicMapper topicMapper, TopicVectorIndexer indexer, Executor executor) {
        this.topicMapper = topicMapper;
        this.indexer = indexer;
        this.executor = executor;
    }

    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        Instant startedAt = Instant.now();
        status = new TopicIndexRebuildStatus(true, 0, 0, 0, startedAt, null);
        try {
            executor.execute(() -> rebuild(startedAt));
        } catch (RuntimeException exception) {
            running.set(false);
            status = new TopicIndexRebuildStatus(false, 0, 0, 1, startedAt, Instant.now());
            throw exception;
        }
        return true;
    }

    public TopicIndexRebuildStatus status() {
        return status;
    }

    private void rebuild(Instant startedAt) {
        int processed = 0;
        int failed = 0;
        try {
            List<Topic> topics = topicMapper.selectList(Wrappers.<Topic>query().eq("invisible", 0));
            status = new TopicIndexRebuildStatus(true, topics.size(), 0, 0, startedAt, null);
            indexer.clear();
            for (Topic topic : topics) {
                try {
                    indexer.index(topic);
                    processed++;
                } catch (RuntimeException exception) {
                    failed++;
                    log.error("Unable to rebuild vector index for topic {}", topic.getId(), exception);
                }
                status = new TopicIndexRebuildStatus(
                        true, topics.size(), processed, failed, startedAt, null
                );
            }
            status = new TopicIndexRebuildStatus(
                    false, topics.size(), processed, failed, startedAt, Instant.now()
            );
        } catch (RuntimeException exception) {
            status = new TopicIndexRebuildStatus(false, 0, processed, failed + 1, startedAt, Instant.now());
            log.error("Unable to load topics for vector index rebuild", exception);
        } finally {
            running.set(false);
        }
    }
}
