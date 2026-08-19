package com.example.agent.api;

import com.example.agent.run.AgentRunConflictException;
import com.example.agent.session.AgentSessionNotFoundException;
import com.example.entity.RestBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent API 的全局异常处理器，只作用于 {@link AgentController}。
 *
 * 作用：把 Agent 模块内部抛出的异常统一转成规范的 HTTP 状态码 + RestBean 响应体，
 * 避免把异常堆栈直接暴露给前端。所有错误消息都是面向用户的安全文案，不泄露内部细节。
 *
 * 状态码映射规则：
 * - 参数校验失败 / JSON 解析失败 → 400 Bad Request
 * - 会话或 run 不存在 → 404 Not Found
 * - 同会话已有 run 在跑（互斥冲突） → 409 Conflict
 * - 其余未知异常 → 500 Internal Server Error（并记录日志）
 */
@Slf4j
@RestControllerAdvice(assignableTypes = AgentController.class)
public class AgentControllerAdvice {

    /**
     * 处理「请求本身不合法」的异常：
     * - MethodArgumentNotValidException：@Valid 校验失败（如 message 为空、editorVersion 为负数）
     * - HttpMessageNotReadableException：请求体 JSON 无法解析
     */
    @ExceptionHandler({ MethodArgumentNotValidException.class, HttpMessageNotReadableException.class })
    public ResponseEntity<RestBean<Void>> badRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "Invalid Agent request");
    }

    /**
     * 处理「资源不存在」：会话被删除/过期、或要取消的 runId 不存在/不属于当前用户。
     */
    @ExceptionHandler({ AgentSessionNotFoundException.class, AgentRunNotFoundException.class })
    public ResponseEntity<RestBean<Void>> notFound(RuntimeException exception) {
        return response(HttpStatus.NOT_FOUND, "Agent session or run not found");
    }

    /**
     * 处理「会话互斥冲突」：同一会话同时只能有一个 run 在跑，
     * 用户在前一个 run 未结束时又发起新 run 就会走到这里。
     */
    @ExceptionHandler(AgentRunConflictException.class)
    public ResponseEntity<RestBean<Void>> conflict(AgentRunConflictException exception) {
        return response(HttpStatus.CONFLICT, "An Agent run is already active for this session");
    }

    /**
     * 兜底处理器：任何未被上面捕获的异常都走到这里。
     * 记录完整堆栈（方便排查），但返回给用户的只有一句安全提示。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<RestBean<Void>> serverError(Exception exception) {
        log.error("Agent API operation failed", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to start or restore Agent operation");
    }

    /**
     * 统一构造响应：把状态码 + 安全文案包装成 RestBean（code 取 HTTP 状态码）。
     */
    private ResponseEntity<RestBean<Void>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(RestBean.failure(status.value(), message));
    }
}
