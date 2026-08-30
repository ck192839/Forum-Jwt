package com.example.entity;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.MDC;

import java.util.List;
import java.util.Optional;

public record PageRestBean<T>(long id, int code, PageList<T> data, String message) {

    record PageList<T>(List<T> list, long total, long page, long blocked) {
    }

    public static <T> PageRestBean<T> success(List<T> list, long total, long page) {
        return success(list, total, page, 0);
    }

    /** blocked 为可选的附加统计（如被屏蔽帖子数），不关心的调用方传 0 即可 */
    public static <T> PageRestBean<T> success(List<T> list, long total, long page, long blocked) {
        return new PageRestBean<>(requestId(), 200, new PageList<>(list, total, page, blocked), "请求成功");
    }

    public static <T> PageRestBean<T> success(Page<T> page) {
        return success(page.getRecords(), page.getTotal(), page.getCurrent());
    }

    private static long requestId() {
        String requestId = Optional.ofNullable(MDC.get("reqId")).orElse("0");
        return Long.parseLong(requestId);
    }
}
