import { beforeEach, describe, expect, test } from 'vitest'
import {
  consumeDraftApplication,
  editorOptimizationRequest,
  pendingDraftApplication,
  publishDraftApplication,
  requestEditorOptimization,
  resetEditorBridge
} from './editorBridge'

describe('Agent editor bridge', () => {
  beforeEach(() => resetEditorBridge())

  test('publishes versioned editor optimization requests', () => {
    requestEditorOptimization({
      editorId: 'editor-a',
      editorVersion: 3,
      editorDraft: { title: 'Title', topicTypeId: 1, bodyMarkdown: 'Body' }
    })

    expect(editorOptimizationRequest.value).toMatchObject({
      editorId: 'editor-a',
      editorVersion: 3
    })
    expect(editorOptimizationRequest.value.requestId).toBeTypeOf('number')
  })

  test('allows only the target editor to consume a draft', () => {
    const draft = { title: 'Improved', editorVersion: 3, targetEditorId: 'editor-a' }
    publishDraftApplication(draft)

    expect(consumeDraftApplication('editor-b', true)).toBeNull()
    expect(pendingDraftApplication.value).not.toBeNull()
    expect(consumeDraftApplication('editor-a', false)).toEqual(draft)
    expect(pendingDraftApplication.value).toBeNull()
  })

  test('routes untargeted conversation drafts only to a new-topic editor', () => {
    const draft = { title: 'New topic', editorVersion: 0 }
    publishDraftApplication(draft)

    expect(consumeDraftApplication('existing-topic', false)).toBeNull()
    expect(consumeDraftApplication('new-topic', true, 7)).toEqual({
      ...draft,
      editorVersion: 7
    })
  })
})
