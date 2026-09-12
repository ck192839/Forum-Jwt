package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 抢活动：线下活动限量名额。grabbed 由消费端行锁 SQL 原子递增，Redis 预扣的库存以
 * total_stock - grabbed 为基准懒加载。
 */
@Data
@TableName("db_activity")
public class Activity {
    @TableId(type = IdType.AUTO)
    Integer id;
    String title;
    String description;
    String location;
    Date activityTime;
    Integer totalStock;
    Integer grabbed;
    Date grabStartTime;
    Date grabEndTime;
    Date createTime;
    /** 1=上架 0=下架（管理端控制，用户侧列表与报名只对上架活动生效） */
    Integer status;

    public static final int STATUS_ON = 1;
    public static final int STATUS_OFF = 0;
}
