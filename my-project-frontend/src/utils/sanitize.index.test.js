import {describe, expect, test} from 'vitest'
import {deltaToHtml, sanitizeHtml} from '@/utils/sanitize'

describe('sanitizeHtml', () => {
    test('剥除 script 标签', () => {
        const result = sanitizeHtml('<p>hello</p><script>alert(1)<\/script>')
        expect(result).not.toContain('<script')
        expect(result).toContain('<p>hello</p>')
    })

    test('剥除事件属性', () => {
        const result = sanitizeHtml('<img src="x" onerror="alert(1)">')
        expect(result).not.toContain('onerror')
    })

    test('剥除 javascript: 链接', () => {
        const result = sanitizeHtml('<a href="javascript:alert(1)">link</a>')
        expect(result).not.toContain('javascript:')
    })

    test('保留搜索高亮 em 标签', () => {
        const result = sanitizeHtml('中介<em>骗局</em>防范')
        expect(result).toContain('<em>骗局</em>')
    })

    test('保留 Quill 内联样式', () => {
        const result = sanitizeHtml('<span style="color: red;">text</span>')
        expect(result).toContain('color')
    })
})

describe('deltaToHtml', () => {
    const delta = JSON.stringify({
        ops: [
            {insert: 'hello '},
            {insert: 'world', attributes: {bold: true}},
            {insert: '\n'}
        ]
    })

    test('转换 delta 为 html 并消毒', () => {
        const result = deltaToHtml(delta)
        expect(result).toContain('hello')
        expect(result).toContain('<strong>world</strong>')
    })

    test('恶意脚本内容被剥除', () => {
        const result = deltaToHtml(delta)
        expect(result).not.toContain('<script')
    })
})
