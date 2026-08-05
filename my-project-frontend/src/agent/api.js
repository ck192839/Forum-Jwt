import axios from 'axios'
import { accessHeader, fetchPost } from '@/net'
import { consumeAgentSse } from './sse'

export async function createAgentSession() {
  return unwrap(await axios.post('/api/agent/sessions', null, requestConfig()))
}

export async function listRecentAgentSessions() {
  return unwrap(await axios.get('/api/agent/sessions/recent', requestConfig()))
}

export async function getAgentSession(sessionId) {
  return unwrap(await axios.get(`/api/agent/sessions/${sessionId}`, requestConfig()))
}

export async function deleteAgentSession(sessionId) {
  return unwrap(await axios.delete(`/api/agent/sessions/${sessionId}`, requestConfig()))
}

export async function startAgentRun(sessionId, request, onEvent, signal = new AbortController().signal) {
  const response = await fetchPost(`/api/agent/sessions/${sessionId}/runs`, request, signal)
  await consumeAgentSse(response, onEvent)
}

export async function cancelAgentRun(runId) {
  return unwrap(await axios.delete(`/api/agent/runs/${encodeURIComponent(runId)}`, requestConfig()))
}

function requestConfig() {
  return { headers: accessHeader() }
}

function unwrap(response) {
  const body = response.data
  if (body?.code !== 200) {
    throw new Error(body?.message || 'Agent request failed')
  }
  return body.data ?? null
}
