import { describe, expect, test, vi } from 'vitest'
import { consumeAgentSse } from './sse'

function responseFrom(chunks, init = {}) {
  const encoder = new TextEncoder()
  return new Response(new ReadableStream({
    start(controller) {
      chunks.forEach(chunk => controller.enqueue(encoder.encode(chunk)))
      controller.close()
    }
  }), {
    status: init.status || 200,
    headers: { 'Content-Type': init.contentType || 'text/event-stream' }
  })
}

describe('consumeAgentSse', () => {
  test('parses typed events split across arbitrary response chunks', async () => {
    const received = []
    const response = responseFrom([
      'event: run_started\r\ndata: {"runId":"r1",',
      '"sessionId":7}\r\n\r\nevent: message_delta\n',
      'data: {"text":"hello"}\n\n'
    ])

    await consumeAgentSse(response, event => received.push(event))

    expect(received).toEqual([
      { type: 'run_started', payload: { runId: 'r1', sessionId: 7 } },
      { type: 'message_delta', payload: { text: 'hello' } }
    ])
  })

  test('fails on non-SSE responses before invoking the event handler', async () => {
    const handler = vi.fn()
    const response = responseFrom(['{"message":"unauthorized"}'], {
      status: 401,
      contentType: 'application/json'
    })

    await expect(consumeAgentSse(response, handler)).rejects.toThrow('Agent request failed with status 401')
    expect(handler).not.toHaveBeenCalled()
  })

  test('fails when an event payload is invalid JSON', async () => {
    const response = responseFrom(['event: error\ndata: not-json\n\n'])

    await expect(consumeAgentSse(response, () => {})).rejects.toThrow('Invalid Agent SSE payload')
  })
})
