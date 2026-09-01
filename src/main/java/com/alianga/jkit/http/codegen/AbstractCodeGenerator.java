package com.alianga.jkit.http.codegen;

import com.alianga.jkit.http.curl.ParsedCurlRequest;

/**
 * 默认支持任意解析结果的生成器基类。
 *
 * @author 郑明亮
 */
public abstract class AbstractCodeGenerator implements CodeGenerator {
    @Override
    public boolean supports(ParsedCurlRequest request) {
        return request != null;
    }
}
