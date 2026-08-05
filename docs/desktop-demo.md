# Desktop Demo Script

## Preparation

1. Start MySQL, Redis, RabbitMQ, Elasticsearch, MinIO, backend, and frontend.
2. Confirm both model keys are present without displaying their values.
3. Log in with a normal desktop user and keep an administrator session ready in
   a second browser profile.
4. Trigger the administrator index rebuild and confirm zero failed topics.
5. Use a seeded visible historical post related to campus VPN or Wi-Fi so the
   citation path is easy to recognize.

Target length: eight minutes. Use a desktop viewport; mobile is outside this
acceptance scope.

## Main Flow

1. Open the floating Agent assistant from the forum page. Point out that the
   most recent unfinished session is restored and older sessions remain in the
   session list.
2. Submit:

   > 先查重，写一篇校园 VPN 使用指南，包含客户端下载、双因素认证和故障排查。以下信息完整，直接生成草稿，不要追问可选信息。

3. While SSE events arrive, show only the user-safe timeline: searching similar
   posts, reading a public post, selecting a section, and validating the draft.
   Emphasize that private chain-of-thought is never displayed.
4. Open one citation and verify it points to a visible public topic. Mention
   that keyword and vector retrieval are fused with RRF and vector failure can
   fall back to keyword search.
5. Review the generated title, recommended section, Markdown body, and
   citations. State explicitly that the Agent has not published anything.
6. Choose **Apply to editor**. Review the diff dialog before confirming. The
   editor should receive text while any existing image remains untouched.
7. Make a small manual edit in the editor. Generate another optimization from
   the older editor version and show that the version conflict prevents stale
   content from overwriting the new edit.
8. Run **AI optimize** from the editor, inspect its diff, and apply the accepted
   version.
9. Publish with the forum's original publish button. This user action, not an
   Agent tool, is the only point where the post is created.

## Secondary Proof Points

- Start a run and cancel it; show the error/completion state without editor
  write-back.
- Refresh the page and restore the conversation, tool events, citations, and
  versioned draft.
- In the administrator profile, show rebuild progress and failure count.
- Show the passing evaluation summary: 100% structure, safety, tool limit, and
  Recall@5 across the 20-case suite.

## Failure-Safe Demo Behavior

If an external model or vector call is temporarily unavailable, do not retry
repeatedly during the presentation. Explain the visible error state, show that
the editor was not changed, and use the checked-in test/evaluation report as
evidence. Keyword retrieval degradation and retry-from-last-user-message are
designed behavior; automatic publishing is not.
