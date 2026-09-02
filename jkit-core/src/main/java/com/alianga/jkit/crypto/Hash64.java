package com.alianga.jkit.crypto;

/**
 * FNV-1a 64 位哈希工具类。
 *
 * <p>FNV（Fowler-Noll-Vo）是一种非密码学哈希函数，设计简洁、计算速度快，
 * 适用于哈希表、缓存、分布式路由等场景，不适合密码学用途。
 *
 * <p>算法公式（FNV-1a）：
 * <pre>
 * hash = OFFSET_BASIS
 * for each byte:
 * hash ^= byte
 * hash *= PRIME
 * </pre>
 *
 * @author 郑明亮
 * @since 1.0
 */
public final class Hash64 {
    /**
     * FNV-1a 64 位偏移基值（Offset Basis）。
     */
    public static final long OFFSET_BASIS = 0xcbf29ce484222325L;

    /**
     * FNV-1a 64 位质数（Prime）。
     */
    public static final long PRIME = 0x100000001b3L;

    private Hash64() {
    }

    /**
     * 对字节数组计算 FNV-1a 64 位哈希值。
     *
     * @param bytes 输入字节数组
     * @return 64 位哈希值
     */
    public static long hash(byte[] bytes) {
        long rv = OFFSET_BASIS;
        for (byte b : bytes) {
            rv ^= b;
            rv *= PRIME;
        }
        return rv;
    }

    /**
     * 对字符串计算 FNV-1a 64 位哈希值。
     *
     * @param chars 输入字符串
     * @return 64 位哈希值
     */
    public static long hash(String chars) {
        long rv = OFFSET_BASIS;
        int len = chars.length();
        for (int i = 0; i < len; i++) {
            rv ^= chars.charAt(i);
            rv *= PRIME;
        }
        return rv;
    }

    /**
     * 基于已有哈希值，追加字符串继续计算 FNV-1a 64 位哈希值。
     *
     * @param rv 已有的哈希值
     * @param chars 待追加的字符串
     * @return 更新后的 64 位哈希值
     */
    public static long hash(long rv, String chars) {
        int len = chars.length();
        for (int i = 0; i < len; i++) {
            rv ^= chars.charAt(i);
            rv *= PRIME;
        }
        return rv;
    }

    /**
     * 基于已有哈希值，追加单个字符继续计算 FNV-1a 64 位哈希值。
     *
     * @param rv 已有的哈希值
     * @param ch 待追加的字符
     * @return 更新后的 64 位哈希值
     */
    public static long hash(long rv, int ch) {
        rv ^= ch;
        rv *= PRIME;
        return rv;
    }
}
