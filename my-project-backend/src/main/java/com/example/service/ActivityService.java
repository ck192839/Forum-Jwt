package com.example.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.entity.dto.Activity;
import com.example.entity.vo.request.ActivityAdminSaveVO;
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

    /** 管理端：分页 + 标题关键字。 */
    Page<Activity> adminList(int page, int size, String keyword);

    /** 管理端：新建（id 为空）或编辑，返回 null 成功、否则为错误信息。 */
    String adminSave(ActivityAdminSaveVO vo);

    /** 管理端：删除（已有报名记录时拒绝），返回 null 成功、否则为错误信息。 */
    String adminDelete(int id);

    /** 管理端：上架/下架。 */
    void adminSetStatus(int id, boolean on);
}
