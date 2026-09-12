package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 报名单：由 MQ 消费端异步落单。(activity_id, uid) 唯一键是幂等的最后兜底。
 */
@Data
@TableName("db_activity_order")
public class ActivityOrder {
    public static final int STATUS_PROCESSING = 0; // 排队中（MQ 已受理、尚未落单）
    public static final int STATUS_SUCCESS = 1;    // 报名成功
    public static final int STATUS_FAILED = 2;     // 报名失败（DB 层名额已满等兜底失败）

    @TableId(type = IdType.AUTO)
    Integer id;
    Integer activityId;
    Integer uid;
    Integer status;
    Date createTime;
    Date updateTime;
}
