package com.example.agent.index;

import java.time.Instant;

/**
 * 索引重建进度状态（管理页面轮询展示）：
 * - running ：是否正在重建
 * - total ：总帖子数
 * - processed ：已成功处理数
 * - failed ：失败数
 * - startedAt / finishedAt：起止时间（finishedAt 为 null 表示未结束）
 */
public record TopicIndexRebuildStatus(
        boolean running,
        int total,
        int processed,
        int failed,
        Instant startedAt,
        Instant finishedAt) {
    /** 空闲状态（从未重建或已结束）。 */
    public static TopicIndexRebuildStatus idle() {
        return new TopicIndexRebuildStatus(false, 0, 0, 0, null, null);
    }
}
