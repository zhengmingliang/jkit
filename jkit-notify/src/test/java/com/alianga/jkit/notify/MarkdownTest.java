package com.alianga.jkit.notify;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Markdown → HTML 转换：通知正文常用子集。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class MarkdownTest {

    /**
     * 空输入返回空串。
     */
    @Test
    public void emptyAndNull() {
        assertEquals("", NotifyUtils.markdownToHtml(null));
        assertEquals("", NotifyUtils.markdownToHtml(""));
    }

    /**
     * 标题、无序列表：SMTP 实发探测里用的那种周报正文。
     */
    @Test
    public void headingAndUnorderedList() {
        String html = NotifyUtils.markdownToHtml("## 本周完成\n- 模块 A");
        assertEquals("<h2>本周完成</h2>\n<ul><li>模块 A</li></ul>", html);
    }

    /**
     * 段落内单换行变成 br；加粗 / 斜体 / 行内代码。
     */
    @Test
    public void paragraphBreaksAndEmphasis() {
        String html = NotifyUtils.markdownToHtml("CPU **95%**\n内存 *80%*\n命令 `ls`");
        assertEquals("<p>CPU <strong>95%</strong><br>内存 <em>80%</em><br>命令 <code>ls</code></p>",
                html);
    }

    /**
     * 有序列表、删除线、链接。
     */
    @Test
    public void orderedListStrikeAndLink() {
        String html = NotifyUtils.markdownToHtml(
                "1. ~~旧~~\n2. [文档](https://example.com)");
        assertEquals("<ol><li><del>旧</del></li><li><a href=\"https://example.com\">文档</a></li></ol>",
                html);
    }

    /**
     * 围栏代码块不解析内部 markdown，并转义 HTML。
     */
    @Test
    public void fencedCodeEscapesAndSkipsMarkdown() {
        String html = NotifyUtils.markdownToHtml("```java\n<a> **bold**\n```");
        assertEquals("<pre><code class=\"language-java\">&lt;a&gt; **bold**</code></pre>", html);
    }

    /**
     * 引用、分割线、图片。
     */
    @Test
    public void quoteHrImage() {
        String html = NotifyUtils.markdownToHtml("> 注意\n\n---\n\n![logo](https://example.com/a.png)");
        assertTrue(html, html.contains("<blockquote><p>注意</p></blockquote>"));
        assertTrue(html, html.contains("<hr>"));
        assertTrue(html, html.contains("<img src=\"https://example.com/a.png\" alt=\"logo\">"));
    }

    /**
     * GFM 表格。
     */
    @Test
    public void gfmTable() {
        String html = NotifyUtils.markdownToHtml("| 项 | 值 |\n| --- | --- |\n| CPU | 95% |");
        assertEquals("<table><thead><tr><th>项</th><th>值</th></tr></thead>"
                + "<tbody><tr><td>CPU</td><td>95%</td></tr></tbody></table>", html);
    }

    /**
     * 正文里的 HTML 特殊字符要转义，避免破坏邮件。
     */
    @Test
    public void htmlSpecialCharsEscaped() {
        String html = NotifyUtils.markdownToHtml("a <b> & \"c\"");
        assertEquals("<p>a &lt;b&gt; &amp; &quot;c&quot;</p>", html);
    }

    /**
     * javascript: 链接降为 #。
     */
    @Test
    public void javascriptUrlNeutralized() {
        String html = NotifyUtils.markdownToHtml("[x](javascript:alert)");
        assertEquals("<p><a href=\"#\">x</a></p>", html);
    }

    /**
     * 一层嵌套列表。
     */
    @Test
    public void nestedList() {
        String html = NotifyUtils.markdownToHtml("- a\n  - b\n- c");
        assertEquals("<ul><li>a<ul><li>b</li></ul></li><li>c</li></ul>", html);
    }

    /**
     * 代码中的 markdown 标记保持字面量。
     */
    @Test
    public void inlineCodeProtectsMarkup() {
        String html = NotifyUtils.markdownToHtml("用 `**x**` 表示加粗");
        assertEquals("<p>用 <code>**x**</code> 表示加粗</p>", html);
        assertFalse(html.contains("<strong>"));
    }

    /**
     * 列表项里缩进的围栏代码块要进 {@code <pre>}，不能当段落把 {@code ```} 露出来。
     */
    @Test
    public void indentedFenceInsideList() {
        String html = NotifyUtils.markdownToHtml(
                "   - 示例：\n     ```sql\n     SELECT 1;\n     ```\n");
        assertTrue(html, html.contains("<pre><code class=\"language-sql\">"));
        assertTrue(html, html.contains("SELECT 1;"));
        assertFalse("fence markers leaked: " + html, html.contains("```"));
        assertTrue(html, html.contains("<li>"));
    }

    /**
     * 列表项里的 GFM 表格。
     */
    @Test
    public void gfmTableInsideList() {
        String html = NotifyUtils.markdownToHtml(
                "- 输出：\n  | A | B |\n  | --- | --- |\n  | 1 | 2 |\n");
        assertTrue(html, html.contains("<table>"));
        assertTrue(html, html.contains("<th>A</th>"));
        assertTrue(html, html.contains("<td>1</td>"));
        assertTrue(html, html.contains("<td>2</td>"));
    }

    /**
     * CLI 宽表：表头用 {@code |}，分隔行用 {@code +}。
     */
    @Test
    public void asciiPlusSeparatedTable() {
        String html = NotifyUtils.markdownToHtml(
                "TABLE_VC    |TABLE_SCHEMA\n------------+------------\nvc1         |test\n");
        assertTrue(html, html.contains("<table>"));
        assertTrue(html, html.contains("<th>TABLE_VC</th>"));
        assertTrue(html, html.contains("<th>TABLE_SCHEMA</th>"));
        assertTrue(html, html.contains("<td>vc1</td>"));
        assertTrue(html, html.contains("<td>test</td>"));
    }

    /**
     * 无语言标记的围栏应整块进 pre，内部 ASCII 表不再被拆成段落。
     */
    @Test
    public void bareFenceKeepsAsciiTable() {
        String html = NotifyUtils.markdownToHtml(
                "```\nHOST | SIZE\n-----+-----\n10.0.0.1 | 116\n```");
        assertTrue(html, html.startsWith("<pre><code>"));
        assertTrue(html, html.contains("HOST | SIZE"));
        assertTrue(html, html.contains("-----+-----"));
        assertFalse(html, html.contains("<table>"));
    }

    /**
     * {@code ~~~} 围栏与 {@code ```} 等价。
     */
    @Test
    public void tildeFence() {
        String html = NotifyUtils.markdownToHtml("~~~\ncode\n~~~");
        assertEquals("<pre><code>code</code></pre>", html);
    }

    /**
     * 用户提供的 GBase 文档：列表嵌套 SQL/bash 代码块、GFM 表、CLI 宽表。
     */
    @Test
    public void gbaseArticleTablesAndFences() throws Exception {
        String markdown = readResource("/gbase-table.md");
        String html = NotifyUtils.markdownToHtml(markdown);

        assertFalse("sql fence leaked: " + snippet(html), html.contains("```sql"));
        assertFalse("bash fence leaked", html.contains("```bash"));
        assertTrue(html, html.contains("<pre><code class=\"language-sql\">"));
        assertTrue(html, html.contains("<pre><code class=\"language-bash\">"));
        assertTrue(html, html.contains("CLUSTER_TABLE_SEGMENTS"));
        assertTrue(html, html.contains("gcadmin showdistribution"));

        assertTrue(html, html.contains("<table>"));
        assertTrue(html, html.contains(">HOST</th>") || html.contains(">HOST</td>")
                || html.contains(">HOST</"));
        assertTrue(html, html.contains("10.0.0.27"));
        assertTrue(html, html.contains("10.0.0.26"));
        assertTrue(html, html.contains("50.1581%"));

        assertTrue("CLI dump should stay preformatted", html.contains("172.16.18.227"));
        assertTrue(html, html.contains("fct_agt_savinf"));
        assertTrue(html, html.contains("<code>information_schema.CLUSTER_TABLE_SEGMENTS</code>")
                || html.contains("information_schema.CLUSTER_TABLE_SEGMENTS"));
    }

    private static String readResource(String path) throws Exception {
        InputStream in = MarkdownTest.class.getResourceAsStream(path);
        assertTrue("missing classpath resource " + path, in != null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String snippet(String html) {
        int i = html.indexOf("```");
        if (i < 0) {
            return html.substring(0, Math.min(200, html.length()));
        }
        int from = Math.max(0, i - 40);
        int to = Math.min(html.length(), i + 80);
        return html.substring(from, to);
    }
}
