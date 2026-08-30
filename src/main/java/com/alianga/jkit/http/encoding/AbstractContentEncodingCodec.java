package com.alianga.jkit.http.encoding;

import java.util.Locale;

/**
 * 按名称（及别名）匹配的编解码适配器基类。
 */
public abstract class AbstractContentEncodingCodec implements ContentEncodingCodec {
    private final String name;
    private final String[] aliases;

    /**
     * 构造编解码适配器
     *
     * @param name 编码的标准名称，需为小写形式，用于名称匹配
     * @param aliases 编码的别名，需为小写形式，为 {@code null} 时按空数组处理
     */
    protected AbstractContentEncodingCodec(String name, String... aliases) {
        this.name = name;
        this.aliases = aliases == null ? new String[0] : aliases;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean matches(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        String lower = token.trim().toLowerCase(Locale.ROOT);
        if (name.equals(lower)) {
            return true;
        }
        for (int i = 0; i < aliases.length; i++) {
            if (aliases[i].equals(lower)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canDecode() {
        return true;
    }

    @Override
    public boolean canEncode() {
        return false;
    }
}
