package com.alianga.jkit.mock.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 响应方案中的一条响应：独立的状态码 / 延迟 / Content-Type / body / 附加响应头，
 * 以及激活规则（全部规则命中才会选中本响应；都不中时回落到方案里的第一条响应）。
 *
 * @author 郑明亮
 * @see MockRoute#responses
 * @since 2.0.4
 */
public final class MockResponse {
    public final int statusCode;
    public final long latencyMs;
    public final String contentType;
    public final String body;
    /** 附加响应头（保序），值支持占位符。 */
    public final Map<String, String> headers;
    /** 激活规则；为空表示无条件（作为默认响应）。 */
    public final List<MockResponseRule> rules;
    /** 为 true 时 body 按 Mock.js 模板每次请求渲染。 */
    public final boolean dynamicTemplate;
    /** 路由挑选策略为 {@link MockPlanStrategy#RANDOM} 时的权重（至少 1）。 */
    public final int weight;

    private MockResponse(Builder builder) {
        this.statusCode = builder.statusCode;
        this.latencyMs = Math.max(0L, builder.latencyMs);
        this.contentType = builder.contentType;
        this.body = builder.body;
        Map<String, String> headerCopy = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : builder.headers.entrySet()) {
            if (e.getKey() != null && !e.getKey().trim().isEmpty()) {
                headerCopy.put(e.getKey().trim(), e.getValue() == null ? "" : e.getValue());
            }
        }
        this.headers = Collections.unmodifiableMap(headerCopy);
        List<MockResponseRule> ruleCopy = new ArrayList<>(builder.rules);
        this.rules = Collections.unmodifiableList(ruleCopy);
        this.dynamicTemplate = builder.dynamicTemplate;
        this.weight = Math.max(1, builder.weight);
    }

    /** 随机权重；未设置时为 1（等权重）。 */
    public int getWeight() {
        return weight;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** {@link MockResponse} 的 builder。 */
    public static final class Builder {
        private int statusCode = 200;
        private long latencyMs;
        private String contentType = "application/json; charset=utf-8";
        private String body = "";
        private Map<String, String> headers = new LinkedHashMap<>();
        private List<MockResponseRule> rules = new ArrayList<>();
        private boolean dynamicTemplate;
        private int weight = 1;

        /** 随机挑选权重（{@link MockPlanStrategy#RANDOM}），至少 1；默认 1。 */
        public Builder weight(int value) {
            this.weight = value;
            return this;
        }

        public Builder statusCode(int value) {
            this.statusCode = value;
            return this;
        }

        public Builder latencyMs(long value) {
            this.latencyMs = value;
            return this;
        }

        public Builder contentType(String value) {
            this.contentType = value;
            return this;
        }

        public Builder body(String value) {
            this.body = value;
            return this;
        }

        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        public Builder headers(Map<String, String> values) {
            if (values != null) {
                this.headers.putAll(values);
            }
            return this;
        }

        public Builder rule(MockResponseRule rule) {
            this.rules.add(rule);
            return this;
        }

        public Builder rules(List<MockResponseRule> values) {
            if (values != null) {
                this.rules.addAll(values);
            }
            return this;
        }

        public Builder dynamicTemplate(boolean value) {
            this.dynamicTemplate = value;
            return this;
        }

        public MockResponse build() {
            return new MockResponse(this);
        }
    }
}
