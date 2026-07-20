package com.example.entity.vo.response;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("db_topic")
public class TopicVO {//帖子
    @TableId(type = IdType.AUTO)
    Integer id;
    String title;
    String content;
    Integer uid;
    Integer type;
    Date time;
    Integer top;
    Integer locked;
    Integer invisible;
}

