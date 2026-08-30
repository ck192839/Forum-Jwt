package com.example.agent.context;

/**
 * token 保守估算器：组装 prompt 预算时用，避免引入分词器依赖。
 *
 * 估算规则（宁可高估，高估只会多裁一点历史，低估才会撑爆上下文）：
 * - CJK 字符（中文/日文/韩文等全角区）按 1 token 计
 * - 其它字符按每 4 个字符 1 token 计（向下取整后与 CJK 部分相加）
 *
 * DeepSeek 真实分词对中文约 0.6 token/字、英文约 0.3 token/字符，
 * 本估算对两者都留了约 40%-60% 的安全余量。
 */
public final class ContextTokenEstimator {
    // 非 CJK 字符的 token 换算：每 N 个字符算 1 token
    private static final int CHARS_PER_TOKEN = 4;

    private ContextTokenEstimator() {
    }

    /** 估算一段文本的 token 数（null/空返回 0）。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjkChars = 0;
        int otherChars = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            if (isCjk(codePoint)) {
                cjkChars++;
            } else {
                otherChars++;
            }
            offset += Character.charCount(codePoint);
        }
        return cjkChars + otherChars / CHARS_PER_TOKEN;
    }

    /** 是否 CJK（中日韩统一表意文字及扩展、全角假名/谚文等主要区段）。 */
    private static boolean isCjk(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HIRAGANA
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.KATAKANA
                || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL;
    }
}
