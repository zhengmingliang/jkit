package com.alianga.jkit.storage.svn;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link SvnConfig} 纯单元测试：协议归一化、默认端口、URL 拼装与编码、
 * 非法配置拦截——全部不触网。
 *
 * @author 郑明亮
 */
public class SvnConfigTest {

    private static SvnConfig parse(Map<String, String> map) {
        return SvnConfig.from(StorageConfig.of(map));
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            result.put(kv[i], kv[i + 1]);
        }
        return result;
    }

    @Test
    public void protocolNormalizationAndDefaultPorts() {
        assertEquals("svn", parse(map("protocol", "SVN://", "host", "h")).protocol());
        assertEquals(3690, parse(map("protocol", "svn", "host", "h")).port());
        assertEquals(80, parse(map("protocol", "http", "host", "h")).port());
        assertEquals(443, parse(map("protocol", "https", "host", "h")).port());
        // 空协议按 svn 处理（平台默认值）
        assertEquals("svn", parse(map("host", "h")).protocol());
    }

    @Test
    public void explicitPortWins() {
        assertEquals(13690, parse(map("host", "h", "port", "13690")).port());
    }

    @Test
    public void rootUrlBuildsByProtocol() {
        assertEquals("svn://h:3690", parse(map("protocol", "svn", "host", "h")).rootUrlText());
        assertEquals("http://h:80", parse(map("protocol", "http", "host", "h")).rootUrlText());
        assertEquals("https://h:443", parse(map("protocol", "https", "host", "h")).rootUrlText());
    }

    @Test
    public void basePathPrefixedToLogicalPath() {
        SvnConfig config = parse(map("protocol", "svn", "host", "h", "basePath", "/trunk/proj"));
        assertEquals("svn://h:3690/trunk/proj", config.rootUrlText());
        assertEquals("svn://h:3690/trunk/proj/docs/a.txt",
                config.url("/docs/a.txt").toString());
        assertEquals("svn://h:3690/trunk/proj/docs/a.txt",
                config.url("docs/a.txt").toString());
        assertEquals("", config.relativePath("/"));
        assertEquals("docs/a.txt", config.relativePath("/docs/a.txt"));
    }

    @Test
    public void specialCharactersArePercentEncoded() {
        SvnConfig config = parse(map("protocol", "http", "host", "h"));
        assertEquals("http://h:80/a/%E6%96%87%E4%BB%B6.txt",
                config.url("a/文件.txt").toString());
        assertEquals("http://h:80/a/b%20c%23d.txt",
                config.url("a/b c#d.txt").toString());
        // 括号是 URI 子分隔符，SVNURL 原样保留
        assertEquals("http://h:80/a-b_c(1).txt",
                config.url("a-b_c(1).txt").toString());
    }

    @Test
    public void fullUrlPastedInHostIsSplit() {
        SvnConfig config = parse(map("host", "https://svn.example.com/svn/repo/trunk"));
        assertEquals("https", config.protocol());
        assertEquals("svn.example.com", config.host());
        assertEquals(443, config.port());
        assertEquals("https://svn.example.com:443/svn/repo/trunk", config.rootUrlText());
        assertEquals("https://svn.example.com:443/svn/repo/trunk/x",
                config.url("x").toString());
    }

    @Test
    public void fullUrlWithExplicitPortAndBasePathMerges() {
        SvnConfig config = parse(map("host", "http://127.0.0.1:8080/repo", "basePath", "trunk"));
        assertEquals(8080, config.port());
        assertEquals("http://127.0.0.1:8080/repo/trunk", config.rootUrlText());
    }

    @Test
    public void anonymousConfig() {
        SvnConfig config = parse(map("host", "h"));
        assertTrue(config.anonymous());
        SvnConfig authed = parse(map("host", "h", "user", "u", "password", "p"));
        assertFalse(authed.anonymous());
        assertEquals("u", authed.user());
    }

    @Test
    public void trustCertDefaultsFalseAndParsesTrue() {
        // 默认必须不信任：HTTPS + Basic 认证下无条件接受证书 = 中间人可窃取凭据。
        // 仅用户显式 opt-in（自签名 / 私有 CA）时才接受全部证书。
        assertFalse("缺省必须校验证书（不信任），否则默认敞开中间人", parse(map("host", "h")).trustServerCert());
        assertFalse(parse(map("host", "h", "trustServerCert", "false")).trustServerCert());
        assertTrue("显式勾选才信任", parse(map("host", "h", "trustServerCert", "true")).trustServerCert());
    }

    @Test
    public void missingHostRejected() {
        assertInvalidCode(parseExpectingFailure(map("protocol", "svn", "host", "  ")));
    }

    @Test
    public void unknownProtocolRejected() {
        assertInvalidCode(parseExpectingFailure(map("protocol", "ftp", "host", "h")));
    }

    @Test
    public void badPortRejected() {
        assertInvalidCode(parseExpectingFailure(map("host", "h", "port", "abc")));
        assertInvalidCode(parseExpectingFailure(map("host", "h", "port", "0")));
        assertInvalidCode(parseExpectingFailure(map("host", "h", "port", "70000")));
    }

    private static StorageException parseExpectingFailure(Map<String, String> map) {
        try {
            parse(map);
            fail("应抛 INVALID_CONFIG");
            return null;
        } catch (StorageException e) {
            return e;
        }
    }

    private static void assertInvalidCode(StorageException e) {
        assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
    }

    /**
     * 完整 URL 里带的 user:pass@ 必须拆成凭据并从 host 剥离。
     *
     * <p>历史 bug：只拆协议 / 主机 / 端口，原始输入留在 host 字段——host 不是
     * secret 字段，等于密码明文落盘 connections.json。</p>
     */
    @Test
    public void userInfoInUrlBecomesCredentialsAndIsStrippedFromHost() {
        SvnConfig withCreds = parse(map("host",
                "https://alice:secretPass@svn.example.com/repo/trunk"));
        assertEquals("URL 里的用户名应回填 user", "alice", withCreds.user());
        assertEquals("URL 里的密码应回填 password（供认证管理器用）",
                "secretPass", withCreds.password());
        assertFalse("凭据不能残留在配置里",
                withCreds.toString().contains("secretPass"));
    }

    /** 显式配置的 user/password 优先于 URL 里带的 userInfo。 */
    @Test
    public void explicitCredentialsBeatUrlUserInfo() {
        SvnConfig config = parse(map("host", "https://bob:urlpass@svn.example.com",
                "user", "alice", "password", "explicit"));
        assertEquals("alice", config.user());
        assertEquals("explicit", config.password());
    }

    /** URL 路径段里的 + 是字面字符，不能被 query 语义的解码变回空格。 */
    @Test
    public void plusInUrlPathStaysLiteral() {
        // c%2B%2Btrunk 应解成 c++trunk；裸 + 也不能变成空格
        SvnConfig config = parse(map("host", "https://svn.example.com/c++trunk",
                "basePath", ""));
        assertTrue("路径里的 + 必须保留（此前 URLDecoder 把它解成空格）："
                        + config.url("x").toString(),
                config.url("x").toString().contains("c++trunk"));
        SvnConfig encoded = parse(map("host", "https://svn.example.com/c%2B%2Btrunk",
                "basePath", ""));
        assertTrue(encoded.url("x").toString().contains("c++trunk"));
    }

    /** 解析失败的错误消息不得回显带凭据的原文。 */
    @Test
    public void parseFailureMessageIsSanitized() {
        try {
            parse(map("host", "https://alice:secretPass@sv n.example.com/repo"));
            fail("空格未编码应解析失败");
        } catch (StorageException e) {
            assertFalse("错误消息不能带密码原文：" + e.getMessage(),
                    e.getMessage().contains("secretPass"));
            assertTrue("错误消息应回显脱敏骨架",
                    e.getMessage().contains("…@"));
        }
    }
}
