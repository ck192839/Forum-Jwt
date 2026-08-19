package com.example.agent.run;

/**
 * run_completed 事件负载：运行结束。
 * status 取值：COMPLETED（正常）/ CANCELLED（用户取消）/ FAILED（出错）。
 */
public record RunCompletedPayload(String runId, String status) {
}
