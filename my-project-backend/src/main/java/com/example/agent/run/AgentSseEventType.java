package com.example.agent.run;

/**
 * Agent SSE 事件的类型枚举（wire 名与前端约定的字符串一一对应）。
 *
 * 事件全集：
 * - run_started ：运行开始（携带 runId）
 * - message_delta ：模型流式文本增量（当前实现预留，状态机保留）
 * - tool_started ：工具开始执行（前端时间线）
 * - tool_completed ：工具执行完成
 * - citation ：一条引用帖子
 * - question ：Agent 向用户追问
 * - draft_ready ：草稿就绪（前端展示并可应用）
 * - run_completed ：运行结束（status = COMPLETED / CANCELLED / FAILED）
 * - error ：运行出错（code + 安全 message）
 *
 * 约定：wireName 是落库和传输用的字符串，枚举值是代码内使用；
 * fromWireName 用于把库里的事件类型还原成枚举（恢复会话时用）。
 */
public enum AgentSseEventType {
    RUN_STARTED("run_started"),
    MESSAGE_DELTA("message_delta"),
    TOOL_STARTED("tool_started"),
    TOOL_COMPLETED("tool_completed"),
    CITATION("citation"),
    QUESTION("question"),
    DRAFT_READY("draft_ready"),
    RUN_COMPLETED("run_completed"),
    ERROR("error");

    private final String wireName;

    AgentSseEventType(String wireName) {
        this.wireName = wireName;
    }

    /** 获取传输/存储用的字符串名。 */
    public String wireName() {
        return wireName;
    }

    /** 由字符串名反查枚举；未知类型抛异常（防御脏数据）。 */
    public static AgentSseEventType fromWireName(String wireName) {
        for (AgentSseEventType type : values()) {
            if (type.wireName.equals(wireName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown Agent event type: " + wireName);
    }
}
