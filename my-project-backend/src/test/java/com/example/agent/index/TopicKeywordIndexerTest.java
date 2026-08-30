package com.example.agent.index;

import com.example.entity.dto.Topic;
import com.example.entity.es.TopicDocument;
import com.example.repository.TopicRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TopicKeywordIndexerTest {

    @Test
    void mapsEveryTopicFieldOntoTheDocument() {
        TopicRepository repository = mock(TopicRepository.class);
        TopicKeywordIndexer indexer = new TopicKeywordIndexer(repository);
        Topic topic = new Topic();
        topic.setId(7);
        topic.setTitle("Campus network guide");
        topic.setIntro("intro text");
        topic.setContent("{\"ops\":[{\"insert\":\"body\"}]}");
        topic.setType(3);
        topic.setUid(12);
        Date time = new Date(0);
        topic.setTime(time);
        topic.setTop(1);
        topic.setLocked(0);
        topic.setInvisible(1);

        indexer.index(topic);

        ArgumentCaptor<TopicDocument> captor = ArgumentCaptor.forClass(TopicDocument.class);
        verify(repository).save(captor.capture());
        TopicDocument document = captor.getValue();
        assertEquals(7, document.getId());
        assertEquals("Campus network guide", document.getTitle());
        assertEquals("intro text", document.getIntro());
        assertEquals("{\"ops\":[{\"insert\":\"body\"}]}", document.getContent());
        assertEquals(3, document.getType());
        assertEquals(12, document.getUid());
        assertEquals(time, document.getTime());
        assertTrue(document.getTop());
        assertFalse(document.getLocked());
        assertTrue(document.getInvisible());
    }

    @Test
    void treatsNullFlagsAsFalse() {
        TopicRepository repository = mock(TopicRepository.class);
        TopicKeywordIndexer indexer = new TopicKeywordIndexer(repository);
        Topic topic = new Topic();
        topic.setId(1);

        indexer.index(topic);

        ArgumentCaptor<TopicDocument> captor = ArgumentCaptor.forClass(TopicDocument.class);
        verify(repository).save(captor.capture());
        TopicDocument document = captor.getValue();
        assertFalse(document.getTop());
        assertFalse(document.getLocked());
        assertFalse(document.getInvisible());
        assertNull(document.getTitle());
    }

    @Test
    void deletesById() {
        TopicRepository repository = mock(TopicRepository.class);
        TopicKeywordIndexer indexer = new TopicKeywordIndexer(repository);

        indexer.delete(9);

        verify(repository).deleteById(9);
    }

    @Test
    void clearRemovesEveryDocument() {
        TopicRepository repository = mock(TopicRepository.class);
        TopicKeywordIndexer indexer = new TopicKeywordIndexer(repository);

        indexer.clear();

        verify(repository).deleteAll();
    }
}
