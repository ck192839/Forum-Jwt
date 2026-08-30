package com.example.agent.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终态 JSON 的「可见文本」增量提取器。
 *
 * 模型在 JSON 模式下输出的是 {"type":"ANSWER","answer":"..."} 这样的原始 JSON，
 * 直接把流式 chunk 推给前端会让用户看到 JSON 结构在打字。
 * 本类在流式过程中增量解码目标字段的字符串值：
 * - type=ANSWER   → answer 字段（问答正文）
 * - type=QUESTION → question 字段（追问）
 * - type=DRAFT    → bodyMarkdown 字段（草稿正文）
 *
 * 用法：每个模型响应新建一个实例，每收到一个文本 chunk 调一次 {@link #append}，
 * 返回值是自上次调用以来新解码出的可见文本（可能为空串）。
 * 值未闭合（还在打字中）时返回目前已解码的部分——这就是流式效果。
 *
 * 只做增量前缀提取：解码结果永远是最终值的前缀，
 * 异常输入（无 type / 非法 JSON）静默返回空串，不影响终态解析器的严格校验。
 */
final class TerminalTextExtractor {
    private static final Pattern TYPE_PATTERN =
            Pattern.compile("\"type\"\\s*:\\s*\"(QUESTION|ANSWER|DRAFT)\"");
    private static final Map<String, Pattern> KEY_PATTERNS = new ConcurrentHashMap<>();

    private final StringBuilder raw = new StringBuilder();
    private final StringBuilder decoded = new StringBuilder();
    private String field;

    /** 追加一段原始输出，返回新解码出的可见文本增量（无新增时为空串）。 */
    String append(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        raw.append(chunk);
        if (field == null) {
            detectField();
        }
        if (field == null) {
            return "";
        }
        String value = decodeFieldValue();
        if (value.length() <= decoded.length()) {
            return "";
        }
        String delta = value.substring(decoded.length());
        decoded.setLength(0);
        decoded.append(value);
        return delta;
    }

    /** 从原始输出中识别终态类型，确定要流式展示的字段。 */
    private void detectField() {
        Matcher type = TYPE_PATTERN.matcher(raw);
        if (!type.find()) {
            return;
        }
        field = switch (type.group(1)) {
            case "QUESTION" -> "question";
            case "ANSWER" -> "answer";
            case "DRAFT" -> "bodyMarkdown";
            default -> null;
        };
    }

    /**
     * 定位字段值并解码到当前已到达的位置。
     * 键必须紧跟在 { 或 , 之后（避免匹配到字符串值内部出现的同名片段）。
     */
    private String decodeFieldValue() {
        Pattern keyPattern = KEY_PATTERNS.computeIfAbsent(field,
                name -> Pattern.compile("[{,]\\s*\"" + name + "\"\\s*:"));
        Matcher key = keyPattern.matcher(raw);
        if (!key.find()) {
            return "";
        }
        int i = key.end();
        while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) {
            i++;
        }
        if (i >= raw.length() || raw.charAt(i) != '"') {
            return "";
        }
        i++; // 跳过值的开引号
        StringBuilder out = new StringBuilder();
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\\') {
                if (i + 1 >= raw.length()) {
                    break; // 尾部转义不完整，等下一段 chunk
                }
                char escaped = raw.charAt(i + 1);
                int next = appendEscape(out, raw, i, escaped);
                if (next < 0) {
                    break; // \\uXXXX 不完整，留在已解码之外，下一段补全
                }
                i = next;
            } else if (c == '"') {
                break; // 值闭合
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 解码一个转义序列，返回推进后的下标；\\uXXXX 不完整时返回 -1（由调用方结束扫描）。 */
    private int appendEscape(StringBuilder out, StringBuilder raw, int i, char escaped) {
        switch (escaped) {
            case '"' -> out.append('"');
            case '\\' -> out.append('\\');
            case '/' -> out.append('/');
            case 'n' -> out.append('\n');
            case 't' -> out.append('\t');
            case 'r' -> out.append('\r');
            case 'b' -> out.append('\b');
            case 'f' -> out.append('\f');
            case 'u' -> {
                if (i + 6 > raw.length()) {
                    return -1;
                }
                out.append((char) Integer.parseInt(raw, i + 2, i + 6, 16));
                return i + 6;
            }
            default -> out.append(escaped);
        }
        return i + 2;
    }
}
