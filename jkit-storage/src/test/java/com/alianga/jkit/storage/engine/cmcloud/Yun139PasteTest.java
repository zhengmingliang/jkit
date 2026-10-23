package com.alianga.jkit.storage.engine.cmcloud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * yun139 粘贴容错：用户实际会怎么贴 Authorization，解析就得扛得住。
 * 用例形态来自真实踩坑（2026-10-23 用户把同一段连 Basic 前缀一起贴了两遍）。
 */
public class Yun139PasteTest {

    private static final String TOKEN =
            "cGM6MTU3MzMxMDA1NzM6YURhTEhCV0N8MXxSQ1N8MTc5NTIzNjMyODM2"
                    + "MHxMblhKNTE0QWtYN25ZbG5zT0JZeXguOTR3NkNKdmtPbHRUWE95"
                    + "dUhuN01VSlkzZ1BzVTZiMWc1bkJoMjdDOEdZSmJlVUE1X1E0dGhr"
                    + "NmpDVUpmcURPM29LbS5jOURFY0QzYlBLeDNJTlZ0Y05IX19YMFVG"
                    + "VEVXaF9BbHJuUXRiTlVlVjBiTVNleUtYQlA4X3A2dFdsNXNjMEZv"
                    + "QmVJVmo2NVhXN3hfZGFMUjQt";

    @Test
    public void plainTokenUntouched() {
        assertEquals(TOKEN, Yun139Config.extractAuthorizationToken(TOKEN));
    }

    @Test
    public void basicPrefixStrippedCaseInsensitive() {
        assertEquals(TOKEN, Yun139Config.extractAuthorizationToken("Basic " + TOKEN));
        assertEquals(TOKEN, Yun139Config.extractAuthorizationToken("basic " + TOKEN));
        assertEquals(TOKEN, Yun139Config.extractAuthorizationToken("BASIC\t" + TOKEN));
    }

    @Test
    public void duplicatedPasteWithGluedHyphen() {
        // 真实踩坑：整段贴两遍，第二段与第一段以 '-' 粘连（token 本身以 '-' 结尾，须保留）
        String raw = "Basic " + TOKEN + "-Basic " + TOKEN;
        assertEquals(TOKEN, Yun139Config.extractAuthorizationToken(raw));
    }

    @Test
    public void duplicatedPasteWithSpace() {
        // 分隔粘贴：第一段尾部的 '-' 是分隔符，按 base64 长度（%4）判掉
        String separator = TOKEN.substring(0, TOKEN.length() - 1);
        String raw = "Basic " + separator + " -Basic " + separator;
        assertEquals(separator, Yun139Config.extractAuthorizationToken(raw));
    }

    @Test
    public void nullAndEmptyGiveEmpty() {
        assertEquals("", Yun139Config.extractAuthorizationToken(null));
        assertEquals("", Yun139Config.extractAuthorizationToken("   "));
        assertEquals("", Yun139Config.extractAuthorizationToken("Basic "));
    }

    @Test
    public void malformedTokenRejectedWithReadableMessage() {
        try {
            Yun139Config.from(com.alianga.jkit.storage.StorageConfig.empty()
                    .set("platform", "yun139")
                    .set("authorization", "\"Basic " + TOKEN + "\"  换行尾巴")
                    .set("account", "15733100573"));
            fail("带引号/中文的粘贴应被拦截并给可读提示");
        } catch (com.alianga.jkit.storage.StorageException e) {
            assertTrue("提示应指向复制方式，实际：" + e.getMessage(),
                    e.getMessage().contains("多余字符"));
        }
    }
}
