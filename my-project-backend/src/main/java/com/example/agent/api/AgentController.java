package com.example.agent.api;

import com.example.agent.config.AgentRuntimeProperties;
import com.example.agent.run.AgentRunCommand;
import com.example.agent.run.AgentRunService;
import com.example.agent.run.SseEmitterAgentEventSink;
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

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentSessionService sessionService;
    private final AgentRunService runService;
    private final AgentDtoMapper dtoMapper;
    private final long sseTimeoutMillis;

    @Autowired
    public AgentController(
            AgentSessionService sessionService,
            AgentRunService runService,
            ObjectMapper objectMapper,
            AgentRuntimeProperties properties
    ) {
        this(sessionService, runService, objectMapper, properties.getSseTimeout().toMillis());
    }

    AgentController(
            AgentSessionService sessionService,
            AgentRunService runService,
            ObjectMapper objectMapper,
            long sseTimeoutMillis
    ) {
        this.sessionService = sessionService;
        this.runService = runService;
        this.dtoMapper = new AgentDtoMapper(objectMapper);
        this.sseTimeoutMillis = sseTimeoutMillis;
    }

    @PostMapping("/sessions")
    public RestBean<AgentApiDtos.SessionSummary> create(
            @RequestAttribute(Const.ATTR_USER_ID) int uid
    ) {
        return RestBean.success(dtoMapper.summary(sessionService.create(uid)));
    }

    @GetMapping("/sessions/recent")
    public RestBean<List<AgentApiDtos.SessionSummary>> recent(
            @RequestAttribute(Const.ATTR_USER_ID) int uid
    ) {
        return RestBean.success(sessionService.listRecent(uid).stream().map(dtoMapper::summary).toList());
    }

    @GetMapping("/sessions/{id}")
    public RestBean<AgentApiDtos.SessionDetail> restore(
            @PathVariable long id,
            @RequestAttribute(Const.ATTR_USER_ID) int uid
    ) {
        return RestBean.success(dtoMapper.detail(sessionService.load(uid, id)));
    }

    @DeleteMapping("/sessions/{id}")
    public RestBean<Void> deleteSession(
            @PathVariable long id,
            @RequestAttribute(Const.ATTR_USER_ID) int uid
    ) {
        sessionService.delete(uid, id);
        return RestBean.success();
    }

    @PostMapping(value = "/sessions/{id}/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startRun(
            @PathVariable long id,
            @RequestAttribute(Const.ATTR_USER_ID) int uid,
            @Valid @RequestBody AgentApiDtos.RunRequest request
    ) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMillis);
        AtomicReference<String> runId = new AtomicReference<>();
        AtomicBoolean disconnected = new AtomicBoolean();
        Runnable cancel = () -> {
            disconnected.set(true);
            String currentRunId = runId.get();
            if (currentRunId != null) {
                runService.cancel(uid, currentRunId);
            }
        };
        SseEmitterAgentEventSink sink = new SseEmitterAgentEventSink(emitter, cancel);
        AgentApiDtos.EditorDraft editor = request.editorDraft();
        String startedRunId = runService.start(uid, id, new AgentRunCommand(
                request.message(),
                request.editorVersion(),
                editor == null ? null : editor.title(),
                editor == null ? null : editor.topicTypeId(),
                editor == null ? null : editor.bodyMarkdown(),
                request.editorId()
        ), sink);
        runId.set(startedRunId);
        if (disconnected.get()) {
            runService.cancel(uid, startedRunId);
        }
        return emitter;
    }

    @DeleteMapping("/runs/{runId}")
    public RestBean<Void> cancelRun(
            @PathVariable String runId,
            @RequestAttribute(Const.ATTR_USER_ID) int uid
    ) {
        if (!runService.cancel(uid, runId)) {
            throw new AgentRunNotFoundException();
        }
        return RestBean.success();
    }
}
