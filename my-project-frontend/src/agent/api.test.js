import { beforeEach, describe, expect, test, vi } from 'vitest'

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
  fetchPost: vi.fn()
}))

vi.mock('axios', () => ({
  default: {
    get: http.get,
    post: http.post,
    delete: http.delete
  }
}))

vi.mock('@/net', () => ({
  accessHeader: () => ({ Authorization: 'Bearer token' }),
  fetchPost: http.fetchPost
}))

import {
  cancelAgentRun,
  createAgentSession,
  deleteAgentSession,
  getAgentSession,
  listRecentAgentSessions,
  startAgentRun
} from './api'

describe('Agent API', () => {
  beforeEach(() => vi.clearAllMocks())

  test('unwraps session endpoints and sends authorization headers', async () => {
    http.post.mockResolvedValueOnce({ data: { code: 200, data: { id: 3 } } })
    http.get
      .mockResolvedValueOnce({ data: { code: 200, data: [{ id: 3 }] } })
      .mockResolvedValueOnce({ data: { code: 200, data: { session: { id: 3 } } } })
    http.delete.mockResolvedValueOnce({ data: { code: 200, data: null } })

    await expect(createAgentSession()).resolves.toEqual({ id: 3 })
    await expect(listRecentAgentSessions()).resolves.toEqual([{ id: 3 }])
    await expect(getAgentSession(3)).resolves.toEqual({ session: { id: 3 } })
    await expect(deleteAgentSession(3)).resolves.toBeNull()

    expect(http.post).toHaveBeenCalledWith('/api/agent/sessions', null, {
      headers: { Authorization: 'Bearer token' }
    })
    expect(http.get).toHaveBeenCalledWith('/api/agent/sessions/recent', {
      headers: { Authorization: 'Bearer token' }
    })
    expect(http.delete).toHaveBeenCalledWith('/api/agent/sessions/3', {
      headers: { Authorization: 'Bearer token' }
    })
  })

  test('streams typed run events and supports cancellation', async () => {
    const encoder = new TextEncoder()
    http.fetchPost.mockResolvedValueOnce(new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode('event: run_started\ndata: {"runId":"r1","sessionId":3}\n\n'))
        controller.close()
      }
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } }))
    http.delete.mockResolvedValueOnce({ data: { code: 200, data: null } })
    const events = []

    await startAgentRun(3, { message: '写帖子', editorVersion: 0 }, event => events.push(event))
    await cancelAgentRun('r1')

    expect(http.fetchPost).toHaveBeenCalledWith('/api/agent/sessions/3/runs', {
      message: '写帖子',
      editorVersion: 0
    }, expect.any(AbortSignal))
    expect(events).toEqual([{ type: 'run_started', payload: { runId: 'r1', sessionId: 3 } }])
    expect(http.delete).toHaveBeenCalledWith('/api/agent/runs/r1', {
      headers: { Authorization: 'Bearer token' }
    })
  })

  test('rejects non-success RestBean responses', async () => {
    http.get.mockResolvedValueOnce({ data: { code: 404, message: '会话不存在' } })

    await expect(getAgentSession(99)).rejects.toThrow('会话不存在')
  })
})
