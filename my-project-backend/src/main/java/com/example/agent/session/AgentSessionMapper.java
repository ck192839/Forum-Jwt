package com.example.agent.session;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.sql.Timestamp;
import java.util.List;

@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSession> {
    @Delete("""
            DELETE FROM agent_session
            WHERE uid = #{uid}
              AND id NOT IN (
                  SELECT id FROM (
                      SELECT id
                      FROM agent_session
                      WHERE uid = #{uid}
                      ORDER BY updated_at DESC, id DESC
                      LIMIT #{keep}
                  ) kept
              )
            """)
    int deleteOlderSessions(@Param("uid") int uid, @Param("keep") int keep);

    @Select("""
            SELECT id, uid, status, created_at, updated_at, expires_at
            FROM agent_session
            WHERE uid = #{uid} AND expires_at > #{now}
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<AgentSession> selectRecentNonExpired(
            @Param("uid") int uid,
            @Param("now") Timestamp now,
            @Param("limit") int limit
    );

    @Select("""
            SELECT id, uid, status, created_at, updated_at, expires_at
            FROM agent_session
            WHERE uid = #{uid}
              AND status = 'ACTIVE'
              AND expires_at > #{now}
            ORDER BY updated_at DESC, id DESC
            LIMIT 1
            """)
    AgentSession selectMostRecentActiveNonExpired(
            @Param("uid") int uid,
            @Param("now") Timestamp now
    );

    @Select("""
            SELECT id, uid, status, created_at, updated_at, expires_at
            FROM agent_session
            WHERE id = #{sessionId} AND uid = #{uid}
            """)
    AgentSession selectOwnedById(@Param("sessionId") long sessionId, @Param("uid") int uid);

    @Delete("DELETE FROM agent_session WHERE id = #{sessionId} AND uid = #{uid}")
    int deleteOwnedById(@Param("sessionId") long sessionId, @Param("uid") int uid);

    @Update("""
            UPDATE agent_session
            SET updated_at = #{updatedAt}
            WHERE id = #{sessionId} AND uid = #{uid}
            """)
    int touchOwnedById(
            @Param("sessionId") long sessionId,
            @Param("uid") int uid,
            @Param("updatedAt") Timestamp updatedAt
    );

    @Delete("DELETE FROM agent_session WHERE expires_at <= #{now}")
    int deleteExpired(@Param("now") Timestamp now);
}
