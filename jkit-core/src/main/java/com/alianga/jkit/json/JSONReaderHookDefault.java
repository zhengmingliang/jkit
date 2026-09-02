package com.alianga.jkit.json;

import java.util.*;

/**
 * @time 2024/10/9 14:04
 */
public class JSONReaderHookDefault extends JSONReaderHook {
    /**
     * 构造默认的读取回调实例。
     */
    public JSONReaderHookDefault() {
    }

    @Override
    protected Map createdMap(String path) {
        return new LinkedHashMap();
    }

    @Override
    protected Collection<?> createdCollection(String path) {
        return new ArrayList();
    }

    @Override
    protected void parseValue(String key, Object value, Object host, int elementIndex, String path, int type)
            throws Exception {
        if (host instanceof Map) {
            ((Map) host).put(key, value);
        } else {
            ((List) host).add(value);
        }
    }
}
