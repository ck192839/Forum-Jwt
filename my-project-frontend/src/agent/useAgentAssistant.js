import { reactive, ref } from 'vue'
import * as defaultApi from './api'
import { applyAgentEvent, createAgentState, restoreAgentSession } from './agentState'

export function createAgentAssistantController(api = defaultApi) {
  const state = reactive(createAgentState())
  const sessions = ref([])
  const prompt = ref('')
  const loading = ref(false)
  const submitting = ref(false)
  let initialized = false
  let abortController = null

  async function initialize() {
    if (initialized) return
    initialized = true
    loading.value = true
    try {
      await refreshSessions()
      const active = sessions.value.find(session => session.status === 'ACTIVE')
      if (active) await selectSession(active.id)
    } finally {
      loading.value = false
    }
  }

  async function refreshSessions() {
    sessions.value = await api.listRecentAgentSessions()
  }

  async function selectSession(sessionId) {
    const detail = await api.getAgentSession(sessionId)
    restoreAgentSession(state, detail)
  }

  async function newSession() {
    const created = await api.createAgentSession()
    await refreshSessions()
    await selectSession(created.id)
  }

  async function deleteCurrentSession() {
    if (state.sessionId == null) return
    const deletedId = state.sessionId
    await api.deleteAgentSession(deletedId)
    Object.assign(state, createAgentState())
    await refreshSessions()
    const active = sessions.value.find(session => session.status === 'ACTIVE')
    if (active) await selectSession(active.id)
  }

  async function submit(editorContext = {}) {
    if (submitting.value) return false
    const message = prompt.value.trim()
    const hasExplicitEditorContext = editorContext.editorDraft
      || editorContext.editorId
      || editorContext.editorVersion != null
    const effectiveEditorContext = hasExplicitEditorContext
      ? editorContext
      : (state.editorContext || editorContext)
    if (!message && !effectiveEditorContext.editorDraft) return false
    submitting.value = true

    try {
      if (state.sessionId == null) await newSession()
      if (message) {
        state.messages.push({ role: 'USER', content: message, createdAt: new Date().toISOString() })
      }
      prompt.value = ''
      state.runStatus = 'starting'
      state.error = null
      abortController = new AbortController()

      const request = {
        message: message || null,
        editorVersion: effectiveEditorContext.editorVersion ?? 0
      }
      if (effectiveEditorContext.editorId) request.editorId = effectiveEditorContext.editorId
      if (effectiveEditorContext.editorDraft) request.editorDraft = effectiveEditorContext.editorDraft

      await api.startAgentRun(
        state.sessionId,
        request,
        event => applyAgentEvent(state, event),
        abortController.signal
      )
      return true
    } catch (error) {
      if (abortController?.signal.aborted || error?.name === 'AbortError') {
        state.runStatus = 'cancelled'
      } else {
        state.runStatus = 'error'
        state.error = {
          code: 'CLIENT_ERROR',
          message: error?.message || 'Agent request failed',
          retryable: true
        }
      }
    } finally {
      submitting.value = false
      abortController = null
    }
  }

  async function cancel() {
    const controller = abortController
    if (!controller) return
    try {
      if (state.runId) await api.cancelAgentRun(state.runId)
    } finally {
      state.runStatus = 'cancelled'
      controller.abort()
    }
  }

  return {
    state,
    sessions,
    prompt,
    loading,
    submitting,
    initialize,
    refreshSessions,
    selectSession,
    newSession,
    deleteCurrentSession,
    submit,
    cancel
  }
}
