package com.example.entity.dto;

/**
 * 报名事件：抢接口 Redis 预扣成功后发往 MQ，消费端负责真正的落单。
 */
public record ActivityGrabEvent(int activityId, int uid) {
}
