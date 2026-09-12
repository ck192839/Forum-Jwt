package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.request.ActivityGrabVO;
import com.example.entity.vo.response.ActivityOrderVO;
import com.example.entity.vo.response.ActivityVO;
import com.example.service.ActivityService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@RestController
@RequestMapping("/api/activity")
@Validated
public class ActivityController {

    @Resource
    ActivityService activityService;

    @GetMapping("/list")
    public RestBean<List<ActivityVO>> listActivity() {
        return RestBean.success(activityService.list());
    }

    /**
     * 抢报名：排队中/重复报名返回 200（data 为提示文案），不可报名返回 4xx/5xx。
     */
    @PostMapping("/grab")
    public RestBean<String> grab(@Valid @RequestBody ActivityGrabVO vo,
                                 @RequestAttribute(Const.ATTR_USER_ID) int uid) {
        ActivityService.GrabResult result = activityService.grab(uid, vo.getActivityId());
        if (result.code() == 200) {
            return RestBean.success(result.message());
        }
        return RestBean.failure(result.code(), result.message());
    }

    @GetMapping("/my-orders")
    public RestBean<List<ActivityOrderVO>> myOrders(@RequestAttribute(Const.ATTR_USER_ID) int uid) {
        return RestBean.success(activityService.myOrders(uid));
    }
}
