package com.example.search;

/**
 * 检索来源枚举：
 * - KEYWORD：命中关键词索引（ES keyword 字段）
 * - VECTOR ：命中向量索引（语义相似）
 * 一个帖子可能同时被两种来源命中。
 */
public enum RetrievalSource {
    KEYWORD,
    VECTOR
}
