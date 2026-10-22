package com.alianga.jkit.storage.engine.cmcloud;

import com.alianga.jkit.json.JSON;

import java.util.Map;

/**
 * IDaaS 授权栈的最小 JSON 读写：对象 ↔ {@code Map<String, Object>}。
 * 复用宿主自带的 {@code com.alianga.jkit.json.JSON}，不引第三方 JSON 库。
 *
 * @author 郑明亮
 */
final class CmCloudIdaasJson {
    private CmCloudIdaasJson() {
    }

    /** @return JSON 字符串；序列化失败按运行时异常上抛（调用方有兜底） */
    static String toJson(Object source) {
        return JSON.toJsonString(source);
    }

    /**
     * @param json JSON 文本
     * @return 对象；非对象或解析失败返回 null（凭据文件损坏按「无凭据」处理）
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseMap(String json) {
        if (json == null || Strs.isBlank(json)) {
            return null;
        }
        try {
            Object parsed = JSON.parse(json);
            if (!(parsed instanceof Map)) {
                return null;
            }
            return (Map<String, Object>) parsed;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
