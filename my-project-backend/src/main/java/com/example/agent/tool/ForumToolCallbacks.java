package com.example.agent.tool;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 工具回调列表的不可变包装（record）。
 * 由 AgentRuntimeConfiguration 把 ForumAuthoringTools 的 @Tool 方法
 * 经 MethodToolCallbackProvider 转成 List<ToolCallback> 后存入。
 * 目的：给 ForumReActAgent 一个类型化的、不可变的工具集合（供查找 validate_draft 等）。
 */
public record ForumToolCallbacks(List<ToolCallback> callbacks) {
    public ForumToolCallbacks {
        callbacks = List.copyOf(callbacks);
    }
}
