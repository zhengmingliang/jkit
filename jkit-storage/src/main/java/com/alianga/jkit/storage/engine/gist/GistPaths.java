package com.alianga.jkit.storage.engine.gist;

import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * GitHub Gist 的「逻辑目录树 ↔ 扁平文件名」可逆编码，以及文件内容编解码。
 *
 * <p>Gist 的 {@code files} 映射是<b>扁平</b>的：文件名不能含 {@code /}（含斜杠会被
 * GitHub 以 422 拒绝），也没有真正的目录。本类把逻辑路径的每一段做一次转义，再用
 * {@code ~} 拼接成单个文件名，读取时按 {@code ~} 拆回目录层级，从而在 {@code FileStorage}
 * 的目录语义与 Gist 的扁平模型之间做桥接：</p>
 *
 * <pre>{@code
 *   docs/readme.txt   <->  docs~readme.txt
 *   a/b/c.md          <->  a~b~c.md
 * }</pre>
 *
 * <p>转义只针对会破坏可逆性的字符（转义符 {@code %}、分隔符 {@code ~}、路径分隔符
 * {@code /} {@code \、冒号 {@code :} 与控制字符），其余字符（含中文）原样保留，
 * 保证 Gist 网页里看到的文件名仍然可读。外部手工建的 Gist 文件若本身含 {@code ~}，
 * 会被视作一层虚拟目录——这是扁平模型无法区分的固有取舍，已在模块 README 说明。</p>
 *
 * <p><b>内容编解码</b>：Gist 是文本片段存储，只接受 UTF-8 文本。合法 UTF-8 内容原样存
 * （可读）；非 UTF-8 字节（二进制）用 {@link #BINARY_MARKER} 前缀 + Base64 包装，
 * 取回时还原，保证 {@code FileStorage} 的字节级往返契约不被悄悄破坏。</p>
 *
 * @author 郑明亮
 */
public final class GistPaths {
    /** 目录层级分隔符（Gist 文件名里不会出现的字符）。 */
    public static final char FLAT_SEP = '~';
    /** 空目录占位文件的保留文件名（列表时隐藏，但仍用于合成目录）。 */
    public static final String PLACEHOLDER_BASENAME = ".jkit-storage-keep";
    /** 空目录占位文件的内容：非空即可（GitHub 拒绝零字节内容）。 */
    public static final String PLACEHOLDER_CONTENT = "jkit-storage directory placeholder";
    /** 二进制内容包装前缀。 */
    public static final String BINARY_MARKER = "JKIT-GIST-BINARY:";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private GistPaths() {
    }

    /**
     * 逻辑路径 → Gist 扁平文件名。
     *
     * @param logicalPath 逻辑路径（{@code /} 分隔、无前导斜杠）
     * @return 扁平文件名；根目录返回空串
     */
    public static String encode(String logicalPath) {
        String normalized = StoragePaths.normalize(logicalPath);
        if (normalized.isEmpty()) {
            return "";
        }
        String[] parts = normalized.split("/");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append(FLAT_SEP);
            }
            out.append(escapeSegment(parts[i]));
        }
        return out.toString();
    }

    /**
     * Gist 扁平文件名 → 逻辑路径。
     *
     * @param flatName 扁平文件名
     * @return 逻辑路径（已规范化）
     */
    public static String decode(String flatName) {
        if (flatName == null || flatName.isEmpty()) {
            return "";
        }
        String[] parts = flatName.split(String.valueOf(FLAT_SEP), -1);
        List<String> segments = new ArrayList<String>();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            segments.add(unescapeSegment(part));
        }
        return StoragePaths.normalize(String.join("/", segments));
    }

    /**
     * @param logicalPath 逻辑路径
     * @return true 表示这是内部空目录占位文件
     */
    public static boolean isPlaceholder(String logicalPath) {
        return PLACEHOLDER_BASENAME.equals(StoragePaths.name(logicalPath));
    }

    /**
     * @param dir 目录逻辑路径
     * @return 该目录占位文件的扁平文件名
     */
    public static String placeholderFor(String dir) {
        return encode(StoragePaths.join(dir, PLACEHOLDER_BASENAME));
    }

    /**
     * 把字节编码成 Gist 可存的 UTF-8 文本。
     *
     * @param bytes 原始字节
     * @return 可直接写入 {@code files[name].content} 的文本
     */
    public static String encodeContent(byte[] bytes) {
        if (bytes == null) {
            bytes = new byte[0];
        }
        if (bytes.length == 0) {
            // 零字节内容 GitHub 会拒绝；包一层标记保证非空且可还原
            return BINARY_MARKER;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return wrapBinary(bytes);
        }
        // 严格 UTF-8 解码后再编码必须逐字节一致（挡掉非法序列），且不能与二进制标记撞车
        if (!Arrays.equals(text.getBytes(StandardCharsets.UTF_8), bytes)
                || text.startsWith(BINARY_MARKER)) {
            return wrapBinary(bytes);
        }
        return text;
    }

    /**
     * 把 Gist 里的文本内容还原为原始字节。
     *
     * @param content Gist {@code content} 字段；可为 null
     * @return 原始字节
     */
    public static byte[] decodeContent(String content) {
        if (content == null || content.isEmpty()) {
            return new byte[0];
        }
        if (content.startsWith(BINARY_MARKER)) {
            String encoded = content.substring(BINARY_MARKER.length()).trim();
            if (encoded.isEmpty()) {
                return new byte[0];
            }
            try {
                return Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException e) {
                throw StorageException.io("Gist 二进制内容损坏，Base64 解码失败", "-", e);
            }
        }
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static String wrapBinary(byte[] bytes) {
        return BINARY_MARKER + Base64.getEncoder().encodeToString(bytes);
    }

    private static String escapeSegment(String segment) {
        StringBuilder out = new StringBuilder(segment.length() + 8);
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            switch (c) {
                case '%':
                    out.append("%25");
                    break;
                case FLAT_SEP:
                    out.append("%7E");
                    break;
                case '/':
                    out.append("%2F");
                    break;
                case '\\':
                    out.append("%5C");
                    break;
                case ':':
                    out.append("%3A");
                    break;
                default:
                    if (c < 0x20 || c == 0x7F) {
                        out.append(pct(c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }

    private static String unescapeSegment(String segment) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(segment.length() + 8);
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '%' && i + 2 < segment.length()) {
                int hi = Character.digit(segment.charAt(i + 1), 16);
                int lo = Character.digit(segment.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    buf.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            // 代理对（emoji 等）必须成对取 UTF-8 字节，否则会被写成 '?' 损坏文件名
            int width = Character.isHighSurrogate(c) && i + 1 < segment.length()
                    && Character.isLowSurrogate(segment.charAt(i + 1)) ? 2 : 1;
            byte[] bytes = segment.substring(i, i + width).getBytes(StandardCharsets.UTF_8);
            buf.write(bytes, 0, bytes.length);
            i += width - 1;
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String pct(int value) {
        return "%" + HEX[(value >> 4) & 0xF] + HEX[value & 0xF];
    }
}
