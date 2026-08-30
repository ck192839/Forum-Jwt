package com.example.agent.session;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.sql.Timestamp;
import java.util.List;

/**
 * 会话表的 MyBatis-Plus Mapper。
 * 自定义 SQL 原则：所有按 uid 过滤的查询都带「归属校验」（uid = ?），
 * 保证多租户隔离——用户只能操作自己的会话。
 * 表结构：agent_session(id, uid, status, created_at, updated_at, expires_at,
 *               context_summary, summarized_message_id)
 */
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSession> {
        /**
         * 删除某个用户「保留 keep 条之外」的旧会话（创建新会话时调用，限制每个用户最多 keep 条）。
         * 用子查询先选出要保留的 id，避免 DELETE 与 SELECT 同一张表的 MySQL 限制。
         */
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

        /** 查询最近 limit 条「未过期」的会话（按最后活动时间倒序）。 */
        @Select("""
                        SELECT id, uid, status, created_at, updated_at, expires_at, context_summary, summarized_message_id
                        FROM agent_session
                        WHERE uid = #{uid} AND expires_at > #{now}
                        ORDER BY updated_at DESC, id DESC
                        LIMIT #{limit}
                        """)
        List<AgentSession> selectRecentNonExpired(
                        @Param("uid") int uid,
                        @Param("now") Timestamp now,
                        @Param("limit") int limit);

        /** 查询最近一条「ACTIVE 且未过期」的会话（前端恢复时自动选中）。 */
        @Select("""
                        SELECT id, uid, status, created_at, updated_at, expires_at, context_summary, summarized_message_id
                        FROM agent_session
                        WHERE uid = #{uid}
                          AND status = 'ACTIVE'
                          AND expires_at > #{now}
                        ORDER BY updated_at DESC, id DESC
                        LIMIT 1
                        """)
        AgentSession selectMostRecentActiveNonExpired(
                        @Param("uid") int uid,
                        @Param("now") Timestamp now);

        /** 按 id + uid 查询（归属校验，不存在返回 null）。 */
        @Select("""
                        SELECT id, uid, status, created_at, updated_at, expires_at, context_summary, summarized_message_id
                        FROM agent_session
                        WHERE id = #{sessionId} AND uid = #{uid}
                        """)
        AgentSession selectOwnedById(@Param("sessionId") long sessionId, @Param("uid") int uid);

        /** 按 id + uid 删除（返回 0 表示不存在/不属于该用户）。 */
        @Delete("DELETE FROM agent_session WHERE id = #{sessionId} AND uid = #{uid}")
        int deleteOwnedById(@Param("sessionId") long sessionId, @Param("uid") int uid);

        /** 更新时间戳（每次追加消息/事件/草稿时调用，返回 0 表示会话不存在）。 */
        @Update("""
                        UPDATE agent_session
                        SET updated_at = #{updatedAt}
                        WHERE id = #{sessionId} AND uid = #{uid}
                        """)
        int touchOwnedById(
                        @Param("sessionId") long sessionId,
                        @Param("uid") int uid,
                        @Param("updatedAt") Timestamp updatedAt);

        /** 删除所有已过期的会话（定时清理任务调用，不需要 uid 过滤）。 */
        @Delete("DELETE FROM agent_session WHERE expires_at <= #{now}")
        int deleteExpired(@Param("now") Timestamp now);

        /**
         * 写入滚动摘要并推进覆盖点（后台摘要任务调用，带 uid 归属校验）。
         * 故意不更新 updated_at：摘要是后台卫生工作，不应把会话顶到列表最前。
         */
        @Update("""
                        UPDATE agent_session
                        SET context_summary = #{summary}, summarized_message_id = #{summarizedMessageId}
                        WHERE id = #{sessionId} AND uid = #{uid}
                        """)
        int updateContextSummary(
                        @Param("sessionId") long sessionId,
                        @Param("uid") int uid,
                        @Param("summary") String summary,
                        @Param("summarizedMessageId") long summarizedMessageId);
}
