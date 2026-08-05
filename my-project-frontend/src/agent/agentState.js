const EVENT_TYPES = new Set([
  'run_started',
  'message_delta',
  'tool_started',
  'tool_completed',
  'citation',
  'question',
  'draft_ready',
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
    draft: null,
    error: null
  }
}

export function restoreAgentSession(state, detail) {
  const fresh = createAgentState()
  Object.assign(state, fresh, {
    sessionId: detail.session.id,
    sessionStatus: detail.session.status,
    messages: detail.messages.filter(message => message.role === 'USER' || message.role === 'ASSISTANT'),
    draft: detail.draft
  })

  for (const event of detail.events) {
    if (event.type === 'tool_started' || event.type === 'tool_completed' || event.type === 'citation') {
      applyAgentEvent(state, event)
    }
  }
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
      state.error = null
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
      state.question = payload.question
      break
    case 'draft_ready':
      state.draft = {
        title: payload.title,
        topicTypeId: payload.topicTypeId,
        bodyMarkdown: payload.bodyMarkdown,
        citations: payload.citations || [],
        version: payload.draftVersion,
        editorVersion: payload.basedOnEditorVersion
      }
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
