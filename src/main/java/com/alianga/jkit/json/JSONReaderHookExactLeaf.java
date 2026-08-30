package com.alianga.jkit.json;

import com.alianga.jkit.beans.ObjectUtils;
import com.alianga.jkit.reflect.GenericParameterizedType;

/**
 * @time 2024/10/9 14:04
 */
final class JSONReaderHookExactLeaf extends JSONReaderHook {
    private final String exactPath;
    private final GenericParameterizedType<?> parameterizedType;

    public JSONReaderHookExactLeaf(String exactPath, GenericParameterizedType<?> parameterizedType) {
        this.exactPath = exactPath;
        this.parameterizedType = parameterizedType;
        parameterizedType.getClass();
    }

    protected boolean filter(String path, int type) {
        return true;
    }

    @Override
    protected void parseValue(String key, Object value, Object host, int elementIndex, String path, int type)
            throws Exception {
        if (type > 2 && path.equals(exactPath)) {
            results.add(ObjectUtils.toType(value, parameterizedType.getActualType(),
                    parameterizedType.getActualClassCategory()));
            abort();
        }
    }
}
