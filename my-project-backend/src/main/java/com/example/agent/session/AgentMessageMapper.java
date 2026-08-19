package com.example.agent.session;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 消息表 Mapper：按会话查询全部消息（按 id 升序 = 时间顺序）。
 * 表结构：agent_message(id, session_id, role, content, created_at)
 */
@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessage> {
    @Select("""
            SELECT id, session_id, role, content, created_at
            FROM agent_message
            WHERE session_id = #{sessionId}
            ORDER BY id ASC
            """)
    List<AgentMessage> selectBySessionIdOrdered(@Param("sessionId") long sessionId);
}
