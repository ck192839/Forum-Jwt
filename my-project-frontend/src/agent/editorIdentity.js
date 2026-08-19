const VERSION_KEY_PREFIX = 'forum-agent:editor-version:'
const fallbackVersions = new Map()

export function stableEditorId(editorKey) {
  return `topic-editor:${encodeURIComponent(normalizeKey(editorKey))}`
}

export function nextEditorVersion(editorKey) {
  const key = normalizeKey(editorKey)
  const storageKey = VERSION_KEY_PREFIX + key
  try {
    const current = Number.parseInt(globalThis.sessionStorage.getItem(storageKey) || '0', 10)
    const next = (Number.isFinite(current) ? current : 0) + 1
    globalThis.sessionStorage.setItem(storageKey, String(next))
    return next
  } catch {
    const next = (fallbackVersions.get(key) || 0) + 1
    fallbackVersions.set(key, next)
    return next
  }
}

export function currentEditorVersion(editorKey) {
  const key = normalizeKey(editorKey)
  const storageKey = VERSION_KEY_PREFIX + key
  try {
    const current = Number.parseInt(globalThis.sessionStorage.getItem(storageKey) || '0', 10)
    return Number.isFinite(current) ? current : 0
  } catch {
    return fallbackVersions.get(key) || 0
  }
}

function normalizeKey(editorKey) {
  const value = String(editorKey || '').trim()
  if (!value) throw new Error('editorKey is required')
  return value
}
