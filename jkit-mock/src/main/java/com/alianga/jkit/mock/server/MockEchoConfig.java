package com.alianga.jkit.mock.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「返回请求参数」响应模式的配置：把入站请求的 query / body / header / path 变量
 * 组装成 JSON 回显。字段路径白名单为空表示全部返回；非空时只返回选中字段。
 *
 * <p>字段路径语法与模板占位符同源：{@code query.uid}、{@code body.user.name}
 * （数组下标 {@code body.items.0.id}）、{@code header.X-Token}（忽略大小写）、
 * {@code param.id}。请求体是 JSON 时按结构返回，否则作为原始字符串放在 {@code body} 字段。</p>
 *
 * <p>与响应模板兼容：路由仍可填写响应体模板，在需要回显 JSON 的位置写占位符
 * {@code {{request}}}，渲染时整体嵌入；模板留空则直接返回回显 JSON。</p>
 *
 * @author 郑明亮
 * @since 2.0.4
 */
public final class MockEchoConfig {
    /** 为 true 时启用本模式；不影响历史 {@link MockRoute#echo}（整包原样回显）。 */
    public final boolean enabled;
    /**
     * 字段路径白名单（保序去重）；为空表示返回全部（method/path/pathParams/query/headers/body）。
     */
    public final List<String> includes;

    private MockEchoConfig(Builder builder) {
        this.enabled = builder.enabled;
        Set<String> unique = new LinkedHashSet<>();
        if (builder.includes != null) {
            for (String path : builder.includes) {
                if (path != null && !path.trim().isEmpty()) {
                    unique.add(path.trim());
                }
            }
        }
        this.includes = Collections.unmodifiableList(new ArrayList<>(unique));
    }

    /** 关闭态（旧配置缺省）。 */
    public static MockEchoConfig disabled() {
        return builder().enabled(false).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        if (!enabled) {
            return "echo-request:off";
        }
        return includes.isEmpty()
                ? "echo-request:all"
                : "echo-request:" + includes;
    }

    /** {@link MockEchoConfig} 的 builder（JDK 8 兼容）。 */
    public static final class Builder {
        private boolean enabled;
        private List<String> includes = new ArrayList<>();

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder include(String path) {
            this.includes.add(path);
            return this;
        }

        public Builder includes(List<String> paths) {
            if (paths != null) {
                this.includes.addAll(paths);
            }
            return this;
        }

        public MockEchoConfig build() {
            return new MockEchoConfig(this);
        }
    }
}
