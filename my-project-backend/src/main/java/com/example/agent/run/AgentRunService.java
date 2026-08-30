package com.example.agent.run;

import com.example.agent.core.AgentCancellationToken;
import com.example.agent.core.AgentAnswerResult;
import com.example.agent.core.AgentDraftResult;
import com.example.agent.core.AgentQuestionResult;
import com.example.agent.core.AgentRunContext;
import com.example.agent.core.AgentRunException;
import com.example.agent.core.AgentRunFailure;
import com.example.agent.core.AgentRunObserver;
import com.example.agent.core.AgentRunRequest;
import com.example.agent.core.AgentRunner;
import com.example.agent.core.AgentTerminalResult;
import com.example.agent.session.AgentDraft;
import com.example.agent.session.AgentDraftInput;
import com.example.agent.session.AgentMessage;
import com.example.agent.session.AgentMessageRole;
import com.example.agent.session.AgentSessionAggregate;
import com.example.agent.session.AgentSessionService;
import com.example.entity.vo.response.WeatherVO;
import com.example.service.WeatherService;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.UUID;

/**
 * Agent 运行的编排服务：HTTP 层与核心 Agent 之间的粘合层。
 *
 * 职责：\n
 * 1. 会话互斥：同一会话同时只能有一个 run（sessionRuns 表）\n
 * 2. 异步执行：把 run 丢到 runExecutor 线程池，不阻塞 HTTP 线程\n
 * 3. 事件流水线：每个事件「先落库、再推 SSE」，保证可重放和顺序\n
 * 4. 结果分流：QUESTION / DRAFT / 失败，各自持久化并推送\n
 * 5. 取消：通过 ActiveRun 持有的取消令牌协作式中断\n
 *
 * 并发安全：sessionRuns / activeRuns 都是 ConcurrentHashMap，跨线程可见。\n
 * 注意：本类由 AgentRuntimeConfiguration 用 new 创建（不是组件扫描），\n
 * 5 参构造器的 runIdSupplier 是给测试注入确定性 runId 用的。
 */
public class AgentRunService {
    // 前端天气模块的默认坐标兜底（TopicList.vue 定位失败时用的同一组值）
    private static final double DEFAULT_LONGITUDE = 116.40529;
    private static final double DEFAULT_LATITUDE = 39.90499;
    // 当前时间文本格式：含星期几，供模型推断饭点（如 "2026-08-30 10:15 星期六"）
    private static final DateTimeFormatter TIME_TEXT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE", Locale.CHINA);
    // 和风天气逐小时预报的 fxTime（如 "2026-08-30T11:00+08:00"）中 HH:mm 的位置
    private static final int FX_TIME_HOUR_START = 11;
    private static final int FX_TIME_HOUR_END = 16;

    private final AgentSessionService sessionService; // 会话读写
    private final AgentRunner agentRunner; // 核心 Agent（ForumReActAgent）
    private final Executor runExecutor; // run 异步执行线程池
    private final ObjectMapper objectMapper; // 事件 payload 序列化
    private final Supplier<String> runIdSupplier; // runId 生成器（默认 UUID）
    private final WeatherService weatherService; // 天气服务（可为 null，表示禁用天气上下文）
    private final Clock clock; // 时钟（可注入测试时钟）
    // 会话 id → 当前 runId（保证互斥）
    private final Map<Long, String> sessionRuns = new ConcurrentHashMap<>();
    // runId → 活跃 run（用于取消/查找）
    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    /** 生产构造器：runId 用默认的随机 UUID。 */
    public AgentRunService(
            AgentSessionService sessionService,
            AgentRunner agentRunner,
            Executor runExecutor,
            ObjectMapper objectMapper) {
        this(sessionService, agentRunner, runExecutor, objectMapper, () -> UUID.randomUUID().toString());
    }

    /** 测试构造器：可注入固定 runId（如 "run-1"），方便断言。天气上下文禁用。 */
    public AgentRunService(
            AgentSessionService sessionService,
            AgentRunner agentRunner,
            Executor runExecutor,
            ObjectMapper objectMapper,
            Supplier<String> runIdSupplier) {
        this(sessionService, agentRunner, runExecutor, objectMapper, runIdSupplier, null, null);
    }

    /** 完整构造器：注入天气服务与时钟（AgentRuntimeConfiguration 使用）。 */
    public AgentRunService(
            AgentSessionService sessionService,
            AgentRunner agentRunner,
            Executor runExecutor,
            ObjectMapper objectMapper,
            Supplier<String> runIdSupplier,
            WeatherService weatherService,
            Clock clock) {
        this.sessionService = sessionService;
        this.agentRunner = agentRunner;
        this.runExecutor = runExecutor;
        this.objectMapper = objectMapper;
        this.runIdSupplier = runIdSupplier;
        this.weatherService = weatherService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 启动一次运行。\n
     * 时序：加载会话 → 生成 runId → 互斥登记（失败抛 409）→ 存用户消息 → 发 run_started → 异步执行 → 返回 runId。
     */
    public String start(
            int uid,
            long sessionId,
            AgentRunCommand command,
            AgentEventSink sink) {
        AgentSessionAggregate aggregate = sessionService.load(uid, sessionId);
        String runId = runIdSupplier.get();
        AgentCancellationToken cancellation = new AgentCancellationToken();
        ActiveRun run = new ActiveRun(uid, sessionId, runId, cancellation, sink, new AtomicInteger());
        // 互斥登记：若已有 run，putIfAbsent 返回旧值 → 抛冲突
        if (sessionRuns.putIfAbsent(sessionId, runId) != null) {
            throw new AgentRunConflictException();
        }
        activeRuns.put(runId, run);
        try {
            // 组装完整提示文本（含编辑器草稿）并落库为用户消息
            String promptText = command.promptText();
            sessionService.appendMessage(uid, sessionId, AgentMessageRole.USER, promptText);
            // 先发 run_started（前端拿到 runId 后取消按钮才可用）
            emit(run, AgentSseEventType.RUN_STARTED, new RunStartedPayload(runId, sessionId));
            // 历史消息转成 Spring AI Message，异步执行
            List<Message> history = toHistory(aggregate.messages());
            runExecutor.execute(() -> execute(run, command, promptText, history));
            return runId;
        } catch (RuntimeException exception) {
            // 启动阶段失败：清理登记，避免留下脏状态
            remove(run);
            throw exception;
        }
    }

    /**
     * 取消运行：只允许取消属于自己的 run（uid 校验）。
     * 
     * @return 是否成功取消（run 不存在或不属于该用户返回 false）
     */
    public boolean cancel(int uid, String runId) {
        ActiveRun run = activeRuns.get(runId);
        if (run == null || run.uid() != uid) {
            return false;
        }
        run.cancellation().cancel(); // 协作式取消，Agent 循环 50ms 内感知
        return true;
    }

    /** 该会话当前是否有活跃 run（供并发场景判断用）。 */
    public boolean hasActiveRun(long sessionId) {
        return sessionRuns.containsKey(sessionId);
    }

    /**
     * 异步执行的 run 主体（在 runExecutor 线程上跑）：\n
     * 调用核心 Agent → 按结果类型分流（追问/草稿）→ 发 run_completed → 关闭 sink。\n
     * 任何 AgentRunException 都转成错误事件；finally 里清理登记。
     */
    private void execute(
            ActiveRun run,
            AgentRunCommand command,
            String promptText,
            List<Message> history) {
        try {
            AgentRunObserver observer = observer(run);
            AgentTerminalResult result = agentRunner.run(
                    new AgentRunRequest(promptText, command.editorVersion(), history, buildContext(command)),
                    run.cancellation(),
                    observer);
            // 结果分流：追问 / 问答回答 / 草稿
            if (result instanceof AgentQuestionResult question) {
                handleQuestion(run, command, question);
            } else if (result instanceof AgentAnswerResult answer) {
                handleAnswer(run, answer);
            } else if (result instanceof AgentDraftResult draft) {
                handleDraft(run, command, draft);
            } else {
                throw new AgentRunException(AgentRunFailure.INVALID_RESPONSE, "Unknown Agent result");
            }
            emit(run, AgentSseEventType.RUN_COMPLETED, new RunCompletedPayload(run.runId(), "COMPLETED"));
            run.sink().complete();
        } catch (AgentRunException exception) {
            handleFailure(run, exception);
        } catch (RuntimeException exception) {
            // 未预料异常统一归类为 EXECUTION
            handleFailure(run, new AgentRunException(
                    AgentRunFailure.EXECUTION,
                    "Agent run failed",
                    exception));
        } finally {
            remove(run);
        }
    }

    /** 处理追问结果：存助手消息 + 推 question 事件（携带编辑器上下文供前端续聊）。 */
    private void handleQuestion(ActiveRun run, AgentRunCommand command, AgentQuestionResult question) {
        sessionService.appendMessage(
                run.uid(),
                run.sessionId(),
                AgentMessageRole.ASSISTANT,
                question.question());
        emit(run, AgentSseEventType.QUESTION, new QuestionPayload(
                question.question(),
                command.editorId(),
                command.editorVersion()));
    }

    /**
     * 处理问答回答结果：
     * 1. 存助手消息（回答正文）
     * 2. 逐条推 citation 事件（与草稿引用同机制，前端去重渲染可跳转出处）
     * 3. 推 answer 事件（携带回答正文）
     */
    private void handleAnswer(ActiveRun run, AgentAnswerResult answer) {
        sessionService.appendMessage(
                run.uid(),
                run.sessionId(),
                AgentMessageRole.ASSISTANT,
                answer.answer());
        answer.citations().forEach(citation -> emit(
                run,
                AgentSseEventType.CITATION,
                new CitationPayload(citation.topicId(), citation.title())));
        emit(run, AgentSseEventType.ANSWER, new AnswerPayload(answer.answer()));
    }

    /**
     * 组装运行环境上下文：当前时间（含星期）+ 位置天气摘要。
     * 天气获取失败（网络/未配置/坐标非法）时静默降级为 null，不阻塞运行。
     */
    private AgentRunContext buildContext(AgentRunCommand command) {
        String timeText = TIME_TEXT_FORMAT.format(LocalDateTime.now(clock.withZone(ZoneId.systemDefault())));
        return new AgentRunContext(timeText, fetchWeatherText(command));
    }

    /** 获取天气摘要文本；未注入天气服务、查询失败或返回空时返回 null。 */
    private String fetchWeatherText(AgentRunCommand command) {
        if (weatherService == null) {
            return null;
        }
        double longitude = command.longitude() == null ? DEFAULT_LONGITUDE : command.longitude();
        double latitude = command.latitude() == null ? DEFAULT_LATITUDE : command.latitude();
        try {
            WeatherVO weather = weatherService.fetchWeather(longitude, latitude);
            return weather == null ? null : summarizeWeather(weather);
        } catch (RuntimeException ignored) {
            return null; // 天气是可选增强，任何失败都不应影响问答
        }
    }

    /** 把 WeatherVO 摘要成一行英文文本（位置 + 当前天气 + 未来几小时预报）。 */
    private String summarizeWeather(WeatherVO weather) {
        StringBuilder text = new StringBuilder();
        if (weather.getLocation() != null && weather.getLocation().getString("name") != null) {
            text.append("User location: ").append(weather.getLocation().getString("name")).append(". ");
        }
        JSONObject now = weather.getNow();
        if (now != null) {
            text.append("Current weather: ")
                    .append(now.getString("temp")).append("°C, ")
                    .append(now.getString("text")).append(". ");
        }
        JSONArray hourly = weather.getHourly();
        if (hourly != null && !hourly.isEmpty()) {
            text.append("Hourly forecast: ");
            for (int i = 0; i < hourly.size(); i++) {
                JSONObject hour = hourly.getJSONObject(i);
                text.append(hourlyText(hour)).append("; ");
            }
        }
        return text.toString().trim();
    }

    /** 单个小时预报的文本（"11:00 25°C 小雨"），fxTime 解析失败时跳过该小时。 */
    private String hourlyText(JSONObject hour) {
        String fxTime = hour.getString("fxTime");
        String time = fxTime != null && fxTime.length() >= FX_TIME_HOUR_END
                ? fxTime.substring(FX_TIME_HOUR_START, FX_TIME_HOUR_END)
                : "";
        return (time + " " + hour.getString("temp") + "°C " + hour.getString("text")).trim();
    }

    /**
     * 处理草稿结果：\n
     * 1. 持久化草稿（含 basedOnEditorVersion、citations、targetEditorId=command.editorId）\n
     * 2. 存一条「已生成草稿」的助手消息\n
     * 3. 逐条推 citation 事件\n
     * 4. 推 draft_ready（前端据此展示草稿卡片）
     */
    private void handleDraft(ActiveRun run, AgentRunCommand command, AgentDraftResult draft) {
        String citationsJson = json(draft.citations());
        AgentDraft persisted = sessionService.saveDraft(
                run.uid(),
                run.sessionId(),
                new AgentDraftInput(
                        draft.basedOnEditorVersion(),
                        draft.title(),
                        draft.topicTypeId(),
                        draft.bodyMarkdown(),
                        citationsJson,
                        command.editorId()));
        sessionService.appendMessage(
                run.uid(),
                run.sessionId(),
                AgentMessageRole.ASSISTANT,
                "已生成草稿：《" + draft.title() + "》");
        // 每条引用单独一个事件（前端去重展示）
        draft.citations().forEach(citation -> emit(
                run,
                AgentSseEventType.CITATION,
                new CitationPayload(citation.topicId(), citation.title())));
        emit(run, AgentSseEventType.DRAFT_READY, new DraftReadyPayload(
                draft.title(),
                draft.topicTypeId(),
                draft.bodyMarkdown(),
                draft.citations(),
                persisted.getVersion(),
                persisted.getEditorVersion(),
                persisted.getTargetEditorId()));
    }

    /**
     * 处理失败：推 error 事件（只含安全文案）+ 按分类推 run_completed（CANCELLED/FAILED）+ 关闭 sink。\n
     * 若推送本身也失败，则以 completeWithError 兜底。
     */
    private void handleFailure(ActiveRun run, AgentRunException exception) {
        try {
            AgentRunFailure failure = exception.failure();
            emit(run, AgentSseEventType.ERROR, new ErrorPayload(
                    run.runId(),
                    failure.name(),
                    safeMessage(failure),
                    true));
            String status = failure == AgentRunFailure.CANCELLED ? "CANCELLED" : "FAILED";
            emit(run, AgentSseEventType.RUN_COMPLETED, new RunCompletedPayload(run.runId(), status));
            run.sink().complete();
        } catch (RuntimeException sinkFailure) {
            run.sink().completeWithError(sinkFailure);
        }
    }

    /** 构造观察者：把 Agent 的工具开始/完成回调转成 SSE 事件（前端时间线）。 */
    private AgentRunObserver observer(ActiveRun run) {
        return new AgentRunObserver() {
            @Override
            public void toolStarted(String name, String arguments) {
                emit(run, AgentSseEventType.TOOL_STARTED, new ToolEventPayload(run.runId(), name));
            }

            @Override
            public void toolCompleted(String name, String result) {
                emit(run, AgentSseEventType.TOOL_COMPLETED, new ToolEventPayload(run.runId(), name));
            }
        };
    }

    /**
     * 事件发射的统一入口：\n
     * 1. 自增序号，生成 eventId（runId:sequence，保证全局顺序）\n
     * 2. 先落库（可重放恢复）\n
     * 3. 再推 SSE
     */
    private void emit(ActiveRun run, AgentSseEventType type, Object payload) {
        int sequence = run.sequence().incrementAndGet();
        String eventId = run.runId() + ":" + sequence;
        sessionService.appendEvent(
                run.uid(),
                run.sessionId(),
                run.runId(),
                sequence,
                type.wireName(),
                json(payload));
        run.sink().emit(type, eventId, payload);
    }

    /** 把存储的聊天消息转成 Spring AI Message（作为模型的历史上下文）。 */
    private List<Message> toHistory(List<AgentMessage> stored) {
        List<Message> history = new ArrayList<>();
        for (AgentMessage message : stored) {
            if (message.getRole() == AgentMessageRole.USER) {
                history.add(new UserMessage(message.getContent()));
            } else if (message.getRole() == AgentMessageRole.ASSISTANT) {
                history.add(new AssistantMessage(message.getContent()));
            }
        }
        return List.copyOf(history);
    }

    /** 事件 payload 序列化为 JSON（落库用）。 */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AgentRunException(AgentRunFailure.EXECUTION, "Unable to serialize Agent event", exception);
        }
    }

    /** 失败分类 → 面向用户的安全文案（绝不泄露内部细节）。 */
    private String safeMessage(AgentRunFailure failure) {
        return switch (failure) {
            case CANCELLED -> "Agent run was cancelled";
            case TIMEOUT -> "Agent run timed out";
            case TOOL_LIMIT -> "Agent reached the tool call limit";
            case INVALID_RESPONSE -> "Agent returned an invalid response";
            case EXECUTION -> "Agent execution failed";
        };
    }

    /** 清理登记（remove 用双参数重载，避免误删并发新建的 run）。 */
    private void remove(ActiveRun run) {
        activeRuns.remove(run.runId(), run);
        sessionRuns.remove(run.sessionId(), run.runId());
    }

    /** 一次活跃运行的内部状态（uid 用于取消时的归属校验）。 */
    private record ActiveRun(
            int uid,
            long sessionId,
            String runId,
            AgentCancellationToken cancellation,
            AgentEventSink sink,
            AtomicInteger sequence) {
    }
}
