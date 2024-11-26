package com.laxqnsys.core.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author shenrc
 * @date 2024/11/26 23:59
 */
@Getter
@AllArgsConstructor
public enum EquipmentTypeEnum {

    MOBLIE(1, "手机"),
    PC(2, "电脑"),
    ;

    private Integer code;

    private String desc;
}
