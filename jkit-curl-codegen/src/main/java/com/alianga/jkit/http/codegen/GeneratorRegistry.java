package com.alianga.jkit.http.codegen;

import com.alianga.jkit.http.codegen.csharp.CsharpHttpClientGenerator;
import com.alianga.jkit.http.codegen.go.GoNetHttpGenerator;
import com.alianga.jkit.http.codegen.har.HarGenerator;
import com.alianga.jkit.http.codegen.http.HttpMessageGenerator;
import com.alianga.jkit.http.codegen.java.ApacheHttpClientGenerator;
import com.alianga.jkit.http.codegen.java.HttpUrlConnectionGenerator;
import com.alianga.jkit.http.codegen.java.JavaUnirestGenerator;
import com.alianga.jkit.http.codegen.java.JdkHttpClientGenerator;
import com.alianga.jkit.http.codegen.java.JkitHttpGenerator;
import com.alianga.jkit.http.codegen.java.OkHttpGenerator;
import com.alianga.jkit.http.codegen.js.AxiosGenerator;
import com.alianga.jkit.http.codegen.js.FetchGenerator;
import com.alianga.jkit.http.codegen.js.JQueryGenerator;
import com.alianga.jkit.http.codegen.js.JsNativeGenerator;
import com.alianga.jkit.http.codegen.js.JsRequestGenerator;
import com.alianga.jkit.http.codegen.js.JsUnirestGenerator;
import com.alianga.jkit.http.codegen.js.XhrGenerator;
import com.alianga.jkit.http.codegen.kotlin.KotlinOkHttpGenerator;
import com.alianga.jkit.http.codegen.lua.LuaSocketHttpGenerator;
import com.alianga.jkit.http.codegen.php.PhpCurlGenerator;
import com.alianga.jkit.http.codegen.php.PhpGuzzleGenerator;
import com.alianga.jkit.http.codegen.php.PhpPeclHttpGenerator;
import com.alianga.jkit.http.codegen.powershell.PowerShellGenerator;
import com.alianga.jkit.http.codegen.python.HttpxGenerator;
import com.alianga.jkit.http.codegen.python.RequestsGenerator;
import com.alianga.jkit.http.codegen.r.RHttr2Generator;
import com.alianga.jkit.http.codegen.ruby.RubyHttpartyGenerator;
import com.alianga.jkit.http.codegen.ruby.RubyNetHttpGenerator;
import com.alianga.jkit.http.codegen.rust.RustReqwestGenerator;
import com.alianga.jkit.http.codegen.shell.CurlPowerShellGenerator;
import com.alianga.jkit.http.codegen.shell.CurlWindowsGenerator;
import com.alianga.jkit.http.codegen.shell.HttpieGenerator;
import com.alianga.jkit.http.codegen.shell.WgetGenerator;
import com.alianga.jkit.http.codegen.swift.SwiftUrlSessionGenerator;
import com.alianga.jkit.http.curl.ParsedCurlRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 代码生成器注册表。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class GeneratorRegistry {
    private static final GeneratorRegistry INSTANCE = new GeneratorRegistry().defaults().loadSpi();
    private final Map<String, CodeGenerator> generators = new LinkedHashMap<String, CodeGenerator>();

    /**
     * @return 全局注册表
     */
    public static GeneratorRegistry get() {
        return INSTANCE;
    }

    /**
     * @param generator 生成器
     * @return this
     */
    public GeneratorRegistry register(CodeGenerator generator) {
        if (generator == null || generator.id() == null) {
            throw new IllegalArgumentException("generator id is required");
        }
        generators.put(generator.id(), generator);
        return this;
    }

    /**
     * @param id 生成器 id
     * @return 生成器，不存在时为 {@code null}
     */
    public CodeGenerator get(String id) {
        return generators.get(id);
    }

    /**
     * @return 全部生成器
     */
    public List<CodeGenerator> list() {
        return Collections.unmodifiableList(new ArrayList<CodeGenerator>(generators.values()));
    }

    /**
     * @param language 语言
     * @return 该语言下的生成器
     */
    public List<CodeGenerator> list(String language) {
        if (language == null) {
            return list();
        }
        String key = language.toLowerCase(Locale.ROOT);
        List<CodeGenerator> out = new ArrayList<CodeGenerator>();
        for (CodeGenerator g : generators.values()) {
            if (key.equalsIgnoreCase(g.language())) {
                out.add(g);
            }
        }
        return out;
    }

    /**
     * @param id 生成器 id
     * @param request 解析模型
     * @return 源码
     */
    public GeneratedCode generate(String id, ParsedCurlRequest request) {
        CodeGenerator g = generators.get(id);
        if (g == null) {
            throw new IllegalArgumentException("unknown generator: " + id);
        }
        if (request == null || request.url() == null || request.url().isEmpty()) {
            throw new IllegalArgumentException("curl url is empty");
        }
        if (!g.supports(request)) {
            throw new IllegalArgumentException(id + " does not support this request");
        }
        return g.generate(request);
    }

    private GeneratorRegistry defaults() {
        register(new OkHttpGenerator());
        register(new ApacheHttpClientGenerator());
        register(new HttpUrlConnectionGenerator());
        register(new JavaUnirestGenerator());
        register(new JdkHttpClientGenerator());
        register(new JkitHttpGenerator());
        register(new KotlinOkHttpGenerator());
        register(new FetchGenerator());
        register(new AxiosGenerator());
        register(new JsRequestGenerator());
        register(new JsUnirestGenerator());
        register(new JsNativeGenerator());
        register(new JQueryGenerator());
        register(new XhrGenerator());
        register(new RequestsGenerator());
        register(new HttpxGenerator());
        register(new GoNetHttpGenerator());
        register(new CsharpHttpClientGenerator());
        register(new PhpCurlGenerator());
        register(new PhpPeclHttpGenerator());
        register(new PhpGuzzleGenerator());
        register(new PowerShellGenerator());
        register(new CurlWindowsGenerator());
        register(new CurlPowerShellGenerator());
        register(new WgetGenerator());
        register(new HttpieGenerator());
        register(new SwiftUrlSessionGenerator());
        register(new RubyNetHttpGenerator());
        register(new RubyHttpartyGenerator());
        register(new RustReqwestGenerator());
        register(new RHttr2Generator());
        register(new HttpMessageGenerator());
        register(new HarGenerator());
        register(new LuaSocketHttpGenerator());
        return this;
    }

    private GeneratorRegistry loadSpi() {
        for (CodeGenerator g : ServiceLoader.load(CodeGenerator.class)) {
            register(g);
        }
        return this;
    }
}
