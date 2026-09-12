import DOMPurify from 'dompurify';
import { QuillDeltaToHtmlConverter } from 'quill-delta-to-html';

/**
 * 所有经 v-html 渲染的内容必须先过本函数：
 * 默认白名单保留 <em>(搜索高亮)、span/style(Quill 内联样式)、img/a 等，
 * 剥除 <script>、事件属性(onerror 等)与 javascript: 链接。
 */
export function sanitizeHtml(html) {
    return DOMPurify.sanitize(html);
}

export function deltaToHtml(content) {
    const ops = JSON.parse(content).ops
    const converter = new QuillDeltaToHtmlConverter(ops, { inlineStyles: true });
    return sanitizeHtml(converter.convert());
}
