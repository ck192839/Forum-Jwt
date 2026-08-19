import { shallowRef } from 'vue'

export const editorOptimizationRequest = shallowRef(null)
export const pendingDraftApplication = shallowRef(null)
export const editorOpenRequest = shallowRef(null)

let requestSequence = 0

export function requestEditorOptimization(request) {
  editorOptimizationRequest.value = {
    ...request,
    requestId: ++requestSequence
  }
}

export function publishDraftApplication(draft) {
  pendingDraftApplication.value = {
    draft,
    targetEditorId: draft.targetEditorId ?? null,
    requestId: ++requestSequence
  }
}

export function requestEditorOpen(editorId) {
  editorOpenRequest.value = { editorId, requestId: ++requestSequence }
}

export function consumeDraftApplication(editorId, acceptsUntargeted, currentEditorVersion) {
  const pending = pendingDraftApplication.value
  if (!pending) return null
  const targetsEditor = pending.targetEditorId === editorId
  const targetsNewTopic = pending.targetEditorId == null && acceptsUntargeted
  if (!targetsEditor && !targetsNewTopic) return null

  pendingDraftApplication.value = null
  return {
    ...pending.draft,
    editorVersion: currentEditorVersion ?? pending.draft.editorVersion
  }
}

export function resetEditorBridge() {
  editorOptimizationRequest.value = null
  pendingDraftApplication.value = null
  editorOpenRequest.value = null
}
