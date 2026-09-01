package com.alianga.jkit.http.codegen;

import com.alianga.jkit.http.curl.ParsedCurlRequest;

/**
 * 把解析后的请求变成某种语言 / 库的源码。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public interface CodeGenerator {
    /**
     * @return 稳定 id，例如 {@code java-okhttp}
     */
    String id();

    /**
     * @return 语言，例如 {@code java} / {@code python}
     */
    String language();

    /**
     * @return 库名，例如 {@code OkHttp 4}
     */
    String library();

    /**
     * @param request 解析模型
     * @return 是否支持
     */
    boolean supports(ParsedCurlRequest request);

    /**
     * @param request 解析模型
     * @return 生成结果
     */
    GeneratedCode generate(ParsedCurlRequest request);
}
