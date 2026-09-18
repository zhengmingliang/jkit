package com.alianga.jkit.notify;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Markdown 主题渲染：主题解析、样式表、内联模式与代码高亮。
 */
public class MarkdownThemeTest {
    private static final String FENCE = "```";

    private static final String MD = "## 发布说明\n"
            + "\n"
            + "正文 **加粗** 与 `code`。\n"
            + "\n"
            + "> 引用一句\n"
            + "\n"
            + "- 列表项\n"
            + "\n"
            + FENCE + "java\n"
            + "// 注释\n"
            + "public class A {\n"
            + "}\n"
            + FENCE + "\n"
            + "\n"
            + "| 名称 | 值 |\n"
            + "| --- | --- |\n"
            + "| a | 1 |\n";

    /**
     * 12 个主题都能渲染，且样式表互不相同——防止配色复制粘贴后忘记改。
     */
    @Test
    public void everyThemeRendersDistinctCss() {
        Set<String> cssSet = new HashSet<String>();
        Set<String> primarySet = new HashSet<String>();
        for (MarkdownTheme theme : MarkdownTheme.values()) {
            String css = theme.css();
            assertTrue(theme.label() + " 样式表过短", css.length() > 400);
            assertTrue(css.contains("body{"));
            assertTrue(css.contains("pre{"));
            cssSet.add(css);
            primarySet.add(MarkdownStyle.of(theme).primary());
        }
        assertEquals("主题数量", 12, MarkdownTheme.values().length);
        assertEquals("样式表应各不相同", 12, cssSet.size());
        assertEquals("主色应各不相同", 12, primarySet.size());
    }

    /**
     * 深色主题的底色要真的是深色。
     */
    @Test
    public void darkThemeUsesDarkBackground() {
        String css = MarkdownTheme.AYER.css();
        assertTrue(css.contains("background:#1f2430"));
        assertTrue(Markdown.toDocument(MD,
                MarkdownRenderOptions.create().theme(MarkdownTheme.AYER)).contains("#1f2430"));
    }

    /**
     * 标识解析：忽略大小写与连字符，未知值回退经典主题。
     */
    @Test
    public void themeOfResolvesId() {
        assertEquals(MarkdownTheme.LARK, MarkdownTheme.of("lark"));
        assertEquals(MarkdownTheme.ORANGE_HEART, MarkdownTheme.of("ORANGE-HEART"));
        assertEquals(MarkdownTheme.ORANGE_HEART, MarkdownTheme.of("orangeheart"));
        assertEquals(MarkdownTheme.DEFAULT, MarkdownTheme.of("not-a-theme"));
        assertEquals(MarkdownTheme.DEFAULT, MarkdownTheme.of(null));
        assertEquals(MarkdownTheme.DEFAULT, MarkdownTheme.of("  "));
    }

    /**
     * {@code <style>} 模式：样式表进 head，正文保持裸标签。
     */
    @Test
    public void styleTagModeKeepsPlainTags() {
        String html = Markdown.toDocument(MD, MarkdownRenderOptions.create()
                .theme(MarkdownTheme.LARK));
        assertTrue(html.contains("<style>"));
        assertTrue(html.contains("#3370ff"));
        assertTrue(html.contains("<h2>发布说明</h2>"));
        assertTrue(html.contains("viewport"));
    }

    /**
     * 浏览器打开完整文档时，正文栏要落在页面中间：{@code body} 铺满视口，
     * {@code max-width} + {@code margin:0 auto} 只挂在容器 div 上。
     *
     * <p>文档壳给 {@code body} 写了内联 {@code margin:0}（铺满底色）。如果样式表
     * 再给 body 加 {@code max-width}，限宽会生效、居中外边距会被内联盖掉，栏就贴左边。
     */
    @Test
    public void documentColumnIsCentered() {
        String css = MarkdownTheme.LAPIS.css();
        int bodyAt = css.indexOf("body{");
        int bodyEnd = css.indexOf('}', bodyAt);
        String bodyRule = css.substring(bodyAt, bodyEnd + 1);
        assertTrue("body 铺满视口", bodyRule.contains("margin:0;padding:0"));
        assertTrue("body 不能限宽", !bodyRule.contains("max-width"));
        int divAt = css.indexOf("div.jkit-md{");
        int divEnd = css.indexOf('}', divAt);
        String divRule = css.substring(divAt, divEnd + 1);
        assertTrue("容器要限宽", divRule.contains("max-width:"));
        assertTrue("容器要水平居中", divRule.contains("margin:0 auto"));
        String html = Markdown.toDocument("## 标题",
                MarkdownRenderOptions.create().theme(MarkdownTheme.LAPIS));
        assertTrue(html.contains("<body style=\"margin:0;padding:0;background:"));
        assertTrue(html.contains("class=\"jkit-md\" style=\"max-width:"));
        assertTrue(html.contains("margin:0 auto"));
        int mobile = css.indexOf("@media (max-width:480px)");
        String media = css.substring(mobile);
        assertTrue("移动端只收容器内边距，不要连 body 一起垫",
                media.contains("div.jkit-md{padding:") && !media.contains("body,div.jkit-md"));
    }

    /**
     * 内联模式：块级标签都带上 style，且不产生 {@code </p style=...} 这种畸形闭合标签。
     */
    @Test
    public void inlineModeStylesBlockTags() {
        String html = Markdown.toHtml(MD, MarkdownRenderOptions.create()
                .theme(MarkdownTheme.VUE).inlineStyle(true));
        assertTrue(html.contains("<h2 style=\""));
        assertTrue(html.contains("<p style=\""));
        assertTrue(html.contains("<blockquote style=\""));
        assertTrue(html.contains("<ul style=\""));
        assertTrue(html.contains("<li style=\""));
        assertTrue(html.contains("<table style=\""));
        assertTrue(html.contains("<th style=\""));
        assertFalse("结束标签不能被上色", html.contains("</p style"));
        assertFalse("结束标签不能被上色", html.contains("</h2 style"));
        assertTrue("内联模式不该再输出 style 标签", !html.contains("<style>"));
    }

    /**
     * 内联模式下 {@code <pre>} 上色，但内部高亮 span 原样保留、{@code <code>} 不再套行内代码样式。
     */
    @Test
    public void inlineModeKeepsHighlightSpans() {
        String html = Markdown.toHtml(MD, MarkdownRenderOptions.create()
                .inlineStyle(true));
        assertTrue(html.contains("<pre style=\""));
        assertTrue(html.contains("<span style=\"color:#cf222e\">public</span>"));
        assertTrue("pre 内的 code 不该被当行内代码上色",
                html.contains("<code class=\"language-java\">"));
        assertFalse(html.contains("<code class=\"language-java\" style="));
    }

    /**
     * 代码高亮：关键字、注释分别着色；关掉高亮后没有 span。
     */
    @Test
    public void highlightColorsKeywordsAndComments() {
        String on = Markdown.toHtml(MD, MarkdownRenderOptions.create());
        assertTrue(on.contains("<span style=\"color:#cf222e\">public</span>"));
        assertTrue(on.contains("<span style=\"color:#cf222e\">class</span>"));
        assertTrue(on.contains("<span style=\"color:#6e7781\">// 注释</span>"));
        String off = Markdown.toHtml(MD, MarkdownRenderOptions.create().highlight(false));
        assertTrue(!off.contains("<span"));
        assertTrue(off.contains("public class A {"));
    }

    /**
     * 高亮输出仍然转义 HTML，代码块里写 {@code <script>} 不会变成真标签。
     */
    @Test
    public void highlightStillEscapesHtml() {
        String md = FENCE + "java\nif (a < b && c > d) { }\n" + FENCE;
        String html = Markdown.toHtml(md, MarkdownRenderOptions.create());
        assertTrue(html.contains("&lt;"));
        assertTrue(html.contains("&gt;"));
        assertTrue(!html.contains("<script"));
        assertTrue(!html.contains("a < b"));
    }

    /**
     * 不支持的语言退化为纯转义，不产生 span。
     */
    @Test
    public void unknownLanguageStaysPlain() {
        String md = FENCE + "text\nselect * from t where a < 1\n" + FENCE;
        String html = Markdown.toHtml(md, MarkdownRenderOptions.create());
        assertTrue(!html.contains("<span"));
        assertTrue(html.contains("&lt;"));
        String sql = Markdown.toHtml(FENCE + "sql\nselect * from t\n" + FENCE,
                MarkdownRenderOptions.create());
        assertTrue(sql.contains("<span style=\"color:#cf222e\">select</span>"));
    }

    /**
     * 2.0.1 入口仍可用：不带主题时输出裸标签，不加 style 属性。
     */
    @Test
    @SuppressWarnings("deprecation")
    public void legacyApiUnchanged() {
        String html = NotifyUtils.markdownToHtml(MD);
        assertTrue(html.contains("<h2>发布说明</h2>"));
        assertTrue(!html.contains("style="));
        String doc = NotifyUtils.markdownToDocument("## 标题", false);
        assertTrue(doc.contains("<h2>标题</h2>"));
        assertTrue(!doc.contains("@media"));
    }

    /**
     * 空内容不产生文档壳。
     */
    @Test
    public void emptyMarkdownReturnsEmptyDocument() {
        assertEquals("", Markdown.toDocument(null, MarkdownRenderOptions.create()));
        assertEquals("", Markdown.toDocument("", MarkdownRenderOptions.create()
                .theme(MarkdownTheme.LARK).inlineStyle(true)));
    }

    /**
     * macOS 窗口风格的代码块要给顶部圆点留出空间，圆点不能压在首行代码上。
     *
     * <p>内联模式下行内 {@code style} 会盖掉样式表的 {@code padding-top}，所以内联样式里
     * 必须自带完整的 padding，不能只写 {@code 12px 14px}。
     */
    @Test
    public void macCodeBlockReservesRoomForDots() {
        MarkdownTheme[] macThemes = {MarkdownTheme.BLUE, MarkdownTheme.VUE, MarkdownTheme.AYER};
        for (MarkdownTheme theme : macThemes) {
            String css = theme.css();
            assertTrue(theme.label() + " 样式表要给圆点留位", css.contains("padding:36px 14px 12px"));
            assertTrue(theme.label() + " 圆点上边距要小于留白", css.contains("top:13px;left:14px"));
            String inline = Markdown.toHtml(MD, MarkdownRenderOptions.create()
                    .theme(theme).inlineStyle(true));
            assertTrue(theme.label() + " 内联样式也要留位",
                    inline.contains("padding:36px 14px 12px"));
        }
    }

    /**
     * 移动端媒体查询不能把代码块的上内边距一起简写覆盖掉（MAC 风格会因此让圆点压住代码）。
     */
    @Test
    public void mobileCssDoesNotClobberPrePadding() {
        String css = MarkdownTheme.BLUE.css();
        int mobile = css.indexOf("@media (max-width:480px)");
        assertTrue("要有移动端媒体查询", mobile >= 0);
        String block = css.substring(mobile);
        assertTrue(block.contains("padding-left:10px"));
        assertTrue("不能用简写 padding 覆盖上内边距", !block.contains("pre{font-size:12px;padding:")
                && !block.contains("padding:10px}"));
    }

    /**
     * 内联模式下行内 style 优先级高于样式表，媒体查询必须带 !important 才能生效。
     */
    @Test
    public void mobileCssUsesImportantToWinInlineStyle() {
        for (MarkdownTheme theme : MarkdownTheme.values()) {
            String block = theme.css();
            int mobile = block.indexOf("@media (max-width:480px)");
            assertTrue(theme.label() + " 缺少移动端媒体查询", mobile >= 0);
            String media = block.substring(mobile);
            assertTrue(theme.label() + " 移动端字号要能压过内联样式",
                    media.contains("h1{font-size:1.45em !important}"));
            assertTrue(theme.label() + " 表格内边距要能压过内联样式",
                    media.contains("padding:6px 8px !important"));
        }
    }

    /**
     * 带引号装饰的引用：装饰符号放在左侧留白里，不与正文抢位置。
     */
    @Test
    public void quoteMarkSitsInGutter() {
        MarkdownTheme[] quoteThemes = {
                MarkdownTheme.PURPLE, MarkdownTheme.LAPIS, MarkdownTheme.ORANGE_HEART};
        for (MarkdownTheme theme : quoteThemes) {
            String css = theme.css();
            assertTrue(theme.label() + " 引用要给引号留左侧空位",
                    css.contains("padding:.4em 1em .4em 2.6em"));
            assertTrue(theme.label() + " 引号要落在留白区", css.contains("left:.5em"));
            String inline = Markdown.toHtml(MD, MarkdownRenderOptions.create()
                    .theme(theme).inlineStyle(true));
            assertTrue(theme.label() + " 内联模式同样要留位",
                    inline.contains("padding:.4em 1em .4em 2.6em"));
        }
    }
}
