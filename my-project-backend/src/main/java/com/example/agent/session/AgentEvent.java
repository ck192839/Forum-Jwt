package com.example.agent.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

/**
 * SSE 事件实体（表 agent_event）。
 * 每次 emit 的事件都先落库再推送，恢复会话时重放这些事件重建前端 UI 状态。
 * - runId + sequenceNo：全局唯一（库里有唯一索引），保证顺序与去重
 * - type ：事件类型（wireName）
 * - payloadJson：事件负载 JSON 字符串
 */
@Data
@TableName("agent_event")
public class AgentEvent {
    @TableId(type = IdType.AUTO)
    private Long id; // 主键
    private Long sessionId; // 所属会话 id
    private String runId; // 产生事件的 run id
    private Integer sequenceNo; // run 内自增序号
    private String type; // 事件类型（run_started / draft_ready ...）
    private String payloadJson; // 事件负载 JSON
    private Timestamp createdAt; // 创建时间
}
