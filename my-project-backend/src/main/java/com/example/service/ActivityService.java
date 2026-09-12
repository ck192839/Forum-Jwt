package com.example.service;

import com.example.entity.vo.response.ActivityOrderVO;
import com.example.entity.vo.response.ActivityVO;

import java.util.List;

public interface ActivityService {

    /**
     * 抢报名结果：
     * - code 200 ：已受理（排队中）或重复报名（幂等返回当前状态）
     * - 其它 code：不可报名（不在窗口内/名额已完/限流等），message 为提示文案
     */
    record GrabResult(int code, String message) {
        public static GrabResult queued() {
            return new GrabResult(200, "已提交，报名处理中，请稍后在「我的报名」查看结果");
        }

        public static GrabResult duplicate(String message) {
            return new GrabResult(200, message);
        }

        public static GrabResult reject(int code, String message) {
            return new GrabResult(code, message);
        }
    }

    List<ActivityVO> list();

    GrabResult grab(int uid, int activityId);

    List<ActivityOrderVO> myOrders(int uid);
}
