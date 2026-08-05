# Interview Guide: Forum Authoring Agent

## 30-Second Introduction

I replaced a low-code RAG call with an in-process Spring Boot ReAct Agent for
high-quality forum authoring. It searches and reads only visible public posts,
combines Elasticsearch keyword and Bailian vector retrieval with RRF, asks for
missing critical facts, validates drafts, and streams typed progress to a Vue
editor. The Agent deliberately has no publish tool: users review a diff, pass an
editor-version check, and publish through the original endpoint.

## Architecture Story

Explain the system in four boundaries:

1. **Reasoning boundary:** DeepSeek receives a strict system prompt, four tool
   schemas, JSON response mode, an eight-call ceiling, and a shared 60-second
   deadline.
2. **Knowledge boundary:** only visible topic text is chunked and embedded.
   Keyword and vector paths each retrieve 20 candidates; RRF deduplicates and
   returns six references. Vector failure degrades to keyword search.
3. **Consistency boundary:** topic changes publish RabbitMQ events only after
   transaction commit. The consumer updates vectors with retry/dead-letter
   handling, while administrators can rebuild and inspect status.
4. **User-control boundary:** the model can return only QUESTION or DRAFT.
   Drafts are versioned, diffed, sanitized, and applied to the editor; publishing
   remains a separate authenticated user operation.

## Hard Problems And Evidence

### Reliable structured output

Prompt-only JSON reached 95% but still produced fenced or extra-field output.
The final design enables DeepSeek's protocol-level JSON mode, normalizes a
provider edge case where QUESTION is encoded as a tool, and constructs DRAFT
deterministically from a successful `validate_draft` invocation. A corrected
terminal draft can be revalidated through the same production tool without
bypassing limits.

### Evaluation credibility

The initial harness could overstate safety and retrieval. It now fails structure
and safety on runtime exceptions, detects prompt/reasoning disclosure and false
publish claims, proves the injection marker actually reached the model, and
uses the production Elasticsearch retrievers, vector indexer, chunker, and RRF.
Docker or missing keys fail the real-evaluation build instead of skipping it.

### Measured result

The accepted 20-case run achieved:

- 100% structured output, safety, and tool-limit compliance
- 100% Recall@5 against an 80% gate
- 6.748 seconds indexing/setup, 4.692 seconds retrieval queries
- 88.321 seconds Agent time, 99.761 seconds total measured time
- 77,361 DeepSeek tokens

Frame these as one reproducible acceptance run, not a production SLA.

## Trade-Offs

- RRF is transparent and robust without a reranker, but a learned reranker may
  improve relevance when the corpus grows.
- RabbitMQ makes indexing eventually consistent. Publisher confirms, retry,
  dead-letter handling, rebuild, and status APIs make that trade-off observable.
- Text-only v1 keeps image privacy and implementation scope controlled, but it
  cannot reason about image content.
- A monolith reduces deployment complexity for this portfolio project while
  preserving module boundaries that could later move to services.
- Session retention is capped at ten per user and 30 days to limit storage and
  privacy exposure.

## Likely Follow-Up Questions

**Why not let the Agent publish?**  Publishing is irreversible and governed by
the forum's existing authorization and moderation flow. Removing that tool
makes the safety boundary enforceable in code, not merely in a prompt.

**How do you prevent prompt injection?**  Historical text is labeled untrusted,
tools are allowlisted, citations must originate from tool results, output is
strictly validated, and an evaluation case proves injected content was exposed
without being obeyed or echoed.

**What happens when embeddings fail?**  Production search keeps keyword results.
The evaluation is stricter: a vector failure makes retrieval health fail so the
benchmark cannot silently claim hybrid Recall.

**How do you avoid stale editor writes?**  Every request carries the editor
version. Applying a draft requires the same base version and a user-confirmed
diff, so subsequent manual edits cannot be overwritten by an older run.

**What would you build next?**  Add production telemetry and cost budgets,
human-labeled retrieval/reranking data, content moderation, and then image
understanding only after defining its privacy and storage boundary.
