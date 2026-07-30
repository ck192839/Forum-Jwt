package com.example.service;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.extension.service.IService;
import com.example.entity.dto.Interact;
import com.example.entity.dto.Topic;
import com.example.entity.dto.TopicType;
import com.example.entity.vo.request.AddCommentVO;
import com.example.entity.vo.request.TopicCreateVO;
import com.example.entity.vo.request.TopicTypeCreateVO;
import com.example.entity.vo.request.TopicUpdateVO;
import com.example.entity.vo.response.*;

import java.util.List;

public interface TopicService extends IService<Topic> {
    List<TopicType> listTypes();
    void updateTopicType(TopicTypeVO vo);
    void deleteTopicType(int id);
    void createTopicType(TopicTypeCreateVO vo);
    void changeTopicType(int tid, int type);
    String createTopic(int uid, TopicCreateVO vo);
    JSONObject listAllTopicByPage(int page, int type, String keyword);
    List<TopicPreviewVO> listTopicByPage(int page, int type);
    List<TopicTopVO> listTopTopics();
    TopicDetailVO getTopic(int tid,int uid);             //获取主题详情
    void interact(Interact interact, boolean state);
    List<TopicPreviewVO> listTopicCollects(int uid);     //获取用户收藏的帖子
    String updateTopic(int uid, TopicUpdateVO vo);      //更新主题
    String createComment(int uid, AddCommentVO vo);      //添加评论
    List<CommentVO> comments(int tid,int pageNumber);   //获取评论
    void deleteComment(int id,int uid);                    //删除评论
    void deleteTopic(int id);                           //删除帖子
    void deleteTopic(int tid, int uid);
    void setTopicTop(int tid, boolean top);             //设置帖子置顶
    void setTopicLocked(int tid, boolean locked);      //设置帖子锁定
    void setTopicInvisible(int tid, boolean invisible);//设置帖子是否可见设置为true表示不可见
    List<TopicVO> listTopicByUser(int uid);            //获取用户发布的帖子
    List<TopicSearchVO> searchTopic(String keyword);


}
