const EVENT_TYPES = new Set([
  'run_started',
  'message_delta',
  'tool_started',
  'tool_completed',
  'citation',
  'question',
  'answer',
  'draft_ready',
  'context_notice',
  'run_completed',
  'error'
])

export function createAgentState() {
  return {
    sessionId: null,
    sessionStatus: null,
    messages: [],
    runId: null,
    runStatus: 'idle',
    streamingText: '',
    timeline: [],
    citations: [],
    question: null,
    editorContext: null,
    draft: null,
    // 上下文治理告知（降级/预算耗尽等），按时间顺序展示在消息列表上方
    notices: [],
    error: null
  }
}

export function restoreAgentSession(state, detail) {
  const fresh = createAgentState()
  Object.assign(state, fresh, {
    sessionId: detail.session.id,
    sessionStatus: detail.session.status,
    messages: [],
    draft: detail.draft
  })

  // 消息与事件按时间归并重放：走到某轮终态事件时，该轮的助手消息恰好是
  // 「目前为止」的最后一条，工具/引用才能挂到正确的消息上（而不是整个会话的最后一条）。
  // 同一时刻的消息排在前（后端先落库消息再发该轮事件）。
  const items = [
    ...detail.messages
      .filter(message => message.role === 'USER' || message.role === 'ASSISTANT')
      .map(message => ({ at: eventTime(message.createdAt), kind: 'message', message })),
    ...detail.events.map(event => ({ at: eventTime(event.createdAt), kind: 'event', event }))
  ].sort((a, b) => {
    if (a.at !== b.at) return a.at - b.at
    if (a.kind === b.kind) return 0
    return a.kind === 'message' ? -1 : 1
  })

  for (const item of items) {
    if (item.kind === 'message') {
      state.messages.push(item.message)
      continue
    }
    const event = item.event
    switch (event.type) {
      case 'tool_started':
      case 'tool_completed':
      case 'citation':
        // 先累积到「当前 run」的缓冲，等该轮终态事件时挂载到对应消息上
        applyAgentEvent(state, event)
        break
      case 'question':
        // 消息正文已持久化在 messages 里，重放只负责挂载本轮工具/引用并恢复状态
        attachRunArtifactsToLastAssistant(state)
        resetRunBuffers(state)
        state.question = event.payload?.question ?? null
        state.editorContext = event.payload?.targetEditorId
          ? {
              editorId: event.payload.targetEditorId,
              editorVersion: event.payload.basedOnEditorVersion
            }
          : null
        break
      case 'answer':
        attachRunArtifactsToLastAssistant(state)
        resetRunBuffers(state)
        state.question = null
        state.editorContext = null
        break
      case 'draft_ready':
        attachRunArtifactsToLastAssistant(state)
        resetRunBuffers(state)
        state.editorContext = null
        break
      case 'run_started':
        // 切回仍在运行的会话时，需要 runId 才能取消；notice 按 run 归属，
        // 重放到新一轮起点时清掉之前 run 的，只保留最后一轮的
        state.runId = event.payload?.runId ?? state.runId
        state.runStatus = 'running'
        state.notices = []
        break
      case 'run_completed':
        state.runStatus = String(event.payload?.status || 'completed').toLowerCase()
        break
      case 'context_notice':
        // 通知事件已落库：重放时还原，保证切回会话后仍可见；
        // 优先用落库时间，恢复后才能看出是哪一轮触发的
        state.notices.push({ text: event.payload?.text || '', at: event.createdAt || new Date().toISOString() })
        break
      default:
        break
    }
  }

  if (detail.draft?.targetEditorId) {
    state.editorContext = {
      editorId: detail.draft.targetEditorId,
      editorVersion: detail.draft.editorVersion
    }
  }
}

/** 把时间戳解析成毫秒数（缺失或不可解析时归 0，靠同刻的 kind 决胜保持稳定顺序）。 */
function eventTime(value) {
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

export function applyAgentEvent(state, event) {
  if (!EVENT_TYPES.has(event.type)) {
    throw new Error(`Unknown Agent event type: ${event.type}`)
  }

  const payload = event.payload || {}
  switch (event.type) {
    case 'run_started':
      state.runId = payload.runId
      state.sessionId = payload.sessionId
      state.runStatus = 'running'
      state.streamingText = ''
      state.timeline = []
      state.citations = []
      state.question = null
      state.draft = null
      state.error = null
      // notice 属于单次 run（降级/预算耗尽只对当轮有意义），新 run 开始即清空
      state.notices = []
      break
    case 'message_delta':
      state.streamingText += payload.text || ''
      break
    case 'tool_started':
      state.timeline.push({
        runId: payload.runId,
        toolName: payload.toolName,
        status: 'running'
      })
      break
    case 'tool_completed': {
      const tool = [...state.timeline].reverse().find(item =>
        item.runId === payload.runId && item.toolName === payload.toolName && item.status === 'running'
      )
      if (tool) {
        tool.status = 'completed'
      } else {
        state.timeline.push({
          runId: payload.runId,
          toolName: payload.toolName,
          status: 'completed'
        })
      }
      break
    }
    case 'citation':
      if (!state.citations.some(citation => citation.topicId === payload.topicId)) {
        state.citations.push(payload)
      }
      break
    case 'question':
      state.messages.push({
        role: 'ASSISTANT',
        content: payload.question,
        createdAt: new Date().toISOString(),
        ...takeRunArtifacts(state)
      })
      state.streamingText = '' // 完整消息已到达，清空打字机缓冲
      state.question = payload.question
      state.editorContext = payload.targetEditorId
        ? {
            editorId: payload.targetEditorId,
            editorVersion: payload.basedOnEditorVersion
          }
        : null
      break
    case 'answer':
      state.messages.push({
        role: 'ASSISTANT',
        content: payload.answer,
        createdAt: new Date().toISOString(),
        ...takeRunArtifacts(state)
      })
      state.streamingText = ''
      state.question = null
      state.editorContext = null
      break
    case 'draft_ready':
      state.messages.push({
        role: 'ASSISTANT',
        content: '已生成草稿：《' + payload.title + '》',
        createdAt: new Date().toISOString(),
        ...takeRunArtifacts(state)
      })
      state.streamingText = ''
      state.draft = {
        title: payload.title,
        topicTypeId: payload.topicTypeId,
        bodyMarkdown: payload.bodyMarkdown,
        citations: payload.citations || [],
        version: payload.draftVersion,
        editorVersion: payload.basedOnEditorVersion,
        targetEditorId: payload.targetEditorId ?? null
      }
      state.editorContext = payload.targetEditorId
        ? { editorId: payload.targetEditorId, editorVersion: payload.basedOnEditorVersion }
        : null
      break
    case 'context_notice':
      // 上下文治理告知：事件已落库，会话恢复重放时也会经过这里（落库事件带 createdAt）
      state.notices.push({ text: payload.text || '', at: event.createdAt || new Date().toISOString() })
      break
    case 'run_completed':
      state.runStatus = String(payload.status || 'completed').toLowerCase()
      break
    case 'error':
      state.runId = payload.runId || state.runId
      state.runStatus = 'error'
      state.error = payload
      break
  }
}

/**
 * 取出「当前 run」的工具时间线与引用：随终态事件挂到该轮生成的助手消息上，
 * 并清空全局缓冲（下一轮 run_started 也会重置），保证每轮的内容只挂在那一轮的消息上。
 */
function takeRunArtifacts(state) {
  const artifacts = {
    timeline: state.timeline.slice(),
    citations: state.citations.slice()
  }
  resetRunBuffers(state)
  return artifacts
}

/** 把缓冲的工具/引用挂到最近一条助手消息上（会话恢复时消息已存在，只做补挂）。 */
function attachRunArtifactsToLastAssistant(state) {
  const message = [...state.messages].reverse().find(item => item.role === 'ASSISTANT')
  if (!message) return
  message.timeline = state.timeline.slice()
  message.citations = state.citations.slice()
}

function resetRunBuffers(state) {
  state.timeline = []
  state.citations = []
}
