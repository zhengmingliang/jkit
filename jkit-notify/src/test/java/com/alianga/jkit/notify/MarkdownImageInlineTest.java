package com.alianga.jkit.notify;

import org.junit.BeforeClass;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 本地相对路径图片内嵌成 Base64（{@link ImageInliner}）。
 */
public class MarkdownImageInlineTest {
    private static File dir;

    @BeforeClass
    public static void prepareImages() throws IOException {
        dir = Files.createTempDirectory("jkit-md-img").toFile();
        File nested = new File(dir, "assets");
        assertTrue(nested.mkdirs());
        writePng(new File(nested, "shot.png"));
        writePng(new File(dir, "top.png"));
        Files.write(new File(dir, "notes.txt").toPath(), "not an image".getBytes("UTF-8"));
    }

    private static void writePng(File file) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "png", file);
    }

    private static String render(String md, MarkdownRenderOptions options) {
        return NotifyUtils.markdownToHtml(md, options);
    }

    /**
     * 相对路径图片转成 data URI。
     */
    @Test
    public void relativeImageBecomesDataUri() {
        String html = render("![截图](./assets/shot.png)",
                MarkdownRenderOptions.create().imageBaseDir(dir.getAbsolutePath()));
        assertTrue(html.contains("src=\"data:image/png;base64,"));
        assertTrue(html.contains("alt=\"截图\""));
        assertFalse("不能再保留原相对路径", html.contains("./assets/shot.png"));
    }

    /**
     * 不带 {@code ./} 的相对路径、上层目录路径同样能解析。
     */
    @Test
    public void resolvesPlainAndParentRelativePaths() {
        String plain = render("![x](top.png)",
                MarkdownRenderOptions.create().imageBaseDir(dir.getAbsolutePath()));
        assertTrue(plain.contains("data:image/png;base64,"));
        String parent = render("![x](../top.png)",
                MarkdownRenderOptions.create().imageBaseDir(dir.getAbsolutePath() + "/assets"));
        assertTrue(parent.contains("data:image/png;base64,"));
    }

    /**
     * 远程地址与 cid 原样保留。
     */
    @Test
    public void remoteAndCidImagesKept() {
        MarkdownRenderOptions options = MarkdownRenderOptions.create()
                .imageBaseDir(dir.getAbsolutePath());
        String remote = render("![x](https://cdn.dog.alianga.com/a.png)", options);
        assertTrue(remote.contains("src=\"https://cdn.dog.alianga.com/a.png\""));
        String cid = render("![x](cid:img1)", options);
        assertTrue(cid.contains("src=\"cid:img1\""));
        String protocolRelative = render("![x](//cdn.dog.alianga.com/a.png)", options);
        assertTrue(protocolRelative.contains("src=\"//cdn.dog.alianga.com/a.png\""));
    }

    /**
     * 文件不存在、不是图片、超过体积上限时保留原路径，不抛异常。
     */
    @Test
    public void keepsOriginalSrcWhenNotEmbeddable() {
        MarkdownRenderOptions options = MarkdownRenderOptions.create()
                .imageBaseDir(dir.getAbsolutePath());
        assertTrue(render("![x](./assets/missing.png)", options).contains("./assets/missing.png"));
        assertTrue(render("![x](./notes.txt)", options).contains("./notes.txt"));
        assertTrue(render("![x](./top.png)", options.maxInlineImageBytes(10))
                .contains("./top.png"));
    }

    /**
     * 没配基准目录、或显式关闭内嵌时，一个字节都不动。
     */
    @Test
    public void noBaseDirOrDisabledLeavesHtmlAlone() {
        String md = "![x](./assets/shot.png)";
        assertTrue(render(md, MarkdownRenderOptions.create()).contains("./assets/shot.png"));
        assertTrue(render(md, MarkdownRenderOptions.create()
                .imageBaseDir(dir.getAbsolutePath()).inlineImage(false))
                .contains("./assets/shot.png"));
        assertTrue(render(md, MarkdownRenderOptions.create().imageBaseDir("   "))
                .contains("./assets/shot.png"));
    }

    /**
     * 内嵌后仍然能被内联上色，且不会破坏后续标签（img 之后还有段落与链接）。
     */
    @Test
    public void embedWorksWithInlineStyleAndFollowingTags() {
        String md = "![x](./assets/shot.png)\n\n正文 **加粗** 与 [链接](https://a.com)\n";
        String html = render(md, MarkdownRenderOptions.create()
                .imageBaseDir(dir.getAbsolutePath()).inlineStyle(true));
        assertTrue(html.contains("src=\"data:image/png;base64,"));
        assertTrue(html.contains("style=\"max-width:100%;height:auto\""));
        assertTrue(html.contains("<strong style="));
        assertTrue(html.contains("href=\"https://a.com\""));
    }

    /**
     * 多张图片各自内嵌，互不影响。
     */
    @Test
    public void embedsEveryImage() {
        String md = "![a](./assets/shot.png)\n\n![b](./top.png)\n";
        String html = render(md, MarkdownRenderOptions.create().imageBaseDir(dir.getAbsolutePath()));
        assertEquals("两张图都要内嵌", 2, countDataUri(html));
    }

    private static int countDataUri(String html) {
        int count = 0;
        int at = 0;
        while (true) {
            int found = html.indexOf("data:image/", at);
            if (found < 0) {
                return count;
            }
            count++;
            at = found + 11;
        }
    }

    /**
     * {@code <imgx>} 这类长得像 img 的标签不被误判。
     */
    @Test
    public void doesNotTouchLookalikeTags() {
        String html = ImageInliner.apply("<imgx src=\"./top.png\">",
                MarkdownRenderOptions.create().imageBaseDir(dir.getAbsolutePath()));
        assertEquals("<imgx src=\"./top.png\">", html);
    }
}
