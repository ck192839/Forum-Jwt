import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'
import { diffLines } from 'diff'
import { deltaToModelText } from './deltaText'

export { deltaToModelText } from './deltaText'

const markdown = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: true
})

export function sanitizeMarkdown(source) {
  return DOMPurify.sanitize(markdown.render(source || ''), {
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'img'],
    FORBID_ATTR: ['style', 'onerror', 'onload']
  })
}

export function markdownToSafeDelta(source, clipboard, currentDelta) {
  const converted = clipboard.convert(sanitizeMarkdown(source))
  const operations = [...(converted?.ops || [])]
  const images = (currentDelta?.ops || [])
    .filter(operation => operation.insert?.image)
    .map(operation => ({
      insert: { image: operation.insert.image },
      ...(operation.attributes ? { attributes: { ...operation.attributes } } : {})
    }))

  for (const image of images) {
    if (!endsWithNewline(operations)) operations.push({ insert: '\n' })
    operations.push(image, { insert: '\n' })
  }
  return { ops: operations }
}

export function createDraftPreview(current, draft) {
  assertDraftVersion(draft, current.editorVersion)
  return {
    draft,
    baseEditorVersion: current.editorVersion,
    title: changedValue(current.title, draft.title),
    topicTypeId: changedValue(current.topicTypeId, draft.topicTypeId),
    body: diffLines(current.bodyText || '', draft.bodyMarkdown || '')
  }
}

export function assertDraftVersion(draft, currentEditorVersion) {
  if (draft.editorVersion !== currentEditorVersion) {
    throw new Error('编辑器内容已发生变化，请重新生成草稿')
  }
}

function changedValue(before, after) {
  return { before, after, changed: before !== after }
}

function endsWithNewline(operations) {
  const last = operations.at(-1)?.insert
  return typeof last === 'string' && last.endsWith('\n')
}
