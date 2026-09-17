package com.alianga.jkit.notify;

import java.util.Locale;

/**
 * Markdown → HTML 的渲染主题。
 *
 * <p>覆盖告警、周报、对外推送等常见排版口味，命名与观感对齐 doocs/md：
 * {@link #DEFAULT} 经典、{@link #LARK} 蓝、{@link #ORANGE_HEART} 橙心、{@link #RAINBOW} 彩虹、
 * {@link #LAPIS} 兰青、{@link #PHYCAT} 嫩黄、{@link #BLUE} 碧蓝、{@link #VUE} Vue 绿、
 * {@link #GREEN} 绿意、{@link #WHEAT} 麦色、{@link #AYER} 墨黑、{@link #PURPLE} 姹紫。
 *
 * <p>主题只影响外观，不改变 Markdown 解析结果。用法：
 * <pre>{@code
 * String html = NotifyUtils.markdownToDocument(md,
 *         MarkdownRenderOptions.create().theme(MarkdownTheme.LARK).inlineStyle(true));
 * }</pre>
 *
 * <p>推荐按投放目标选：邮件正文走 {@code <style>} 模式即可；复制到微信、发往剥离
 * {@code <style>} 标签的邮件客户端（Outlook、部分企业邮箱）时打开 {@code inlineStyle}。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public enum MarkdownTheme {
    /**
     * 经典：GitHub 观感，灰蓝主色、网格表格。
     */
    DEFAULT("default", "经典"),

    /**
     * 蓝：飞书蓝，标题竖条、圆角卡片。
     */
    LARK("lark", "蓝"),

    /**
     * 橙心：暖橙主色，一级标题居中，斑马纹表格。
     */
    ORANGE_HEART("orangeheart", "橙心"),

    /**
     * 彩虹：玫红主色，分割线为四色渐变。
     */
    RAINBOW("rainbow", "彩虹"),

    /**
     * 兰青：青绿主色，极简表格。
     */
    LAPIS("lapis", "兰青"),

    /**
     * 嫩黄：浅黄底、大圆角，偏轻快。
     */
    PHYCAT("phycat", "嫩黄"),

    /**
     * 碧蓝：深蓝主色，代码块为 macOS 窗口风格。
     */
    BLUE("blue", "碧蓝"),

    /**
     * Vue 绿：主色取 Vue 品牌色。
     */
    VUE("vue", "Vue 绿"),

    /**
     * 绿意：清新绿，斑马纹表格。
     */
    GREEN("green", "绿意"),

    /**
     * 麦色：米黄底 + 衬线字体，偏阅读。
     */
    WHEAT("wheat", "麦色"),

    /**
     * 墨黑：深色底 + 深色代码配色，适合夜间/大屏展示。
     */
    AYER("ayer", "墨黑"),

    /**
     * 姹紫：紫色主色，标题居中。
     */
    PURPLE("purple", "姹紫");

    private final String id;
    private final String label;
    private MarkdownStyle style;

    MarkdownTheme(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /**
     * 主题标识，用于配置化取值（{@code smtp.markdownTheme=lark}）。
     *
     * @return 小写英文标识
     */
    public String id() {
        return id;
    }

    /**
     * 中文名，便于在控制台/文档里展示。
     *
     * @return 主题中文名
     */
    public String label() {
        return label;
    }

    /**
     * 主题的样式表（含移动端 / 桌面端媒体查询）。
     *
     * @return CSS 文本
     */
    public String css() {
        return MarkdownStyle.of(this).css(true);
    }

    /**
     * 按标识解析主题，忽略大小写与 {@code -} / {@code _} 差异。
     *
     * <p>也接受枚举常量名（如 {@code ORANGE_HEART}）。无法识别时回退 {@link #DEFAULT}，
     * 不抛异常——渠道配置里写错主题不该让整条告警发不出去。
     *
     * @param id 标识或常量名，{@code null} / 空串返回 {@link #DEFAULT}
     * @return 主题
     */
    public static MarkdownTheme of(String id) {
        if (id == null) {
            return DEFAULT;
        }
        String key = id.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (key.isEmpty()) {
            return DEFAULT;
        }
        for (MarkdownTheme theme : values()) {
            if (theme.id.equals(key) || theme.name().equalsIgnoreCase(key)) {
                return theme;
            }
        }
        return DEFAULT;
    }

    MarkdownStyle styleOf() {
        return style;
    }

    MarkdownStyle store(MarkdownStyle built) {
        style = built;
        return built;
    }
}
