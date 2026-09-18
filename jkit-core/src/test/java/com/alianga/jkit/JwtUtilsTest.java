package com.alianga.jkit;

import com.alianga.jkit.JwtUtils.Algorithm;
import com.alianga.jkit.JwtUtils.DecodedJwt;
import com.alianga.jkit.JwtUtils.JwtException;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JwtUtilsTest {

    private static final String SECRET = "this-is-a-long-enough-shared-secret-32bytes!!";

    private static KeyPair rsaKeyPair() throws Exception {
        return EncryptUtils.RSA.buildKeyPair(2048);
    }

    private static String b64url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void hs256RoundTrip() {
        Date future = new Date(System.currentTimeMillis() + 3600_000L);
        String token = JwtUtils.builder()
                .subject("user-1")
                .issuer("jkit")
                .audience("svc-a", "svc-b")
                .expiration(future)
                .claim("role", "admin")
                .compact(Algorithm.HS256, SECRET);

        assertFalse("base64url 不应带填充 =", token.contains("="));

        DecodedJwt parsed = JwtUtils.parse(token);
        assertEquals("user-1", parsed.getSubject());
        assertEquals("jkit", parsed.getIssuer());
        assertEquals(2, parsed.getAudience().size());
        assertEquals("admin", parsed.getClaim("role"));

        DecodedJwt verified = JwtUtils.verify(token, SECRET);
        assertEquals("user-1", verified.getSubject());
        assertNotNull(verified.getExpiration());
        assertEquals("HS256", verified.getHeader().get("alg"));
        assertEquals("JWT", verified.getHeader().get("typ"));
    }

    @Test
    public void hs256WrongSecretFails() {
        String token = JwtUtils.builder().subject("u").compact(Algorithm.HS256, SECRET);
        assertThrows(JwtException.class, () -> JwtUtils.verify(token, "wrong-secret-wrong-secret-wrong-secret!!"));
    }

    @Test
    public void hs256TamperedFails() {
        String token = JwtUtils.builder().subject("u").compact(Algorithm.HS256, SECRET);
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + b64url("{\"sub\":\"attacker\"}") + "." + parts[2];
        assertThrows(JwtException.class, () -> JwtUtils.verify(tampered, SECRET));
    }

    @Test
    public void rs256RoundTrip() throws Exception {
        KeyPair kp = rsaKeyPair();
        String token = JwtUtils.builder()
                .subject("user-2")
                .expiration(new Date(System.currentTimeMillis() + 3600_000L))
                .compact(Algorithm.RS256, kp.getPrivate());

        DecodedJwt verified = JwtUtils.verify(token, kp.getPublic());
        assertEquals("user-2", verified.getSubject());
        assertEquals("RS256", verified.getHeader().get("alg"));
    }

    @Test
    public void rs256WrongKeyFails() throws Exception {
        KeyPair kp = rsaKeyPair();
        KeyPair other = rsaKeyPair();
        String token = JwtUtils.builder().subject("u").compact(Algorithm.RS256, kp.getPrivate());
        assertThrows(JwtException.class, () -> JwtUtils.verify(token, other.getPublic()));
    }

    @Test
    public void rs256CannotVerifyWithSecret() throws Exception {
        KeyPair kp = rsaKeyPair();
        String token = JwtUtils.builder().subject("u").compact(Algorithm.RS256, kp.getPrivate());
        assertThrows(JwtException.class, () -> JwtUtils.verify(token, SECRET));
    }

    @Test
    public void expiredTokenFails() {
        String token = JwtUtils.builder()
                .subject("u")
                .expiration(new Date(System.currentTimeMillis() - 3600_000L))
                .compact(Algorithm.HS256, SECRET);
        assertThrows(JwtException.class, () -> JwtUtils.verify(token, SECRET));
    }

    @Test
    public void notYetValidFails() {
        String token = JwtUtils.builder()
                .subject("u")
                .notBefore(new Date(System.currentTimeMillis() + 3600_000L))
                .compact(Algorithm.HS256, SECRET);
        assertThrows(JwtException.class, () -> JwtUtils.verify(token, SECRET));
    }

    @Test
    public void parseWithoutVerificationKeepsExpiredClaims() {
        String token = JwtUtils.builder()
                .subject("u")
                .expiration(new Date(System.currentTimeMillis() - 3600_000L))
                .compact(Algorithm.HS256, SECRET);
        DecodedJwt parsed = JwtUtils.parse(token);
        assertEquals("u", parsed.getSubject());
        assertNotNull(parsed.getExpiration());
    }

    @Test
    public void audienceSingleVsMultiple() {
        String single = JwtUtils.builder().audience("only-one").compact(Algorithm.HS256, SECRET);
        assertEquals(1, JwtUtils.parse(single).getAudience().size());

        String multi = JwtUtils.builder().audience("a", "b", "c").compact(Algorithm.HS256, SECRET);
        assertEquals(3, JwtUtils.parse(multi).getAudience().size());
    }

    @Test
    public void createHS256Convenience() {
        Map<String, Object> claims = new HashMap<String, Object>();
        claims.put("sub", "conv-user");
        claims.put("role", "reader");
        String token = JwtUtils.createHS256(claims, SECRET);

        DecodedJwt verified = JwtUtils.verify(token, SECRET);
        assertEquals("conv-user", verified.getSubject());
        assertEquals("reader", verified.getClaim("role"));
    }

    @Test
    public void createRS256Convenience() throws Exception {
        KeyPair kp = rsaKeyPair();
        Map<String, Object> claims = new HashMap<String, Object>();
        claims.put("sub", "conv-user");
        String token = JwtUtils.createRS256(claims, kp.getPrivate());

        assertEquals("conv-user", JwtUtils.verify(token, kp.getPublic()).getSubject());
    }

    @Test
    public void noneAlgRejected() {
        String header = b64url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = b64url("{\"sub\":\"x\"}");
        String token = header + "." + payload + ".";
        assertThrows(JwtException.class, () -> JwtUtils.verify(token, SECRET));
    }

    @Test
    public void malformedTokensRejected() {
        assertThrows(JwtException.class, () -> JwtUtils.parse("onlyone"));
        assertThrows(JwtException.class, () -> JwtUtils.parse("a.b.c.d"));
        assertThrows(JwtException.class, () -> JwtUtils.parse(null));
    }

    @Test
    public void issuedAtAndJtiPreserved() {
        String token = JwtUtils.builder()
                .id("trace-123")
                .issuedAt(new Date())
                .compact(Algorithm.HS256, SECRET);
        DecodedJwt parsed = JwtUtils.parse(token);
        assertEquals("trace-123", parsed.getId());
        assertNotNull(parsed.getIssuedAt());
    }

    @Test
    public void emptyCustomClaimNotWritten() {
        String token = JwtUtils.builder().expiration(null).compact(Algorithm.HS256, SECRET);
        DecodedJwt parsed = JwtUtils.parse(token);
        assertTrue(parsed.getClaims().isEmpty() || parsed.getClaim("exp") == null);
    }
}
