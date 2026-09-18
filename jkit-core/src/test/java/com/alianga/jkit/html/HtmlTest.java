package com.alianga.jkit.html;

import org.junit.Assert;
import org.junit.Test;

/**
 * 轻量 HTML 模块测试：解析、抽取、CSS 选择器、容错与实体转义。
 */
public class HtmlTest {

    private static final String HTML = "<html><head><title>Hello</title></head>"
            + "<body class='pg'><div id='main'><p class='a'>x</p><p class='b'>y</p>"
            + "<p class='a b'>z</p></div>"
            + "<ul><li>1</li><li>2</li></ul>"
            + "<img src='a.png'><br>"
            + "<!-- comment --></body></html>";

    private Document doc() {
        return Html.parse(HTML);
    }

    @Test
    public void parseStructure() {
        Document doc = doc();
        Assert.assertEquals("Hello", doc.title());
        Assert.assertNotNull(doc.body());
        Assert.assertNotNull(doc.head());
        Assert.assertTrue(doc.body().hasClass("pg"));
    }

    @Test
    public void implicitSkeleton() {
        Document doc = Html.parse("<p>hi</p>");
        Assert.assertNotNull(doc.head());
        Assert.assertNotNull(doc.body());
        Assert.assertEquals(1, doc.body().children().size());
        Assert.assertEquals("hi", doc.text());
    }

    @Test
    public void selectByIdAndTag() {
        Document doc = doc();
        Assert.assertEquals(1, doc.select("#main").size());
        Assert.assertEquals(3, doc.select("p").size());
        Assert.assertEquals("x z", doc.select("p.a").text());
        Assert.assertEquals("y z", doc.select("p.b").text());
    }

    @Test
    public void selectClassCompound() {
        Document doc = doc();
        Assert.assertEquals(1, doc.select("p.a.b").size());
        Assert.assertEquals("z", doc.select("p.a.b").text());
        Assert.assertTrue(doc.select("body").first().hasClass("pg"));
    }

    @Test
    public void descendantAndChild() {
        Document doc = doc();
        Assert.assertEquals(3, doc.select("div p").size());
        Assert.assertEquals(1, doc.select("body > div").size());
        Assert.assertEquals(4, doc.select("body > *").size());
    }

    @Test
    public void siblingCombinators() {
        Document doc = Html.parse("<div><h1>T</h1><p>a</p><p>b</p><span>s</span></div>");
        Assert.assertEquals("a", doc.select("h1 + p").text());
        Assert.assertEquals("a b", doc.select("h1 ~ p").text());
        Assert.assertEquals("b", doc.select("p + p").text());
        Assert.assertEquals("s", doc.select("p + span").text());
        Assert.assertEquals(0, doc.select("span + p").size());
    }

    @Test
    public void grouping() {
        Document doc = doc();
        Assert.assertEquals(3, doc.select("p.a, p.b").size());
        Assert.assertEquals(2, doc.select("li").size());
        Assert.assertEquals("1 2", doc.select("ul li").text());
    }

    @Test
    public void attributeSelectors() {
        Document doc = doc();
        Assert.assertEquals(1, doc.select("img[src]").size());
        Assert.assertEquals(1, doc.select("img[src=a.png]").size());
        Assert.assertEquals(1, doc.select("div[id=main]").size());
        Assert.assertEquals(1, doc.select("img[src^=a]").size());
        Assert.assertEquals(1, doc.select("img[src$=png]").size());
        Assert.assertEquals(1, doc.select("img[src*=png]").size());
        Assert.assertEquals(0, doc.select("img[src!=a.png]").size());
    }

    @Test
    public void attributeNotEquals() {
        Document doc = Html.parse("<a href='/'>r</a><a href='https://x.io'>x</a><a>n</a>");
        Assert.assertEquals(2, doc.select("a[href!=/]").size());
        Assert.assertEquals(1, doc.select("a[href^=https]").size());
    }

    @Test
    public void pseudoClasses() {
        Document doc = doc();
        Assert.assertEquals("1", doc.select("li:first-child").text());
        Assert.assertEquals("2", doc.select("li:last-child").text());
        Assert.assertEquals(2, doc.select("li:not(.x)").size());
        Assert.assertEquals("x", doc.select("p:nth-child(1)").text());
        Assert.assertEquals("y", doc.select("p:nth-child(2)").text());
        Assert.assertEquals("z", doc.select("p:nth-child(3)").text());
        Assert.assertEquals("x z", doc.select("p:nth-child(2n+1)").text());
        Assert.assertEquals("y", doc.select("p:nth-child(even)").text());
        Assert.assertEquals("z", doc.select("p:nth-last-child(1)").text());
        Assert.assertEquals(1, doc.select(":root").size());
    }

    @Test
    public void pseudoOfType() {
        Document doc = Html.parse("<div><h2>a</h2><p>1</p><p>2</p><h2>b</h2></div>");
        Assert.assertEquals("a", doc.select("h2:first-of-type").text());
        Assert.assertEquals("b", doc.select("h2:last-of-type").text());
        Assert.assertEquals("a", doc.select("h2:nth-of-type(1)").text());
        Assert.assertEquals("b", doc.select("h2:nth-of-type(2n)").text());
        Assert.assertEquals("1", doc.select("p:nth-of-type(1)").text());
        Assert.assertEquals("2", doc.select("p:nth-last-of-type(1)").text());
        Assert.assertEquals(0, doc.select("p:only-of-type").size());
        Assert.assertEquals(0, doc.select("h2:only-of-type").size());

        Document single = Html.parse("<div><p>1</p><span>s</span></div>");
        Assert.assertEquals(1, single.select("p:only-of-type").size());
        Assert.assertEquals(1, single.select("span:only-of-type").size());
    }

    @Test
    public void containsAndMatches() {
        Document doc = Html.parse("<ul><li>Java <b>基础</b></li><li>HTML 解析</li></ul>");
        Assert.assertEquals("Java 基础", doc.select("li:contains(基础)").text());
        Assert.assertEquals(1, doc.select("li:containsOwn(Java)").size());
        Assert.assertEquals(0, doc.select("li:containsOwn(基础)").size());
        Assert.assertEquals(1, doc.select("li:matches(.*解析)").size());
        Assert.assertEquals(1, doc.select("li:containsown(HTML)").size());
    }

    @Test
    public void emptyPseudo() {
        Document doc = Html.parse("<div><span></span><p>  </p><i>x</i><!-- c --></div>");
        Assert.assertEquals(1, doc.select("span:empty").size());
        Assert.assertEquals(1, doc.select("p:empty").size());
        Assert.assertEquals(0, doc.select("i:empty").size());
    }

    @Test
    public void instanceSelectDelegates() {
        Document doc = doc();
        Assert.assertEquals(2, doc.body().select("li").size());
        Assert.assertEquals(2, doc.select("li").select("li").size());
    }

    @Test
    public void selectFirstAndMiss() {
        Document doc = doc();
        Assert.assertEquals("Hello", doc.selectFirst("title").text());
        Assert.assertNull(doc.selectFirst("table"));
    }

    @Test(timeout = 5000)
    public void illegalSelectorThrowsInsteadOfHanging() {
        Document doc = doc();
        assertThrows("p ~ + p");
        assertThrows("h1 @ p");
        assertThrows("p:");
        assertThrows("p:not()");
        assertThrows("p:contains()");
        Assert.assertNotNull(doc.select("p"));
    }

    private void assertThrows(String query) {
        try {
            doc().select(query);
            Assert.fail("选择器应当报错: " + query);
        } catch (SelectorException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().length() > 0);
        }
    }

    @Test
    public void voidElementsSerializeWithoutCloseTag() {
        Document doc = doc();
        Assert.assertEquals("<img src=\"a.png\">", doc.select("img").first().outerHtml());
        Assert.assertEquals("<br>", doc.select("br").first().outerHtml());
    }

    @Test
    public void textExtraction() {
        Document doc = doc();
        String text = doc.text();
        Assert.assertTrue(text.contains("Hello"));
        Assert.assertTrue(text.contains("x"));
        Assert.assertTrue(text.contains("y"));
        Assert.assertTrue(text.contains("z"));
        Assert.assertTrue(text.contains("1 2"));
    }

    @Test
    public void textNormalisation() {
        Document doc = Html.parse("<div><h2>标题</h2><p>第一段\n   拼接</p>"
                + "<pre>  保留  空白  </pre><script>var a=1;</script></div>");
        Assert.assertEquals("标题 第一段 拼接   保留  空白", doc.select("div").text());
        Assert.assertEquals("保留  空白", doc.select("pre").text());
        Assert.assertEquals("", doc.selectFirst("script").text());
        Assert.assertEquals("", doc.select("div").first().ownText());
    }

    @Test
    public void ownTextAndSiblings() {
        Document doc = Html.parse("<ul><li>A<span>s</span></li><li>B</li><li>C</li></ul>");
        Element first = doc.selectFirst("li");
        Assert.assertEquals("A", first.ownText());
        Assert.assertEquals("As", first.text());
        Assert.assertEquals("B", first.nextElementSibling().text());
        Assert.assertNull(first.previousElementSibling());
        Assert.assertEquals(0, first.elementSiblingIndex());
        Assert.assertEquals(2, first.nextElementSibling().nextElementSibling().elementSiblingIndex());
    }

    @Test
    public void tolerantParsing() {
        Document doc = Html.parse("<div><span>hi</span></div><br><div/");
        Assert.assertEquals(2, doc.select("div").size());
        Assert.assertEquals("hi", doc.select("span").text());
        Assert.assertEquals(1, doc.select("br").size());
    }

    @Test
    public void omittedEndTags() {
        Document doc = Html.parse("<p>一<p>二<ul><li>a<li>b</ul>"
                + "<dl><dt>x<dd>y<dt>z<dd>w</dl>");
        Assert.assertEquals(2, doc.select("body > p").size());
        Assert.assertEquals("一 二", doc.select("p").text());
        Assert.assertEquals(2, doc.select("ul > li").size());
        Assert.assertEquals("a b", doc.select("li").text());
        Assert.assertEquals(2, doc.select("dt").size());
        Assert.assertEquals("y w", doc.select("dd").text());
    }

    @Test
    public void blockTagClosesBuriedParagraph() {
        Document doc = Html.parse("<div><p>a<b>c<div>d</div></div>");
        Assert.assertEquals(2, doc.select("div").size());
        Assert.assertEquals(1, doc.select("p").size());
        Assert.assertEquals("d", doc.select("div > div").text());
    }

    @Test
    public void tableImplicitTbody() {
        Document doc = Html.parse("<table><tr><td>x</td></tr></table>");
        Assert.assertEquals(1, doc.select("tbody").size());
        Assert.assertEquals(1, doc.select("table > tbody > tr > td").size());
        Assert.assertEquals("x", doc.select("td").text());
    }

    @Test
    public void tableSections() {
        Document doc = Html.parse("<table><thead><tr><th>A<th>B</thead>"
                + "<tbody><tr><td>1<td>2<tr><td>3<td>4</tbody>"
                + "<tfoot><tr><td>x</table>");
        Assert.assertEquals(2, doc.select("thead th").size());
        Assert.assertEquals(4, doc.select("tbody td").size());
        Assert.assertEquals(1, doc.select("tfoot td").size());
        Assert.assertEquals(4, doc.select("tr").size());
        Assert.assertEquals("1 2", doc.select("tbody > tr:first-child td").text());
        Assert.assertEquals("2 4", doc.select("td:nth-child(2)").text());
    }

    @Test
    public void dirtyAttributes() {
        Document doc = Html.parse("<DIV CLASS=Box  ><a href=/x?a=1&b=2>X</A>"
                + "<input disabled><img src=a.png alt='A B'>");
        Assert.assertEquals(1, doc.select(".Box").size());
        Assert.assertEquals(0, doc.select(".box").size());
        Assert.assertEquals("/x?a=1&b=2", doc.selectFirst("a").attr("href"));
        Assert.assertEquals("X", doc.selectFirst("a").text());
        Assert.assertTrue(doc.selectFirst("input").hasAttr("disabled"));
        Assert.assertEquals("A B", doc.selectFirst("img").attr("alt"));
    }

    @Test
    public void escapeAndUnescape() {
        Assert.assertEquals("a&lt;b&gt;&amp;", Html.escape("a<b>&"));
        Assert.assertEquals("a&b", Html.unescape("a&amp;b"));
        Assert.assertEquals("A", Html.unescape("&#65;"));
        Assert.assertEquals("A", Html.unescape("&#x41;"));
        Assert.assertEquals("a&b", Html.unescape("a&b"));
    }

    @Test
    public void namedEntities() {
        Assert.assertEquals("©", Html.unescape("&copy;"));
        Assert.assertEquals("—", Html.unescape("&mdash;"));
        Assert.assertEquals("…", Html.unescape("&hellip;"));
        Assert.assertEquals("“q”", Html.unescape("&ldquo;q&rdquo;"));
        Assert.assertEquals(" ", Html.unescape("&nbsp;"));
        Assert.assertEquals("&nbspx;", Html.unescape("&nbspx;"));
        Assert.assertTrue(Entities.size() > 150);
    }

    @Test
    public void attrsAndHtml() {
        Document doc = doc();
        Element img = doc.select("img").first();
        Assert.assertEquals("a.png", img.attr("src"));
        Assert.assertEquals("<div id=\"main\">", doc.select("#main").first().outerHtml().substring(0, 15));
        Assert.assertEquals("<p class=\"a\">x</p>", doc.selectFirst("p.a").outerHtml());
        Assert.assertEquals("x", doc.selectFirst("p.a").innerHtml());
    }

    @Test
    public void rawTextOfScriptAndTextarea() {
        Document doc = Html.parse("<head><script>if (a<b && c>d) f();</script></head>"
                + "<body><textarea>&lt;p&gt;</textarea></body>");
        Assert.assertEquals("if (a<b && c>d) f();", doc.selectFirst("script").nodeText());
        Assert.assertEquals("if (a<b && c>d) f();", doc.selectFirst("script").innerHtml());
        Assert.assertEquals("<p>", doc.selectFirst("textarea").val());
        Assert.assertNotNull(doc.head());
    }

    @Test
    public void commentsArePreserved() {
        Document doc = Html.parse("<div><!-- 说明 --><p>x</p></div>");
        Assert.assertEquals(1, doc.select("div").first().childNodeSize() - 1);
        Assert.assertTrue(doc.select("div").first().outerHtml().contains("<!-- 说明 -->"));
        Assert.assertEquals("x", doc.text());
    }

    @Test
    public void nullAndEmptyInput() {
        Assert.assertEquals("", Html.parse(null).text());
        Assert.assertEquals("", Html.parse("").text());
        Assert.assertNotNull(Html.parse("").body());
    }
}
