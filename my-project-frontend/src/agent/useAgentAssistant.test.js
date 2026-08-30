import { describe, expect, test, vi } from 'vitest'
import { createAgentAssistantController } from './useAgentAssistant'
import { clearLocationCacheForTests } from './location'

function detail(id) {
  return {
    session: { id, status: 'ACTIVE' },
    messages: [{ id: 1, role: 'USER', content: `session ${id}` }],
    events: [],
    draft: null
  }
}

describe('Agent assistant controller', () => {
  test('restores the most recent active session', async () => {
    const api = {
      listRecentAgentSessions: vi.fn().mockResolvedValue([
        { id: 8, status: 'COMPLETED' },
        { id: 7, status: 'ACTIVE' }
      ]),
      getAgentSession: vi.fn().mockResolvedValue(detail(7))
    }
    const controller = createAgentAssistantController(api)

    await controller.initialize()

    expect(controller.state.sessionId).toBe(7)
    expect(controller.state.messages[0].content).toBe('session 7')
    expect(api.getAgentSession).toHaveBeenCalledWith(7)
  })

  test('creates a session on demand and deletes the current session', async () => {
    const api = {
      listRecentAgentSessions: vi.fn()
        .mockResolvedValueOnce([])
        .mockResolvedValueOnce([{ id: 11, status: 'ACTIVE' }])
        .mockResolvedValueOnce([]),
      createAgentSession: vi.fn().mockResolvedValue({ id: 11, status: 'ACTIVE' }),
      getAgentSession: vi.fn().mockResolvedValue(detail(11)),
      deleteAgentSession: vi.fn().mockResolvedValue(null)
    }
    const controller = createAgentAssistantController(api)

    await controller.initialize()
    await controller.newSession()
    expect(controller.state.sessionId).toBe(11)

    await controller.deleteCurrentSession()
    expect(api.deleteAgentSession).toHaveBeenCalledWith(11)
    expect(controller.state.sessionId).toBeNull()
  })

  test('submits a message, merges typed events and can cancel a running request', async () => {
    let release
    const api = {
      listRecentAgentSessions: vi.fn().mockResolvedValue([{ id: 5, status: 'ACTIVE' }]),
      getAgentSession: vi.fn().mockResolvedValue(detail(5)),
      startAgentRun: vi.fn(async (sessionId, request, onEvent) => {
        onEvent({ type: 'run_started', payload: { runId: 'run-5', sessionId } })
        onEvent({ type: 'message_delta', payload: { text: '处理中' } })
        await new Promise(resolve => { release = resolve })
      }),
      cancelAgentRun: vi.fn().mockResolvedValue(null)
    }
    const controller = createAgentAssistantController(api)
    await controller.initialize()
    controller.prompt.value = '帮我写一篇帖子'

    const running = controller.submit()
    await vi.waitFor(() => expect(controller.state.runId).toBe('run-5'))
    expect(api.startAgentRun).toHaveBeenCalledWith(
      5,
      { message: '帮我写一篇帖子', editorVersion: 0 },
      expect.any(Function),
      expect.any(AbortSignal)
    )
    expect(controller.state.streamingText).toBe('处理中')

    await controller.cancel()
    release()
    await running

    expect(api.cancelAgentRun).toHaveBeenCalledWith('run-5')
    expect(controller.state.runStatus).toBe('cancelled')
  })

  test('includes the source editor id in an editor optimization run', async () => {
    const api = {
      listRecentAgentSessions: vi.fn().mockResolvedValue([{ id: 5, status: 'ACTIVE' }]),
      getAgentSession: vi.fn().mockResolvedValue(detail(5)),
      startAgentRun: vi.fn().mockResolvedValue(undefined)
    }
    const controller = createAgentAssistantController(api)
    await controller.initialize()

    await controller.submit({
      editorId: 'topic-editor-7',
      editorVersion: 6,
      editorDraft: { title: 'Draft', topicTypeId: 2, bodyMarkdown: 'Body' }
    })

    expect(api.startAgentRun).toHaveBeenCalledWith(
      5,
      {
        message: null,
        editorId: 'topic-editor-7',
        editorVersion: 6,
        editorDraft: { title: 'Draft', topicTypeId: 2, bodyMarkdown: 'Body' }
      },
      expect.any(Function),
      expect.any(AbortSignal)
    )
  })

  test('carries question editor context into the follow-up run', async () => {
    const api = {
      listRecentAgentSessions: vi.fn().mockResolvedValue([{ id: 5, status: 'ACTIVE' }]),
      getAgentSession: vi.fn().mockResolvedValue(detail(5)),
      startAgentRun: vi.fn().mockResolvedValue(undefined)
    }
    const controller = createAgentAssistantController(api)
    await controller.initialize()
    controller.state.editorContext = {
      editorId: 'topic-editor-question',
      editorVersion: 8
    }
    controller.prompt.value = 'For first-year students'

    await controller.submit()

    expect(api.startAgentRun).toHaveBeenCalledWith(
      5,
      {
        message: 'For first-year students',
        editorId: 'topic-editor-question',
        editorVersion: 8
      },
      expect.any(Function),
      expect.any(AbortSignal)
    )
  })

  test('allows only one run submission while the first request is in flight', async () => {
    let releaseFirst
    const api = {
      listRecentAgentSessions: vi.fn().mockResolvedValue([{ id: 5, status: 'ACTIVE' }]),
      getAgentSession: vi.fn().mockResolvedValue(detail(5)),
      startAgentRun: vi.fn()
        .mockImplementationOnce(() => new Promise(resolve => { releaseFirst = resolve }))
        .mockResolvedValueOnce(undefined)
    }
    const controller = createAgentAssistantController(api)
    await controller.initialize()

    controller.prompt.value = 'First request'
    const first = controller.submit()
    controller.prompt.value = 'Second request'
    const second = controller.submit()

    await vi.waitFor(() => expect(api.startAgentRun).toHaveBeenCalled())
    releaseFirst()
    await Promise.all([first, second])

    expect(api.startAgentRun).toHaveBeenCalledTimes(1)
  })

  test('attaches the cached user location to the run request', async () => {
    const geolocation = {
      getCurrentPosition: success => success({ coords: { longitude: 120.1, latitude: 30.2 } })
    }
    Object.defineProperty(globalThis.navigator, 'geolocation', { value: geolocation, configurable: true })
    const api = {
      listRecentAgentSessions: vi.fn().mockResolvedValue([{ id: 5, status: 'ACTIVE' }]),
      getAgentSession: vi.fn().mockResolvedValue(detail(5)),
      startAgentRun: vi.fn().mockResolvedValue(undefined)
    }
    const controller = createAgentAssistantController(api)
    await controller.initialize()
    controller.prompt.value = '哪里有好吃的牛腩？'

    try {
      await controller.submit()

      expect(api.startAgentRun).toHaveBeenCalledWith(
        5,
        expect.objectContaining({ message: '哪里有好吃的牛腩？', longitude: 120.1, latitude: 30.2 }),
        expect.any(Function),
        expect.any(AbortSignal)
      )
    } finally {
      delete globalThis.navigator.geolocation
      clearLocationCacheForTests()
    }
  })
})
