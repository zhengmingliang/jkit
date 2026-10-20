package com.alianga.jkit.storage.engine;

import com.alianga.jkit.json.JSON;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 离线：真 Node + 真 xmtls.wasm 加密，校验记录头、类型与 65 字节 P-256 公钥；无网络。
 * PATH 无 node 或无预置 xmtls 时跳过（CI 不一定装 Node）。
 *
 * @author 郑明亮
 */
public class QqMailXmtlsNodeOfflineTest {

    private static Path fixtureDir() {
        String[] candidates = {
                System.getProperty("qqmail.xmtls.dir", ""),
                System.getProperty("user.home") + "/tmp-bench/qqjs",
                "/home/zml/tmp-bench/qqjs"
        };
        for (String c : candidates) {
            if (c == null || c.isEmpty()) {
                continue;
            }
            Path p = Paths.get(c);
            if (Files.isRegularFile(p.resolve("xmtls.js")) && Files.isRegularFile(p.resolve("xmtls.wasm"))) {
                return p;
            }
        }
        return null;
    }

    @Test
    public void encryptRecordMatchesBrowserShape() throws Exception {
        String node = QqMailXmtlsNode.findOnPath(System.getenv("PATH"), false);
        Path dir = fixtureDir();
        Assume.assumeTrue("本机无 node", node != null);
        Assume.assumeTrue("无预置 xmtls 目录", dir != null);

        try (QqMailXmtlsNode x = new QqMailXmtlsNode(node, dir.toString(), 20_000L)) {
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("timestamp", 1_760_500_000_123L);
            filter.put("nonce", "1234567890123456");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("auth_type", 6);
            params.put("pwd_key", "dummy_pwd_key_0123456789abcdef");
            params.put("pwd_ticket_key", "dummy_ticket_key_abcdefghijklmnop");
            params.put("filter_param", filter);
            QqMailXmtls.Sealed sealed = x.encrypt("dummy_pwd_key_0123456789abcdef", JSON.toJsonString(params));
            assertNotNull(sealed.body);
            assertTrue(sealed.body.length() > 100);
            // 只取 base64 字母数字与 +/；网页有时加 padding =
            String b64 = sealed.body.replace("=", "");
            // 补齐到 4k：先把 pad 全剔除，再按长度余数补回（写死追加 === 会算错余 3 的长度）
            byte[] bin = Base64.getDecoder().decode(
                    b64 + "=".repeat((4 - (b64.length() % 4)) % 4));
            // 长度前缀：大端 u32 == 整包长度
            int declared = ((bin[0] & 0xff) << 24) | ((bin[1] & 0xff) << 16)
                    | ((bin[2] & 0xff) << 8) | (bin[3] & 0xff);
            assertEquals(bin.length, declared);
            // 类型区：与 HAR AAABQgAAAAE5… 同形态（字节 4.. 含 00 00 00 01 …）
            assertEquals(0, bin[4]);
            assertEquals(0, bin[5]);
            assertEquals(0, bin[6]);
            assertEquals(1, bin[7]);
            // 65 字节未压缩 P-256 公钥 0x04 || X || Y
            int pk = -1;
            for (int i = 8; i < Math.min(bin.length - 65, 40); i++) {
                if ((bin[i] & 0xff) == 0x04) {
                    pk = i;
                    break;
                }
            }
            assertTrue("找不到 0x04 公钥前缀", pk >= 0);
            assertEquals(0x04, bin[pk] & 0xff);
            // 公钥后还有密文（ticket / data），整包应大于 65+头
            assertTrue(bin.length > pk + 65 + 16);

            // 解密垃圾应失败并释放会话
            assertEquals(null, x.decrypt(sealed.handle, "AAAA"));
            // 二次解密同一句柄：会话已释放
            assertEquals(null, x.decrypt(sealed.handle, "AAAA"));

            // 不回显密钥：toString 干净
            assertFalse(sealed.toString().contains("dummy"));
            assertFalse(x.toString().contains("dummy"));
        }
    }
}
