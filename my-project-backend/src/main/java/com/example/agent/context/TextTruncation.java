package com.example.agent.context;

/**
 * 上下文治理共用的文本截断工具（按 Unicode 码点计数，正确处理中文/emoji）。
 * 各截断点共用一处实现，保证码点计数与标记风格一致。
 */
public final class TextTruncation {
    /** 头部截断默认标记 */
    public static final String HEAD_MARKER = "…";
    /** 头尾保留截断默认标记 */
    public static final String MIDDLE_MARKER = "\n…[truncated]…\n";

    private TextTruncation() {
    }

    /**
     * 头部截断：超长时保留前 maxChars 个码点并以 marker 结尾。
     * 用于检索摘要、历史消息等「尾部价值低」的文本。
     */
    public static String truncateHead(String text, int maxChars, String marker) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int count = 0;
        for (int offset = 0; offset < text.length(); ) {
            if (count == maxChars) {
                return text.substring(0, offset) + marker;
            }
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            count++;
        }
        return text;
    }

    /**
     * 头尾保留截断：超长时保留前一半与后一半，中间以 marker 衔接。
     * 用于读帖正文、用户请求等「开头与结尾都有信息量」的文本。
     */
    public static String truncateHeadAndTail(String text, int maxChars, String marker) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int total = text.codePointCount(0, text.length());
        if (total <= maxChars) {
            return text;
        }
        int headChars = maxChars / 2 + maxChars % 2; // 前半（奇数时多一个）
        int tailChars = maxChars / 2; // 后半
        int headEnd = text.offsetByCodePoints(0, headChars);
        int tailStart = text.offsetByCodePoints(text.length(), -tailChars);
        return text.substring(0, headEnd) + marker + text.substring(tailStart);
    }
}
