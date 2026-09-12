package com.example.controller.admin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.entity.PageRestBean;
import com.example.entity.RestBean;
import com.example.entity.dto.Activity;
import com.example.entity.vo.request.ActivityAdminSaveVO;
import com.example.entity.vo.response.ActivityVO;
import com.example.service.ActivityService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/activity")
public class ActivityAdminController {

    @Resource
    ActivityService activityService;

    @GetMapping("/list")
    public PageRestBean<ActivityVO> list(@RequestParam int page,
                                         @RequestParam int size,
                                         @RequestParam(required = false) String keyword) {
        Page<Activity> result = activityService.adminList(page, size, keyword);
        List<ActivityVO> list = result.getRecords().stream().map(this::toVO).toList();
        return PageRestBean.success(list, result.getTotal(), page);
    }

    @PostMapping("/save")
    public RestBean<Void> save(@Valid @RequestBody ActivityAdminSaveVO vo) {
        String error = activityService.adminSave(vo);
        if (error != null) return RestBean.failure(400, error);
        return RestBean.success();
    }

    @GetMapping("/delete")
    public RestBean<Void> delete(@RequestParam int id) {
        String error = activityService.adminDelete(id);
        if (error != null) return RestBean.failure(400, error);
        return RestBean.success();
    }

    @PostMapping("/status")
    public RestBean<Void> setStatus(@RequestBody ActivityStatusVO vo) {
        activityService.adminSetStatus(vo.id(), vo.status());
        return RestBean.success();
    }

    private ActivityVO toVO(Activity activity) {
        ActivityVO vo = new ActivityVO();
        vo.setId(activity.getId());
        vo.setTitle(activity.getTitle());
        vo.setDescription(activity.getDescription());
        vo.setLocation(activity.getLocation());
        vo.setActivityTime(activity.getActivityTime());
        vo.setTotalStock(activity.getTotalStock());
        vo.setGrabbed(activity.getGrabbed());
        vo.setGrabStartTime(activity.getGrabStartTime());
        vo.setGrabEndTime(activity.getGrabEndTime());
        vo.setStatus(activity.getStatus());
        return vo;
    }

    /** 上/下架请求体。 */
    record ActivityStatusVO(int id, boolean status) {
    }
}
