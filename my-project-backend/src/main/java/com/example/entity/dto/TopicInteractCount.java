package com.example.entity.dto;

import lombok.Data;

/**
 * 帖子互动计数批量查询的行结果（interactCountBatch）。
 */
@Data
public class TopicInteractCount {
    Integer tid;
    Integer total;
}
