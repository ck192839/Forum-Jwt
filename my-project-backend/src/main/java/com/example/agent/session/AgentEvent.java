package com.example.agent.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

@Data
@TableName("agent_event")
public class AgentEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String runId;
    private Integer sequenceNo;
    private String type;
    private String payloadJson;
    private Timestamp createdAt;
}
