import { describe, expect, test, vi } from 'vitest'
import {
  assertDraftVersion,
  createDraftPreview,
  deltaToModelText,
  markdownToSafeDelta,
  sanitizeMarkdown
} from './draftApply'

describe('Agent draft application', () => {
  test('sanitizes Markdown before handing HTML to Quill', () => {
    const html = sanitizeMarkdown(`
# Safe title

<img src=x onerror="alert(1)">

[unsafe](javascript:alert(1))
`)

    expect(html).toContain('<h1>Safe title</h1>')
    expect(html).not.toMatch(/<img|<script|href=["']javascript:/i)
  })

  test('excludes images from model text and preserves them in the applied Delta', () => {
    const current = {
      ops: [
        { insert: 'Old text\n' },
        { insert: { image: 'https://example.test/one.png' }, attributes: { width: '240' } },
        { insert: '\n' }
      ]
    }
    const clipboard = {
      convert: vi.fn().mockReturnValue({ ops: [{ insert: 'New text\n' }] })
    }

    expect(deltaToModelText(current)).toBe('Old text\n\n')
    const applied = markdownToSafeDelta('New **text**', clipboard, current)

    expect(clipboard.convert).toHaveBeenCalledWith(expect.stringContaining('<strong>text</strong>'))
    expect(applied.ops).toContainEqual({
      insert: { image: 'https://example.test/one.png' },
      attributes: { width: '240' }
    })
  })

  test('builds a title, section and line diff for a matching editor version', () => {
    const preview = createDraftPreview({
      title: 'Old title',
      topicTypeId: 1,
      bodyText: 'Old line\nShared line\n',
      editorVersion: 4
    }, {
      title: 'New title',
      topicTypeId: 2,
      bodyMarkdown: 'New line\nShared line\n',
      editorVersion: 4
    })

    expect(preview.title).toEqual({ before: 'Old title', after: 'New title', changed: true })
    expect(preview.topicTypeId).toEqual({ before: 1, after: 2, changed: true })
    expect(preview.body.some(part => part.removed && part.value.includes('Old line'))).toBe(true)
    expect(preview.body.some(part => part.added && part.value.includes('New line'))).toBe(true)
  })

  test('rejects stale drafts both before preview and again before apply', () => {
    expect(() => createDraftPreview({
      title: '', topicTypeId: null, bodyText: '', editorVersion: 5
    }, {
      title: 'Draft', topicTypeId: 1, bodyMarkdown: 'Body', editorVersion: 4
    })).toThrow('编辑器内容已发生变化')

    const preview = createDraftPreview({
      title: '', topicTypeId: null, bodyText: '', editorVersion: 4
    }, {
      title: 'Draft', topicTypeId: 1, bodyMarkdown: 'Body', editorVersion: 4
    })

    expect(() => assertDraftVersion(preview.draft, 5)).toThrow('编辑器内容已发生变化')
  })
})
