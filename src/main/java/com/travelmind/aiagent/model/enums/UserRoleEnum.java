package com.travelmind.aiagent.model.enums;

import lombok.Getter;

/**
 * 用户角色枚举
 */
@Getter
public enum UserRoleEnum {

    /**
     * 普通用户
     */
    USER("user", "普通用户"),

    /**
     * 管理员
     */
    ADMIN("admin", "管理员");

    private final String value;
    private final String text;

    UserRoleEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    /**
     * 根据 value 获取枚举
     */
    public static UserRoleEnum getEnumByValue(String value) {
        if (value == null) {
            return null;
        }
        for (UserRoleEnum userRoleEnum : UserRoleEnum.values()) {
            if (userRoleEnum.value.equals(value)) {
                return userRoleEnum;
            }
        }
        return null;
    }
}

