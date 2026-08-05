export async function consumeAgentSse(response, onEvent) {
  if (!response.ok) {
    throw new Error(`Agent request failed with status ${response.status}`)
  }

  const contentType = response.headers.get('content-type') || ''
  if (!contentType.toLowerCase().includes('text/event-stream')) {
    throw new Error('Agent response is not an SSE stream')
  }
  if (!response.body) {
    throw new Error('Agent SSE response has no body')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value, { stream: !done })

    let boundary = eventBoundary(buffer)
    while (boundary) {
      const block = buffer.slice(0, boundary.index)
      buffer = buffer.slice(boundary.index + boundary.length)
      emitBlock(block, onEvent)
      boundary = eventBoundary(buffer)
    }

    if (done) {
      if (buffer.trim()) {
        emitBlock(buffer, onEvent)
      }
      return
    }
  }
}

function eventBoundary(buffer) {
  const unix = buffer.indexOf('\n\n')
  const windows = buffer.indexOf('\r\n\r\n')
  if (unix < 0 && windows < 0) return null
  if (windows >= 0 && (unix < 0 || windows < unix)) {
    return { index: windows, length: 4 }
  }
  return { index: unix, length: 2 }
}

function emitBlock(block, onEvent) {
  let type = null
  const data = []
  for (const rawLine of block.replaceAll('\r\n', '\n').split('\n')) {
    if (!rawLine || rawLine.startsWith(':')) continue
    const separator = rawLine.indexOf(':')
    const field = separator < 0 ? rawLine : rawLine.slice(0, separator)
    const value = separator < 0 ? '' : rawLine.slice(separator + 1).replace(/^ /, '')
    if (field === 'event') type = value
    if (field === 'data') data.push(value)
  }
  if (!type) return

  try {
    onEvent({ type, payload: JSON.parse(data.join('\n')) })
  } catch (error) {
    if (error instanceof SyntaxError) {
      throw new Error(`Invalid Agent SSE payload for ${type}`, { cause: error })
    }
    throw error
  }
}
