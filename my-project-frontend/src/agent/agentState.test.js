import { describe, expect, test } from 'vitest'
import {
  applyAgentEvent,
  createAgentState,
  restoreAgentSession
} from './agentState'

describe('agent state', () => {
  test('restores public messages, tool events, citations and the latest draft', () => {
    const state = createAgentState()

    restoreAgentSession(state, {
      session: { id: 9, status: 'ACTIVE' },
      messages: [
        { id: 1, role: 'SYSTEM', content: 'private instructions' },
        { id: 2, role: 'USER', content: '帮我写网络教程' },
        { id: 3, role: 'TOOL', content: 'raw tool output' },
        { id: 4, role: 'ASSISTANT', content: '需要确认适用宿舍楼吗？' }
      ],
      events: [
        { id: 1, runId: 'r1', sequence: 1, type: 'tool_started', payload: { runId: 'r1', toolName: 'search_similar_topics' } },
        { id: 2, runId: 'r1', sequence: 2, type: 'tool_completed', payload: { runId: 'r1', toolName: 'search_similar_topics' } },
        { id: 3, runId: 'r1', sequence: 3, type: 'citation', payload: { topicId: 12, title: '校园网指南' } }
      ],
      draft: {
        version: 2,
        editorVersion: 4,
        targetEditorId: 'topic-editor-4',
        title: '宿舍网络配置指南',
        topicTypeId: 1,
        bodyMarkdown: '正文',
        citations: [{ topicId: 12, title: '校园网指南' }]
      }
    })

    expect(state.sessionId).toBe(9)
    expect(state.messages.map(message => message.role)).toEqual(['USER', 'ASSISTANT'])
    expect(state.timeline).toEqual([
      { runId: 'r1', toolName: 'search_similar_topics', status: 'completed' }
    ])
    expect(state.citations).toEqual([{ topicId: 12, title: '校园网指南' }])
    expect(state.draft.version).toBe(2)
    expect(state.draft.targetEditorId).toBe('topic-editor-4')
  })

  test('merges the typed live event stream without exposing internal reasoning', () => {
    const state = createAgentState()
    const events = [
      ['run_started', { runId: 'r2', sessionId: 9 }],
      ['message_delta', { text: '正在检查' }],
      ['message_delta', { text: '历史帖子' }],
      ['tool_started', { runId: 'r2', toolName: 'search_similar_topics' }],
      ['tool_completed', { runId: 'r2', toolName: 'search_similar_topics' }],
      ['citation', { topicId: 12, title: '校园网指南' }],
      ['citation', { topicId: 12, title: '校园网指南' }],
      ['question', { question: '适用于哪个宿舍楼？' }],
      ['draft_ready', {
        title: '宿舍网络配置指南',
        topicTypeId: 1,
        bodyMarkdown: '正文',
        citations: [{ topicId: 12, title: '校园网指南' }],
        draftVersion: 3,
        basedOnEditorVersion: 5,
        targetEditorId: 'topic-editor-5'
      }],
      ['run_completed', { runId: 'r2', status: 'COMPLETED' }]
    ]

    events.forEach(([type, payload]) => applyAgentEvent(state, { type, payload }))

    expect(state.runId).toBe('r2')
    expect(state.streamingText).toBe('正在检查历史帖子')
    expect(state.timeline[0].status).toBe('completed')
    expect(state.citations).toHaveLength(1)
    expect(state.question).toBe('适用于哪个宿舍楼？')
    expect(state.draft).toMatchObject({
      version: 3,
      editorVersion: 5,
      targetEditorId: 'topic-editor-5'
    })
    expect(state.runStatus).toBe('completed')
    expect(JSON.stringify(state)).not.toContain('thought')
  })

  test('records retryable errors and rejects unknown event types', () => {
    const state = createAgentState()

    applyAgentEvent(state, {
      type: 'error',
      payload: { runId: 'r3', code: 'MODEL_FAILED', message: '模型暂不可用', retryable: true }
    })

    expect(state.runStatus).toBe('error')
    expect(state.error).toMatchObject({ code: 'MODEL_FAILED', retryable: true })
    expect(() => applyAgentEvent(state, { type: 'model_thought', payload: {} }))
      .toThrow('Unknown Agent event type')
  })

  test('clears the previous draft as soon as a new run starts', () => {
    const state = createAgentState()
    state.draft = { title: 'Old draft', editorVersion: 0 }

    applyAgentEvent(state, {
      type: 'run_started',
      payload: { runId: 'r4', sessionId: 9 }
    })

    expect(state.draft).toBeNull()
  })

  test('retains editor context across a question and clears it when a draft arrives', () => {
    const state = createAgentState()

    applyAgentEvent(state, {
      type: 'question',
      payload: {
        question: 'Which audience?',
        targetEditorId: 'topic-editor-question',
        basedOnEditorVersion: 8
      }
    })

    expect(state.editorContext).toEqual({
      editorId: 'topic-editor-question',
      editorVersion: 8
    })

    applyAgentEvent(state, {
      type: 'draft_ready',
      payload: {
        title: 'Draft',
        topicTypeId: 1,
        bodyMarkdown: 'Body',
        draftVersion: 1,
        basedOnEditorVersion: 8,
        targetEditorId: 'topic-editor-question'
      }
    })

    expect(state.editorContext).toBeNull()
  })

  test('restores editor context when the latest terminal event is a question', () => {
    const state = createAgentState()

    restoreAgentSession(state, {
      session: { id: 9, status: 'ACTIVE' },
      messages: [],
      events: [{
        id: 1,
        runId: 'r-question',
        sequence: 1,
        type: 'question',
        payload: {
          question: 'Which audience?',
          targetEditorId: 'topic-editor-restored-question',
          basedOnEditorVersion: 11
        }
      }],
      draft: null
    })

    expect(state.editorContext).toEqual({
      editorId: 'topic-editor-restored-question',
      editorVersion: 11
    })
  })
})
