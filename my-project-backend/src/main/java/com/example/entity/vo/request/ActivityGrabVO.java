package com.example.entity.vo.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ActivityGrabVO {
    @NotNull(message = "活动 id 不能为空")
    Integer activityId;
}
