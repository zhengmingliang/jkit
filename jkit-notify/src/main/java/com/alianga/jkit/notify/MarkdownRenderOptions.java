package com.alianga.jkit.notify;

import java.io.File;

/**
 * Markdown → HTML 的渲染选项（主题、内联样式、代码高亮、响应式、本地图片内嵌）。
 *
 * <p>典型用法：
 * <pre>{@code
 * // 邮件：默认主题 + 代码高亮，样式放 <head><style>
 * NotifyUtils.markdownToDocument(md, MarkdownRenderOptions.create());
 *
 * // 微信 / Outlook：内联样式，兼容剥离 <style> 的客户端
 * NotifyUtils.markdownToDocument(md, MarkdownRenderOptions.create()
 *         .theme(MarkdownTheme.LARK)
 *         .inlineStyle(true));
 *
 * // 文档里的 ./assets/x.png 内嵌成 Base64，随正文一起发出去
 * NotifyUtils.markdownToDocument(md, MarkdownRenderOptions.create()
 *         .imageBaseDir("/opt/docs/articles"));
 * }</pre>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class MarkdownRenderOptions {
    /**
     * 单张图片内嵌的体积上限默认值（2MB）。公众号配图常在 1~2MB，卡在 1MB 会让功能形同虚设；
     * 但 Base64 会再膨胀约 1/3，超过上限的图片保留原路径，避免把整封邮件撑爆。
     */
    public static final long DEFAULT_MAX_INLINE_IMAGE_BYTES = 2L * 1024L * 1024L;

    private MarkdownTheme theme = MarkdownTheme.DEFAULT;
    private boolean inlineStyle;
    private boolean highlight = true;
    private boolean responsive = true;
    private String imageBaseDir;
    private boolean inlineImage = true;
    private long maxInlineImageBytes = DEFAULT_MAX_INLINE_IMAGE_BYTES;

    private MarkdownRenderOptions() {
    }

    /**
     * 创建一份默认选项：经典主题、开启代码高亮与响应式、样式放 {@code <style>} 标签。
     *
     * @return 新选项实例
     */
    public static MarkdownRenderOptions create() {
        return new MarkdownRenderOptions();
    }

    /**
     * 设置主题，{@code null} 视为 {@link MarkdownTheme#DEFAULT}。
     *
     * @param theme 主题
     * @return 当前实例，便于链式调用
     */
    public MarkdownRenderOptions theme(MarkdownTheme theme) {
        this.theme = theme == null ? MarkdownTheme.DEFAULT : theme;
        return this;
    }

    /**
     * 是否把样式内联到每个标签的 {@code style} 属性。
     *
     * <p>默认 {@code false}（只输出 {@code <style>} 样式表）。发往 Outlook、企业邮箱或粘贴到微信时
     * 建议打开；内联后伪元素与斑马纹等选择器类样式不可用，观感会略简化。
     *
     * @param inlineStyle 是否内联
     * @return 当前实例，便于链式调用
     */
    public MarkdownRenderOptions inlineStyle(boolean inlineStyle) {
        this.inlineStyle = inlineStyle;
        return this;
    }

    /**
     * 是否对围栏代码块做语法高亮（java/sql/json/yaml/xml/shell 等常见语言）。
     *
     * @param highlight 是否高亮
     * @return 当前实例，便于链式调用
     */
    public MarkdownRenderOptions highlight(boolean highlight) {
        this.highlight = highlight;
        return this;
    }

    /**
     * 是否附带移动端 / 桌面端媒体查询。
     *
     * @param responsive 是否响应式
     * @return 当前实例，便于链式调用
     */
    public MarkdownRenderOptions responsive(boolean responsive) {
        this.responsive = responsive;
        return this;
    }

    /**
     * 设置本地图片的基准目录，开启相对路径图片内嵌。
     *
     * <p>Markdown 里的 {@code ![](./assets/a.png)} 会按这个目录解析，读到的图片转成
     * {@code data:image/png;base64,...} 写进 {@code src}，正文就能脱离原文件独立展示（邮件、
     * 微信粘贴、导出单文件 HTML 都靠它）。远程地址（{@code http(s)://}、{@code cid:} 等）不受影响。
     *
     * <p>文件不存在、不是图片、超过 {@link #maxInlineImageBytes(long)}（默认 2MB）时保留原 {@code src}，
     * 不抛异常。不设置基准目录则不做任何内嵌。
     *
     * @param dir 基准目录，{@code null} / 空串表示关闭内嵌
     * @return 当前实例，便于链式调用
     */
    public MarkdownRenderOptions imageBaseDir(String dir) {
        this.imageBaseDir = dir == null || dir.trim().isEmpty() ? null : dir.trim();
        return this;
    }

    /**
     * 设置本地图片的基准目录（{@link File} 便捷重载）。
     *
     * @param dir 基准目录，{@code null} 表示关闭内嵌
     * @return 当前实例，便于链式调用
     */
    public MarkdownRenderOptions imageBaseDir(File dir) {
        return imageBaseDir(dir == null ? null : dir.getAbsolutePath());
    }

    /**
     * 是否把本地相对路径图片内嵌成 Base64，默认 {@code true}（仅在设置了基准目录时生效）。
     *
     * @param inlineImage 内嵌返回 {@code true}
     * @return 当前实例，便于链式调用
     */
    public MarkdownRenderOptions inlineImage(boolean inlineImage) {
        this.inlineImage = inlineImage;
        return this;
    }

    /**
     * 单张图片内嵌的体积上限，超过则保留原 {@code src}，默认 2MB。
     *
     * @param maxBytes 字节上限，非正数表示不限制
     * @return 当前实例，便于链式调用
     */
    public MarkdownRenderOptions maxInlineImageBytes(long maxBytes) {
        this.maxInlineImageBytes = maxBytes;
        return this;
    }

    /**
     * 主题。
     *
     * @return 主题，永不为 {@code null}
     */
    public MarkdownTheme theme() {
        return theme;
    }

    /**
     * 是否内联样式。
     *
     * @return 内联返回 {@code true}
     */
    public boolean inlineStyle() {
        return inlineStyle;
    }

    /**
     * 是否做代码高亮。
     *
     * @return 高亮返回 {@code true}
     */
    public boolean highlight() {
        return highlight;
    }

    /**
     * 是否响应式。
     *
     * @return 响应式返回 {@code true}
     */
    public boolean responsive() {
        return responsive;
    }

    /**
     * 本地图片基准目录。
     *
     * @return 目录路径；未设置时为 {@code null}
     */
    public String imageBaseDir() {
        return imageBaseDir;
    }

    /**
     * 是否内嵌本地图片。
     *
     * @return 内嵌返回 {@code true}
     */
    public boolean inlineImage() {
        return inlineImage;
    }

    /**
     * 单张图片内嵌的体积上限。
     *
     * @return 字节上限
     */
    public long maxInlineImageBytes() {
        return maxInlineImageBytes;
    }
}
