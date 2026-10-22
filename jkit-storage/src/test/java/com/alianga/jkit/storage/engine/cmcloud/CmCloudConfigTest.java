package com.alianga.jkit.storage.engine.cmcloud;

import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link CmCloudConfig} 纯单元测试：默认值、凭据校验、摘要算法与有效期越界拦截、
 * 云端路径拼装——全部不触网。
 *
 * @author 郑明亮
 */
public class CmCloudConfigTest {

    /** 一组合法凭据（测试用假值，不对应任何真实授权）。 */
    private static final String[] CREDENTIALS = {
            "creditToken", "test-credit-token",
            "skillId", "skill-1",
            "clientId", "client-1",
            "strategyId", "strategy-1",
            "clawUid", "uid-1",
    };

    private static CmCloudConfig parse(Map<String, String> map) {
        return CmCloudConfig.from(StorageConfig.of(map));
    }

    private static Map<String, String> valid() {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < CREDENTIALS.length; i += 2) {
            map.put(CREDENTIALS[i], CREDENTIALS[i + 1]);
        }
        return map;
    }

    private static void expectInvalidConfig(Map<String, String> map, String keyword) {
        try {
            parse(map);
            fail("应抛出 INVALID_CONFIG");
        } catch (StorageException e) {
            assertEquals(StorageErrorCode.INVALID_CONFIG, e.code());
            if (keyword != null) {
                assertTrue("异常文案应包含「" + keyword + "」：" + e.getMessage(),
                        e.getMessage().contains(keyword));
            }
        }
    }

    @Test
    public void defaultsApplied() {
        CmCloudConfig config = parse(valid());
        assertEquals("https://openapi.yun.139.com/open-ai-space", config.baseUrl());
        assertEquals("sha256", config.hashAlgorithm());
        assertEquals(3600, config.downloadExpireSec());
        assertTrue(config.rootSegments().isEmpty());
        assertEquals("/", config.cloudPath(""));
        assertEquals("/备份/周报.docx", config.cloudPath("备份/周报.docx"));
    }

    @Test
    public void apiUrlJoinsGatewayAndPrefix() {
        CmCloudConfig config = parse(valid());
        assertEquals("https://openapi.yun.139.com/open-ai-space"
                        + CmCloudConfig.PERSONAL_SAAS_PREFIX + "/file/list",
                config.apiUrl("/file/list"));
        // 用户多打尾斜杠也能容错
        Map<String, String> withSlash = valid();
        withSlash.put("baseUrl", "https://openapi.yun.139.com/open-ai-space/");
        assertEquals("https://openapi.yun.139.com/open-ai-space"
                        + CmCloudConfig.PERSONAL_SAAS_PREFIX + "/file/list",
                parse(withSlash).apiUrl("/file/list"));
    }

    @Test
    public void nonHttpsGatewayRejected() {
        Map<String, String> map = valid();
        map.put("baseUrl", "http://openapi.yun.139.com/open-ai-space");
        expectInvalidConfig(map, "https");
    }

    @Test
    public void missingCreditTokenRejectedWithScanHint() {
        // 本机可能真有扫码授权落盘的票据缓存（~/.cmcloud/auth/jkit/...）：
        // 缓存存在且可读时，缺配置也能按三级回落解析成功——此时跳过「无缓存报错」分支，
        // 不与真实授权环境耦合（有缓存时的回落行为由 builtinRegistrationFallback 覆盖）
        if (hasLocalTicketCache()) {
            CmCloudConfig config = parse(new LinkedHashMap<>());
            assertTrue("缓存票据应被采用：" + config,
                    config.creditToken() != null && !config.creditToken().trim().isEmpty());
            return;
        }
        // 真无缓存：报「扫码授权」指引（skillId 等内置常量兜底，不再要求手填）
        expectInvalidConfig(new LinkedHashMap<>(), "扫码授权");
        expectInvalidConfig(new LinkedHashMap<>(), "creditToken");
    }

    /** 本机是否已有扫码授权留下的可读票据缓存（有则缺配置场景不再报「扫码授权」）。 */
    private static boolean hasLocalTicketCache() {
        try {
            CmCloudConfig config = parse(new LinkedHashMap<>());
            return config.creditToken() != null && !config.creditToken().trim().isEmpty();
        } catch (StorageException e) {
            return false;
        }
    }

    @Test
    public void builtinRegistrationFallback() {
        // skillId / clientId / strategyId 留空时回落到内置登记常量（无需手填）
        Map<String, String> bare = new LinkedHashMap<>();
        bare.put("creditToken", "t-abc123456");
        bare.put("clawUid", "uid-1");
        CmCloudConfig config = parse(bare);
        assertEquals(CmCloudIdaasProfile.SKILL_ID, config.skillId());
        assertEquals(CmCloudIdaasProfile.CLIENT_ID, config.clientId());
        assertEquals(CmCloudIdaasProfile.STRATEGY_ID, config.strategyId());
    }

    @Test
    public void unknownHashAlgorithmRejected() {
        Map<String, String> map = valid();
        map.put("hashAlgorithm", "md5");
        expectInvalidConfig(map, "sha256");
    }

    @Test
    public void hashAlgorithmCaseInsensitive() {
        Map<String, String> map = valid();
        map.put("hashAlgorithm", " SHA1 ");
        assertEquals("sha1", parse(map).hashAlgorithm());
    }

    @Test
    public void downloadExpireSecBounds() {
        Map<String, String> nan = valid();
        nan.put("downloadExpireSec", "abc");
        expectInvalidConfig(nan, "数字");

        Map<String, String> tooSmall = valid();
        tooSmall.put("downloadExpireSec", "0");
        expectInvalidConfig(tooSmall, "1~86400");

        Map<String, String> tooBig = valid();
        tooBig.put("downloadExpireSec", "86401");
        expectInvalidConfig(tooBig, "1~86400");

        Map<String, String> ok = valid();
        ok.put("downloadExpireSec", "86400");
        assertEquals(86400, parse(ok).downloadExpireSec());
    }

    @Test
    public void basePathSegmentsResolved() {
        Map<String, String> map = valid();
        map.put("basePath", "/AI空间/工作归档/");
        CmCloudConfig config = parse(map);
        assertEquals(java.util.Arrays.asList("AI空间", "工作归档"), config.rootSegments());
        assertEquals("/AI空间/工作归档/新建目录", config.cloudPath("新建目录"));
        assertEquals("/AI空间/工作归档", config.cloudPath(""));
    }

    @Test
    public void toStringMasksToken() {
        CmCloudConfig config = parse(valid());
        String text = config.toString();
        assertTrue(text.contains("test-c***"));
        assertTrue(!text.contains("test-credit-token"));
    }
}
