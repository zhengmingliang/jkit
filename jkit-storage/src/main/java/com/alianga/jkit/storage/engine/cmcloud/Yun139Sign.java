package com.alianga.jkit.storage.engine.cmcloud;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/**
 * {@code Mcloud-Sign} 签名算法（网页版同款，AList / OpenList / cloud139 一致）：
 *
 * <ol>
 *   <li>对请求体 JSON 做 JS {@code encodeURIComponent}（严格版：仅 {@code A-Za-z0-9-_.~}
 *   不转义，{@code !'()*} 与空格等一律转成大写百分号编码）；</li>
 *   <li>把编码后的字符串<b>按字符排序</b>（这一步使签名与 JSON 键序无关）；</li>
 *   <li>Base64 编码；</li>
 *   <li>{@code sign = UPPER(MD5( MD5(b64) + MD5(ts + ":" + rand) ))}，其中
 *   {@code ts} 为本地时间 {@code yyyy-MM-dd HH:mm:ss}，{@code rand} 为 16 位随机串。</li>
 * </ol>
 *
 * @author 郑明亮
 */
final class Yun139Sign {
    private Yun139Sign() {
    }

    /** 计算签名（32 位大写十六进制）。 */
    static String calc(String bodyJson, String ts, String rand) {
        String encoded = encodeURIComponent(bodyJson);
        char[] chars = encoded.toCharArray();
        Arrays.sort(chars);
        String sorted = new String(chars);
        String b64 = Base64.getEncoder().encodeToString(sorted.getBytes(StandardCharsets.UTF_8));
        return md5Hex(md5Hex(b64) + md5Hex(ts + ":" + rand)).toUpperCase(Locale.ROOT);
    }

    /**
     * JS {@code encodeURIComponent} 的严格版：按 UTF-8 字节逐字节编码，
     * 仅 {@code A-Za-z0-9} 与 {@code -_.~} 保持原样（空格 → {@code %20}，
     * {@code !'()*} → {@code %21%27%28%29%2A}，非 ASCII 逐字节 {@code %XX}）。
     */
    static String encodeURIComponent(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '_' || b == '.' || b == '~') {
                out.append(c);
            } else {
                out.append('%').append(hexByte(b));
            }
        }
        return out.toString();
    }

    /** 两位大写十六进制（{@code %XX} 的 XX 部分）。 */
    private static String hexByte(byte b) {
        return String.format("%02X", b & 0xFF);
    }

    static String md5Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 MD5 摘要算法", e);
        }
    }
}
