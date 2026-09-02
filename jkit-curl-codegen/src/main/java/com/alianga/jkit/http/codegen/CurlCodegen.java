package com.alianga.jkit.http.codegen;

import com.alianga.jkit.http.CurlParser;
import com.alianga.jkit.http.curl.ParsedCurlRequest;

import java.util.List;

/**
 * curl 转其它语言 / HTTP 库源码的入口。
 *
 * <p>解析仍走 {@link CurlParser#parseModel(String)}（位于 jkit），本类只负责挑选生成器并输出源码。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class CurlCodegen {
    private CurlCodegen() {
    }

    /**
     * 按生成器 id 把 curl 命令转成源码。
     *
     * @param generatorId 如 {@code java-okhttp}、{@code js-fetch}
     * @param curl 完整命令
     * @return 生成结果
     */
    public static GeneratedCode generate(String generatorId, String curl) {
        return generate(generatorId, CurlParser.parseModel(curl));
    }

    /**
     * 按生成器 id 把已解析的请求转成源码。
     *
     * @param generatorId 如 {@code java-okhttp}、{@code js-fetch}
     * @param request 解析模型
     * @return 生成结果
     */
    public static GeneratedCode generate(String generatorId, ParsedCurlRequest request) {
        return GeneratorRegistry.get().generate(generatorId, request);
    }

    /**
     * @return 全部已注册生成器
     */
    public static List<CodeGenerator> list() {
        return GeneratorRegistry.get().list();
    }

    /**
     * @param language 语言，例如 {@code java} / {@code python}；{@code null} 时等同 {@link #list()}
     * @return 该语言下的生成器
     */
    public static List<CodeGenerator> list(String language) {
        return GeneratorRegistry.get().list(language);
    }
}
