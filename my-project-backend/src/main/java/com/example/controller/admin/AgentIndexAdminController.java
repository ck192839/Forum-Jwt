package com.example.controller.admin;

import com.example.agent.index.TopicIndexRebuildService;
import com.example.agent.index.TopicIndexRebuildStatus;
import com.example.entity.RestBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/agent/index")
public class AgentIndexAdminController {
    private final TopicIndexRebuildService rebuildService;

    public AgentIndexAdminController(TopicIndexRebuildService rebuildService) {
        this.rebuildService = rebuildService;
    }

    @PostMapping("/rebuild")
    public RestBean<TopicIndexRebuildStatus> rebuild() {
        if (!rebuildService.start()) {
            return RestBean.failure(409, "A topic index rebuild is already running");
        }
        return RestBean.success(rebuildService.status());
    }

    @GetMapping("/status")
    public RestBean<TopicIndexRebuildStatus> status() {
        return RestBean.success(rebuildService.status());
    }
}
