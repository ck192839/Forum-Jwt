package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.dto.Interact;
import com.example.entity.dto.Topic;
import com.example.entity.vo.response.TopicVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TopicMapper extends BaseMapper<Topic> {
        @InsertProvider(type = TopicSqlProvider.class, method = "addInteract")
        void addInteract(@Param("interacts") List<Interact> interacts,
                         @Param("type") InteractType type);

        @DeleteProvider(type = TopicSqlProvider.class, method = "deleteInteract")
        int deleteInteract(@Param("interacts") List<Interact> interacts,
                           @Param("type") InteractType type);

        @SelectProvider(type = TopicSqlProvider.class, method = "interactCount")
        int interactCount(@Param("tid") int tid, @Param("type") InteractType type);//获取帖子的互动次数

        @SelectProvider(type = TopicSqlProvider.class, method = "userInteractCount")
        int userInteractCount(@Param("tid") int tid, @Param("uid") int uid,
                              @Param("type") InteractType type);//获取用户对帖子的互动

        @Select("""
            select * from db_topic_interact_collect right join db_topic on tid = db_topic.id
             where db_topic_interact_collect.uid = #{uid}
            """)
        List<Topic> collectTopics(int uid);

        @Delete("delete from db_topic_interact_collect where tid = #{tid}")
        int deleteTopicCollect(int tid);//删除帖子的收藏

        @Select("""
            select * from db_topic_interact_like right join db_topic on tid = db_topic.id
             where db_topic_interact_like.uid = #{uid}
            """)
        List<Topic> likeTopics(int uid);

        @Delete("delete from db_topic_interact_like where tid = #{tid}")
        int deleteTopicLike(int tid);


}
