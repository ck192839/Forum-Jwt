package com.example.entity.vo.response;

import lombok.Data;

import java.util.Date;

@Data
public class ActivityVO {
    int id;
    String title;
    String description;
    String location;
    Date activityTime;
    int totalStock;
    int grabbed;
    Date grabStartTime;
    Date grabEndTime;
    /** 1=上架 0=下架（仅管理端列表使用，用户侧列表只返回上架活动） */
    int status;
}
