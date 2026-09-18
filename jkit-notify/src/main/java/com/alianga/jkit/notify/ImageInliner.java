package com.alianga.jkit.notify;

import com.alianga.jkit.Base64Utils;
import com.alianga.jkit.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.util.Locale;

/**
 * 把 Markdown 里引用本地相对路径的图片转成 {@code data:image/...;base64,...}（零依赖）。
 *
 * <p>邮件正文是独立的 HTML 文档，带不走 {@code ./assets/x.png} 这种相对路径——收件端要么裂图，
 * 要么得靠 base 标签碰运气。内嵌成 data URI 后图片随正文一起走，无需附件、无需图床。
 *
 * <p>只对**看起来是本地路径**的 {@code src} 动手：{@code http(s)://}、{@code //}、{@code data:}、
 * {@code cid:}、{@code mailto:} 一律原样保留。文件不存在、不是图片、超过体积上限、读取失败时
 * 也保留原 {@code src}——通知渠道不该因为一张配图就发不出去。
 *
 * <p>与 {@link HtmlInliner} 同属片段后处理：只认 {@link Markdown} 自己产出的 {@code <img src="...">}，
 * 其余内容原样透传。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
final class ImageInliner {
    /**
     * 一眼就能判断是远程 / 协议型地址的前缀，命中即跳过。
     */
    private static final String[] REMOTE_PREFIXES = {
            "http://", "https://", "//", "data:", "cid:", "mailto:", "ftp://", "about:"
    };

    private ImageInliner() {
    }

    /**
     * 把片段里的本地图片转成 data URI。
     *
     * @param html {@link Markdown} 产出的片段
     * @param options 渲染选项，未配置基准目录或关闭开关时原样返回
     * @return 处理后的片段
     */
    static String apply(String html, MarkdownRenderOptions options) {
        String baseDir = options.imageBaseDir();
        if (html == null || html.isEmpty() || !options.inlineImage()
                || StringUtils.isEmpty(baseDir)) {
            return html;
        }
        StringBuilder out = new StringBuilder(html.length() + 256);
        int i = 0;
        while (i < html.length()) {
            int at = indexOfImg(html, i);
            if (at < 0) {
                out.append(html, i, html.length());
                break;
            }
            out.append(html, i, at);
            int end = html.indexOf('>', at);
            if (end < 0) {
                out.append(html, at, html.length());
                break;
            }
            out.append(embed(html.substring(at, end), baseDir, options.maxInlineImageBytes()));
            i = end + 1;
        }
        return out.toString();
    }

    /**
     * 找下一个 {@code <img} 起始标签（后面必须跟空白或 {@code >}，避免误匹配 {@code <imgx>}）。
     */
    private static int indexOfImg(String html, int from) {
        int at = from;
        while (at >= 0) {
            at = html.indexOf("<img", at);
            if (at < 0) {
                return -1;
            }
            int next = at + 4;
            if (next >= html.length()) {
                return -1;
            }
            char c = html.charAt(next);
            if (c == '>' || c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '/') {
                return at;
            }
            at = next;
        }
        return -1;
    }

    private static String embed(String tag, String baseDir, long maxBytes) {
        int srcAt = tag.indexOf("src=\"");
        if (srcAt < 0) {
            return tag;
        }
        int valueAt = srcAt + 5;
        int close = tag.indexOf('"', valueAt);
        if (close < 0) {
            return tag;
        }
        String dataUri = toDataUri(tag.substring(valueAt, close), baseDir, maxBytes);
        if (dataUri == null) {
            return tag;
        }
        return tag.substring(0, valueAt) + dataUri + tag.substring(close);
    }

    /**
     * 本地图片转 data URI；不可内嵌时返回 {@code null}（调用方保留原 {@code src}）。
     */
    private static String toDataUri(String src, String baseDir, long maxBytes) {
        if (StringUtils.isEmpty(src) || isRemote(src)) {
            return null;
        }
        File file = resolve(baseDir, src);
        if (file == null || !file.isFile()) {
            return null;
        }
        long size = file.length();
        if (size <= 0 || size > Integer.MAX_VALUE || (maxBytes > 0 && size > maxBytes)) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            return null;
        }
        String mime = NotifyUtils.detectMimeType(file.getName(), bytes);
        if (mime == null || !mime.startsWith("image/")) {
            return null;
        }
        return "data:" + mime + ";base64," + Base64Utils.encodeToString(bytes);
    }

    private static boolean isRemote(String src) {
        String lower = src.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("#")) {
            return true;
        }
        for (String prefix : REMOTE_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按基准目录解析图片路径：支持 {@code ./}、{@code ../}、绝对路径与 {@code file:} 前缀。
     */
    private static File resolve(String baseDir, String src) {
        String path = src.trim();
        if (path.toLowerCase(Locale.ROOT).startsWith("file:")) {
            path = path.substring("file:".length());
            while (path.startsWith("//")) {
                path = path.substring(1);
            }
        }
        int cut = path.indexOf('?');
        if (cut < 0) {
            cut = path.indexOf('#');
        }
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        if (StringUtils.isEmpty(path)) {
            return null;
        }
        if (path.indexOf('%') >= 0) {
            try {
                path = URLDecoder.decode(path, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                // UTF-8 必然存在，走到这里说明环境异常，保持原样继续
            } catch (IllegalArgumentException e) {
                // % 后面不是合法转义，按字面量处理
            }
        }
        File file = new File(path);
        return file.isAbsolute() ? file : new File(baseDir, path);
    }
}
