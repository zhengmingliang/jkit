package com.alianga.jkit.http.codegen;

import com.alianga.jkit.Base64Utils;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Auth;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码生成共用逻辑（媒体类型、认证头）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class CurlGenSupport {
    private CurlGenSupport() {
    }

    /**
     * @param req 解析模型
     * @return Content-Type
     */
    public static String mediaType(ParsedCurlRequest req) {
        String ct = req.header("Content-Type");
        if (ct != null) {
            int sc = ct.indexOf(';');
            return sc < 0 ? ct.trim() : ct.substring(0, sc).trim();
        }
        if (req.body().kind() == Body.Kind.JSON) {
            return "application/json";
        }
        if (req.body().kind() == Body.Kind.URLENCODED) {
            return "application/x-www-form-urlencoded";
        }
        if (req.body().kind() == Body.Kind.FILE) {
            return "application/octet-stream";
        }
        return "text/plain";
    }

    /**
     * @param req 解析模型
     * @return 含认证信息的请求头
     */
    public static List<Header> headersWithAuth(ParsedCurlRequest req) {
        List<Header> list = new ArrayList<Header>(req.headers());
        Auth auth = req.auth();
        if (auth == null || req.hasHeader("Authorization")) {
            return list;
        }
        if ("bearer".equals(auth.type())) {
            list.add(new Header("Authorization", "Bearer " + auth.token()));
        } else if ("basic".equals(auth.type())) {
            String token = basicToken(auth.user(), auth.password());
            list.add(new Header("Authorization", "Basic " + token));
        }
        return list;
    }

    static String basicToken(String user, String password) {
        String raw = (user == null ? "" : user) + ":" + (password == null ? "" : password);
        return Base64Utils.encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static boolean alwaysSupports(ParsedCurlRequest request) {
        return request != null;
    }
}
