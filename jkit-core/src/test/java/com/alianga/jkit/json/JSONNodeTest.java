package com.alianga.jkit.json;

import org.junit.Test;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JSONNodeTest {
    private static final String JSON_TEXT =
            "{\"name\":\"Tom\",\"tags\":[\"a\",\"b\"],\"dept\":{\"id\":7,\"name\":\"dev\"}}";

    private static final String STORE_JSON =
            "{\"store\":{\"book\":["
                    + "{\"category\":\"reference\",\"author\":\"Nigel Rees\",\"title\":\"Sayings of the Century\",\"price\":8.95},"
                    + "{\"category\":\"fiction\",\"author\":\"Evelyn Waugh\",\"title\":\"Sword of Honour\",\"price\":12.99},"
                    + "{\"category\":\"fiction\",\"author\":\"Herman Melville\",\"title\":\"Moby Dick\",\"isbn\":\"0-553-21311-3\",\"price\":8.99},"
                    + "{\"category\":\"fiction\",\"author\":\"J. R. R. Tolkien\",\"title\":\"The Lord of the Rings\",\"isbn\":\"0-395-19395-8\",\"price\":22.99}"
                    + "],\"bicycle\":{\"color\":\"red\",\"price\":19.95}},\"expensive\":10}";

    @Test
    public void testParseAndGet() {
        JSONNode node = JSONNode.parse(JSON_TEXT);
        assertTrue(node.get("name").toString().contains("Tom"));
        // 路径查询（/ 分隔）
        assertTrue(node.get("dept/id").toString().contains("7"));
        // 数组字段
        assertTrue(JSON.toJsonString(node.get("tags")).contains("\"b\""));
    }

    @Test
    public void testNodePathCollect() {
        JSONNode node = JSONNode.parse(JSON_TEXT);
        List<JSONNode> matched = JSONNodePath.parse("/dept/id").collect(node);
        assertEquals(1, matched.size());
        assertTrue(matched.get(0).toString().contains("7"));
    }

    @Test
    public void testNodePathRecursive() {
        JSONNode node = JSONNode.parse(JSON_TEXT);
        // 双斜杠递归查找所有 name
        List<JSONNode> names = JSONNodePath.parse("//name").collect(node);
        assertEquals(2, names.size());
    }

    @Test
    public void testRoundTrip() {
        JSONNode node = JSONNode.parse(JSON_TEXT);
        String out = JSON.toJsonString(node);
        JSONNode back = JSONNode.parse(out);
        assertTrue(JSON.toJsonString(back.get("name")).contains("Tom"));
        assertTrue(JSON.toJsonString(back.get("dept/id")).contains("7"));
    }

    // ---------------------------------------------------------------------
    // 按需解析（懒加载）
    // ---------------------------------------------------------------------

    @Test
    public void testOnDemandParsing() {
        // parse 只扫描一次，针对路径按需解析
        JSONNode root = JSONNode.parse(STORE_JSON);
        // 直接取深层值
        String firstAuthor = root.getPathValue("/store/book/0/author", String.class);
        assertEquals("Nigel Rees", firstAuthor);

        // 获取数组长度时触发按需解析
        JSONNode bookNode = root.get("/store/book");
        assertEquals(4, bookNode.getElementCount());

        // 获取 key 集合
        Collection<Serializable> keys = root.keyNames();
        assertTrue(keys.contains("store"));
        assertTrue(keys.contains("expensive"));
    }

    @Test
    public void testFromPartialRoot() {
        // from 只提取指定路径片段作为新的根节点
        JSONNode bookRoot = JSONNode.from(STORE_JSON, "/store/book");
        assertTrue(bookRoot.isArray());
        assertEquals(4, bookRoot.getElementCount());

        JSONNode title = JSONNode.from(STORE_JSON, "/store/book/0/title");
        assertEquals("Sayings of the Century", title.getStringValue());
    }

    @Test
    public void testFromWithValueClass() {
        Integer price = JSONNode.from(STORE_JSON, "/store/book/0/price", Integer.class);
        assertNull(price); // price 为小数，无法转 Integer（期待 null 而非异常）
        Double priceDouble = JSONNode.from(STORE_JSON, "/store/book/0/price", Double.class);
        assertEquals(8.95, priceDouble.doubleValue(), 0.001);
    }

    @Test
    public void testParseWithPath() {
        JSONNode dept = JSONNode.parse(JSON_TEXT, "/dept");
        assertTrue(dept.isObject());
        assertEquals("dev", dept.getChildValue("name", String.class));
    }

    // ---------------------------------------------------------------------
    // xpath 提取
    // ---------------------------------------------------------------------

    @Test
    public void testExtractWildcard() {
        List<JSONNode> authors = JSONNode.extract(STORE_JSON, "/store/book/*/author");
        assertEquals(4, authors.size());
        assertEquals("Nigel Rees", authors.get(0).getStringValue());
        assertEquals("J. R. R. Tolkien", authors.get(3).getStringValue());
    }

    @Test
    public void testExtractIndexRange() {
        // 下标 1- 表示前 2 个（包含下标 1）
        List<JSONNode> top2 = JSONNode.extract(STORE_JSON, "/store/book/1-/author");
        assertEquals(2, top2.size());
        // 下标 1+ 表示从下标 1 开始到结尾
        List<JSONNode> from1 = JSONNode.extract(STORE_JSON, "/store/book/1+/author");
        assertEquals(3, from1.size());
    }

    @Test
    public void testExtractDiscreteIndex() {
        // 编程式构建离散下标路径，在已解析的节点上 collect
        JSONNodePath path = JSONNodePath.create().exact("store").exact("book").indexs(0, 2).exact("author");
        List<JSONNode> selected = path.collect(JSONNode.parse(STORE_JSON));
        assertEquals(2, selected.size());
        assertEquals("Nigel Rees", selected.get(0).getStringValue());
        assertEquals("Herman Melville", selected.get(1).getStringValue());
    }

    @Test
    public void testCollectRecursive() {
        // //book/1+/author 递归查找
        List<JSONNode> authors = JSONNode.collect(STORE_JSON, "//book/1+/author");
        assertEquals(3, authors.size());
        // 递归查找所有 price
        List<JSONNode> prices = JSONNode.collect(STORE_JSON, "//price");
        assertEquals(5, prices.size());
    }

    @Test
    public void testCollectFilter() {
        // 属性过滤：author == 'Nigel Rees'（* 后紧跟 [表达式]）
        List<JSONNode> matched = JSONNode.collect(STORE_JSON, "/store/book/*[author == 'Nigel Rees']/title");
        assertEquals(1, matched.size());
        assertEquals("Sayings of the Century", matched.get(0).getStringValue());
    }

    @Test
    public void testFirstAndLast() {
        String first = JSONNode.first(STORE_JSON, "/store/book/*/author", String.class);
        assertEquals("Nigel Rees", first);
        String last = JSONNode.last(STORE_JSON, "/store/book/*/author", String.class);
        assertEquals("J. R. R. Tolkien", last);
        String emptyDefault = JSONNode.firstIfEmpty(STORE_JSON, "/store/nothing/*/x", String.class, "fallback");
        assertEquals("fallback", emptyDefault);
    }

    @Test
    public void testCollectAs() {
        JSONNode root = JSONNode.parse(STORE_JSON);
        List<Object> authors = root.collectAs(JSONNodePath.parse("/store/book/*/author"));
        assertEquals(4, authors.size());
        assertEquals("Nigel Rees", authors.get(0));
    }

    // ---------------------------------------------------------------------
    // 节点操作 / 修改
    // ---------------------------------------------------------------------

    @Test
    public void testSetPathValue() {
        JSONNode root = JSONNode.parse(JSON_TEXT);
        root.setPathValue("/dept/name", "newdev");
        assertEquals("newdev", root.getPathValue("/dept/name", String.class));
        // 序列化能看到修改
        assertTrue(root.toJsonString(true).contains("newdev"));
    }

    @Test
    public void testSetChildValue() {
        JSONNode root = JSONNode.parse(JSON_TEXT);
        root.setChildValue("name", "Jerry");
        assertEquals("Jerry", root.getChildValue("name", String.class));

        root.setChildValue("extra", "added", true);
        assertEquals("added", root.getChildValue("extra", String.class));
    }

    @Test
    public void testRemoveField() {
        JSONNode root = JSONNode.parse(JSON_TEXT);
        JSONNode removed = root.removeField("dept");
        assertNotNull(removed);
        assertNull(root.get("dept"));
    }

    @Test
    public void testRemoveElementAt() {
        JSONNode root = JSONNode.parse(STORE_JSON);
        JSONNode book = root.get("/store/book");
        // 先触发数组解析
        assertEquals(4, book.getElementCount());
        // 移除最后一个元素
        book.removeElementAt(3);
        assertEquals(3, book.getElementCount());
        assertEquals("Nigel Rees", book.getElementAt(0).getChildValue("author", String.class));
        assertEquals("Herman Melville", book.getElementAt(2).getChildValue("author", String.class));
    }

    // ---------------------------------------------------------------------
    // 值与类型转换
    // ---------------------------------------------------------------------

    @Test
    public void testToBean() {
        JSONNode node = JSONNode.parse("{\"name\":\"Tom\",\"age\":18,\"tags\":[\"a\",\"b\"]}");
        JSONTest.User user = node.toBean(JSONTest.User.class);
        assertEquals("Tom", user.getName());
        assertEquals(18, user.getAge());
        assertEquals(2, user.getTags().size());
    }

    @Test
    public void testAsMapAndAsList() {
        JSONNode obj = JSONNode.parse(JSON_TEXT);
        Map<String, Object> map = obj.asMap();
        assertEquals("Tom", map.get("name"));

        JSONNode arr = JSONNode.parse("[1,2,3]");
        List<Object> list = arr.asList();
        assertEquals(3, list.size());
    }

    @Test
    public void testToList() {
        JSONNode arr = JSONNode.parse("[{\"name\":\"A\"},{\"name\":\"B\"}]");
        List<JSONTest.User> users = arr.toList(JSONTest.User.class);
        assertEquals(2, users.size());
        assertEquals("B", users.get(1).getName());
    }

    @Test
    public void testTypeChecks() {
        JSONNode obj = JSONNode.parse(JSON_TEXT);
        assertTrue(obj.isObject());
        assertFalse(obj.isLeaf());
        assertTrue(obj.get("name").isLeaf());
        assertTrue(obj.get("name").getValue() instanceof String);
        assertEquals(JSONNode.STRING, obj.get("name").getType());
    }

    // ---------------------------------------------------------------------
    // 聚合 / diff / 其它
    // ---------------------------------------------------------------------

    @Test
    public void testMaxMinAvg() {
        String json = "[{\"price\":8.95},{\"price\":12.99},{\"price\":8.99}]";
        JSONNode arr = JSONNode.parse(json);
        assertEquals(12.99, arr.max("price"), 0.001);
        assertEquals(8.95, arr.min("price"), 0.001);
        assertEquals((8.95 + 12.99 + 8.99) / 3, arr.avg("price"), 0.001);

        JSONNode nums = JSONNode.parse("[1,2,3,4]");
        assertEquals(4.0, nums.max(), 0.001);
        assertEquals(1.0, nums.min(), 0.001);
        assertEquals(2.5, nums.avg(), 0.001);
    }

    @Test
    public void testDiff() {
        String a = "{\"a\":1,\"b\":2}";
        String b = "{\"a\":1,\"b\":3}";
        JSONNode[] diff = JSONNode.diff(a, b);
        assertNotNull(diff);
        assertEquals(2, ((Number) diff[0].getValue()).intValue());
        assertEquals(3, ((Number) diff[1].getValue()).intValue());

        // 相同内容 diff 返回 null
        assertNull(JSONNode.diff("{\"a\":1}", "{\"a\":1}"));
    }

    @Test
    public void testGetByPaths() {
        JSONNode root = JSONNode.parse(JSON_TEXT);
        JSONNode name = root.getByPaths("dept", "name");
        assertEquals("dev", name.getStringValue());
    }

    @Test
    public void testAll() {
        JSONNode root = JSONNode.parse(JSON_TEXT);
        List<JSONNode> leaves = root.all(true);
        assertTrue(leaves.size() >= 3);
    }

    @Test
    public void testParseFromByteArrayAndStream() {
        JSONNode node = JSONNode.parse(JSON_TEXT.getBytes());
        assertEquals("Tom", node.getChildValue("name", String.class));

        JSONNode fromStream = JSONNode.parse(new java.io.ByteArrayInputStream(JSON_TEXT.getBytes()));
        assertEquals("Tom", fromStream.getChildValue("name", String.class));
    }
}