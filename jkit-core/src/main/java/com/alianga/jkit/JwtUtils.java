package com.alianga.jkit;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.log.Log;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JWT（JSON Web Token）工具类，支持 HS256 与 RS256 两种签名算法，不引入任何第三方依赖。
 *
 * <p>提供三种使用方式：
 * <ul>
 *   <li>流式构造：{@link #builder()} 设置声明后调用 {@code compact(...)} 完成签名；</li>
 *   <li>便捷构造：{@link #createHS256(Map, String)} / {@link #createRS256(Map, PrivateKey)}；</li>
 *   <li>解析与验签：{@link #parse(String)} 仅解码不验签，
 *       {@link #verify(String, String)} / {@link #verify(String, PublicKey)} 在验签的同时校验 exp / nbf。</li>
 * </ul>
 *
 * <p>头部固定为 {@code {"alg":&lt;算法&gt;,"typ":"JWT"}}，载荷为标准 JWT 注册声明
 * （sub / iss / aud / exp / nbf / iat / jti），时间类声明以「秒」为单位的数值存储。
 * HS256 的共享密钥建议使用长度不小于 32 字节的高熵随机串。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class JwtUtils {
    private static final Log log = Log.get(JwtUtils.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String TYP = "JWT";
    private static final String DOT = ".";
    private static final String HEADER_ALG = "alg";
    private static final String HEADER_TYP = "typ";
    private static final String CLAIM_SUB = "sub";
    private static final String CLAIM_ISS = "iss";
    private static final String CLAIM_AUD = "aud";
    private static final String CLAIM_EXP = "exp";
    private static final String CLAIM_NBF = "nbf";
    private static final String CLAIM_IAT = "iat";
    private static final String CLAIM_JTI = "jti";

    private JwtUtils() {
        throw new UnsupportedOperationException("utility class");
    }

    /**
     * 签名算法枚举。
     *
     * @author 郑明亮
     * @since 2.0.2
     */
    public enum Algorithm {
        /** HMAC-SHA256，使用对称密钥（字符串或字节）。 */
        HS256("HS256", "HmacSHA256", false),
        /** RSASSA-PKCS1-v1_5 + SHA-256，使用非对称密钥（私钥签名 / 公钥验签）。 */
        RS256("RS256", "SHA256withRSA", true);

        private final String jwsName;
        private final String jcaName;
        private final boolean asymmetric;

        Algorithm(String jwsName, String jcaName, boolean asymmetric) {
            this.jwsName = jwsName;
            this.jcaName = jcaName;
            this.asymmetric = asymmetric;
        }

        /**
         * 返回 JWT 头部 {@code alg} 字段使用的标准名称。
         *
         * @return JWS 算法名
         */
        public String jwsName() {
            return jwsName;
        }

        /**
         * 返回 JCA（Java Cryptography Architecture）算法名。
         *
         * @return JCA 算法名
         */
        String jcaName() {
            return jcaName;
        }

        /**
         * 判断是否为非对称算法（RS256 为 {@code true}，HS256 为 {@code false}）。
         *
         * @return 非对称算法返回 {@code true}
         */
        boolean asymmetric() {
            return asymmetric;
        }
    }

    /**
     * JWT 解析 / 验签过程中抛出的统一异常。
     *
     * @author 郑明亮
     * @since 2.0.2
     */
    public static class JwtException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * 使用指定错误信息构造异常。
         *
         * @param message 错误描述
         */
        public JwtException(String message) {
            super(message);
        }

        /**
         * 使用指定错误信息与底层原因构造异常。
         *
         * @param message 错误描述
         * @param cause 底层异常
         */
        public JwtException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 已解码（可验签）的 JWT，持有头部、载荷、签名三段，并提供若干类型安全的声明访问器。
     *
     * @author 郑明亮
     * @since 2.0.2
     */
    public static class DecodedJwt {
        private final String token;
        private final Map<String, Object> header;
        private final Map<String, Object> payload;
        private final byte[] signature;

        DecodedJwt(String token, Map<String, Object> header, Map<String, Object> payload, byte[] signature) {
            this.token = token;
            this.header = header;
            this.payload = payload;
            this.signature = signature;
        }

        /**
         * 返回 JWT 头部声明集合（含 alg / typ）。
         *
         * @return 头部 Map
         */
        public Map<String, Object> getHeader() {
            return header;
        }

        /**
         * 返回 JWT 载荷声明集合。
         *
         * @return 载荷 Map
         */
        public Map<String, Object> getPayload() {
            return payload;
        }

        /**
         * 返回 JWT 载荷声明集合（{@link #getPayload()} 的别名）。
         *
         * @return 载荷 Map
         */
        public Map<String, Object> getClaims() {
            return payload;
        }

        /**
         * 按名称读取载荷声明。
         *
         * @param name 声明名称
         * @return 声明值；不存在时返回 {@code null}
         */
        public Object getClaim(String name) {
            return payload.get(name);
        }

        /**
         * 返回 subject 声明。
         *
         * @return subject；不存在时返回 {@code null}
         */
        public String getSubject() {
            return asString(payload.get(CLAIM_SUB));
        }

        /**
         * 返回 issuer 声明。
         *
         * @return issuer；不存在时返回 {@code null}
         */
        public String getIssuer() {
            return asString(payload.get(CLAIM_ISS));
        }

        /**
         * 返回 audience 声明，统一以列表形式返回。
         *
         * @return audience 列表；不存在时返回空列表
         */
        public List<String> getAudience() {
            Object aud = payload.get(CLAIM_AUD);
            if (aud == null) {
                return Collections.emptyList();
            }
            if (aud instanceof List) {
                List<String> result = new ArrayList<String>();
                for (Object o : (List<?>) aud) {
                    result.add(asString(o));
                }
                return result;
            }
            return Collections.singletonList(asString(aud));
        }

        /**
         * 返回 expiration 声明对应的时间。
         *
         * @return 过期时间；不存在时返回 {@code null}
         */
        public Date getExpiration() {
            return toDate(payload.get(CLAIM_EXP));
        }

        /**
         * 返回 not-before 声明对应的时间。
         *
         * @return 生效时间；不存在时返回 {@code null}
         */
        public Date getNotBefore() {
            return toDate(payload.get(CLAIM_NBF));
        }

        /**
         * 返回 issued-at 声明对应的时间。
         *
         * @return 签发时间；不存在时返回 {@code null}
         */
        public Date getIssuedAt() {
            return toDate(payload.get(CLAIM_IAT));
        }

        /**
         * 返回 jwt-id 声明。
         *
         * @return jti；不存在时返回 {@code null}
         */
        public String getId() {
            return asString(payload.get(CLAIM_JTI));
        }

        /**
         * 返回解码后的签名原始字节。
         *
         * @return 签名字节数组
         */
        public byte[] getSignature() {
            return signature;
        }

        /**
         * 返回完整的原始令牌字符串。
         *
         * @return 原始令牌
         */
        public String getToken() {
            return token;
        }
    }

    /**
     * JWT 流式构造器，设置头部与载荷声明后调用 {@code compact(...)} 完成签名。
     *
     * @author 郑明亮
     * @since 2.0.2
     */
    public static class Builder {
        private final Map<String, Object> headers = new HashMap<String, Object>();
        private final Map<String, Object> claims = new HashMap<String, Object>();

        Builder() {
        }

        /**
         * 设置 subject 声明。
         *
         * @param subject 主体标识
         * @return 当前构造器
         */
        public Builder subject(String subject) {
            return claim(CLAIM_SUB, subject);
        }

        /**
         * 设置 issuer 声明。
         *
         * @param issuer 签发者
         * @return 当前构造器
         */
        public Builder issuer(String issuer) {
            return claim(CLAIM_ISS, issuer);
        }

        /**
         * 设置 audience 声明，支持多个受众。
         *
         * @param audience 受众列表
         * @return 当前构造器
         */
        public Builder audience(String... audience) {
            if (audience == null || audience.length == 0) {
                return this;
            }
            if (audience.length == 1) {
                claims.put(CLAIM_AUD, audience[0]);
            } else {
                List<String> list = new ArrayList<String>();
                Collections.addAll(list, audience);
                claims.put(CLAIM_AUD, list);
            }
            return this;
        }

        /**
         * 设置绝对过期时间（expiration 声明）。
         *
         * @param expiration 过期时间；为 {@code null} 时不写入
         * @return 当前构造器
         */
        public Builder expiration(Date expiration) {
            if (expiration != null) {
                claims.put(CLAIM_EXP, toSeconds(expiration));
            }
            return this;
        }

        /**
         * 设置生效时间（not-before 声明）。
         *
         * @param notBefore 生效时间；为 {@code null} 时不写入
         * @return 当前构造器
         */
        public Builder notBefore(Date notBefore) {
            if (notBefore != null) {
                claims.put(CLAIM_NBF, toSeconds(notBefore));
            }
            return this;
        }

        /**
         * 设置签发时间（issued-at 声明）。
         *
         * @param issuedAt 签发时间；为 {@code null} 时不写入
         * @return 当前构造器
         */
        public Builder issuedAt(Date issuedAt) {
            if (issuedAt != null) {
                claims.put(CLAIM_IAT, toSeconds(issuedAt));
            }
            return this;
        }

        /**
         * 设置 jwt-id 声明。
         *
         * @param id 唯一标识
         * @return 当前构造器
         */
        public Builder id(String id) {
            return claim(CLAIM_JTI, id);
        }

        /**
         * 设置自定义载荷声明。
         *
         * @param name 声明名称
         * @param value 声明值
         * @return 当前构造器
         */
        public Builder claim(String name, Object value) {
            claims.put(name, value);
            return this;
        }

        /**
         * 设置自定义头部声明（{@code alg} / {@code typ} 由 {@code compact} 固定，此处设置会被覆盖）。
         *
         * @param name 头部字段名
         * @param value 头部字段值
         * @return 当前构造器
         */
        public Builder header(String name, Object value) {
            headers.put(name, value);
            return this;
        }

        /**
         * 使用 HS256 算法与共享密钥对当前声明签名，返回完整令牌。
         *
         * @param algorithm 签名算法（应为 {@link Algorithm#HS256}）
         * @param secret 共享密钥（按 UTF-8 取字节），长度不足 32 字节（256 位）时拒绝签发，
         *               短密钥签出的令牌可被离线爆破
         * @return 形如 {@code header.payload.signature} 的 JWT 字符串
         */
        public String compact(Algorithm algorithm, String secret) {
            if (algorithm.asymmetric()) {
                throw new JwtException("算法 " + algorithm.jwsName() + " 需要 PrivateKey，而非密钥字符串");
            }
            byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
            checkHs256Secret(secretBytes);
            Map<String, Object> header = buildHeader(algorithm);
            String input = encode(header, claims);
            byte[] sig = hmac(algorithm.jcaName(), input.getBytes(StandardCharsets.UTF_8), secretBytes);
            return input + DOT + B64.encodeToString(sig);
        }

        /**
         * 使用 RS256 算法与 RSA 私钥对当前声明签名，返回完整令牌。
         *
         * @param algorithm 签名算法（应为 {@link Algorithm#RS256}）
         * @param privateKey RSA 私钥
         * @return 形如 {@code header.payload.signature} 的 JWT 字符串
         */
        public String compact(Algorithm algorithm, PrivateKey privateKey) {
            if (!algorithm.asymmetric()) {
                throw new JwtException("算法 " + algorithm.jwsName() + " 需要密钥字符串，而非 PrivateKey");
            }
            Map<String, Object> header = buildHeader(algorithm);
            String input = encode(header, claims);
            byte[] sig = rsaSign(algorithm.jcaName(), input.getBytes(StandardCharsets.UTF_8), privateKey);
            return input + DOT + B64.encodeToString(sig);
        }

        private Map<String, Object> buildHeader(Algorithm algorithm) {
            Map<String, Object> header = new HashMap<String, Object>(headers);
            header.put(HEADER_TYP, TYP);
            header.put(HEADER_ALG, algorithm.jwsName());
            return header;
        }
    }

    /**
     * 创建一个空声明的流式构造器。
     *
     * @return 新的 {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 便捷方法：用 HS256 与共享密钥，将给定声明集合直接签名为令牌。
     *
     * <p>时间类声明（exp / nbf / iat）应以「秒」为单位的 {@code Long} 写入，本方法不做转换。
     *
     * @param claims 载荷声明集合
     * @param secret 共享密钥
     * @return 签名后的 JWT 字符串
     */
    public static String createHS256(Map<String, Object> claims, String secret) {
        Builder builder = builder();
        for (Map.Entry<String, Object> entry : claims.entrySet()) {
            builder.claim(entry.getKey(), entry.getValue());
        }
        return builder.compact(Algorithm.HS256, secret);
    }

    /**
     * 便捷方法：用 RS256 与 RSA 私钥，将给定声明集合直接签名为令牌。
     *
     * <p>时间类声明（exp / nbf / iat）应以「秒」为单位的 {@code Long} 写入，本方法不做转换。
     *
     * @param claims 载荷声明集合
     * @param privateKey RSA 私钥
     * @return 签名后的 JWT 字符串
     */
    public static String createRS256(Map<String, Object> claims, PrivateKey privateKey) {
        Builder builder = builder();
        for (Map.Entry<String, Object> entry : claims.entrySet()) {
            builder.claim(entry.getKey(), entry.getValue());
        }
        return builder.compact(Algorithm.RS256, privateKey);
    }

    /**
     * 仅解码 JWT，不做任何签名或时间校验。
     *
     * <p>适用于需要先读取声明再决定如何校验的场景。如需校验签名，请使用 {@link #verify(String, String)}。
     *
     * @param token 待解码的令牌字符串
     * @return 解码后的 {@link DecodedJwt}
     */
    public static DecodedJwt parse(String token) {
        String[] parts = split(token);
        Map<String, Object> header = decodeJson(parts[0]);
        Map<String, Object> payload = decodeJson(parts[1]);
        byte[] signature = new byte[0];
        if (parts.length == 3 && !parts[2].isEmpty()) {
            try {
                signature = B64D.decode(parts[2]);
            } catch (RuntimeException e) {
                throw new JwtException("JWT 签名段 base64url 解码失败", e);
            }
        }
        return new DecodedJwt(token, header, payload, signature);
    }

    /**
     * 用 HS256 与共享密钥验签，并校验 exp / nbf 时间声明。
     *
     * @param token 待验签的令牌字符串
     * @param secret 共享密钥，长度不足 32 字节（256 位）时拒绝验签
     * @return 验签通过的 {@link DecodedJwt}
     * @throws JwtException 签名不符、算法不匹配或时间声明不通过时抛出
     */
    public static DecodedJwt verify(String token, String secret) {
        return verify(token, Algorithm.HS256, secret.getBytes(StandardCharsets.UTF_8), null, 0L);
    }

    /**
     * 用 HS256 与共享密钥验签，并校验 exp / nbf 时间声明，允许配置时钟偏移余量。
     *
     * @param token 待验签的令牌字符串
     * @param secret 共享密钥，长度不足 32 字节（256 位）时拒绝验签
     * @param leewaySeconds 时间声明校验的时钟偏移余量（秒），多机部署时钟略有偏差时使用，非负
     * @return 验签通过的 {@link DecodedJwt}
     * @throws JwtException 签名不符、算法不匹配或时间声明不通过时抛出
     */
    public static DecodedJwt verify(String token, String secret, long leewaySeconds) {
        return verify(token, Algorithm.HS256, secret.getBytes(StandardCharsets.UTF_8), null, leewaySeconds);
    }

    /**
     * 用 RS256 与 RSA 公钥验签，并校验 exp / nbf 时间声明。
     *
     * @param token 待验签的令牌字符串
     * @param publicKey RSA 公钥
     * @return 验签通过的 {@link DecodedJwt}
     * @throws JwtException 签名不符、算法不匹配或时间声明不通过时抛出
     */
    public static DecodedJwt verify(String token, PublicKey publicKey) {
        return verify(token, Algorithm.RS256, null, publicKey, 0L);
    }

    /**
     * 用 RS256 与 RSA 公钥验签，并校验 exp / nbf 时间声明，允许配置时钟偏移余量。
     *
     * @param token 待验签的令牌字符串
     * @param publicKey RSA 公钥
     * @param leewaySeconds 时间声明校验的时钟偏移余量（秒），非负
     * @return 验签通过的 {@link DecodedJwt}
     * @throws JwtException 签名不符、算法不匹配或时间声明不通过时抛出
     */
    public static DecodedJwt verify(String token, PublicKey publicKey, long leewaySeconds) {
        return verify(token, Algorithm.RS256, null, publicKey, leewaySeconds);
    }

    private static DecodedJwt verify(String token, Algorithm expected, byte[] secretBytes, PublicKey publicKey,
                                     long leewaySeconds) {
        if (leewaySeconds < 0) {
            throw new IllegalArgumentException("leewaySeconds 不能为负");
        }
        String[] parts = split(token);
        if (parts.length != 3) {
            throw new JwtException("JWT 必须包含 header.payload.signature 三段");
        }
        Map<String, Object> header = decodeJson(parts[0]);
        Map<String, Object> payload = decodeJson(parts[1]);
        Object algObj = header.get(HEADER_ALG);
        if (!(algObj instanceof String)) {
            throw new JwtException("JWT 头部缺少 alg");
        }
        String algName = (String) algObj;
        if (!algName.equals(expected.jwsName())) {
            throw new JwtException("JWT alg 与期望不符: " + algName + " != " + expected.jwsName());
        }
        byte[] expectedSig;
        try {
            expectedSig = B64D.decode(parts[2]);
        } catch (RuntimeException e) {
            throw new JwtException("JWT 签名段 base64url 解码失败", e);
        }
        String signingInput = parts[0] + DOT + parts[1];
        boolean ok;
        if (expected.asymmetric()) {
            ok = rsaVerify(expected.jcaName(), signingInput.getBytes(StandardCharsets.UTF_8), expectedSig, publicKey);
        } else {
            checkHs256Secret(secretBytes);
            byte[] actual = hmac(expected.jcaName(), signingInput.getBytes(StandardCharsets.UTF_8), secretBytes);
            ok = slowEquals(actual, expectedSig);
        }
        if (!ok) {
            throw new JwtException("JWT 签名校验失败");
        }
        validateTimeClaims(payload, leewaySeconds);
        return new DecodedJwt(token, header, payload, expectedSig);
    }

    /**
     * HS256 共享密钥最低长度校验：密钥不足 32 字节（256 位）时拒绝，
     * 与类文档「建议不小于 32 字节」对齐，避免低熵密钥签出的令牌被离线爆破。
     *
     * @param secretBytes 共享密钥字节
     */
    private static void checkHs256Secret(byte[] secretBytes) {
        if (secretBytes == null || secretBytes.length < 32) {
            throw new IllegalArgumentException(
                    "HS256 共享密钥长度不足 32 字节（256 位），易被离线爆破，请使用更长的密钥");
        }
    }

    private static String[] split(String token) {
        if (token == null) {
            throw new JwtException("token 为 null");
        }
        // 保留尾部空串："h.p.sig.." 这类多余分段直接判非法，而不是被 split 默默吞掉
        String[] parts = token.split("\\.", -1);
        if (parts.length < 2 || parts.length > 3) {
            throw new JwtException("JWT 格式不合法，应为 2 或 3 段");
        }
        return parts;
    }

    private static String encode(Map<String, Object> header, Map<String, Object> claims) {
        String h = B64.encodeToString(JSON.toJsonString(header).getBytes(StandardCharsets.UTF_8));
        String p = B64.encodeToString(JSON.toJsonString(claims).getBytes(StandardCharsets.UTF_8));
        return h + DOT + p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decodeJson(String part) {
        try {
            byte[] bytes = B64D.decode(part);
            String json = new String(bytes, StandardCharsets.UTF_8);
            Map<String, Object> raw = JSON.parseObject(json);
            Map<String, Object> result = new HashMap<String, Object>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                result.put(entry.getKey(), entry.getValue());
            }
            return result;
        } catch (RuntimeException e) {
            throw new JwtException("JWT 段 base64url 解码或 JSON 解析失败", e);
        }
    }

    private static byte[] hmac(String jca, byte[] data, byte[] key) {
        try {
            Mac mac = Mac.getInstance(jca);
            mac.init(new SecretKeySpec(key, jca));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException e) {
            throw new JwtException("不支持的 HMAC 算法: " + jca, e);
        } catch (InvalidKeyException e) {
            throw new JwtException("HMAC 密钥非法", e);
        }
    }

    private static byte[] rsaSign(String jca, byte[] data, PrivateKey key) {
        try {
            Signature signature = Signature.getInstance(jca);
            signature.initSign(key);
            signature.update(data);
            return signature.sign();
        } catch (NoSuchAlgorithmException e) {
            throw new JwtException("不支持的签名算法: " + jca, e);
        } catch (InvalidKeyException e) {
            throw new JwtException("RSA 私钥非法", e);
        } catch (SignatureException e) {
            throw new JwtException("RSA 签名失败", e);
        }
    }

    private static boolean rsaVerify(String jca, byte[] data, byte[] sig, PublicKey key) {
        try {
            Signature signature = Signature.getInstance(jca);
            signature.initVerify(key);
            signature.update(data);
            return signature.verify(sig);
        } catch (NoSuchAlgorithmException e) {
            throw new JwtException("不支持的签名算法: " + jca, e);
        } catch (InvalidKeyException e) {
            throw new JwtException("RSA 公钥非法", e);
        } catch (SignatureException e) {
            throw new JwtException("RSA 验签失败", e);
        }
    }

    /**
     * 校验 exp / nbf 时间声明，允许指定的时钟偏移余量。
     *
     * @param payload 载荷声明
     * @param leewaySeconds 时钟偏移余量（秒），非负；0 表示严格比较
     */
    private static void validateTimeClaims(Map<String, Object> payload, long leewaySeconds) {
        long now = System.currentTimeMillis() / 1000;
        Long exp = toLong(payload.get(CLAIM_EXP));
        if (exp != null && now - leewaySeconds > exp) {
            throw new JwtException("JWT 已过期");
        }
        Long nbf = toLong(payload.get(CLAIM_NBF));
        if (nbf != null && now + leewaySeconds < nbf) {
            throw new JwtException("JWT 尚未生效");
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.valueOf((String) value);
            } catch (NumberFormatException e) {
                throw new JwtException("声明不是合法的数字: " + value);
            }
        }
        throw new JwtException("声明不是数字类型: " + value);
    }

    private static Date toDate(Object value) {
        Long seconds = toLong(value);
        return seconds == null ? null : new Date(seconds * 1000);
    }

    private static Long toSeconds(Date date) {
        return date == null ? null : date.getTime() / 1000;
    }

    private static boolean slowEquals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= (a[i] ^ b[i]);
        }
        return result == 0;
    }
}
