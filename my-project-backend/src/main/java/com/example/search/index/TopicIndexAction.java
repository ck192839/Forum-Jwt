package com.example.search.index;

/**
 * 索引事件动作：
 * - UPSERT ：插入或更新帖子索引（新增/编辑帖子时）
 * - DELETE ：删除帖子索引（帖子被删除时）
 */
public enum TopicIndexAction {
    UPSERT,
    DELETE
}
