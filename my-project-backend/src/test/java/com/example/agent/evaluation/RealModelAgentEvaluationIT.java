package com.example.agent.evaluation;

import com.example.agent.config.AgentRuntimeProperties;
import com.example.agent.index.TopicChunker;
import com.example.agent.index.TopicVectorIndexer;
import com.example.agent.search.ElasticsearchKeywordTopicRetriever;
import com.example.agent.search.KeywordTopicRetriever;
import com.example.agent.search.SpringAiVectorTopicRetriever;
import com.example.agent.search.VectorTopicRetriever;
import com.example.agent.tool.ForumAuthoringTools;
import com.example.entity.dto.Topic;
import com.example.entity.dto.TopicType;
import com.example.entity.es.TopicDocument;
import com.example.mapper.TopicMapper;
import com.example.mapper.TopicTypeMapper;
import com.example.repository.TopicRepository;
import com.example.utils.ProhibitedUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class RealModelAgentEvaluationIT {
    private static final String DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode";
    private static final String DEFAULT_CHAT_MODEL = "deepseek-chat";
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-v4";
    private static final String VECTOR_INDEX = "forum-topic-vectors-evaluation";
    private static final int VECTOR_DIMENSIONS = 1024;

    @Container
    private static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            "elasticsearch:8.18.1"
    ).withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withStartupTimeout(Duration.ofMinutes(2));

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void evaluatesRealModelsAndWritesReports() throws Exception {
        String deepSeekKey = requiredEnvironment("DEEPSEEK_API_KEY");
        String dashScopeKey = requiredEnvironment("DASHSCOPE_API_KEY");
        String chatModelName = environment("DEEPSEEK_CHAT_MODEL", DEFAULT_CHAT_MODEL);
        String embeddingModelName = environment("DASHSCOPE_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL);
        AgentEvaluationDataset dataset = AgentEvaluationDataset.load(objectMapper, "/agent-evaluation/cases.json");

        ChatModel chatModel = chatModel(deepSeekKey, chatModelName);
        EmbeddingModel embeddingModel = embeddingModel(dashScopeKey, embeddingModelName);
        KeywordRepositoryConfiguration.address = ELASTICSEARCH.getHttpHostAddress();
        try (AnnotationConfigApplicationContext context = keywordContext();
             RestClient restClient = RestClient.builder(HttpHost.create(ELASTICSEARCH.getHttpHostAddress())).build()) {
            TopicRepository topicRepository = context.getBean(TopicRepository.class);
            ElasticsearchVectorStore vectorStore = vectorStore(restClient, embeddingModel);
            TopicVectorIndexer vectorIndexer = new TopicVectorIndexer(
                    vectorStore,
                    new TopicChunker(2400, 400)
            );

            Instant setupStarted = Instant.now();
            indexKeywordCorpus(topicRepository, context.getBean(ElasticsearchOperations.class), dataset);
            indexVectorCorpus(vectorIndexer, restClient, dataset);
            long indexingSetupLatencyMillis = Duration.between(setupStarted, Instant.now()).toMillis();

            KeywordTopicRetriever keywordRetriever = new ElasticsearchKeywordTopicRetriever(topicRepository);
            VectorTopicRetriever vectorRetriever = new SpringAiVectorTopicRetriever(vectorStore);
            HybridRetrievalEvaluator retrievalEvaluator = new HybridRetrievalEvaluator(
                    keywordRetriever,
                    vectorRetriever
            );
            ForumAuthoringTools tools = tools(dataset, retrievalEvaluator);
            AgentRuntimeProperties runtimeProperties = Binder.get(new StandardEnvironment())
                    .bind("agent.execution", Bindable.of(AgentRuntimeProperties.class))
                    .orElseGet(AgentRuntimeProperties::new);

            List<AgentCaseEvaluation> agentResults = new RealAgentEvaluator(
                    chatModel,
                    tools,
                    objectMapper,
                    runtimeProperties
            ).evaluate(dataset.agentCases());
            List<RetrievalCaseEvaluation> retrievalResults = retrievalEvaluator.evaluate(dataset);
            AgentEvaluationSummary summary = AgentEvaluationSummary.from(
                    agentResults,
                    retrievalResults,
                    indexingSetupLatencyMillis
            );
            Path backendProjectDirectory = backendProjectDirectory();
            new AgentEvaluationReportWriter(objectMapper).write(
                    backendProjectDirectory,
                    chatModelName,
                    embeddingModelName,
                    agentResults,
                    retrievalResults,
                    summary
            );

            Path report = backendProjectDirectory.resolve("target/agent-evaluation/report.md");
            assertTrue(summary.passed(), () -> "Agent evaluation gates failed; inspect " + report);
        }
    }

    private Path backendProjectDirectory() throws Exception {
        Path testClasses = Path.of(
                RealModelAgentEvaluationIT.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).toAbsolutePath().normalize();
        Path targetDirectory = testClasses.getParent();
        if (targetDirectory == null || !"target".equals(targetDirectory.getFileName().toString())) {
            throw new IllegalStateException("Unable to locate backend project from " + testClasses);
        }
        return targetDirectory.getParent();
    }

    private AnnotationConfigApplicationContext keywordContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(KeywordRepositoryConfiguration.class);
        context.refresh();
        return context;
    }

    private ElasticsearchVectorStore vectorStore(RestClient restClient, EmbeddingModel embeddingModel) {
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(VECTOR_INDEX);
        options.setDimensions(VECTOR_DIMENSIONS);
        ElasticsearchVectorStore vectorStore = ElasticsearchVectorStore.builder(restClient, embeddingModel)
                .options(options)
                .initializeSchema(true)
                .build();
        vectorStore.afterPropertiesSet();
        return vectorStore;
    }

    private void indexKeywordCorpus(
            TopicRepository topicRepository,
            ElasticsearchOperations operations,
            AgentEvaluationDataset dataset
    ) {
        List<TopicDocument> documents = dataset.corpus().stream()
                .map(this::topicDocument)
                .toList();
        topicRepository.saveAll(documents);
        operations.indexOps(TopicDocument.class).refresh();
    }

    private void indexVectorCorpus(
            TopicVectorIndexer indexer,
            RestClient restClient,
            AgentEvaluationDataset dataset
    ) throws Exception {
        for (AgentEvaluationDataset.CorpusTopic corpusTopic : dataset.corpus()) {
            indexer.index(topic(corpusTopic));
        }
        restClient.performRequest(new Request("POST", "/" + VECTOR_INDEX + "/_refresh"));
    }

    private TopicDocument topicDocument(AgentEvaluationDataset.CorpusTopic corpusTopic) {
        TopicDocument document = new TopicDocument();
        document.setId(corpusTopic.topicId());
        document.setTitle(corpusTopic.title());
        document.setIntro(corpusTopic.body());
        document.setContent(corpusTopic.body());
        document.setType(corpusTopic.topicTypeId());
        document.setUid(1);
        document.setTime(new Date());
        document.setTop(false);
        document.setLocked(false);
        document.setInvisible(false);
        return document;
    }

    private ChatModel chatModel(String apiKey, String model) {
        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(environment("DEEPSEEK_BASE_URL", DEFAULT_DEEPSEEK_BASE_URL))
                .apiKey(apiKey)
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model(model)
                        .temperature(0.2)
                        .build())
                .build();
    }

    private EmbeddingModel embeddingModel(String apiKey, String model) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(environment("DASHSCOPE_BASE_URL", DEFAULT_DASHSCOPE_BASE_URL))
                .apiKey(apiKey)
                .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .dimensions(VECTOR_DIMENSIONS)
                .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }

    private ForumAuthoringTools tools(
            AgentEvaluationDataset dataset,
            HybridRetrievalEvaluator retrievalEvaluator
    ) {
        TopicTypeMapper typeMapper = mock(TopicTypeMapper.class);
        Map<Integer, TopicType> types = topicTypes();
        when(typeMapper.selectList(null)).thenReturn(new ArrayList<>(types.values()));
        when(typeMapper.selectById(anyInt())).thenAnswer(
                invocation -> types.get(invocation.getArgument(0, Integer.class))
        );

        TopicMapper topicMapper = mock(TopicMapper.class);
        Map<Integer, Topic> topics = new HashMap<>();
        for (AgentEvaluationDataset.CorpusTopic corpusTopic : dataset.corpus()) {
            topics.put(corpusTopic.topicId(), topic(corpusTopic));
        }
        when(topicMapper.selectById(anyInt())).thenAnswer(
                invocation -> topics.get(invocation.getArgument(0, Integer.class))
        );

        ProhibitedUtils prohibited = mock(ProhibitedUtils.class);
        when(prohibited.containsProhibitedWord(anyString())).thenReturn(false);
        return new ForumAuthoringTools(
                typeMapper,
                topicMapper,
                retrievalEvaluator.searchService(),
                prohibited,
                200,
                8000
        );
    }

    private Map<Integer, TopicType> topicTypes() {
        Map<Integer, TopicType> types = new HashMap<>();
        for (int id = 1; id <= 8; id++) {
            TopicType type = new TopicType();
            type.setId(id);
            type.setName("Section " + id);
            type.setDesc("Evaluation section " + id);
            types.put(id, type);
        }
        return types;
    }

    private Topic topic(AgentEvaluationDataset.CorpusTopic corpusTopic) {
        Topic topic = new Topic();
        topic.setId(corpusTopic.topicId());
        topic.setTitle(corpusTopic.title());
        topic.setIntro(corpusTopic.body());
        topic.setType(corpusTopic.topicTypeId());
        topic.setInvisible(0);
        try {
            topic.setContent(objectMapper.writeValueAsString(Map.of(
                    "ops", List.of(Map.of("insert", corpusTopic.body()))
            )));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return topic;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the real Agent evaluation");
        }
        return value;
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
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
}
