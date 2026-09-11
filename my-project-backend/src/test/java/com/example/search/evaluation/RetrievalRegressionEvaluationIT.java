package com.example.search.evaluation;

import com.example.search.ElasticsearchKeywordTopicRetriever;
import com.example.search.HybridTopicSearchService;
import com.example.search.RankedTopic;
import com.example.search.SpringAiVectorTopicRetriever;
import com.example.entity.es.TopicDocument;
import com.example.repository.TopicRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索回归评测（无外部模型依赖）：真实 ES + 确定性 embedding 跑混合检索全链路。
 *
 * 与 RealModelAgentEvaluationIT 的分工：
 * - 本测试不调 DeepSeek/DashScope——向量路用确定性 embedding（字符 2-gram 频次向量），
 *   关键词路是真实 ES 查询（match_phrase + match 容错 + highlight），
 *   因此改动检索逻辑后 `./mvnw verify -Pagent-eval` 即可重复验证 Recall@5，不需要 API Key；
 * - 门禁与简历口径一致：Recall@5 >= 80%，关键词路零召回率 = 0
 *   （措辞改写用例必须由关键词路自身召回，验证 match 容错生效）。
 */
@Testcontainers(disabledWithoutDocker = true)
class RetrievalRegressionEvaluationIT {
    private static final String VECTOR_INDEX = "forum-topic-vectors-retrieval-regression";
    private static final int VECTOR_DIMENSIONS = 64;
    private static final double REQUIRED_RECALL_AT_FIVE = 0.8;

    @Container
    private static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            "elasticsearch:8.18.1"
    ).withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    private static RestClient restClient;
    private static AnnotationConfigApplicationContext keywordContext;

    @BeforeAll
    static void start() throws Exception {
        restClient = RestClient.builder(HttpHost.create(ELASTICSEARCH.getHttpHostAddress())).build();
        KeywordRepositoryConfiguration.address = ELASTICSEARCH.getHttpHostAddress();
        keywordContext = new AnnotationConfigApplicationContext();
        keywordContext.register(KeywordRepositoryConfiguration.class);
        keywordContext.refresh();
    }

    @AfterAll
    static void stop() throws IOException {
        if (keywordContext != null) {
            keywordContext.close();
        }
        if (restClient != null) {
            restClient.close();
        }
    }

    @Test
    void hybridRetrievalKeepsRecallAtFiveAboveGate() throws Exception {
        RetrievalEvaluationDataset dataset = RetrievalEvaluationDataset.load(
                new ObjectMapper(), "/search-evaluation/cases.json");

        ElasticsearchOperations operations = keywordContext.getBean(ElasticsearchOperations.class);
        indexKeywordCorpus(keywordContext.getBean(TopicRepository.class), operations, dataset);

        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(VECTOR_INDEX);
        options.setDimensions(VECTOR_DIMENSIONS);
        BigramEmbeddingModel embeddingModel = new BigramEmbeddingModel();
        ElasticsearchVectorStore vectorStore = ElasticsearchVectorStore.builder(restClient, embeddingModel)
                .options(options)
                .initializeSchema(true)
                .build();
        vectorStore.afterPropertiesSet();
        for (RetrievalEvaluationDataset.CorpusTopic corpusTopic : dataset.corpus()) {
            vectorStore.add(List.of(new Document(
                    String.valueOf(corpusTopic.topicId()),
                    corpusTopic.title() + "\n" + corpusTopic.body(),
                    java.util.Map.of("topicId", corpusTopic.topicId()))));
        }
        restClient.performRequest(new Request("POST", "/" + VECTOR_INDEX + "/_refresh"));

        HybridTopicSearchService searchService = new HybridTopicSearchService(
                new ElasticsearchKeywordTopicRetriever(operations),
                new SpringAiVectorTopicRetriever(vectorStore));
        ElasticsearchKeywordTopicRetriever keywordRetriever = new ElasticsearchKeywordTopicRetriever(operations);

        int relevant = 0;
        int recalled = 0;
        int keywordZeroRecall = 0;
        StringBuilder failures = new StringBuilder();
        for (RetrievalEvaluationDataset.RetrievalCase testCase : dataset.retrievalCases()) {
            List<Integer> retrieved = searchService.search(testCase.query()).stream()
                    .map(RankedTopic::topic)
                    .map(hit -> hit.topicId())
                    .limit(5)
                    .toList();
            List<Integer> keywordOnly = keywordRetriever
                    .search(testCase.query()).stream()
                    .map(hit -> hit.topicId())
                    .limit(5)
                    .toList();

            for (Integer expected : testCase.relevantTopicIds()) {
                relevant++;
                if (retrieved.contains(expected)) {
                    recalled++;
                } else {
                    failures.append(String.format(Locale.ROOT,
                            "%s expected %d, got top5=%s (keyword=%s)%n",
                            testCase.id(), expected, retrieved, keywordOnly));
                }
            }
            if (keywordOnly.stream().noneMatch(testCase.relevantTopicIds()::contains)) {
                keywordZeroRecall++;
            }
        }

        double recallAtFive = relevant == 0 ? 0.0 : (double) recalled / relevant;
        assertTrue(recallAtFive + 0.0000001 >= REQUIRED_RECALL_AT_FIVE,
                () -> "Recall@5 dropped below " + REQUIRED_RECALL_AT_FIVE + ": "
                        + recallAtFive + System.lineSeparator() + failures);
        int zeroRecallCases = keywordZeroRecall;
        assertEquals(0, zeroRecallCases,
                "keyword retriever returned zero recall for " + zeroRecallCases
                        + " case(s); match fallback should cover paraphrased queries");
    }

    private void indexKeywordCorpus(
            TopicRepository topicRepository,
            ElasticsearchOperations operations,
            RetrievalEvaluationDataset dataset
    ) {
        List<TopicDocument> documents = dataset.corpus().stream()
                .map(corpusTopic -> {
                    TopicDocument document = new TopicDocument();
                    document.setId(corpusTopic.topicId());
                    document.setTitle(corpusTopic.title());
                    document.setIntro(corpusTopic.body());
                    document.setType(corpusTopic.topicTypeId());
                    document.setUid(1);
                    document.setTime(new java.util.Date());
                    document.setTop(false);
                    document.setLocked(false);
                    document.setInvisible(false);
                    return document;
                })
                .toList();
        topicRepository.saveAll(documents);
        operations.indexOps(TopicDocument.class).refresh();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableElasticsearchRepositories(basePackageClasses = TopicRepository.class)
    static class KeywordRepositoryConfiguration extends ElasticsearchConfiguration {
        private static String address;

        @Override
        public ClientConfiguration clientConfiguration() {
            return ClientConfiguration.builder().connectedTo(address).build();
        }
    }

    /**
     * 确定性 embedding：中文按 2-gram、英文按小写单词生成频次向量。
     * 不依赖外部模型，且同一模型同时用于索引与查询，语义相近的文本向量相近，
     * 使向量路在回归测试中提供真实的（尽管是近似的）语义召回信号。
     */
    private static final class BigramEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = IntStream.range(0, request.getInstructions().size())
                    .mapToObj(index -> new Embedding(
                            vector(request.getInstructions().get(index)), index))
                    .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        @Override
        public int dimensions() {
            return VECTOR_DIMENSIONS;
        }

        private float[] vector(String text) {
            float[] vector = new float[VECTOR_DIMENSIONS];
            if (text == null) {
                return vector;
            }
            tokens(text).forEach(token -> vector[Math.floorMod(token.hashCode(), VECTOR_DIMENSIONS)] += 1.0f);
            double norm = Math.sqrt(IntStream.range(0, VECTOR_DIMENSIONS)
                    .mapToObj(i -> vector[i])
                    .mapToDouble(v -> v * v)
                    .sum());
            if (norm > 0) {
                for (int i = 0; i < VECTOR_DIMENSIONS; i++) {
                    vector[i] /= (float) norm;
                }
            }
            return vector;
        }

        private java.util.stream.Stream<String> tokens(String text) {
            String lower = text.toLowerCase(Locale.ROOT);
            java.util.List<String> latin = java.util.Arrays.stream(lower.split("[^a-z0-9]+"))
                    .filter(token -> !token.isEmpty())
                    .toList();
            java.util.List<String> all = new java.util.ArrayList<>(latin);
            for (int i = 0; i + 2 <= lower.length(); i++) {
                all.add(lower.substring(i, i + 2));
            }
            return all.stream();
        }
    }
}
