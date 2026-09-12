package com.example.entity.vo.response;

import lombok.Data;

import java.util.Date;

@Data
public class ActivityOrderVO {
    int id;
    int activityId;
    String activityTitle;
    int status;
    Date createTime;
}
