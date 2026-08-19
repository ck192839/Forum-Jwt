package com.example.agent.api;

import com.example.agent.config.AgentRuntimeProperties;
import com.example.agent.run.AgentRunCommand;
import com.example.agent.run.AgentRunService;
import com.example.agent.run.SseEmitterAgentEventSink;
import com.example.agent.session.AgentSessionAggregate;
import com.example.agent.session.AgentSessionService;
import com.example.entity.RestBean;
import com.example.utils.Const;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 模块的 HTTP 入口（REST + SSE）。路由前缀 /api/agent。
 *
 * 提供两类能力：
 * 1. 会话管理（创建 / 列表 / 恢复 / 删除）——普通 REST，返回 RestBean
 * 2. 运行管理（发起运行 / 取消运行）——发起运行返回 SseEmitter 流式推送事件
 *
 * 鉴权：所有接口都从请求属性里取 uid（由全局过滤器注入），
 * 因此每个操作都天然限定在当前用户自己的数据上（多租户隔离）。
 *
 * 注意：本类被组件扫描，且有两个构造器，所以 public 构造器必须标注 @Autowired；
 * 包级私有构造器是给测试用的（可注入自定义 sseTimeoutMillis）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentSessionService sessionService; // 会话的读写服务
    private final AgentRunService runService; // 运行编排服务
    private final AgentDtoMapper dtoMapper; // 实体 → DTO 转换
    private final long sseTimeoutMillis; // SSE 连接超时时间（毫秒）

    /**
     * 生产用构造器（Spring 注入）：sseTimeoutMillis 从配置读取。
     */
    @Autowired
    public AgentController(
            AgentSessionService sessionService,
            AgentRunService runService,
            ObjectMapper objectMapper,
            AgentRuntimeProperties properties) {
        this(sessionService, runService, objectMapper, properties.getSseTimeout().toMillis());
    }

    /**
     * 测试用构造器（包级私有）：sseTimeoutMillis 由调用方显式传入。
     */
    AgentController(
            AgentSessionService sessionService,
            AgentRunService runService,
            ObjectMapper objectMapper,
            long sseTimeoutMillis) {
        this.sessionService = sessionService;
        this.runService = runService;
        this.dtoMapper = new AgentDtoMapper(objectMapper);
        this.sseTimeoutMillis = sseTimeoutMillis;
    }

    /**
     * 创建新会话。返回会话摘要（此时还没有消息，title 为 null）。
     * 前端「新建会话」按钮调用。
     */
    @PostMapping("/sessions")
    public RestBean<AgentApiDtos.SessionSummary> create(
            @RequestAttribute(Const.ATTR_USER_ID) int uid) {
        return RestBean.success(dtoMapper.summary(sessionService.create(uid), null));
    }

    /**
     * 查询当前用户最近的会话列表（含自动解析出的标题）。
     * 前端打开助手时用来渲染会话下拉框。
     */
    @GetMapping("/sessions/recent")
    public RestBean<List<AgentApiDtos.SessionSummary>> recent(
            @RequestAttribute(Const.ATTR_USER_ID) int uid) {
        return RestBean.success(sessionService.listRecent(uid).stream()
                .map(session -> dtoMapper.summary(session, sessionService.resolveTitle(session)))
                .toList());
    }

    /**
     * 恢复指定会话：返回消息 + 历史事件 + 草稿。
     * 前端切换会话时调用，用事件重放重建 UI 状态。
     */
    @GetMapping("/sessions/{id}")
    public RestBean<AgentApiDtos.SessionDetail> restore(
            @PathVariable long id,
            @RequestAttribute(Const.ATTR_USER_ID) int uid) {
        AgentSessionAggregate aggregate = sessionService.load(uid, id);
        return RestBean.success(dtoMapper.detail(aggregate, sessionService.resolveTitle(aggregate.session())));
    }

    /**
     * 删除会话（级联删除消息 / 事件 / 草稿）。
     * 注意：整个项目里只有这里用了 @DeleteMapping（REST 语义），旧代码删除类操作都是 @PostMapping。
     */
    @DeleteMapping("/sessions/{id}")
    public RestBean<Void> deleteSession(
            @PathVariable long id,
            @RequestAttribute(Const.ATTR_USER_ID) int uid) {
        sessionService.delete(uid, id);
        return RestBean.success();
    }

    /**
     * 发起一次 Agent 运行，返回 SSE 流（text/event-stream）。
     *
     * 时序：
     * 1. 创建 SseEmitter（超时 = sseTimeoutMillis，通常比 Agent 的 60s 预算略长）
     * 2. 构造「断连回调」：客户端断开时取消对应的 run（防止 run 空转占用线程）
     * 3. 把 RunRequest 转成 AgentRunCommand（含编辑器上下文），交给 runService.start 异步执行
     * 4. 立刻返回 emitter；后续事件通过 SseEmitterAgentEventSink 推送
     *
     * 断连竞态处理：runId 用 AtomicReference 保存，start 返回后若发现已断连则立即取消。
     */
    @PostMapping(value = "/sessions/{id}/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startRun(
            @PathVariable long id,
            @RequestAttribute(Const.ATTR_USER_ID) int uid,
            @Valid @RequestBody AgentApiDtos.RunRequest request) {
        // 1. 创建 SSE 发射器，超时后由框架触发回调
        SseEmitter emitter = new SseEmitter(sseTimeoutMillis);
        // runId 在 start 之后才确定，用原子引用供断连回调读取
        AtomicReference<String> runId = new AtomicReference<>();
        // 标记客户端是否已断开
        AtomicBoolean disconnected = new AtomicBoolean();
        // 断连/超时回调：标记断开，并取消正在执行的 run
        Runnable cancel = () -> {
            disconnected.set(true);
            String currentRunId = runId.get();
            if (currentRunId != null) {
                runService.cancel(uid, currentRunId);
            }
        };
        // 2. 用 emitter + 取消回调构造事件下沉器（负责把事件写入 SSE 并落库）
        SseEmitterAgentEventSink sink = new SseEmitterAgentEventSink(emitter, cancel);
        // 3. 提取编辑器上下文，构造运行命令并异步启动
        AgentApiDtos.EditorDraft editor = request.editorDraft();
        String startedRunId = runService.start(uid, id, new AgentRunCommand(
                request.message(),
                request.editorVersion(),
                editor == null ? null : editor.title(),
                editor == null ? null : editor.topicTypeId(),
                editor == null ? null : editor.bodyMarkdown(),
                request.editorId()), sink);
        runId.set(startedRunId);
        // 4. 处理「start 与断连之间的竞态」：若已断连则立即取消
        if (disconnected.get()) {
            runService.cancel(uid, startedRunId);
        }
        return emitter;
    }

    /**
     * 取消正在运行的 run（用户点「取消」按钮）。
     * 取消不了（runId 不存在或不属于该用户）时抛 404。
     */
    @DeleteMapping("/runs/{runId}")
    public RestBean<Void> cancelRun(
            @PathVariable String runId,
            @RequestAttribute(Const.ATTR_USER_ID) int uid) {
        if (!runService.cancel(uid, runId)) {
            throw new AgentRunNotFoundException();
        }
        return RestBean.success();
    }
}
