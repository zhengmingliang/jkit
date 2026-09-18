package com.alianga.jkit.html;

import org.junit.Assert;
import org.junit.Test;

/**
 * 轻量 HTML 模块测试：解析、抽取、CSS 选择器与实体转义。
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
    }

    @Test
    public void selectByIdAndTag() {
        Document doc = doc();
        Assert.assertEquals(1, doc.select("#main").size());
        Assert.assertEquals(3, doc.select("p").size());
        Assert.assertEquals("xz", doc.select("p.a").text());
        Assert.assertEquals("yz", doc.select("p.b").text());
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
    public void grouping() {
        Document doc = doc();
        Assert.assertEquals(3, doc.select("p.a, p.b").size());
        Assert.assertEquals(2, doc.select("li").size());
        Assert.assertEquals("12", doc.select("ul li").text());
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
    }

    @Test
    public void pseudoClasses() {
        Document doc = doc();
        Assert.assertEquals("1", doc.select("li:first-child").text());
        Assert.assertEquals("2", doc.select("li:last-child").text());
        Assert.assertEquals(2, doc.select("li:not(.x)").size());
        Assert.assertEquals("x", doc.select("p:nth-child(1)").text());
        Assert.assertEquals("y", doc.select("p:nth-child(2)").text());
        Assert.assertEquals(1, doc.select(":root").size());
    }

    @Test
    public void instanceSelectDelegates() {
        Document doc = doc();
        Assert.assertEquals(2, doc.body().select("li").size());
    }

    @Test
    public void selectFirstAndMiss() {
        Document doc = doc();
        Assert.assertEquals("Hello", doc.selectFirst("title").text());
        Assert.assertNull(doc.selectFirst("table"));
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
        Assert.assertTrue(text.contains("12"));
    }

    @Test
    public void tolerantParsing() {
        Document doc = Html.parse("<div><span>hi</span></div><br><div/");
        Assert.assertEquals(2, doc.select("div").size());
        Assert.assertEquals("hi", doc.select("span").text());
        Assert.assertEquals(1, doc.select("br").size());
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
    public void attrsAndHtml() {
        Document doc = doc();
        Element img = doc.select("img").first();
        Assert.assertEquals("a.png", img.attr("src"));
        Assert.assertEquals("<div id=\"main\">", doc.select("#main").first().outerHtml().substring(0, 15));
    }
}
