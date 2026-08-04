package com.example.agent.session;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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
