package com.example.agent.index;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.example.entity.dto.Topic;
import com.example.mapper.TopicMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class TopicVectorElasticsearchIntegrationTest {
    private static final String INDEX = "forum-topic-vectors-integration";

    @Container
    private static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            "elasticsearch:8.18.1"
    ).withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    private static RestClient restClient;
    private static ElasticsearchVectorStore vectorStore;
    private static TopicVectorIndexer indexer;

    @BeforeAll
    static void createRealVectorStore() throws Exception {
        restClient = RestClient.builder(HttpHost.create(ELASTICSEARCH.getHttpHostAddress())).build();
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(INDEX);
        options.setDimensions(4);
        vectorStore = ElasticsearchVectorStore.builder(restClient, new DeterministicEmbeddingModel())
                .options(options)
                .initializeSchema(true)
                .build();
        vectorStore.afterPropertiesSet();
        indexer = new TopicVectorIndexer(vectorStore, new TopicChunker(24, 4));
    }

    @AfterAll
    static void closeClient() throws IOException {
        if (restClient != null) {
            restClient.close();
        }
    }

    @BeforeEach
    void clearIndex() throws Exception {
        Request request = new Request("POST", "/" + INDEX + "/_delete_by_query?refresh=true");
        request.setJsonEntity("{\"query\":{\"match_all\":{}}}");
        restClient.performRequest(request);
    }

    @Test
    void visibleChunksAreWrittenReplacedAndClearedForHiddenOrDeletedTopics() throws Exception {
        Topic topic = topic(42, 0, "Old network setup details spanning multiple chunks");
        indexer.index(topic);
        refresh();
        List<Document> original = documentsFor(42);
        assertFalse(original.isEmpty());
        assertTrue(original.stream().allMatch(document -> document.getMetadata().get("topicId").equals(42)));

        topic.setIntro("New replacement content only");
        indexer.index(topic);
        refresh();
        List<Document> replacement = documentsFor(42);
        assertFalse(replacement.isEmpty());
        assertTrue(replacement.stream().anyMatch(document -> document.getText().contains("New replacement")));
        assertTrue(replacement.stream().noneMatch(document -> document.getText().contains("Old network")));

        topic.setInvisible(1);
        indexer.index(topic);
        refresh();
        assertTrue(documentsFor(42).isEmpty());

        topic.setInvisible(0);
        indexer.index(topic);
        refresh();
        indexer.delete(42);
        refresh();
        assertTrue(documentsFor(42).isEmpty());
    }

    @Test
    void fullRebuildLoadsOnlyVisibleRowsAndWritesThemToTheRealStore() throws Exception {
        TopicMapper mapper = mock(TopicMapper.class);
        AtomicReference<Wrapper<Topic>> query = new AtomicReference<>();
        Topic visible = topic(51, 0, "Visible rebuild content");
        indexer.index(topic(50, 0, "Stale content for a deleted topic"));
        refresh();
        assertFalse(documentsFor(50).isEmpty());
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            query.set(invocation.getArgument(0));
            return List.of(visible);
        });
        TopicIndexRebuildService rebuild = new TopicIndexRebuildService(mapper, indexer, Runnable::run);

        assertTrue(rebuild.start());
        refresh();

        assertEquals(1, rebuild.status().processed());
        assertFalse(documentsFor(51).isEmpty());
        assertTrue(documentsFor(50).isEmpty());
        assertTrue(query.get().getSqlSegment().contains("invisible"));
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) query.get();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(0));
    }

    private static Topic topic(int id, int invisible, String intro) {
        Topic topic = new Topic();
        topic.setId(id);
        topic.setTitle("Campus network");
        topic.setIntro(intro);
        topic.setContent("{\"ops\":[{\"insert\":\"ignored raw content\"}]}");
        topic.setType(1);
        topic.setInvisible(invisible);
        return topic;
    }

    private static List<Document> documentsFor(int topicId) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query("network")
                .topK(100)
                .similarityThresholdAll()
                .filterExpression("topicId == " + topicId)
                .build());
    }

    private static void refresh() throws IOException {
        restClient.performRequest(new Request("POST", "/" + INDEX + "/_refresh"));
    }

    private static final class DeterministicEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = IntStream.range(0, request.getInstructions().size())
                    .mapToObj(index -> new Embedding(vector(request.getInstructions().get(index)), index))
                    .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        @Override
        public int dimensions() {
            return 4;
        }

        private float[] vector(String text) {
            int hash = text == null ? 0 : text.hashCode();
            return new float[]{
                    1.0f,
                    ((hash >>> 0) & 0xff) / 255.0f,
                    ((hash >>> 8) & 0xff) / 255.0f,
                    ((hash >>> 16) & 0xff) / 255.0f
            };
        }
    }
}
