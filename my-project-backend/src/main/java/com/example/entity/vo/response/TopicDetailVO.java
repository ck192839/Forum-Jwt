package com.example.entity.vo.response;

import com.example.entity.BaseData;
import com.example.entity.dto.Interact;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

@Data
public class TopicDetailVO {
    Integer id;
    String title;
    String content;
    Integer type;
    Date time;
    User user;
    Interact interact;
    Long comments;
    Integer locked;
@Data
@AllArgsConstructor
public static class Interact{
    Boolean like;
    Boolean collect;
}
    @Data
    public static class User{//包含accountprivacy的字段
        Integer id;
        String username;
        String avatar;
        String desc;
        Integer gender;
        String qq;
        String wx;
        String phone;
        String email;

    }
}
