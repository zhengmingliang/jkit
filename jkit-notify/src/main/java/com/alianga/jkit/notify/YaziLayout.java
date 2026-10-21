package com.alianga.jkit.notify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YAZI 主题的结构化排版：把扁平 HTML 片段重排成 quickref.cn / refs-cli 的文档骨架。
 *
 * <p>参考实现（jaywcjlove/refs-cli）不是给标题换颜色，而是按标题层级重组 DOM：
 * H1 作为页头，H2 作为分区，H3 作为网格卡片（{@code col-span-2} / {@code row-span-2}
 * 控制跨列跨行），H4-H6 留在卡片正文里。本类在零依赖前提下复刻这条规则，并补齐
 * rehype-slug 风格的锚点 id 与 rehype-autolink-headings 的空锚点。
 *
 * <p>普通模式输出类名，由 {@link YaziTheme} 的样式表控制版式；内联模式（邮件）把
 * 结构、卡片、标签、表格与代码块全部写成 {@code style} 属性，不依赖 {@code class}
 * 与 {@code <style>}，并用 flex 代替 grid、长代码自动换行，适配会过滤类名的邮件客户端。
 *
 * <p>输入是 {@link Markdown} 已渲染好的扁平片段，输出是带 {@code jkit-yazi} 根节点的
 * 结构化片段。只被 {@link MarkdownTheme#YAZI} 使用。
 *
 * @author 郑明亮
 * @since 2.0.5
 */
final class YaziLayout {
    private static final String ICON_LINK = "<span class=\"icon icon-link\"></span>";

    private static final String ROOT_INLINE = "font-family:" + YaziTheme.FONT
            + ";font-size:14px;line-height:1.6;color:#24292f;background:transparent;"
            + "word-wrap:break-word";

    private static final String H1_HEADER_INLINE = "text-align:center;padding:24px 0 8px";
    private static final String H1_INLINE = "margin:0 0 20px;font-size:42px;line-height:1.15;"
            + "font-weight:400;color:#24292f;text-align:center";
    private static final String H1_BODY_INLINE = "color:#6e7781;text-align:center;margin:0";
    private static final String H1_P_INLINE = "margin:0 0 8px;color:#6e7781;font-size:15px;"
            + "line-height:1.7;text-align:center";

    private static final String H2_SECTION_INLINE = "margin:0 0 28px";
    private static final String H2_INLINE = "margin:0 0 12px;font-size:28px;line-height:1.25;"
            + "font-weight:300;color:#24292f";
    private static final String H2_BODY_INLINE = "color:#94a3b8;margin:0 0 14px";
    private static final String H2_P_INLINE = "margin:0 0 8px;color:#94a3b8;font-size:15px;"
            + "line-height:1.7";
    private static final String H2_GRID_INLINE = "display:flex;flex-wrap:wrap;gap:15px;"
            + "align-items:stretch;font-size:14px;line-height:1.6";

    private static final String H3_INLINE = "display:block;float:right;margin:-24px 0 0;"
            + "padding:4px 12px;background:#228e6c;color:#e2e8f0;font-size:12px;line-height:16px;"
            + "font-weight:400;border-radius:0 8px;letter-spacing:.5px";
    private static final String CARD_INLINE = "display:flex;flex-direction:column;"
            + "background:#fff;border-radius:8px;padding:24px 0 6px;margin:0 0 15px;"
            + "box-shadow:0 1px 2px rgba(102,119,136,.3);overflow:hidden;flex:1 1 30%;"
            + "min-width:220px";
    private static final String CARD_P_INLINE = "margin:0;padding:10px 16px;color:#6e7781;"
            + "background:#f3f4f6;font-size:14px;line-height:1.7";
    private static final String H4_INLINE = "margin:0;padding:6px 16px;background:#eef0f3;"
            + "color:#24292f;text-align:center;font-size:14px;font-weight:400";

    private static final String TABLE_INLINE = "display:table;width:100%;max-width:100%;"
            + "border:0;border-collapse:collapse;margin:0;font-size:14px;line-height:1.6;"
            + "color:#24292f;background:transparent";
    private static final String THEAD_SHOW_INLINE = "display:table-header-group;"
            + "border-bottom:1px solid #e5e7eb";
    private static final String THEAD_HIDE_INLINE = "display:none";
    private static final String TH_INLINE = "padding:9px 14px;text-align:left;font-weight:400;"
            + "color:#10b981;background:transparent;border:0;border-bottom:1px solid #e5e7eb;"
            + "white-space:nowrap";
    private static final String TD_INLINE = "padding:9px 14px;text-align:left;vertical-align:top;"
            + "border:0;border-bottom:1px solid #ececec;overflow-wrap:break-word";
    private static final String TABLE_CODE_INLINE = "font-family:" + YaziTheme.MONO
            + ";font-size:13px;color:#059669;background:transparent;padding:0;"
            + "white-space:pre-wrap;overflow-wrap:break-word";
    private static final String KBD_CODE_INLINE = "display:inline-block;padding:1px 6px;"
            + "background:#eef0f3;border:1px solid #d0d7de;border-radius:3px;color:#6e7781;"
            + "font-family:Arial,Helvetica,sans-serif;font-size:12px;line-height:1.5;"
            + "letter-spacing:.5px;white-space:nowrap";

    private static final String CARD_UL_INLINE = "margin:8px 0;padding:0 0 0 18px;"
            + "list-style:disc";
    private static final String CARD_OL_INLINE = "margin:8px 0;padding:0 0 0 18px;"
            + "list-style:none";
    private static final String CARD_LI_INLINE = "margin:0;padding:7px 0;"
            + "border-bottom:1px solid #ececec";
    private static final String BODY_UL_INLINE = "margin:0 0 0 1.2em;padding:0;list-style:disc";
    private static final String BODY_OL_INLINE = "margin:0 0 0 1.2em;padding:0;list-style:decimal";
    private static final String BODY_LI_INLINE = "margin:6px 0;padding:0;border:0";

    private static final String PRE_INLINE = "margin:0;padding:14px 16px;background:transparent;"
            + "color:#24292f;font-family:" + YaziTheme.MONO + ";font-size:13px;line-height:1.6;"
            + "white-space:pre-wrap;word-break:break-word;overflow-wrap:anywhere";
    private static final String PRE_CODE_INLINE = "display:block;background:transparent;padding:0;"
            + "font-family:" + YaziTheme.MONO + ";font-size:13px;line-height:1.6;"
            + "white-space:pre-wrap;word-break:break-word";
    private static final String INLINE_CODE_INLINE = "font-family:" + YaziTheme.MONO
            + ";font-size:13px;color:#059669;background:transparent;padding:0";
    private static final String LINK_INLINE = "color:#0284c7;text-decoration:underline";
    private static final String QUOTE_INLINE = "margin:0;padding:12px 16px;color:#6e7781;"
            + "background:#f8fafc;border-left:3px solid #dbe2ea";
    private static final String HR_INLINE = "border:0;border-top:1px solid #d0d7de;margin:16px 0";
    private static final String IMG_INLINE = "max-width:100%;height:auto;display:block;"
            + "margin:8px 0";

    private static final Pattern TABLE_ROW = Pattern.compile("<tr>(.*?)</tr>",
            Pattern.DOTALL);
    private static final Pattern TABLE_CELL = Pattern.compile("<t([dh])([^>]*)>(.*?)</t\\1>",
            Pattern.DOTALL);

    private YaziLayout() {
    }

    /**
     * 渲染 YAZI 主题片段（样式表模式）。
     *
     * @param markdown Markdown 原文
     * @param renderer 代码渲染器，可为 {@code null}
     * @return 以 {@code <div class="jkit-yazi">} 开头的 HTML 片段
     */
    static String render(String markdown, Markdown.CodeRenderer renderer) {
        return render(markdown, renderer, false);
    }

    /**
     * 渲染 YAZI 主题片段。
     *
     * @param markdown Markdown 原文
     * @param renderer 代码渲染器，可为 {@code null}
     * @param inline {@code true} 时输出不依赖类名与样式表的邮件安全内联版式
     * @return 以 {@code <div class="jkit-yazi">} 开头的 HTML 片段
     */
    static String render(String markdown, Markdown.CodeRenderer renderer, boolean inline) {
        List<Block> blocks = parseBlocks(Markdown.toHtml(markdown, renderer));
        if (blocks.isEmpty()) {
            return "";
        }
        Slugger slugger = new Slugger();
        StringBuilder out = new StringBuilder(4096);
        out.append("<div class=\"jkit-yazi\"");
        appendStyle(out, inline ? ROOT_INLINE : null, null);
        out.append('>');
        int i = 0;
        boolean hasH1 = blocks.get(0).isHeading(1);
        if (hasH1) {
            Block h1 = blocks.get(0);
            int end = 1;
            while (end < blocks.size() && !blocks.get(end).isHeading(2)) {
                end++;
            }
            openWrap(out, h1, "wrap h1body-exist", inline, null);
            out.append("<header class=\"wrap-header h1wrap\"");
            appendStyle(out, inline ? H1_HEADER_INLINE : null, null);
            out.append('>');
            out.append(heading(h1, slugger, inline, Ctx.H1));
            out.append("<div class=\"wrap-body\"");
            appendStyle(out, inline ? H1_BODY_INLINE : null, null);
            out.append('>');
            for (int k = 1; k < end; k++) {
                out.append(renderBlock(blocks.get(k), slugger, inline, Ctx.H1));
            }
            out.append("</div></header>");
            out.append("<div class=\"h1wrap-body");
            appendClass(out, h1.attr("body-class"));
            out.append('"');
            appendStyle(out, null, h1.attr("body-style"));
            out.append('>');
            i = end;
        } else {
            int end = 0;
            while (end < blocks.size() && !blocks.get(end).isHeading(2)) {
                end++;
            }
            for (int k = 0; k < end; k++) {
                out.append(renderBlock(blocks.get(k), slugger, inline, Ctx.H1));
            }
            out.append("<div class=\"h1wrap-body\">");
            i = end;
        }
        while (i < blocks.size()) {
            Block block = blocks.get(i);
            if (block.isHeading(2)) {
                i = appendSection(out, blocks, i, slugger, inline);
            } else if (block.isHeading(3)) {
                i = appendOrphanCards(out, blocks, i, slugger, inline);
            } else {
                out.append(renderBlock(block, slugger, inline, Ctx.H2));
                i++;
            }
        }
        out.append("</div>");
        if (hasH1) {
            out.append("</div>");
        }
        out.append("</div>");
        return out.toString();
    }

    /**
     * 输出一个 H2 分区：标题 + 分区前言 + H3 卡片网格。
     *
     * @return 下一个待处理块的下标
     */
    private static int appendSection(StringBuilder out, List<Block> blocks, int index,
                                     Slugger slugger, boolean inline) {
        Block h2 = blocks.get(index);
        int end = index + 1;
        while (end < blocks.size() && !blocks.get(end).isHeading(2)
                && !blocks.get(end).isHeading(3)) {
            end++;
        }
        boolean hasCards = end < blocks.size() && blocks.get(end).isHeading(3);
        openWrap(out, h2, hasCards ? "wrap h2body-exist" : "wrap h2body-not-exist",
                inline, H2_SECTION_INLINE);
        out.append("<div class=\"wrap-header h2wrap\">");
        out.append(heading(h2, slugger, inline, Ctx.H2));
        out.append("<div class=\"wrap-body\"");
        appendStyle(out, inline ? H2_BODY_INLINE : null, null);
        out.append('>');
        for (int k = index + 1; k < end; k++) {
            out.append(renderBlock(blocks.get(k), slugger, inline, Ctx.H2));
        }
        out.append("</div></div>");
        out.append("<div class=\"h2wrap-body");
        appendClass(out, h2.attr("body-class"));
        out.append('"');
        if (inline) {
            String style = H2_GRID_INLINE;
            if (h2.attr("body-style") != null && !h2.attr("body-style").isEmpty()) {
                style = style + ";" + h2.attr("body-style");
            }
            appendStyle(out, style, null);
        } else {
            appendStyle(out, null, h2.attr("body-style"));
        }
        out.append('>');
        int i = end;
        while (i < blocks.size() && blocks.get(i).isHeading(3)) {
            i = appendCard(out, blocks, i, slugger, inline);
        }
        out.append("</div></div>");
        return i;
    }

    /**
     * 输出一张 H3 卡片。refs-cli 把每个 H3 变成网格里的一张独立卡片，
     * 卡片类名可带 {@code col-span-2} / {@code row-span-2}。
     *
     * @return 下一个待处理块的下标
     */
    private static int appendCard(StringBuilder out, List<Block> blocks, int index,
                                  Slugger slugger, boolean inline) {
        Block h3 = blocks.get(index);
        int end = index + 1;
        while (end < blocks.size() && !blocks.get(end).isHeading(2)
                && !blocks.get(end).isHeading(3)) {
            end++;
        }
        boolean hasChild = false;
        for (int k = index + 1; k < end; k++) {
            if (blocks.get(k).level >= 4) {
                hasChild = true;
                break;
            }
        }
        String cardStyle = CARD_INLINE + flexSpan(h3.attr("wrap-class"));
        openWrap(out, h3, hasChild ? "wrap h3body-exist" : "wrap h3body-not-exist",
                inline, cardStyle);
        out.append("<div class=\"wrap-header h3wrap\"");
        appendStyle(out, inline ? "width:100%" : null, null);
        out.append('>');
        out.append(heading(h3, slugger, inline, Ctx.H3));
        out.append("<div class=\"wrap-body");
        appendClass(out, h3.attr("body-class"));
        out.append('"');
        if (inline) {
            String style = "width:100%";
            if (h3.attr("body-style") != null && !h3.attr("body-style").isEmpty()) {
                style = style + ";" + h3.attr("body-style");
            }
            appendStyle(out, style, null);
        } else {
            appendStyle(out, null, h3.attr("body-style"));
        }
        out.append('>');
        for (int k = index + 1; k < end; k++) {
            out.append(renderBlock(blocks.get(k), slugger, inline, Ctx.H3));
        }
        out.append("</div></div></div>");
        return end;
    }

    /**
     * 没有 H2 父级时，把连续的 H3 包进一张网格容器，保持卡片观感。
     */
    private static int appendOrphanCards(StringBuilder out, List<Block> blocks, int index,
                                         Slugger slugger, boolean inline) {
        out.append("<div class=\"h2wrap-body\"");
        appendStyle(out, inline ? H2_GRID_INLINE : null, null);
        out.append('>');
        int i = index;
        while (i < blocks.size() && blocks.get(i).isHeading(3)) {
            i = appendCard(out, blocks, i, slugger, inline);
        }
        out.append("</div>");
        return i;
    }

    private static void openWrap(StringBuilder out, Block block, String baseClass,
                                 boolean inline, String inlineStyle) {
        out.append("<div class=\"").append(baseClass);
        appendClass(out, block.attr("wrap-class"));
        out.append('"');
        appendStyle(out, inline ? inlineStyle : null, block.attr("wrap-style"));
        out.append('>');
    }

    private static void appendClass(StringBuilder out, String classes) {
        if (classes != null && !classes.isEmpty()) {
            out.append(' ').append(classes);
        }
    }

    /**
     * 写入 style 属性：内联版式在前，调用方配置的 style 追加在后。
     */
    private static void appendStyle(StringBuilder out, String inlineStyle, String userStyle) {
        StringBuilder style = new StringBuilder();
        if (inlineStyle != null && !inlineStyle.isEmpty()) {
            style.append(inlineStyle);
        }
        if (userStyle != null && !userStyle.isEmpty()) {
            if (style.length() > 0) {
                style.append(';');
            }
            style.append(userStyle);
        }
        if (style.length() > 0) {
            out.append(" style=\"").append(NotifyUtils.escapeHtml(style.toString())).append('"');
        }
    }

    /**
     * 输出带 id 与空锚点的标题。锚点的 {@code #} 由 CSS 伪元素绘制，与
     * rehype-autolink-headings 的默认行为一致；内联模式用真实 span 绘制。
     */
    private static String heading(Block block, Slugger slugger, boolean inline, Ctx ctx) {
        String id = slugger.slug(plainText(block.inner));
        StringBuilder html = new StringBuilder(block.html.length() + 128);
        html.append("<h").append(block.level).append(" id=\"")
                .append(NotifyUtils.escapeHtml(id)).append('"');
        appendClassAttribute(html, block.attr("class"));
        if (inline) {
            appendStyleAttribute(html, headingInline(block.level));
        } else {
            appendStyleAttribute(html, block.attr("style"));
        }
        html.append("><a aria-hidden=\"true\" tabindex=\"-1\" href=\"#")
                .append(NotifyUtils.escapeHtml(id)).append("\">")
                .append(ICON_LINK).append("</a>");
        if (inline && block.level == 2) {
            html.append("<span style=\"color:#10b981;padding-right:6px\">#</span>");
        }
        html.append(block.inner).append("</h").append(block.level).append('>');
        return html.toString();
    }

    private static String headingInline(int level) {
        if (level == 1) {
            return H1_INLINE;
        }
        if (level == 2) {
            return H2_INLINE;
        }
        if (level == 3) {
            return H3_INLINE;
        }
        return H4_INLINE;
    }

    private static void appendClassAttribute(StringBuilder out, String classes) {
        if (classes != null && !classes.isEmpty()) {
            out.append(" class=\"").append(NotifyUtils.escapeHtml(classes)).append('"');
        }
    }

    private static void appendStyleAttribute(StringBuilder out, String style) {
        if (style != null && !style.isEmpty()) {
            out.append(" style=\"").append(NotifyUtils.escapeHtml(style)).append('"');
        }
    }

    /**
     * 正文中的块：标题补锚点，代码块 / 表格 / 列表在邮件模式下补内联样式。
     */
    private static String renderBlock(Block block, Slugger slugger, boolean inline, Ctx ctx) {
        if (block.level > 0) {
            return heading(block, slugger, inline, ctx);
        }
        String html = block.html.startsWith("<pre><code")
                ? styleCodeBlock(block.html) : block.html;
        if (!inline) {
            return html;
        }
        if (html.startsWith("<pre")) {
            return inlinePre(html);
        }
        if (html.startsWith("<table")) {
            return inlineTable(html);
        }
        if (html.startsWith("<ul") || html.startsWith("<ol")) {
            return inlineList(html, ctx);
        }
        if (html.startsWith("<blockquote")) {
            return inlineQuote(html);
        }
        if (html.startsWith("<p")) {
            return inlineParagraph(html, ctx);
        }
        if (html.startsWith("<hr")) {
            return "<hr style=\"" + HR_INLINE + "\">";
        }
        return html;
    }

    /**
     * 复刻 rehype-prism-plus 的产物：{@code <pre class="language-x">} +
     * {@code <code class="language-x code-highlight">}。视觉不变，便于外部样式按
     * 参考站点的选择器复用。
     */
    private static String styleCodeBlock(String html) {
        int preEnd = html.indexOf('>');
        int codeStart = html.indexOf("<code", preEnd);
        if (preEnd < 0 || codeStart < 0) {
            return html;
        }
        int codeOpenEnd = html.indexOf('>', codeStart);
        if (codeOpenEnd < 0) {
            return html;
        }
        String codeOpen = html.substring(codeStart, codeOpenEnd + 1);
        String langClass = attrValue(codeOpen, "class");
        String codeClass = "code-highlight";
        String preClass = "";
        if (langClass != null && langClass.startsWith("language-")) {
            preClass = langClass;
            codeClass = langClass + " code-highlight";
        }
        StringBuilder out = new StringBuilder(html.length() + 32);
        out.append("<pre");
        if (!preClass.isEmpty()) {
            out.append(" class=\"").append(preClass).append('"');
        }
        out.append('>').append(codeOpen, 0, 5).append(" class=\"").append(codeClass).append("\">");
        out.append(html.substring(codeOpenEnd + 1));
        return out.toString();
    }

    /**
     * 邮件模式：代码块内联样式，长行换行而不是横向裁切。
     */
    private static String inlinePre(String html) {
        int preEnd = html.indexOf('>');
        StringBuilder out = new StringBuilder(html.length() + 256);
        out.append(html, 0, preEnd).append(" style=\"").append(PRE_INLINE).append("\">");
        int codeStart = html.indexOf("<code", preEnd);
        if (codeStart < 0) {
            out.append(html, preEnd + 1, html.length());
            return out.toString();
        }
        int codeOpenEnd = html.indexOf('>', codeStart);
        if (codeOpenEnd < 0) {
            out.append(html, preEnd + 1, html.length());
            return out.toString();
        }
        out.append(html, preEnd + 1, codeStart);
        out.append(html, codeStart, codeOpenEnd).append(" style=\"").append(PRE_CODE_INLINE)
                .append("\">");
        out.append(html, codeOpenEnd + 1, html.length());
        return out.toString();
    }

    private static String inlineParagraph(String html, Ctx ctx) {
        int openEnd = html.indexOf('>');
        if (openEnd < 0 || !html.endsWith("</p>")) {
            return html;
        }
        String style = ctx == Ctx.H3 ? CARD_P_INLINE : ctx == Ctx.H2 ? H2_P_INLINE : H1_P_INLINE;
        String inner = html.substring(openEnd + 1, html.length() - 4);
        return "<p style=\"" + style + "\">" + inlineText(inner) + "</p>";
    }

    private static String inlineList(String html, Ctx ctx) {
        boolean card = ctx == Ctx.H3;
        String ul = card ? CARD_UL_INLINE : BODY_UL_INLINE;
        String ol = card ? CARD_OL_INLINE : BODY_OL_INLINE;
        String li = card ? CARD_LI_INLINE : BODY_LI_INLINE;
        String result = html.replace("<ul>", "<ul style=\"" + ul + "\">")
                .replace("<ol>", "<ol style=\"" + ol + "\">")
                .replace("<li>", "<li style=\"" + li + "\">");
        return inlineText(result);
    }

    private static String inlineQuote(String html) {
        String result = html.replace("<blockquote>", "<blockquote style=\"" + QUOTE_INLINE + "\">")
                .replace("<p>", "<p style=\"margin:0\">");
        return inlineText(result);
    }

    /**
     * 给表格、表头、单元格与单元格内代码补内联样式。
     *
     * <p>{@code show-header} 控制表头显隐，{@code left-align} 取消末列右对齐，
     * {@code shortcuts} 把首列代码渲染成键帽——这些都在客户端过滤 class 之前解析。
     */
    private static String inlineTable(String html) {
        int openEnd = html.indexOf('>');
        if (openEnd < 0) {
            return html;
        }
        String openTag = html.substring(0, openEnd);
        String classes = attrValue(openTag, "class");
        boolean showHeader = hasClass(classes, "show-header");
        boolean leftAlign = hasClass(classes, "left-align");
        boolean shortcuts = hasClass(classes, "shortcuts");
        StringBuilder out = new StringBuilder(html.length() + 512);
        out.append(openTag).append(" style=\"").append(TABLE_INLINE).append("\">")
                .append(html.substring(openEnd + 1));
        String table = out.toString();
        table = table.replace("<thead>", "<thead style=\""
                + (showHeader ? THEAD_SHOW_INLINE : THEAD_HIDE_INLINE) + "\">");
        Matcher row = TABLE_ROW.matcher(table);
        StringBuffer styled = new StringBuffer(table.length() + 512);
        while (row.find()) {
            String rowHtml = styleTableRow(row.group(1), leftAlign, shortcuts);
            row.appendReplacement(styled, Matcher.quoteReplacement(rowHtml));
        }
        row.appendTail(styled);
        return styled.toString();
    }

    private static String styleTableRow(String row, boolean leftAlign, boolean shortcuts) {
        Matcher cell = TABLE_CELL.matcher(row);
        List<String> tags = new ArrayList<String>();
        List<String> attrs = new ArrayList<String>();
        List<String> inners = new ArrayList<String>();
        while (cell.find()) {
            tags.add(cell.group(1));
            attrs.add(cell.group(2));
            inners.add(cell.group(3));
        }
        if (tags.isEmpty()) {
            return row;
        }
        StringBuilder out = new StringBuilder(row.length() + 256);
        int last = tags.size() - 1;
        for (int i = 0; i < tags.size(); i++) {
            boolean header = "h".equals(tags.get(i));
            String style = header ? TH_INLINE : TD_INLINE;
            if (i == last && !leftAlign) {
                style = style + ";text-align:right";
            }
            String inner = inlineTextNoCode(inners.get(i));
            if (!header) {
                inner = styleCellCode(inner, shortcuts && i < last
                        ? KBD_CODE_INLINE : TABLE_CODE_INLINE);
            }
            out.append("<t").append(tags.get(i)).append(" style=\"").append(style).append('"')
                    .append(attrs.get(i)).append('>').append(inner)
                    .append("</t").append(tags.get(i)).append('>');
        }
        return out.toString();
    }

    private static String styleCellCode(String html, String style) {
        return html.replace("<code>", "<code style=\"" + style + "\">");
    }

    private static String inlineText(String html) {
        return inlineTextNoCode(html).replace("<code>",
                "<code style=\"" + INLINE_CODE_INLINE + "\">");
    }

    private static String inlineTextNoCode(String html) {
        return html.replace("<a ", "<a style=\"" + LINK_INLINE + "\" ")
                .replace("<strong>", "<strong style=\"font-weight:700\">")
                .replace("<em>", "<em style=\"font-style:italic\">")
                .replace("<del>", "<del style=\"color:inherit\">")
                .replace("<img ", "<img style=\"" + IMG_INLINE + "\" ");
    }

    /**
     * 卡片跨列：{@code col-span-2} 转成两倍 flex 宽度；在窄屏上仍会自然换行。
     */
    private static String flexSpan(String classes) {
        if (classes == null) {
            return "";
        }
        String[] tokens = classes.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (!token.startsWith("col-span-")) {
                continue;
            }
            try {
                int span = Integer.parseInt(token.substring("col-span-".length()));
                if (span >= 2 && span <= 4) {
                    return ";flex:" + span + " 1 " + span * 30 + "%;min-width:"
                            + (span >= 2 ? 300 : 220) + "px";
                }
            } catch (NumberFormatException ignored) {
                // 非法跨列值按普通卡片处理
            }
        }
        return "";
    }

    private static boolean hasClass(String classes, String name) {
        if (classes == null) {
            return false;
        }
        String[] tokens = classes.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if (name.equals(tokens[i])) {
                return true;
            }
        }
        return false;
    }

    private static String attrValue(String tag, String name) {
        String needle = name + "=\"";
        int start = 0;
        while ((start = tag.indexOf(needle, start)) >= 0) {
            if (start == 0 || Character.isWhitespace(tag.charAt(start - 1))) {
                int valueStart = start + needle.length();
                int end = tag.indexOf('"', valueStart);
                return end < 0 ? null : tag.substring(valueStart, end);
            }
            start += needle.length();
        }
        return null;
    }

    /**
     * 去掉标题里的内联标签，得到 slug 与锚点文本。
     */
    private static String plainText(String html) {
        StringBuilder out = new StringBuilder(html.length());
        int i = 0;
        while (i < html.length()) {
            int open = html.indexOf('<', i);
            if (open < 0) {
                out.append(html, i, html.length());
                break;
            }
            out.append(html, i, open);
            int close = html.indexOf('>', open);
            if (close < 0) {
                break;
            }
            i = close + 1;
        }
        return unescape(out.toString());
    }

    private static String unescape(String text) {
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    /**
     * 把扁平片段拆成顶层块。标签栈只按顶层标签配对，{@code <pre>} 内部的
     * 高亮 span 也会被正确吞进同一个块。
     */
    private static List<Block> parseBlocks(String html) {
        List<Block> blocks = new ArrayList<Block>();
        int i = 0;
        int n = html.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(html.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int start = i;
            if (html.charAt(i) != '<') {
                int newline = html.indexOf('\n', i);
                i = newline < 0 ? n : newline;
            } else {
                int tagEnd = html.indexOf('>', i);
                if (tagEnd < 0) {
                    i = n;
                } else {
                    String name = tagName(html, i);
                    if (isVoidTag(name) || html.charAt(tagEnd - 1) == '/') {
                        i = tagEnd + 1;
                    } else {
                        int depth = 1;
                        int j = tagEnd + 1;
                        while (j < n && depth > 0) {
                            int open = html.indexOf('<', j);
                            if (open < 0) {
                                j = n;
                                break;
                            }
                            int close = html.indexOf('>', open);
                            if (close < 0) {
                                j = n;
                                break;
                            }
                            if (html.startsWith("</", open)) {
                                depth--;
                            } else {
                                String child = tagName(html, open);
                                if (!isVoidTag(child) && html.charAt(close - 1) != '/') {
                                    depth++;
                                }
                            }
                            j = close + 1;
                        }
                        i = j;
                    }
                }
            }
            String block = html.substring(start, i).trim();
            if (!block.isEmpty()) {
                blocks.add(new Block(block));
            }
        }
        return blocks;
    }

    private static String tagName(String html, int open) {
        int i = open + 1;
        if (i < html.length() && html.charAt(i) == '/') {
            i++;
        }
        int start = i;
        while (i < html.length()) {
            char c = html.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')) {
                i++;
            } else {
                break;
            }
        }
        return html.substring(start, i).toLowerCase(Locale.ROOT);
    }

    private static boolean isVoidTag(String name) {
        return "br".equals(name) || "hr".equals(name) || "img".equals(name)
                || "input".equals(name) || "meta".equals(name) || "link".equals(name)
                || "source".equals(name) || "wbr".equals(name) || "area".equals(name)
                || "base".equals(name) || "col".equals(name) || "embed".equals(name)
                || "param".equals(name) || "track".equals(name);
    }

    /**
     * 正文语境：页头、H2 分区、H3 卡片。
     */
    private enum Ctx {
        H1,
        H2,
        H3
    }

    /**
     * 顶层块，附带标题层级、标题内 HTML 与标题开标签上的属性。
     */
    private static final class Block {
        private final String html;
        private final int level;
        private final String inner;
        private final String openTag;

        Block(String html) {
            this.html = html;
            int detected = 0;
            String content = "";
            String tag = "";
            if (html.length() > 4 && html.charAt(0) == '<' && html.charAt(1) == 'h'
                    && html.charAt(2) >= '1' && html.charAt(2) <= '6') {
                char after = html.charAt(3);
                if (after == '>' || after == ' ') {
                    int lv = html.charAt(2) - '0';
                    String close = "</h" + lv + ">";
                    if (html.endsWith(close)) {
                        int openEnd = html.indexOf('>');
                        if (openEnd > 0) {
                            detected = lv;
                            tag = html.substring(0, openEnd + 1);
                            content = html.substring(openEnd + 1, html.length() - close.length());
                        }
                    }
                }
            }
            this.level = detected;
            this.openTag = tag;
            this.inner = content;
        }

        boolean isHeading(int expected) {
            return level == expected;
        }

        String attr(String name) {
            if (openTag.isEmpty()) {
                return null;
            }
            boolean direct = "class".equals(name) || "style".equals(name);
            return attrValue(openTag, direct ? name : "data-" + name);
        }
    }

    /**
     * github-slugger 的近似实现：保留 Unicode 字母、数字、组合符、下划线、连字符、
     * 空格，其余标点去掉；空格转连字符；重复标题追加 {@code -1} / {@code -2}。
     */
    private static final class Slugger {
        private final Map<String, Integer> seen = new HashMap<String, Integer>();

        String slug(String text) {
            String base = slugify(text);
            if (base.isEmpty()) {
                return "";
            }
            Integer count = seen.get(base);
            if (count == null) {
                seen.put(base, 0);
                return base;
            }
            int next = count + 1;
            seen.put(base, next);
            return base + "-" + next;
        }

        private String slugify(String text) {
            String lower = text.toLowerCase(Locale.ROOT).trim();
            StringBuilder out = new StringBuilder(lower.length());
            for (int i = 0; i < lower.length(); ) {
                int cp = lower.codePointAt(i);
                int type = Character.getType(cp);
                if (Character.isLetterOrDigit(cp) || cp == '-' || cp == '_'
                        || cp == ' ' || type == Character.NON_SPACING_MARK
                        || type == Character.COMBINING_SPACING_MARK) {
                    out.appendCodePoint(cp);
                }
                i += Character.charCount(cp);
            }
            return out.toString().trim().replace(' ', '-');
        }
    }
}
