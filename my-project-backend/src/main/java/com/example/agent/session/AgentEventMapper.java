package com.example.agent.session;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 事件表 Mapper：按会话查询全部事件（按 id 升序，用于恢复会话时重放）。
 * 表结构：agent_event(id, session_id, run_id, sequence_no, type, payload_json,
 * created_at)
 */
@Mapper
public interface AgentEventMapper extends BaseMapper<AgentEvent> {
    @Select("""
            SELECT id, session_id, run_id, sequence_no, type, payload_json, created_at
            FROM agent_event
            WHERE session_id = #{sessionId}
            ORDER BY id ASC
            """)
    List<AgentEvent> selectBySessionIdOrdered(@Param("sessionId") long sessionId);
}
