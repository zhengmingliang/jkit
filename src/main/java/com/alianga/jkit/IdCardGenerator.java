package com.alianga.jkit;

import java.util.Map;

/**
 * @deprecated 请使用 {@link IdCardUtils}。本类仅保留兼容入口。
 */
@Deprecated
public class IdCardGenerator extends IdCardUtils {
    /**
     * 地区名 → 代码。同名区县只保留一个代码，解析请改用 {@link IdCardUtils#getAreaName(int)}。
     *
     * @deprecated 名称可能重复，已改为按代码索引
     */
    @Deprecated
    public static final Map<String, Integer> areaCode = IdCardUtils.legacyNameToCode();

    private IdCardGenerator() {
    }
}
