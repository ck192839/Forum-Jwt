package com.example.agent.run;

import com.example.agent.core.AgentCancellationToken;
import com.example.agent.core.AgentDraftResult;
import com.example.agent.core.AgentQuestionResult;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.UUID;

public class AgentRunService {
    private final AgentSessionService sessionService;
    private final AgentRunner agentRunner;
    private final Executor runExecutor;
    private final ObjectMapper objectMapper;
    private final Supplier<String> runIdSupplier;
    private final Map<Long, String> sessionRuns = new ConcurrentHashMap<>();
    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    public AgentRunService(
            AgentSessionService sessionService,
            AgentRunner agentRunner,
            Executor runExecutor,
            ObjectMapper objectMapper
    ) {
        this(sessionService, agentRunner, runExecutor, objectMapper, () -> UUID.randomUUID().toString());
    }

    public AgentRunService(
            AgentSessionService sessionService,
            AgentRunner agentRunner,
            Executor runExecutor,
            ObjectMapper objectMapper,
            Supplier<String> runIdSupplier
    ) {
        this.sessionService = sessionService;
        this.agentRunner = agentRunner;
        this.runExecutor = runExecutor;
        this.objectMapper = objectMapper;
        this.runIdSupplier = runIdSupplier;
    }

    public String start(
            int uid,
            long sessionId,
            AgentRunCommand command,
            AgentEventSink sink
    ) {
        AgentSessionAggregate aggregate = sessionService.load(uid, sessionId);
        String runId = runIdSupplier.get();
        AgentCancellationToken cancellation = new AgentCancellationToken();
        ActiveRun run = new ActiveRun(uid, sessionId, runId, cancellation, sink, new AtomicInteger());
        if (sessionRuns.putIfAbsent(sessionId, runId) != null) {
            throw new AgentRunConflictException();
        }
        activeRuns.put(runId, run);
        try {
            String promptText = command.promptText();
            sessionService.appendMessage(uid, sessionId, AgentMessageRole.USER, promptText);
            emit(run, AgentSseEventType.RUN_STARTED, new RunStartedPayload(runId, sessionId));
            List<Message> history = toHistory(aggregate.messages());
            runExecutor.execute(() -> execute(run, command, promptText, history));
            return runId;
        } catch (RuntimeException exception) {
            remove(run);
            throw exception;
        }
    }

    public boolean cancel(int uid, String runId) {
        ActiveRun run = activeRuns.get(runId);
        if (run == null || run.uid() != uid) {
            return false;
        }
        run.cancellation().cancel();
        return true;
    }

    public boolean hasActiveRun(long sessionId) {
        return sessionRuns.containsKey(sessionId);
    }

    private void execute(
            ActiveRun run,
            AgentRunCommand command,
            String promptText,
            List<Message> history
    ) {
        try {
            AgentRunObserver observer = observer(run);
            AgentTerminalResult result = agentRunner.run(
                    new AgentRunRequest(promptText, command.editorVersion(), history),
                    run.cancellation(),
                    observer
            );
            if (result instanceof AgentQuestionResult question) {
                handleQuestion(run, command, question);
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
            handleFailure(run, new AgentRunException(
                    AgentRunFailure.EXECUTION,
                    "Agent run failed",
                    exception
            ));
        } finally {
            remove(run);
        }
    }

    private void handleQuestion(ActiveRun run, AgentRunCommand command, AgentQuestionResult question) {
        sessionService.appendMessage(
                run.uid(),
                run.sessionId(),
                AgentMessageRole.ASSISTANT,
                question.question()
        );
        emit(run, AgentSseEventType.MESSAGE_DELTA, new MessageDeltaPayload(question.question()));
        emit(run, AgentSseEventType.QUESTION, new QuestionPayload(
                question.question(),
                command.editorId(),
                command.editorVersion()
        ));
    }

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
                        command.editorId()
                )
        );
        sessionService.appendMessage(
                run.uid(),
                run.sessionId(),
                AgentMessageRole.ASSISTANT,
                json(draft)
        );
        draft.citations().forEach(citation -> emit(
                run,
                AgentSseEventType.CITATION,
                new CitationPayload(citation.topicId(), citation.title())
        ));
        emit(run, AgentSseEventType.DRAFT_READY, new DraftReadyPayload(
                draft.title(),
                draft.topicTypeId(),
                draft.bodyMarkdown(),
                draft.citations(),
                persisted.getVersion(),
                persisted.getEditorVersion(),
                persisted.getTargetEditorId()
        ));
    }

    private void handleFailure(ActiveRun run, AgentRunException exception) {
        try {
            AgentRunFailure failure = exception.failure();
            emit(run, AgentSseEventType.ERROR, new ErrorPayload(
                    run.runId(),
                    failure.name(),
                    safeMessage(failure),
                    true
            ));
            String status = failure == AgentRunFailure.CANCELLED ? "CANCELLED" : "FAILED";
            emit(run, AgentSseEventType.RUN_COMPLETED, new RunCompletedPayload(run.runId(), status));
            run.sink().complete();
        } catch (RuntimeException sinkFailure) {
            run.sink().completeWithError(sinkFailure);
        }
    }

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

    private void emit(ActiveRun run, AgentSseEventType type, Object payload) {
        int sequence = run.sequence().incrementAndGet();
        String eventId = run.runId() + ":" + sequence;
        sessionService.appendEvent(
                run.uid(),
                run.sessionId(),
                run.runId(),
                sequence,
                type.wireName(),
                json(payload)
        );
        run.sink().emit(type, eventId, payload);
    }

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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AgentRunException(AgentRunFailure.EXECUTION, "Unable to serialize Agent event", exception);
        }
    }

    private String safeMessage(AgentRunFailure failure) {
        return switch (failure) {
            case CANCELLED -> "Agent run was cancelled";
            case TIMEOUT -> "Agent run timed out";
            case TOOL_LIMIT -> "Agent reached the tool call limit";
            case INVALID_RESPONSE -> "Agent returned an invalid response";
            case EXECUTION -> "Agent execution failed";
        };
    }

    private void remove(ActiveRun run) {
        activeRuns.remove(run.runId(), run);
        sessionRuns.remove(run.sessionId(), run.runId());
    }

    private record ActiveRun(
            int uid,
            long sessionId,
            String runId,
            AgentCancellationToken cancellation,
            AgentEventSink sink,
            AtomicInteger sequence
    ) {
    }
}
