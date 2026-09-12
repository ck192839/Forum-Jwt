package com.example.entity.vo.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Date;

/**
 * 管理端创建/编辑活动。id 为空表示新建；grabbed 由报名链路维护，此处不可修改。
 */
@Data
public class ActivityAdminSaveVO {
    Integer id;

    @NotBlank(message = "活动标题不能为空")
    String title;

    @NotBlank(message = "活动描述不能为空")
    String description;

    @NotBlank(message = "活动地点不能为空")
    String location;

    @NotNull(message = "活动时间不能为空")
    Date activityTime;

    @NotNull(message = "名额数量不能为空")
    @Min(value = 1, message = "名额数量至少为 1")
    Integer totalStock;

    @NotNull(message = "报名开始时间不能为空")
    Date grabStartTime;

    @NotNull(message = "报名截止时间不能为空")
    Date grabEndTime;
}
