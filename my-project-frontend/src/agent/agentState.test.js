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
    expect(state.editorContext).toEqual({
      editorId: 'topic-editor-4',
      editorVersion: 4
    })
  })

  test('merges the typed live event stream without exposing internal reasoning', () => {
    const state = createAgentState()
    applyAgentEvent(state, { type: 'run_started', payload: { runId: 'r2', sessionId: 9 } })
    applyAgentEvent(state, { type: 'message_delta', payload: { text: '正在检查' } })
    applyAgentEvent(state, { type: 'message_delta', payload: { text: '历史帖子' } })
    expect(state.streamingText).toBe('正在检查历史帖子')

    const events = [
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
    // 终态消息到达后打字机缓冲被清空，避免与正式消息重复
    expect(state.streamingText).toBe('')
    expect(state.citations).toHaveLength(0)
    expect(state.question).toBe('适用于哪个宿舍楼？')
    // 每轮的工具行为与引用挂在对应消息上，而不是全局状态
    expect(state.messages[0].citations).toEqual([{ topicId: 12, title: '校园网指南' }])
    expect(state.messages[0].timeline).toEqual([
      { runId: 'r2', toolName: 'search_similar_topics', status: 'completed' }
    ])
    expect(state.messages).toEqual([
      {
        role: 'ASSISTANT',
        content: '适用于哪个宿舍楼？',
        createdAt: expect.any(String),
        timeline: [{ runId: 'r2', toolName: 'search_similar_topics', status: 'completed' }],
        citations: [{ topicId: 12, title: '校园网指南' }]
      },
      {
        role: 'ASSISTANT',
        content: '已生成草稿：《宿舍网络配置指南》',
        createdAt: expect.any(String),
        timeline: [],
        citations: []
      }
    ])
    expect(state.draft).toMatchObject({
      version: 3,
      editorVersion: 5,
      targetEditorId: 'topic-editor-5'
    })
    expect(state.runStatus).toBe('completed')
    expect(JSON.stringify(state)).not.toContain('thought')
  })

  test('adds an answer event as an assistant message and keeps citations', () => {
    const state = createAgentState()

    const events = [
      ['run_started', { runId: 'r3', sessionId: 9 }],
      ['citation', { topicId: 42, title: '牛腩探店' }],
      ['answer', { answer: '东镇大街的牛腩口感很好，食材新鲜，但价格偏贵。' }],
      ['run_completed', { runId: 'r3', status: 'COMPLETED' }]
    ]
    events.forEach(([type, payload]) => applyAgentEvent(state, { type, payload }))

    expect(state.messages).toEqual([
      {
        role: 'ASSISTANT',
        content: '东镇大街的牛腩口感很好，食材新鲜，但价格偏贵。',
        createdAt: expect.any(String),
        timeline: [],
        citations: [{ topicId: 42, title: '牛腩探店' }]
      }
    ])
    expect(state.citations).toHaveLength(0)
    expect(state.question).toBeNull()
    expect(state.editorContext).toBeNull()
    expect(state.runStatus).toBe('completed')
  })

  test('keeps each round of tool activity and citations when a new run starts', () => {
    const state = createAgentState()

    const firstRun = [
      ['run_started', { runId: 'r1', sessionId: 9 }],
      ['tool_started', { runId: 'r1', toolName: 'search_similar_topics' }],
      ['tool_completed', { runId: 'r1', toolName: 'search_similar_topics' }],
      ['citation', { topicId: 42, title: '牛腩探店' }],
      ['answer', { answer: '第一轮回答' }]
    ]
    firstRun.forEach(([type, payload]) => applyAgentEvent(state, { type, payload }))

    const secondRun = [
      ['run_started', { runId: 'r2', sessionId: 9 }],
      ['answer', { answer: '第二轮回答' }]
    ]
    secondRun.forEach(([type, payload]) => applyAgentEvent(state, { type, payload }))

    // 第一轮的行为与引用仍挂在第一轮的消息上，第二轮为空
    expect(state.messages[0].citations).toEqual([{ topicId: 42, title: '牛腩探店' }])
    expect(state.messages[0].timeline).toHaveLength(1)
    expect(state.messages[1].citations).toEqual([])
    expect(state.messages[1].timeline).toEqual([])
  })

  test('replays answer events when restoring a session', () => {
    const state = createAgentState()

    restoreAgentSession(state, {
      session: { id: 9, status: 'ACTIVE' },
      messages: [
        { id: 1, role: 'USER', content: '哪里有好吃的牛腩？' },
        { id: 2, role: 'ASSISTANT', content: '东镇大街的牛腩口感很好。' }
      ],
      events: [
        { id: 1, runId: 'r4', sequence: 1, type: 'citation', payload: { topicId: 42, title: '牛腩探店' } },
        { id: 2, runId: 'r4', sequence: 2, type: 'answer', payload: { answer: '东镇大街的牛腩口感很好。' } }
      ],
      draft: null
    })

    // 恢复会话时不重复 push 消息，引用挂到本轮的助手消息上而不是全局列表
    expect(state.messages.map(message => message.role)).toEqual(['USER', 'ASSISTANT'])
    expect(state.messages[1].citations).toEqual([{ topicId: 42, title: '牛腩探店' }])
    expect(state.citations).toHaveLength(0)
  })

  test('records retryable errors and rejects unknown event types', () => {    const state = createAgentState()

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

  test('retains the target editor in editor context after a draft arrives', () => {
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

    expect(state.editorContext).toEqual({
      editorId: 'topic-editor-question',
      editorVersion: 8
    })
  })

  test('clears editor context when a draft has no target editor', () => {
    const state = createAgentState()

    applyAgentEvent(state, {
      type: 'draft_ready',
      payload: {
        title: 'New topic draft',
        topicTypeId: 1,
        bodyMarkdown: 'Body',
        draftVersion: 1,
        basedOnEditorVersion: 0,
        targetEditorId: null
      }
    })

    expect(state.editorContext).toBeNull()
  })

  test('restores tool activity and citations for every run, not only the last one', () => {
    const state = createAgentState()

    restoreAgentSession(state, {
      session: { id: 9, status: 'ACTIVE' },
      messages: [
        { id: 1, role: 'USER', content: '第一问', createdAt: '2026-08-30T01:00:00Z' },
        { id: 2, role: 'ASSISTANT', content: '第一答', createdAt: '2026-08-30T01:00:05Z' },
        { id: 3, role: 'USER', content: '第二问', createdAt: '2026-08-30T01:01:00Z' },
        { id: 4, role: 'ASSISTANT', content: '第二答', createdAt: '2026-08-30T01:01:05Z' }
      ],
      events: [
        { id: 1, runId: 'r1', sequence: 1, type: 'citation', payload: { topicId: 42, title: '牛腩探店' }, createdAt: '2026-08-30T01:00:04Z' },
        { id: 2, runId: 'r1', sequence: 2, type: 'answer', payload: { answer: '第一答' }, createdAt: '2026-08-30T01:00:05Z' },
        { id: 3, runId: 'r2', sequence: 1, type: 'citation', payload: { topicId: 43, title: '米粉店' }, createdAt: '2026-08-30T01:01:04Z' },
        { id: 4, runId: 'r2', sequence: 2, type: 'answer', payload: { answer: '第二答' }, createdAt: '2026-08-30T01:01:05Z' }
      ],
      draft: null
    })

    expect(state.messages).toHaveLength(4)
    // 每轮的引用挂在各自那轮的助手消息上，而不是全部堆到最后一条
    expect(state.messages[1].citations).toEqual([{ topicId: 42, title: '牛腩探店' }])
    expect(state.messages[3].citations).toEqual([{ topicId: 43, title: '米粉店' }])
    expect(state.citations).toHaveLength(0)
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
