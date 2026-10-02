package com.alianga.jkit.mock.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 长连接（SSE / WebSocket）路由的推送策略：决定消息何时推送、推几次。
 * 消息内容本身仍由路由 {@code body} 承载（支持占位符与 Mock.js 无关的纯占位符渲染），
 * 本类只描述「时机」。
 *
 * <ul>
 *   <li>{@link Mode#ONCE}：连接建立后延迟 {@link #initialDelayMs} 推送一次，之后保持连接；</li>
 *   <li>{@link Mode#INTERVAL}：从 {@link #initialDelayMs} 起，每隔 {@link #intervalMs}
 *       推一帧，{@link #repeat} 为 0 表示不限次（直到客户端断开 / 路由禁用 / 服务停止）；</li>
 *   <li>{@link Mode#EVENT}：仅 WebSocket 可用（SSE 无客户端上行通道）；收到客户端消息即触发
 *       一帧，{@link #events} 为空表示任意消息都触发，非空时消息包含任一关键字才触发。
 *       触发消息原文可用模板占位符 {@code {{event}}} 引用。</li>
 * </ul>
 *
 * <p>{@code eventName} 仅 SSE 使用：非空时帧以 {@code event: <name>} 投递。</p>
 *
 * @author 郑明亮
 * @since 2.0.4
 */
public final class MockPushPolicy {
    /** 推送时机。 */
    public enum Mode {
        /** 连接建立后推送一次。 */
        ONCE,
        /** 按固定间隔反复推送。 */
        INTERVAL,
        /** 收到客户端消息（WebSocket）后推送。 */
        EVENT
    }

    public final Mode mode;
    /** 首帧延迟（毫秒）。 */
    public final long initialDelayMs;
    /** INTERVAL 模式的帧间隔（毫秒）。 */
    public final long intervalMs;
    /** INTERVAL 模式推送次数；0 表示不限次。 */
    public final int repeat;
    /** EVENT 模式触发关键字（子串匹配）；空表表示任意消息触发。 */
    public final List<String> events;
    /** SSE 事件名（{@code event:} 字段）；空串表示不写 event 行。WebSocket 忽略。 */
    public final String eventName;

    private MockPushPolicy(Builder builder) {
        this.mode = builder.mode == null ? Mode.ONCE : builder.mode;
        this.initialDelayMs = Math.max(0L, builder.initialDelayMs);
        this.intervalMs = Math.max(1L, builder.intervalMs);
        this.repeat = Math.max(0, builder.repeat);
        Set<String> unique = new LinkedHashSet<>();
        if (builder.events != null) {
            for (String event : builder.events) {
                if (event != null && !event.isEmpty()) {
                    unique.add(event);
                }
            }
        }
        this.events = Collections.unmodifiableList(new ArrayList<>(unique));
        this.eventName = builder.eventName == null ? "" : builder.eventName.trim();
    }

    /** 缺省策略：连接建立立即推一次。 */
    public static MockPushPolicy once() {
        return builder().mode(Mode.ONCE).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 收到客户端消息时是否应触发推送（EVENT 模式）；未配置关键字时任意非空消息触发。
     *
     * @param message 客户端消息文本
     * @return true 表示应推送一帧
     */
    public boolean matchesEvent(String message) {
        if (mode != Mode.EVENT || message == null || message.isEmpty()) {
            return false;
        }
        if (events.isEmpty()) {
            return true;
        }
        for (String keyword : events) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        switch (mode) {
            case INTERVAL:
                return "push:interval " + intervalMs + "ms"
                        + (repeat > 0 ? " x" + repeat : "")
                        + (initialDelayMs > 0 ? " after " + initialDelayMs + "ms" : "");
            case EVENT:
                return events.isEmpty() ? "push:event(*)" : "push:event" + events;
            default:
                return "push:once" + (initialDelayMs > 0 ? " after " + initialDelayMs + "ms" : "");
        }
    }

    /** {@link MockPushPolicy} 的 builder（JDK 8 兼容）。 */
    public static final class Builder {
        private Mode mode = Mode.ONCE;
        private long initialDelayMs;
        private long intervalMs = 1000L;
        private int repeat;
        private List<String> events = new ArrayList<>();
        private String eventName = "";

        public Builder mode(Mode value) {
            this.mode = value;
            return this;
        }

        public Builder initialDelayMs(long value) {
            this.initialDelayMs = value;
            return this;
        }

        public Builder intervalMs(long value) {
            this.intervalMs = value;
            return this;
        }

        public Builder repeat(int value) {
            this.repeat = value;
            return this;
        }

        public Builder event(String keyword) {
            this.events.add(keyword);
            return this;
        }

        public Builder events(List<String> values) {
            if (values != null) {
                this.events.addAll(values);
            }
            return this;
        }

        public Builder eventName(String value) {
            this.eventName = value == null ? "" : value;
            return this;
        }

        public MockPushPolicy build() {
            return new MockPushPolicy(this);
        }
    }
}
