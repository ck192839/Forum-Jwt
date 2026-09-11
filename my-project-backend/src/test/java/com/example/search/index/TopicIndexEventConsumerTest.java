package com.example.search.index;

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
        TopicKeywordIndexer keywordIndexer = mock(TopicKeywordIndexer.class);
        Topic topic = new Topic();
        topic.setId(7);
        when(mapper.selectById(7)).thenReturn(topic);
        TopicIndexEventConsumer consumer = new TopicIndexEventConsumer(mapper, indexer, keywordIndexer);

        consumer.handle(new TopicIndexEvent(7, TopicIndexAction.UPSERT));

        verify(indexer).index(topic);
        verify(keywordIndexer).index(topic);
    }

    @Test
    void deletesBothIndexesWhenTheTopicNoLongerExists() {
        TopicMapper mapper = mock(TopicMapper.class);
        TopicVectorIndexer indexer = mock(TopicVectorIndexer.class);
        TopicKeywordIndexer keywordIndexer = mock(TopicKeywordIndexer.class);
        when(mapper.selectById(7)).thenReturn(null);
        TopicIndexEventConsumer consumer = new TopicIndexEventConsumer(mapper, indexer, keywordIndexer);

        consumer.handle(new TopicIndexEvent(7, TopicIndexAction.UPSERT));

        verify(indexer).delete(7);
        verify(keywordIndexer).delete(7);
    }

    @Test
    void deletesBothIndexesForDeleteEventsWithoutReadingTheDatabase() {
        TopicMapper mapper = mock(TopicMapper.class);
        TopicVectorIndexer indexer = mock(TopicVectorIndexer.class);
        TopicKeywordIndexer keywordIndexer = mock(TopicKeywordIndexer.class);
        TopicIndexEventConsumer consumer = new TopicIndexEventConsumer(mapper, indexer, keywordIndexer);

        consumer.handle(new TopicIndexEvent(7, TopicIndexAction.DELETE));

        verify(indexer).delete(7);
        verify(keywordIndexer).delete(7);
    }
}
