package com.alianga.jkit.storage.engine.cmcloud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link Yun139Sign} 的跨语言一致性验证：黄金向量由同一算法的 Python 独立实现
 * （MD5/排序/Base64 语义逐项对照 AList Go 实现）离线算出，防止 Java 侧
 * 编码 / 排序 / 大小写细节写偏。
 *
 * @author 郑明亮
 */
public class Yun139SignTest {

    /** 黄金向量：calc('{"name":"测试 文件","size":12}', '2026-10-22 20:00:00', 'abcdefghijklmnop')。 */
    private static final String GOLDEN_SIGN = "E29E627738D05B7C870450E9C259ED79";

    @Test
    public void goldenVectorFromIndependentImplementation() {
        String sign = Yun139Sign.calc("{\"name\":\"测试 文件\",\"size\":12}",
                "2026-10-22 20:00:00", "abcdefghijklmnop");
        assertEquals(GOLDEN_SIGN, sign);
    }

    @Test
    public void signIsIndependentOfJsonKeyOrder() {
        // 排序发生在「编码后的字符串」上：键序不同 → 字符多重集相同 → 签名相同
        String a = Yun139Sign.calc("{\"name\":\"a\",\"size\":1}", "2026-01-01 00:00:00", "0123456789abcdef");
        String b = Yun139Sign.calc("{\"size\":1,\"name\":\"a\"}", "2026-01-01 00:00:00", "0123456789abcdef");
        assertEquals("键序不应影响签名", a, b);
        assertEquals(32, a.length());
        assertEquals(a, a.toUpperCase(java.util.Locale.ROOT));
    }

    @Test
    public void encodeURIComponentMatchesJavaScriptStrictVariant() {
        assertEquals("a%20b%21c%27d%28e%29f%2Ag%E4%B8%AD",
                Yun139Sign.encodeURIComponent("a b!c'd(e)f*g中"));
        assertEquals("unreserved.-~_09Azaz", Yun139Sign.encodeURIComponent("unreserved.-~_09Azaz"));
        assertEquals("%7B%22k%22%3A1%7D", Yun139Sign.encodeURIComponent("{\"k\":1}"));
        assertEquals("%2F", Yun139Sign.encodeURIComponent("/"));
    }

    @Test
    public void md5HexWellKnown() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Yun139Sign.md5Hex(""));
        assertEquals("0cc175b9c0f1b6a831c399e269772661", Yun139Sign.md5Hex("a"));
    }
}
