package com.alianga.jkit.notify;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * YAZI（速查）主题：结构重排、参考站点样式令牌、属性注释与样例渲染。
 *
 * <p>渲染产物写入 {@code target/markdown-samples/}，同时生成 {@code index.html}，
 * 便于用浏览器逐份检查；断言覆盖结构、样式令牌与属性注释处理。
 */
public class MarkdownYaziThemeTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String SAMPLE = "指南\n"
            + "===\n"
            + "\n"
            + "简介段落。\n"
            + "\n"
            + "入门\n"
            + "----\n"
            + "\n"
            + "### 介绍\n"
            + "\n"
            + "正文 **加粗** 与 `code`。\n"
            + "\n"
            + "### 安装\n"
            + "<!--rehype:wrap-class=col-span-2 row-span-2-->\n"
            + "\n"
            + "系统 | 命令\n"
            + ":- | :-\n"
            + "Debian | `apt install jkit`\n"
            + "<!--rehype:className=show-header left-align-->\n"
            + "\n"
            + "### 命令\n"
            + "\n"
            + "```shell\n"
            + "$ jkit --version\n"
            + "```\n";

    /**
     * 主题标识与别名。
     */
    @Test
    public void yaziThemeResolvesAliases() {
        assertEquals(MarkdownTheme.YAZI, MarkdownTheme.of("yazi"));
        assertEquals(MarkdownTheme.YAZI, MarkdownTheme.of("YAZI"));
        assertEquals(MarkdownTheme.YAZI, MarkdownTheme.of("quickref"));
        assertEquals(MarkdownTheme.YAZI, MarkdownTheme.of("reference"));
    }

    /**
     * 结构要与 refs-cli 一致：H1 页头、H2 分区、H3 卡片网格、标题锚点。
     */
    @Test
    public void yaziRebuildsDocumentStructure() {
        String html = Markdown.toHtml(SAMPLE, MarkdownRenderOptions.create()
                .theme(MarkdownTheme.YAZI));
        assertTrue(html.startsWith("<div class=\"jkit-yazi\">"));
        assertTrue(html.contains("<div class=\"wrap h1body-exist\">"));
        assertTrue(html.contains("<header class=\"wrap-header h1wrap\">"));
        assertTrue(html.contains("<div class=\"h1wrap-body\">"));
        assertTrue(html.contains("<div class=\"wrap h2body-exist\">"));
        assertTrue(html.contains("<div class=\"h2wrap-body\">"));
        assertTrue(html.contains("<div class=\"wrap h3body-not-exist col-span-2 row-span-2\">"));
        assertTrue(html.contains("<div class=\"wrap h3body-not-exist\">"));
        assertTrue(html.contains("<h1 id=\"指南\">"));
        assertTrue(html.contains("<h2 id=\"入门\">"));
        assertTrue(html.contains("<h3 id=\"介绍\">"));
        assertTrue(html.contains("<a aria-hidden=\"true\" tabindex=\"-1\" href=\"#介绍\">"
                + "<span class=\"icon icon-link\"></span></a>"));
        assertTrue(html.contains("</div></div></div>"));
    }

    /**
     * 表格属性注释要落到 table 上，注释本身不能变成可见文本。
     */
    @Test
    public void yaziAppliesRehypeTableAttributes() {
        String html = Markdown.toHtml(SAMPLE, MarkdownRenderOptions.create()
                .theme(MarkdownTheme.YAZI));
        assertTrue(html.contains("<table class=\"show-header left-align\">"));
        assertFalse(html.contains("rehype:"));
        assertFalse(html.contains("&lt;!--rehype"));
        assertFalse(html.contains("data-wrap-class"));
    }

    /**
     * 围栏代码块要带 Prism 风格的 pre/code 类名，且高亮仍在。
     */
    @Test
    public void yaziStylesCodeBlocksLikeReference() {
        String html = Markdown.toHtml(SAMPLE, MarkdownRenderOptions.create()
                .theme(MarkdownTheme.YAZI));
        assertTrue(html.contains("<pre class=\"language-shell\">"
                + "<code class=\"language-shell code-highlight\">"));
        assertTrue(html.contains("$ jkit --version"));
    }

    /**
     * 样式令牌对齐参考站点：浅灰底、白色卡片、绿色标签、隐藏表头、键帽。
     */
    @Test
    public void yaziCssMatchesQuickRefTokens() {
        String css = MarkdownTheme.YAZI.css();
        assertTrue(css.contains("background:#f1f5f9"));
        assertTrue(css.contains("background:#228e6c"));
        assertTrue(css.contains("color:#10b981"));
        assertTrue(css.contains("rgba(236,236,236,.58)"));
        assertTrue(css.contains("table thead{display:none"));
        assertTrue(css.contains("table.show-header thead{display:table-header-group}"));
        assertTrue(css.contains("grid-template-columns:repeat(3,minmax(0,1fr))"));
        assertTrue(css.contains("font-size:30px"));
        assertTrue(css.contains("font-weight:200"));
        assertTrue(css.contains("background:rgba(175,184,193,.2)"));
        assertTrue(css.contains(".jkit-yazi .h3wrap > h3{position:sticky;top:0;float:right"));
    }

    /**
     * {@code responsive(false)} 要真正去掉断点，不能把媒体查询留在结构化样式里。
     */
    @Test
    public void yaziResponsiveCanBeTurnedOff() {
        assertTrue(MarkdownStyle.of(MarkdownTheme.YAZI).css(true)
                .contains("@media (min-width:1024px)"));
        String css = MarkdownStyle.of(MarkdownTheme.YAZI).css(false);
        assertFalse(css.contains("@media (min-width:1024px)"));
        assertFalse(css.contains("@media (max-width:480px)"));
        String document = Markdown.toDocument(SAMPLE, MarkdownRenderOptions.create()
                .theme(MarkdownTheme.YAZI).responsive(false));
        assertFalse(document.contains("viewport"));
        assertFalse(document.contains("@media"));
    }

    /**
     * 主题切换不能改变 Markdown 解析结果：同样的文本、同样的标题层级。
     */
    @Test
    public void yaziKeepsParsedContent() {
        String plain = Markdown.toHtml(SAMPLE);
        String yazi = Markdown.toHtml(SAMPLE, MarkdownRenderOptions.create()
                .theme(MarkdownTheme.YAZI));
        assertTrue(plain.contains("<h1>指南</h1>"));
        assertTrue(plain.contains("<h2>入门</h2>"));
        assertTrue(plain.contains("<h3>介绍</h3>"));
        assertTrue(yazi.contains(">指南</h1>"));
        assertTrue(yazi.contains(">入门</h2>"));
        assertTrue(yazi.contains(">介绍</h3>"));
        assertTrue(yazi.contains("正文 <strong>加粗</strong> 与 <code>code</code>。"));
    }

    /**
     * Setext 标题（标题 + === / ---）在普通主题与速查主题下都要生效。
     */
    @Test
    public void setextHeadingsWorkForAllThemes() {
        assertEquals("<h1>标题</h1>", Markdown.toHtml("标题\n==="));
        assertEquals("<h2>小节</h2>", Markdown.toHtml("小节\n---"));
        String yazi = Markdown.toHtml("标题\n===\n\n小节\n---\n",
                MarkdownRenderOptions.create().theme(MarkdownTheme.YAZI));
        assertTrue(yazi.contains(">标题</h1>"));
        assertTrue(yazi.contains(">小节</h2>"));
    }

    /**
     * 忽略块整段删除，不留下注释或内容。
     */
    @Test
    public void rehypeIgnoreBlockIsRemoved() {
        String md = "开头\n\n<!--rehype:ignore:start-->\n隐藏内容\n"
                + "<!--rehype:ignore:end-->\n\n结尾\n";
        String html = Markdown.toHtml(md);
        assertTrue(html.contains("开头"));
        assertTrue(html.contains("结尾"));
        assertFalse(html.contains("隐藏内容"));
        assertFalse(html.contains("ignore:"));
    }

    /**
     * 锚点 id 按 github-slugger 去重。
     */
    @Test
    public void yaziSluggerDeduplicates() {
        String md = "标题\n===\n\n## 重复\n\n## 重复\n\n## Windows 注意事项\n"
                + "\n## keymap.toml 层\n";
        String html = Markdown.toHtml(md, MarkdownRenderOptions.create()
                .theme(MarkdownTheme.YAZI));
        assertTrue(html.contains("id=\"重复\""));
        assertTrue(html.contains("id=\"重复-1\""));
        assertTrue(html.contains("id=\"windows-注意事项\""));
        assertTrue(html.contains("id=\"keymaptoml-层\""));
    }

    /**
     * 邮件模式：卡片、标签、网格全部写成内联样式，不依赖类名与样式表。
     */
    @Test
    public void yaziInlineModeBuildsEmailSafeLayout() {
        String fragment = Markdown.toHtml(SAMPLE, MarkdownRenderOptions.create()
                .theme(MarkdownTheme.YAZI).inlineStyle(true));
        assertTrue(fragment.startsWith("<div class=\"jkit-yazi\" style=\""));
        assertTrue(fragment.contains("<div class=\"wrap h1body-exist\">"));
        assertTrue(fragment.contains("<div class=\"wrap h2body-exist\" style=\"margin:0 0 28px\">"));
        assertTrue(fragment.contains("display:flex;flex-wrap:wrap"));
        assertTrue(fragment.contains("background:#fff"));
        assertTrue(fragment.contains("float:right"));
        assertTrue(fragment.contains("<thead style=\"display:table-header-group"));
        assertTrue(fragment.contains("<span style=\"color:#10b981;padding-right:6px\">#</span>"));
        String document = Markdown.toDocument(SAMPLE, MarkdownRenderOptions.create()
                .theme(MarkdownTheme.YAZI).inlineStyle(true));
        assertFalse("邮件安全版不应再输出样式表", document.contains("<style>"));
        assertTrue(document.contains("background:#fff"));
    }

    /**
     * 模拟邮件客户端过滤 class：去掉全部 class 后，卡片与标签样式仍由内联样式生效。
     */
    @Test
    public void yaziInlineLayoutSurvivesClassStripping() {
        String html = Markdown.toHtml(SAMPLE, MarkdownRenderOptions.create()
                .theme(MarkdownTheme.YAZI).inlineStyle(true));
        String stripped = html.replaceAll(" class=\"[^\"]*\"", "");
        assertFalse(stripped.contains("class=\""));
        assertTrue(stripped.contains("background:#fff"));
        assertTrue(stripped.contains("float:right"));
        assertTrue(stripped.contains("display:flex"));
        assertTrue(stripped.contains("border-bottom:1px solid #ececec"));
        assertTrue(stripped.contains("color:#10b981"));
    }

    /**
     * 邮件模式的表格：默认隐藏表头、show-header 显示、shortcuts 首列变键帽。
     */
    @Test
    public void yaziInlineTablesHonorReferenceClasses() {
        String md = "标题\n===\n\n## 分区\n\n### 普通表\n\n"
                + "名称 | 值\n:- | :-\na | b\n\n"
                + "### 显示表头\n\n名称 | 值\n:- | :-\nx | y\n"
                + "<!--rehype:className=show-header-->\n\n"
                + "### 键位\n\n键位 | 操作\n:- | :-\n`h` | home\n"
                + "<!--rehype:className=shortcuts left-align-->\n";
        String html = Markdown.toHtml(md, MarkdownRenderOptions.create()
                .theme(MarkdownTheme.YAZI).inlineStyle(true));
        assertTrue(html.contains("<thead style=\"display:none\">"));
        assertTrue(html.contains("<thead style=\"display:table-header-group"));
        assertTrue(html.contains("<code style=\"display:inline-block;padding:1px 6px;"
                + "background:#eef0f3;"));
        assertTrue(html.contains("<td style=\"padding:9px 14px;text-align:left;"
                + "vertical-align:top;border:0;border-bottom:1px solid #ececec;"));
    }

    /**
     * 渲染全部样例并落盘，供浏览器逐份检查；同时生成索引页。
     */
    @Test
    public void renderAllSamplesToTarget() throws IOException {
        String[] names = {
                "01-basic-text.md", "02-code-blocks.md", "03-tables.md",
                "04-lists.md", "05-cheatsheet.md"
        };
        File dir = new File("target/markdown-samples");
        assertTrue("样例目录创建失败", dir.exists() || dir.mkdirs());
        List<String> pages = new ArrayList<String>();
        for (String name : names) {
            String markdown = readResource("/markdown-samples/" + name);
            assertTrue(name + " 样例为空", markdown.length() > 100);
            String html = Markdown.toDocument(markdown, MarkdownRenderOptions.create()
                    .theme(MarkdownTheme.YAZI));
            assertTrue(name + " 缺少主题根节点", html.contains("<div class=\"jkit-yazi\">"));
            assertTrue(name + " 仍残留属性注释", !html.contains("rehype:"));
            assertTrue(name + " 缺少卡片结构", html.contains("h2wrap-body"));
            String outName = name.replace(".md", ".html");
            write(new File(dir, outName), html);
            pages.add(outName);
            String email = Markdown.toDocument(markdown, MarkdownRenderOptions.create()
                    .theme(MarkdownTheme.YAZI).inlineStyle(true));
            assertTrue(name + " 邮件模式缺少卡片内联样式", email.contains("background:#fff"));
            assertTrue(name + " 邮件模式仍依赖类名", email.replaceAll(" class=\"[^\"]*\"", "")
                    .contains("background:#fff"));
            String emailName = name.replace(".md", "-email.html");
            write(new File(dir, emailName), email);
            pages.add(emailName);
        }
        assertEquals("样例数量", 10, pages.size());
        StringBuilder index = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<title>YAZI 主题样例</title><style>body{font-family:sans-serif;padding:2rem}"
                + "li{margin:.5rem 0}a{color:#0284c7}</style></head><body>"
                + "<h1>YAZI 主题样例</h1><ul>");
        for (String page : pages) {
            index.append("<li><a href=\"").append(page).append("\">").append(page)
                    .append("</a></li>");
        }
        index.append("</ul></body></html>");
        write(new File(dir, "index.html"), index.toString());
    }

    private static String readResource(String path) throws IOException {
        InputStream in = MarkdownYaziThemeTest.class.getResourceAsStream(path);
        assertTrue("找不到样例 " + path, in != null);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) >= 0) {
                out.write(buffer, 0, length);
            }
            return new String(out.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }

    private static void write(File file, String content) throws IOException {
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes(UTF8));
        } finally {
            out.close();
        }
    }
}
