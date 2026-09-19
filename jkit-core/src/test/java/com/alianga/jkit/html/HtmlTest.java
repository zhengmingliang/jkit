package com.alianga.jkit.html;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

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
    public void rawTextCloseTagIsCaseInsensitive() {
        // 结束标签大小写不敏感，且内容里的 < 不能误判为结束
        Document doc = Html.parse("<SCRIPT>var s = \"<div>\"; if (a < b) {}</SCRIPT>"
                + "<STYLE>.a{color:red}</style>"
                + "<TEXTAREA>a &lt; b</TEXTAREA>");
        Assert.assertEquals("var s = \"<div>\"; if (a < b) {}", doc.selectFirst("script").nodeText());
        Assert.assertEquals(".a{color:red}", doc.selectFirst("style").nodeText());
        Assert.assertEquals("a < b", doc.selectFirst("textarea").text());
        // 结束标签后带空白也要认（</script >）
        Document d2 = Html.parse("<script>var a=1</script ><p>后</p>");
        Assert.assertEquals("var a=1", d2.selectFirst("script").nodeText());
        Assert.assertEquals("后", d2.selectFirst("p").text());
    }

    @Test
    public void preContextKeepsNewlines() {
        // pre 内的 code 必须保留换行，与 Jsoup 一致（此前被折叠成空格）
        Document doc = Html.parse("<pre><code class=\"language-java\">"
                + "int a = 1;\n    int b = 2;\n</code></pre>");
        Element code = doc.selectFirst("pre code");
        Assert.assertEquals("language-java", code.attr("class"));
        Assert.assertEquals("int a = 1;\n    int b = 2;", code.text());
        // 直接取 pre 同样保留
        Assert.assertEquals("int a = 1;\n    int b = 2;", doc.selectFirst("pre").text());
        // pre 内嵌套非行内元素后不再保留：pre > div > code 走普通归一化
        Document d2 = Html.parse("<pre><div><code>x\n   y</code></div></pre>");
        Assert.assertEquals("x y", d2.selectFirst("code").text());
        // pre 外仍然是折叠空白
        Document d3 = Html.parse("<div><code>x\n   y</code></div>");
        Assert.assertEquals("x y", d3.selectFirst("code").text());
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

    @Test
    public void indexAndTraversalReturnTheSameElements() {
        // 查询根是 Document 时走索引，是普通元素时走全树遍历。同一个查询在两种入口下
        // 必须给出完全相同的元素序列，否则说明索引路径丢了元素或顺序错了。
        Document doc = Html.parse("<main id='main'><article class='post'><h2>标题</h2>"
                + "<p class='lead'>段<i>斜</i></p><ul><li>甲<li>乙</ul></article>"
                + "<article class='post'><h2>次</h2><p class='lead'>段二</p></article></main>");
        Element main = doc.selectFirst("main");
        String[] queries = {"p", ".post", "h2", "article.post h2", "main > article",
                ".post p", "p i", "li", ".lead", "#main article"};
        for (String q : queries) {
            Elements viaIndex = doc.select(q);
            Elements viaWalk = main.select(q);
            Assert.assertEquals(q + " 两种入口条数不一致", viaWalk.size(), viaIndex.size());
            for (int i = 0; i < viaIndex.size(); i++) {
                Assert.assertSame(q + " 第 " + (i + 1) + " 个元素不一致",
                        viaWalk.get(i), viaIndex.get(i));
            }
        }
    }

    @Test
    public void indexKeepsDocumentOrder() {
        Document doc = Html.parse("<div><span>1</span><p><span>2</span></p></div>"
                + "<span>3</span><section><span>4</span></section>");
        Elements spans = doc.select("span");
        Assert.assertEquals(4, spans.size());
        Assert.assertEquals("1", spans.get(0).text());
        Assert.assertEquals("2", spans.get(1).text());
        Assert.assertEquals("3", spans.get(2).text());
        Assert.assertEquals("4", spans.get(3).text());
    }

    @Test
    public void repeatedIdAndMultiClassAreIndexedCompletely() {
        Document doc = Html.parse("<p id='x'>1</p><p id='x'>2</p><div class='a b'>3</div>"
                + "<div class='b'>4</div>");
        // id 重复时两个都得回来：索引若只留第一个就会漏
        Assert.assertEquals(2, doc.select("#x").size());
        Assert.assertEquals("1", doc.select("#x").first().text());
        Assert.assertEquals("2", doc.select("#x").get(1).text());
        // class 多值：元素要出现在每个 token 的键下
        Assert.assertEquals(1, doc.select(".a").size());
        Assert.assertEquals(2, doc.select(".b").size());
        Assert.assertEquals(1, doc.select("div.a").size());
        Assert.assertEquals(2, doc.select("div.b").size());
    }

    @Test
    public void missKeyYieldsEmptyInsteadOfCrash() {
        Document doc = Html.parse("<div><p>x</p></div>");
        Assert.assertTrue(doc.select(".nope").isEmpty());
        Assert.assertTrue(doc.select("#nope").isEmpty());
        Assert.assertTrue(doc.select("table").isEmpty());
        Assert.assertNull(doc.selectFirst("table"));
        Assert.assertTrue(doc.select("div .nope").isEmpty());
    }

    @Test
    public void indexIsBuiltOnceAndReused() {
        Document doc = Html.parse("<p class='a'>1</p><p class='b'>2</p>");
        Map<String, List<Element>> first = doc.index();
        Assert.assertSame("索引应缓存复用，不能每次查询重建", first, doc.index());
        Assert.assertTrue(first.containsKey(Document.CLASS_PREFIX + "a"));
        Assert.assertTrue(first.containsKey(Document.TAG_PREFIX + "p"));
        // 只建索引不查询的文档，索引应保持未构建（惰性）
        Document idle = Html.parse("<p class='a'>1</p>");
        Assert.assertNotNull(idle.body());
    }

    // ------------------------------------------------------------------
    // 修复回归：畸形选择器一律抛 SelectorException，不再崩溃或静默错配
    // ------------------------------------------------------------------

    private static void assertSelectorFails(String query) {
        try {
            Html.parse("<p>x</p>").select(query);
            Assert.fail("选择器 '" + query + "' 应抛 SelectorException");
        } catch (SelectorException expected) {
            // 期望路径
        }
    }

    @Test
    public void emptySelectorFailsInsteadOfCrash() {
        // 空串此前抛 IndexOutOfBoundsException，空白串静默匹配全文档
        assertSelectorFails("");
        assertSelectorFails("   ");
    }

    @Test
    public void danglingGroupOperandFails() {
        // 前置/连续/末尾逗号此前会静默匹配整个文档（含 #document 根）
        assertSelectorFails(",p");
        assertSelectorFails("p,");
        assertSelectorFails("p,,p");
    }

    @Test
    public void danglingCombinatorFails() {
        // 末尾悬空组合符此前被静默忽略，"p > " 等价于 "p"
        assertSelectorFails("p >");
        assertSelectorFails("> p");
    }

    @Test
    public void malformedAttrSelectorFails() {
        // 未闭合 [ 此前抛 StringIndexOutOfBoundsException；
        // 操作符缺 '=' 此前被静默丢弃，退化成存在性匹配
        assertSelectorFails("img[src");
        assertSelectorFails("[src");
        assertSelectorFails("[src^png]");
        assertSelectorFails("[src!]");
        assertSelectorFails("[src='a.png");
        assertSelectorFails("[src=a.png");
    }

    @Test
    public void validAttrSelectorStillWorks() {
        Document doc = Html.parse("<img src='a.png' alt='x'>");
        Assert.assertEquals(1, doc.select("img[src]").size());
        Assert.assertEquals(1, doc.select("img[src='a.png']").size());
        Assert.assertEquals(1, doc.select("img[src^='a.']").size());
        Assert.assertEquals(1, doc.select("img[src$='.png']").size());
        Assert.assertEquals(1, doc.select("img[src*='pn']").size());
        Assert.assertEquals(0, doc.select("img[src='b.png']").size());
    }

    @Test
    public void notSupportsSelectorListAndComplex() {
        Document doc = Html.parse("<div><a>1</a><p>2</p><span>3</span></div>");
        // 选择器列表：:not(a,p) 此前只取第一个参数，p 会被错误排除
        Assert.assertEquals(1, doc.select("div :not(a,p)").size());
        Assert.assertEquals("3", doc.select("div :not(a,p)").text());
        // 含组合器的复杂选择器
        Document nested = Html.parse("<div><a><b>x</b></a><b>y</b></div>");
        Assert.assertEquals(1, nested.select("div b:not(a b)").size());
        Assert.assertEquals("y", nested.select("div b:not(a b)").text());
    }

    @Test
    public void unterminatedPseudoParenFails() {
        // 此前 "p:nth-child(23" 被静默截成 nth-child(2) 返回错误结果
        assertSelectorFails("p:nth-child(23");
        assertSelectorFails("p:nth-child(2");
    }

    @Test
    public void unsupportedPseudoFailsEvenWithoutCandidate() {
        // 不支持的伪类必须在解析期报错，不能因文档无候选元素而静默返回空结果
        assertSelectorFails("table:hover");
    }

    @Test
    public void emptyCommentDoesNotSwallowRest() {
        // HTML5：<!--> 与 <!---> 是完整的空注释，此前会把剩余整个文档吞进注释
        Document doc = Html.parse("<!-->x<p>y</p>");
        Assert.assertEquals(1, doc.select("p").size());
        Assert.assertEquals("x y", doc.text());
        Document dashed = Html.parse("<!--->z<p>w</p>");
        Assert.assertEquals(1, dashed.select("p").size());
        Assert.assertEquals("z w", dashed.text());
        // 常规注释与未闭合注释行为不变
        Assert.assertEquals("1", Html.parse("<!-- c -->hi<p>1</p>").select("p").text());
        Assert.assertEquals("hi 1", Html.parse("<!-- c -->hi<p>1</p>").text());
        Assert.assertEquals(0, Html.parse("<div>a<!--broken<p>b</p></div>").select("p").size());
    }

    @Test
    public void astralNumericEntityProducesSurrogatePair() {
        // >0xFFFF 的码点此前被强转 char 截断成 U+F600 乱码
        Assert.assertEquals("\uD83D\uDE00", Html.unescape("&#128512;"));
        Assert.assertEquals("\uD83D\uDE00", Html.unescape("&#x1F600;"));
        Assert.assertEquals("\uD83D\uDE00", Html.parse("<p>&#128512;</p>").select("p").text());
        // 基本平面行为不变
        Assert.assertEquals("A", Html.unescape("&#65;"));
        Assert.assertEquals("a&b", Html.unescape("a&b"));
    }

    @Test
    public void deepNestingDoesNotStackOverflow() {
        // 解析是迭代的，但 text/outerHtml/select 此前是递归的，万层嵌套直接栈溢出
        int depth = 20000;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("<div class='d'>");
        }
        sb.append("leaf");
        for (int i = 0; i < depth; i++) {
            sb.append("</div>");
        }
        Document doc = Html.parse(sb.toString());
        Assert.assertTrue(doc.text().contains("leaf"));
        Assert.assertTrue(doc.outerHtml().contains("leaf"));
        Assert.assertEquals(depth, doc.select("div.d").size());
        Assert.assertNotNull(doc.selectFirst("div"));
    }

    @Test
    public void htmlTagAttributesPreserved() {
        // <html> 标签上的属性此前被静默丢弃（head/body 都保留，唯独 html 丢）
        Document doc = Html.parse("<html lang='en'><body>x</body></html>");
        Assert.assertEquals("en", doc.selectFirst("html").attr("lang"));
    }

    @Test
    public void elementsSelectDeduplicates() {
        // 嵌套 div 都命中 "div"，两个子树的 p 查询会命中同一个元素，去重后只保留一次
        Document doc = Html.parse("<div id='a'><div id='b'><p>1</p></div></div>");
        Assert.assertEquals(2, doc.select("div").size());
        Assert.assertEquals(1, doc.select("div").select("p").size());
        // 分组选择器内部重复的项也去重
        Assert.assertEquals(2, doc.select("div, .x, div").size());
    }
}
