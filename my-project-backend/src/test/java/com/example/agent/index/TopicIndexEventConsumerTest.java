package com.example.agent.index;

import com.example.entity.dto.Topic;
import com.example.mapper.TopicMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicIndexEventConsumerTest {

    @Test
    void indexesTheLatestTopicStateForUpsertEvents() {
        TopicMapper mapper = mock(TopicMapper.class);
        TopicVectorIndexer indexer = mock(TopicVectorIndexer.class);
        Topic topic = new Topic();
        topic.setId(7);
        when(mapper.selectById(7)).thenReturn(topic);
        TopicIndexEventConsumer consumer = new TopicIndexEventConsumer(mapper, indexer);

        consumer.handle(new TopicIndexEvent(7, TopicIndexAction.UPSERT));

        verify(indexer).index(topic);
    }

    @Test
    void deletesTheIndexWhenTheTopicNoLongerExists() {
        TopicMapper mapper = mock(TopicMapper.class);
        TopicVectorIndexer indexer = mock(TopicVectorIndexer.class);
        when(mapper.selectById(7)).thenReturn(null);
        TopicIndexEventConsumer consumer = new TopicIndexEventConsumer(mapper, indexer);

        consumer.handle(new TopicIndexEvent(7, TopicIndexAction.UPSERT));

        verify(indexer).delete(7);
    }

    @Test
    void deletesTheIndexForDeleteEventsWithoutReadingTheDatabase() {
        TopicMapper mapper = mock(TopicMapper.class);
        TopicVectorIndexer indexer = mock(TopicVectorIndexer.class);
        TopicIndexEventConsumer consumer = new TopicIndexEventConsumer(mapper, indexer);

        consumer.handle(new TopicIndexEvent(7, TopicIndexAction.DELETE));

        verify(indexer).delete(7);
    }
}
