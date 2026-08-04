package com.example.agent.index;

import com.example.entity.dto.Topic;
import com.example.mapper.TopicMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopicIndexRebuildServiceTest {

    @Test
    void reportsProcessedAndFailedTopics() {
        TopicMapper mapper = mock(TopicMapper.class);
        TopicVectorIndexer indexer = mock(TopicVectorIndexer.class);
        Topic first = topic(1);
        Topic second = topic(2);
        when(mapper.selectList(any())).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("embedding failed")).when(indexer).index(second);
        TopicIndexRebuildService service = new TopicIndexRebuildService(mapper, indexer, Runnable::run);

        assertTrue(service.start());

        TopicIndexRebuildStatus status = service.status();
        assertFalse(status.running());
        assertEquals(2, status.total());
        assertEquals(1, status.processed());
        assertEquals(1, status.failed());
    }

    @Test
    void clearsRunningStateWhenLoadingTopicsFails() {
        TopicMapper mapper = mock(TopicMapper.class);
        TopicVectorIndexer indexer = mock(TopicVectorIndexer.class);
        when(mapper.selectList(any())).thenThrow(new IllegalStateException("database unavailable"));
        TopicIndexRebuildService service = new TopicIndexRebuildService(mapper, indexer, Runnable::run);

        assertTrue(service.start());

        assertFalse(service.status().running());
        assertEquals(1, service.status().failed());
    }

    private Topic topic(int id) {
        Topic topic = new Topic();
        topic.setId(id);
        return topic;
    }
}
