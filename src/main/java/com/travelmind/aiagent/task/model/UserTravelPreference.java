package com.travelmind.aiagent.task.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_travel_preference")
public class UserTravelPreference {
    @TableId
    private Long userId;
    private String preferencesJson;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
