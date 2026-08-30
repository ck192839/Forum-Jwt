import { reactive, ref } from 'vue'
import * as defaultApi from './api'
import { applyAgentEvent, createAgentState, restoreAgentSession } from './agentState'
import { requestUserLocation } from './location'

export function createAgentAssistantController(api = defaultApi) {
  const state = reactive(createAgentState())
  const sessions = ref([])
  const prompt = ref('')
  const loading = ref(false)
  const submitting = ref(false)
  let initialized = false
  // sessionId → 进行中 run 的 AbortController（后端按会话互斥，不同会话可并行）
  const activeRuns = new Map()

  function isRunActive(sessionId) {
    return activeRuns.has(sessionId)
  }

  /** 「处理中」指示跟随当前查看的会话：查看的会话有 run 在跑才算忙。 */
  function syncRunIndicator() {
    submitting.value = isRunActive(state.sessionId)
  }

  async function initialize() {
    if (initialized) return
    initialized = true
    loading.value = true
    try {
      // 提前触发定位（异步缓存），用户提交问答时位置通常已就绪
      requestUserLocation()
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
    // 切回有 run 在跑的会话时恢复「处理中」指示；后台 run 的事件由订阅回调按会话过滤
    syncRunIndicator()
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
    // 同一会话已有 run 在跑则拒绝（后端也会 409）；其他会话的 run 不受影响
    if (isRunActive(state.sessionId)) return false
    const message = prompt.value.trim()
    const hasExplicitEditorContext = editorContext.editorDraft
      || editorContext.editorId
      || editorContext.editorVersion != null
    const effectiveEditorContext = hasExplicitEditorContext
      ? editorContext
      : (state.editorContext || editorContext)
    if (!message && !effectiveEditorContext.editorDraft) return false

    try {
      if (state.sessionId == null) await newSession()
    } catch (error) {
      return false
    }
    // 本次 run 所属的会话（期间用户可能切走，事件按此过滤）
    const runSessionId = state.sessionId
    const controller = new AbortController()
    activeRuns.set(runSessionId, controller)
    syncRunIndicator()

    try {
      if (message) {
        state.messages.push({ role: 'USER', content: message, createdAt: new Date().toISOString() })
      }
      prompt.value = ''
      state.runStatus = 'starting'
      state.error = null

      const request = {
        message: message || null,
        editorVersion: effectiveEditorContext.editorVersion ?? 0
      }
      if (effectiveEditorContext.editorId) request.editorId = effectiveEditorContext.editorId
      if (effectiveEditorContext.editorDraft) request.editorDraft = effectiveEditorContext.editorDraft
      // 附带用户位置（用于天气建议）；未授权/失败时省略，后端回退默认坐标
      const location = await requestUserLocation()
      if (location) {
        request.longitude = location.longitude
        request.latitude = location.latitude
      }

      await api.startAgentRun(
        runSessionId,
        request,
        // 用户切到别的会话时丢弃事件（已落库，切回时重新拉快照 + 续接实时流）
        event => {
          if (state.sessionId === runSessionId) applyAgentEvent(state, event)
        },
        controller.signal
      )
      return true
    } catch (error) {
      if (state.sessionId !== runSessionId) return false
      if (controller.signal.aborted || error?.name === 'AbortError') {
        state.runStatus = 'cancelled'
      } else {
        state.runStatus = 'error'
        state.error = {
          code: 'CLIENT_ERROR',
          message: error?.message || 'Agent request failed',
          retryable: true
        }
      }
      return false
    } finally {
      if (activeRuns.get(runSessionId) === controller) activeRuns.delete(runSessionId)
      syncRunIndicator()
    }
  }

  async function cancel() {
    const controller = activeRuns.get(state.sessionId)
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
