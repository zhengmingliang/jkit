package com.alianga.jkit;

import com.alianga.jkit.http.HttpRequest;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 守卫测试：调用方显式设置的 {@link HttpRequest#preferHttp2(boolean)} 不能被
 * {@code applyDefaults} 用全局 {@code HttpConfig.isHttp2()} 覆盖。
 *
 * <p>否则明文 http:// 请求即使 preferHttp2(false) 仍发 h2c Upgrade，
 * 不支持 h2c 的服务器（Next.js / Node 等）会直接断连，表现为
 * “HTTP/1.1 header parser received no bytes”。</p>
 *
 * @author 郑明亮
 */
public class PreferHttp2OverrideTest {

    @Test
    public void explicitFalseIsMarkedSetAndSurvivesApplyDefaults() {
        HttpRequest request = new HttpRequest("GET", "http://example.com/")
                .preferHttp2(false);
        assertTrue(request.isPreferHttp2Set());
        assertFalse(request.isPreferHttp2());

        // 与 connect/read 超时同样的“未显式设置才补默认”语义
        HttpUtils.applyDefaults(request);
        assertFalse("显式 preferHttp2(false) 不应被全局默认覆盖",
                request.isPreferHttp2());
    }

    @Test
    public void explicitTrueAlsoSurvives() {
        HttpRequest request = new HttpRequest("GET", "https://example.com/")
                .preferHttp2(true);
        HttpUtils.applyDefaults(request);
        assertTrue(request.isPreferHttp2());
    }

    @Test
    public void untouchedRequestFallsBackToGlobalDefault() {
        HttpRequest request = new HttpRequest("GET", "http://example.com/");
        assertFalse("未调用过 preferHttp2 时不应标记为已显式设置",
                request.isPreferHttp2Set());
        HttpUtils.applyDefaults(request);
        // applyDefaults 后值等于全局配置（默认 true）
        assertTrue(request.isPreferHttp2());
    }
}
