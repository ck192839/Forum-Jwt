package com.example.search.index;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块器：把帖子正文切成适合向量化的片段。
 *
 * 参数（SearchConfiguration 里配置为 2400 / 400）：
 * - chunkSize：每块最大字符数
 * - overlap ：相邻块的重叠字符数（保证切分边界处的语义不丢失）
 *
 * split 逻辑：
 * - 正文为空时只返回标题（或无内容时返回空）
 * - 每个分块前面拼上标题作为上下文前缀（"标题\n\n正文片段"）
 * - 按 chunkSize 滑动切分，块间保留 overlap 重叠
 */
public class TopicChunker {
    private final int chunkSize; // 块大小
    private final int overlap; // 重叠量

    public TopicChunker(int chunkSize, int overlap) {
        // 参数校验：块大小必须为正、重叠不能为负也不能超过块大小
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("chunkSize must be positive and overlap must be smaller");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /**
     * 分块。
     * 
     * @param title 标题（作为每个块的前缀上下文）
     * @param body  正文
     * @return 分块列表
     */
    public List<String> split(String title, String body) {
        String safeTitle = title == null ? "" : title.trim();
        String safeBody = body == null ? "" : body.trim();
        // 正文为空：只返回标题（或空列表）
        if (safeBody.isEmpty()) {
            return safeTitle.isEmpty() ? List.of() : List.of(safeTitle);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        // 滑动窗口切分：每次取 chunkSize 长度，结尾处保留 overlap 重叠
        while (start < safeBody.length()) {
            int end = Math.min(start + chunkSize, safeBody.length());
            String prefix = safeTitle.isEmpty() ? "" : safeTitle + "\n\n";
            chunks.add(prefix + safeBody.substring(start, end));
            if (end == safeBody.length()) {
                break;
            }
            start = end - overlap;
        }
        return chunks;
    }
}
