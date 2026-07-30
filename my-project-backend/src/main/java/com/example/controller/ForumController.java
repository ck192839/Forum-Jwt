package com.example.controller;

import com.alibaba.fastjson2.JSONObject;
import com.example.entity.RestBean;
import com.example.entity.dto.Account;
import com.example.entity.dto.Interact;
import com.example.entity.vo.request.AddCommentVO;
import com.example.entity.vo.request.TopicCreateVO;
import com.example.entity.vo.request.TopicUpdateVO;
import com.example.entity.vo.response.*;
import com.example.service.AccountService;
import com.example.service.TopicService;
import com.example.service.WeatherService;
import com.example.utils.Const;
import com.example.utils.ControllerUtils;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/forum")
@Validated
public class ForumController {
    @Resource
    WeatherService service;

    @Resource
    TopicService topicService;

    @Resource
    ControllerUtils utils;

    @Resource
    AccountService accountService;


    @GetMapping("/weather")//获取天气信息
    public RestBean<WeatherVO> weather(double longitude, double latitude) {
        WeatherVO vo = service.fetchWeather(longitude, latitude);
        return vo == null ?
                RestBean.failure(400, "获取地理位置信息与天气失败，请联系管理员！") : RestBean.success(vo);
    }

    @GetMapping("/types")//将所有主题类型从dto转为vo返回给前端
    public RestBean<List<TopicTypeVO>> listTypes() {
        return RestBean.success(topicService
                .listTypes()
                .stream()
                .map(type -> type.asViewObject(TopicTypeVO.class))
                .toList());
    }

    @PostMapping("/create-topic")
    public RestBean<Void> createTopic(@Valid @RequestBody TopicCreateVO vo,
                                      @RequestAttribute(Const.ATTR_USER_ID) int id) {
        Account account = accountService.findAccountById(id);
        if(account.isMute()) {
            return RestBean.forbidden("您已被禁言，无法创建新的主题");

        }
        return utils.messageHandle(() -> topicService.createTopic(id, vo));
    }


    @GetMapping("/list-topic")//获取主题列表
    public RestBean<List<TopicPreviewVO>> listTopicByPage(@RequestParam @Min(0) int page,
                                                          @RequestParam @Min(0) int type) {
        return RestBean.success(topicService.listTopicByPage(page+1, type));//适应前端从1开始的分页
    }

    @GetMapping("/top-topic")//获取热门主题
    public RestBean<List<TopicTopVO>> listTopTopics() {
        return RestBean.success(topicService.listTopTopics());
    }
    @GetMapping("/topic")//获取主题详情
    public RestBean<TopicDetailVO> Topic(@RequestParam @Min(0) int tid,
                                         @RequestAttribute(Const.ATTR_USER_ID) int id) {
        TopicDetailVO topic=topicService.getTopic(tid,id);
        if(topic!=null)  {
            return RestBean.success(topic);
        }
        else{
            return RestBean.failure(404, "帖子不存在或被屏蔽");
        }
    }
    @GetMapping("/interact")//帖子交互
    public RestBean<Void> interact( @RequestParam @Min(0) int tid,
                                   @RequestParam @Pattern(regexp = "like|collect") String type,
                                   @RequestParam boolean state,
                                  @RequestAttribute(Const.ATTR_USER_ID) int id) {
            topicService.interact(new Interact(tid, id,new Date(), type),state);
            return RestBean.success();
    }
    @GetMapping("/collects")//获取用户收藏的帖子
    public RestBean<List<TopicPreviewVO>> collects(@RequestAttribute(Const.ATTR_USER_ID) int id) {
        return RestBean.success(topicService.listTopicCollects(id));
    }
    @PostMapping("/update-topic")//更新主题
    public RestBean<Void> updateTopic(@Valid @RequestBody TopicUpdateVO vo,
                                     @RequestAttribute(Const.ATTR_USER_ID) int id) {
        return utils.messageHandle(() -> topicService.updateTopic(id, vo));
    }
    @PostMapping("/add-comment")//添加评论
    public RestBean<Void> addComment(@Valid @RequestBody AddCommentVO vo,
                                     @RequestAttribute(Const.ATTR_USER_ID) int id){
        Account account = accountService.findAccountById(id);
        if(account.isMute()) {
            return RestBean.forbidden("您已被禁言，无法创建新的回复");
        }
        return utils.messageHandle(() -> topicService.createComment(id, vo));
    }

    @GetMapping("/comments")//获取评论列表
    public RestBean<List<CommentVO>> listComment(@RequestParam @Min(0) int tid,
                                                 @RequestParam @Min(0) int page) {
        return RestBean.success(topicService.comments(tid,page+1));
    }
    @GetMapping("/delete-comment")//删除评论
    public RestBean<Void> deleteComment(@RequestParam @Min(0) int id,
                                        @RequestAttribute(Const.ATTR_USER_ID) int uid) {
        topicService.deleteComment(id,uid);
        return RestBean.success();
    }
    @GetMapping("/user-topic")
    public RestBean<List<TopicVO>> userTopic(@RequestAttribute(Const.ATTR_USER_ID) int uid) {
        return RestBean.success(topicService.listTopicByUser(uid));
    }
    @GetMapping("/delete-topic")
    public RestBean<Void> deleteTopic(@RequestParam @Min(0) int tid,
                                      @RequestAttribute(Const.ATTR_USER_ID) int uid){
        topicService.deleteTopic(tid, uid);
        return RestBean.success();
    }
    @GetMapping("/search-topic")
    public RestBean<List<TopicSearchVO>> searchTopic(@RequestParam String keyword){
        return RestBean.success(topicService.searchTopic(keyword));
    }

}
