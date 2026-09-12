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
}
