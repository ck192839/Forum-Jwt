# Forum Authoring Agent Architecture

## Request And Indexing Paths

```mermaid
flowchart LR
    U["Desktop user"] --> V["Vue 3 assistant and topic editor"]
    V -->|"typed SSE request"| API["Spring Agent API"]
    API --> RUN["Run and session services"]
    RUN --> DB[("MySQL Agent sessions, events, drafts")]
    RUN --> REACT["ForumReActAgent, 8 tools / 60 seconds"]
    REACT --> DS["DeepSeek chat and tool calling"]
    REACT --> TOOLS["Fixed authoring tools"]
    TOOLS --> TYPES["Forum sections and public topics"]
    TOOLS --> SEARCH["Hybrid topic search"]
    SEARCH --> KEY["Elasticsearch keyword index"]
    SEARCH --> VEC["Elasticsearch vector index"]
    SEARCH --> RRF["RRF, deduplicate, top 6"]
    VEC --> BAILIAN["Bailian text-embedding-v4"]
    REACT -->|"QUESTION or validated DRAFT"| RUN
    RUN -->|"SSE events, no chain of thought"| V
    V -->|"apply after diff and version check"| EDITOR["Existing topic editor"]
    EDITOR -->|"user confirms"| PUBLISH["Existing publish API"]

    TOPIC["Topic transaction committed"] --> MQ["RabbitMQ index event"]
    MQ --> CONSUMER["Retrying index consumer"]
    CONSUMER --> KEY
    CONSUMER --> BAILIAN
    BAILIAN --> VEC
    CONSUMER --> DLQ["Dead-letter queue on exhausted retries"]
```

## Agent Sequence

```mermaid
sequenceDiagram
    participant UI as Vue desktop UI
    participant API as Agent API / SSE
    participant A as ForumReActAgent
    participant M as DeepSeek
    participant T as Fixed tools
    participant E as Elasticsearch

    UI->>API: message plus current editor version
    API->>A: run with cancellation token
    loop Until QUESTION or validated DRAFT
        A->>M: messages, JSON mode, allowed tool schemas
        alt More facts are required
            M-->>A: QUESTION JSON or tool-encoded QUESTION
            A-->>API: validated QUESTION
        else Tool call
            A->>T: list, search, read, or validate
            T->>E: keyword and vector retrieval when searching
            T-->>A: untrusted tool result
        else Draft validation succeeds
            A-->>API: deterministic DRAFT from validated arguments
        end
    end
    API-->>UI: typed status, citation, question, draft, completion events
```

## Design Decisions

- The model sees only visible public-topic content. Hidden topics, comments,
  campus records, and images are excluded.
- Keyword and vector retrieval each request 20 hits. Reciprocal Rank Fusion
  merges them, deduplicates by topic, and returns six references. Keyword-only
  fallback remains available when embedding/vector search is unavailable.
- Tool output is untrusted prompt data. The system prompt forbids following
  historical instructions, disclosing hidden prompts/reasoning, or claiming a
  post was published.
- DRAFT fields are taken from a successful production `validate_draft` call.
  The runtime also normalizes provider-specific QUESTION tool encoding and can
  revalidate a corrected strict terminal draft without relaxing validation.
- Cancellation and every model/tool operation share one hard 60-second budget.
  Tool calls, including runtime revalidation, share the same limit of eight.
- SSE exposes status and results, never the model's private reasoning content.
- Editor versions prevent a stale Agent result from overwriting later user
  edits. Markdown is sanitized before conversion to Quill Delta; existing
  images stay in the editor and are not sent for model analysis.
- Publishing remains outside the Agent boundary and always requires a user
  action through the existing forum endpoint.
