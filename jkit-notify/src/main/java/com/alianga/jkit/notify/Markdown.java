package com.alianga.jkit.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 面向通知正文的 Markdown → HTML 转换（零依赖）。
 *
 * <p>覆盖告警 / 周报 / 技术文档常用子集：ATX 标题、段落（单换行变 {@code <br>}）、
 * 引用、有序/无序列表（列表项内可嵌套代码块、表格、子列表）、GFM 表格、
 * {@code |...|+...+} 形式的 CLI 宽表、围栏代码块（{@code ```}/{@code ~~~}，允许缩进）、
 * 分割线、链接、图片、加粗 / 斜体 / 删除线 / 行内代码。不是完整 CommonMark。
 *
 * <p>文本节点做 HTML 转义；{@code javascript:}/{@code data:} 链接降为 {@code #}。
 * SMTP 等不原生渲染 Markdown 的渠道通过 {@link NotifyUtils#markdownToHtml(String)} 调用。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
final class Markdown {
    private Markdown() {
    }

    /**
     * 把 Markdown 转成 HTML 片段（不含 {@code <html>} 文档壳）。
     *
     * @param markdown 原文，{@code null} 或空串返回空串
     * @return HTML 片段
     */
    static String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        return convertBlocks(normalized);
    }

    private static String convertBlocks(String text) {
        String[] lines = text.split("\n", -1);
        return convertRange(lines, 0, lines.length);
    }

    private static String convertRange(String[] lines, int from, int to) {
        StringBuilder html = new StringBuilder();
        int i = from;
        while (i < to) {
            if (isBlank(lines[i])) {
                i++;
                continue;
            }
            if (html.length() > 0) {
                html.append('\n');
            }
            if (isFence(lines[i])) {
                i = appendFence(html, lines, i, to);
            } else if (looksLikeTable(lines, i, to)) {
                i = appendTable(html, lines, i, to);
            } else {
                int heading = headingLevel(lines[i]);
                if (heading > 0) {
                    html.append("<h").append(heading).append('>')
                            .append(renderInline(headingText(lines[i], heading)))
                            .append("</h").append(heading).append('>');
                    i++;
                } else if (isHr(lines[i])) {
                    html.append("<hr>");
                    i++;
                } else if (isBlockquote(lines[i])) {
                    i = appendQuote(html, lines, i, to);
                } else if (ulMarkerEnd(lines[i]) >= 0) {
                    i = appendList(html, lines, i, to, false);
                } else if (olMarkerEnd(lines[i]) >= 0) {
                    i = appendList(html, lines, i, to, true);
                } else {
                    i = appendParagraph(html, lines, i, to);
                }
            }
        }
        return html.toString();
    }

    private static int appendFence(StringBuilder html, String[] lines, int start, int to) {
        int fenceIndent = indent(lines[start]);
        String open = lines[start].trim();
        char tick = open.charAt(0);
        int ticks = leadingCharCount(open, tick);
        String lang = open.substring(ticks).trim();
        StringBuilder code = new StringBuilder();
        int i = start + 1;
        while (i < to) {
            String trimmed = lines[i].trim();
            if (!trimmed.isEmpty() && trimmed.charAt(0) == tick) {
                int close = leadingCharCount(trimmed, tick);
                if (close >= ticks && trimmed.substring(close).trim().isEmpty()) {
                    i++;
                    break;
                }
            }
            if (code.length() > 0) {
                code.append('\n');
            }
            code.append(stripIndent(lines[i], fenceIndent));
            i++;
        }
        html.append("<pre><code");
        if (!lang.isEmpty()) {
            html.append(" class=\"language-").append(NotifyUtils.escapeHtml(lang)).append('"');
        }
        html.append('>').append(NotifyUtils.escapeHtml(code.toString())).append("</code></pre>");
        return i;
    }

    private static int appendQuote(StringBuilder html, String[] lines, int start, int to) {
        StringBuilder inner = new StringBuilder();
        int i = start;
        while (i < to && isBlockquote(lines[i])) {
            if (inner.length() > 0) {
                inner.append('\n');
            }
            inner.append(stripQuote(lines[i]));
            i++;
        }
        html.append("<blockquote>").append(convertBlocks(inner.toString())).append("</blockquote>");
        return i;
    }

    private static int appendParagraph(StringBuilder html, String[] lines, int start, int to) {
        html.append("<p>");
        int i = start;
        boolean first = true;
        while (i < to && isParagraphLine(lines, i, to)) {
            if (!first) {
                html.append("<br>");
            }
            html.append(renderInline(lines[i].trim()));
            first = false;
            i++;
        }
        html.append("</p>");
        return i;
    }

    private static boolean isParagraphLine(String[] lines, int i, int to) {
        String line = lines[i];
        return !isBlank(line) && headingLevel(line) == 0 && !isFence(line) && !isHr(line)
                && !isBlockquote(line) && ulMarkerEnd(line) < 0 && olMarkerEnd(line) < 0
                && !looksLikeTable(lines, i, to);
    }

    private static int appendList(StringBuilder html, String[] lines, int start, int to, boolean ordered) {
        int baseIndent = indent(lines[start]);
        html.append(ordered ? "<ol>" : "<ul>");
        int i = start;
        while (i < to) {
            if (isBlank(lines[i])) {
                int next = skipBlanks(lines, i + 1, to);
                if (next < to && listMarkerEnd(lines[next], ordered) >= 0
                        && indent(lines[next]) == baseIndent) {
                    i = next;
                    continue;
                }
                break;
            }
            if (indent(lines[i]) != baseIndent) {
                break;
            }
            int marker = listMarkerEnd(lines[i], ordered);
            if (marker < 0) {
                break;
            }
            String first = marker <= lines[i].length() ? lines[i].substring(marker) : "";
            int contentCol = marker;
            int itemEnd = i + 1;
            boolean tight = true;
            while (itemEnd < to) {
                if (isBlank(lines[itemEnd])) {
                    int peek = skipBlanks(lines, itemEnd + 1, to);
                    if (peek < to && indent(lines[peek]) >= contentCol) {
                        tight = false;
                        itemEnd++;
                        continue;
                    }
                    break;
                }
                int nestedIndent = indent(lines[itemEnd]);
                if (nestedIndent == baseIndent
                        && (ulMarkerEnd(lines[itemEnd]) >= 0 || olMarkerEnd(lines[itemEnd]) >= 0)) {
                    break;
                }
                if (nestedIndent < contentCol) {
                    break;
                }
                itemEnd++;
            }
            StringBuilder nested = new StringBuilder(first);
            for (int k = i + 1; k < itemEnd; k++) {
                nested.append('\n');
                if (!isBlank(lines[k])) {
                    nested.append(stripIndent(lines[k], contentCol));
                }
            }
            String inner = convertBlocks(nested.toString());
            if (tight) {
                inner = unwrapTightParagraphs(inner);
            }
            html.append("<li>").append(inner).append("</li>");
            i = itemEnd;
        }
        html.append(ordered ? "</ol>" : "</ul>");
        return i;
    }

    /**
     * 紧凑列表项里的段落不包 {@code <p>}，与 CommonMark 及既有单测一致。
     */
    private static String unwrapTightParagraphs(String html) {
        StringBuilder out = new StringBuilder(html.length());
        int i = 0;
        while (i < html.length()) {
            if (html.startsWith("<p>", i)) {
                int end = html.indexOf("</p>", i + 3);
                if (end >= 0) {
                    out.append(html, i + 3, end);
                    i = end + 4;
                    if (i < html.length() && html.charAt(i) == '\n') {
                        i++;
                    }
                    continue;
                }
            }
            out.append(html.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static int appendTable(StringBuilder html, String[] lines, int start, int to) {
        String[] header = tableCells(lines[start]);
        String[] aligns = tableAlignments(lines[start + 1], header.length);
        html.append("<table><thead><tr>");
        for (int c = 0; c < header.length; c++) {
            html.append("<th").append(alignAttr(aligns, c)).append('>')
                    .append(renderInline(header[c])).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        int i = start + 2;
        while (i < to && !isBlank(lines[i]) && lines[i].indexOf('|') >= 0
                && !isTableSeparator(lines[i]) && !isFence(lines[i])) {
            String[] cells = tableCells(lines[i]);
            html.append("<tr>");
            int cols = Math.max(cells.length, header.length);
            for (int c = 0; c < cols; c++) {
                String cell = c < cells.length ? cells[c] : "";
                html.append("<td").append(alignAttr(aligns, c)).append('>')
                        .append(renderInline(cell)).append("</td>");
            }
            html.append("</tr>");
            i++;
        }
        html.append("</tbody></table>");
        return i;
    }

    private static String alignAttr(String[] aligns, int index) {
        if (aligns == null || index >= aligns.length || aligns[index] == null) {
            return "";
        }
        return " align=\"" + aligns[index] + '"';
    }

    private static String[] tableAlignments(String separator, int columns) {
        String[] cells = tableCells(separator);
        if (cells.length == 0 || cells.length == 1 && cells[0].indexOf('+') >= 0) {
            return null;
        }
        String[] aligns = new String[Math.max(columns, cells.length)];
        for (int i = 0; i < cells.length; i++) {
            String cell = cells[i].trim();
            boolean left = cell.startsWith(":");
            boolean right = cell.endsWith(":");
            if (left && right) {
                aligns[i] = "center";
            } else if (right) {
                aligns[i] = "right";
            } else if (left) {
                aligns[i] = "left";
            }
        }
        return aligns;
    }

    private static String renderInline(String text) {
        List<String> codes = new ArrayList<String>();
        StringBuilder stripped = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '`') {
                int close = text.indexOf('`', i + 1);
                if (close > i) {
                    codes.add(text.substring(i + 1, close));
                    stripped.append('\u0001').append(codes.size() - 1).append('\u0001');
                    i = close + 1;
                    continue;
                }
            }
            stripped.append(text.charAt(i));
            i++;
        }
        String html = NotifyUtils.escapeHtml(stripped.toString());
        html = applyLinksAndImages(html);
        html = applyDelimited(html, "***", "<strong><em>", "</em></strong>");
        html = applyDelimited(html, "**", "<strong>", "</strong>");
        html = applyDelimited(html, "__", "<strong>", "</strong>");
        html = applyDelimited(html, "~~", "<del>", "</del>");
        html = applyDelimited(html, "*", "<em>", "</em>");
        for (int n = codes.size() - 1; n >= 0; n--) {
            html = html.replace("\u0001" + n + "\u0001",
                    "<code>" + NotifyUtils.escapeHtml(codes.get(n)) + "</code>");
        }
        return html;
    }

    private static String applyLinksAndImages(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf('[', i);
            if (open < 0) {
                out.append(text, i, text.length());
                break;
            }
            boolean image = open > 0 && text.charAt(open - 1) == '!';
            int close = text.indexOf(']', open + 1);
            if (close < 0 || close + 1 >= text.length() || text.charAt(close + 1) != '(') {
                out.append(text, i, open + 1);
                i = open + 1;
                continue;
            }
            int paren = text.indexOf(')', close + 2);
            if (paren < 0) {
                out.append(text, i, open + 1);
                i = open + 1;
                continue;
            }
            String label = text.substring(open + 1, close);
            String url = stripUrlTitle(text.substring(close + 2, paren).trim());
            int from = image ? open - 1 : open;
            out.append(text, i, from);
            if (image) {
                out.append("<img src=\"").append(safeUrl(url)).append("\" alt=\"")
                        .append(label).append("\">");
            } else {
                out.append("<a href=\"").append(safeUrl(url)).append("\">")
                        .append(label).append("</a>");
            }
            i = paren + 1;
        }
        return out.toString();
    }

    private static String applyDelimited(String text, String delim, String openTag, String closeTag) {
        int delimLen = delim.length();
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (true) {
            int start = text.indexOf(delim, i);
            if (start < 0) {
                out.append(text, i, text.length());
                return out.toString();
            }
            int end = text.indexOf(delim, start + delimLen);
            if (end < 0) {
                out.append(text, i, text.length());
                return out.toString();
            }
            String inner = text.substring(start + delimLen, end);
            if (inner.isEmpty()
                    || Character.isWhitespace(inner.charAt(0))
                    || Character.isWhitespace(inner.charAt(inner.length() - 1))) {
                out.append(text, i, start + delimLen);
                i = start + delimLen;
                continue;
            }
            out.append(text, i, start);
            out.append(openTag).append(inner).append(closeTag);
            i = end + delimLen;
        }
    }

    private static String stripUrlTitle(String url) {
        if (url.length() >= 2) {
            char last = url.charAt(url.length() - 1);
            if (last == '"' || last == '\'') {
                int space = url.lastIndexOf(' ');
                if (space > 0) {
                    return url.substring(0, space).trim();
                }
            }
        }
        return url;
    }

    private static String safeUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("data:")) {
            return "#";
        }
        return url;
    }

    private static boolean looksLikeTable(String[] lines, int i, int to) {
        return i + 1 < to && lines[i].indexOf('|') >= 0 && isTableSeparator(lines[i + 1]);
    }

    /**
     * GFM {@code |---|---:|} 以及 CLI 宽表的 {@code ----+----} 分隔行。
     */
    private static boolean isTableSeparator(String line) {
        String trimmed = line.trim();
        if (trimmed.length() < 3) {
            return false;
        }
        boolean hasDash = false;
        int pipesOrPlus = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '-') {
                hasDash = true;
            } else if (c == '|' || c == '+') {
                pipesOrPlus++;
            } else if (c != ':' && c != ' ' && c != '\t') {
                return false;
            }
        }
        return hasDash && pipesOrPlus >= 1;
    }

    private static String[] tableCells(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] raw = trimmed.split("\\|", -1);
        String[] cells = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            cells[i] = raw[i].trim();
        }
        return cells;
    }

    private static boolean isFence(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        char tick = trimmed.charAt(0);
        if (tick != '`' && tick != '~') {
            return false;
        }
        return leadingCharCount(trimmed, tick) >= 3;
    }

    private static boolean isHr(String line) {
        String trimmed = line.trim();
        if (trimmed.length() < 3) {
            return false;
        }
        char marker = 0;
        int count = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == ' ' || ch == '\t') {
                continue;
            }
            if (ch != '-' && ch != '*' && ch != '_') {
                return false;
            }
            if (marker == 0) {
                marker = ch;
            } else if (ch != marker) {
                return false;
            }
            count++;
        }
        return count >= 3;
    }

    private static boolean isBlockquote(String line) {
        int i = leadingSpaceIndex(line);
        return i < line.length() && line.charAt(i) == '>';
    }

    private static String stripQuote(String line) {
        int i = leadingSpaceIndex(line);
        if (i < line.length() && line.charAt(i) == '>') {
            i++;
            if (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
                i++;
            }
        }
        return line.substring(i);
    }

    private static int headingLevel(String line) {
        int i = leadingSpaceIndex(line);
        if (indent(line) > 3) {
            return 0;
        }
        int hashes = 0;
        while (i < line.length() && line.charAt(i) == '#') {
            hashes++;
            i++;
            if (hashes > 6) {
                return 0;
            }
        }
        if (hashes == 0) {
            return 0;
        }
        if (i < line.length() && line.charAt(i) != ' ' && line.charAt(i) != '\t') {
            return 0;
        }
        return hashes;
    }

    private static String headingText(String line, int level) {
        int i = leadingSpaceIndex(line) + level;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        String text = line.substring(i);
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '#') {
            end--;
        }
        if (end < text.length() && end > 0
                && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
            return text.substring(0, end).trim();
        }
        return text.trim();
    }

    private static int ulMarkerEnd(String line) {
        int i = leadingSpaceIndex(line);
        if (i >= line.length()) {
            return -1;
        }
        char c = line.charAt(i);
        if (c != '-' && c != '*' && c != '+') {
            return -1;
        }
        if (i + 1 >= line.length()) {
            return -1;
        }
        char next = line.charAt(i + 1);
        if (next != ' ' && next != '\t') {
            return -1;
        }
        return i + 2;
    }

    private static int olMarkerEnd(String line) {
        int i = leadingSpaceIndex(line);
        if (i >= line.length() || !Character.isDigit(line.charAt(i))) {
            return -1;
        }
        int j = i;
        while (j < line.length() && Character.isDigit(line.charAt(j))) {
            j++;
        }
        if (j >= line.length() || line.charAt(j) != '.') {
            return -1;
        }
        if (j + 1 >= line.length()) {
            return -1;
        }
        char next = line.charAt(j + 1);
        if (next != ' ' && next != '\t') {
            return -1;
        }
        return j + 2;
    }

    private static int listMarkerEnd(String line, boolean ordered) {
        return ordered ? olMarkerEnd(line) : ulMarkerEnd(line);
    }

    private static int indent(String line) {
        int col = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                col++;
            } else if (c == '\t') {
                col += 4 - (col % 4);
            } else {
                break;
            }
        }
        return col;
    }

    private static int leadingSpaceIndex(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static String stripIndent(String line, int cols) {
        int col = 0;
        int i = 0;
        while (i < line.length() && col < cols) {
            char c = line.charAt(i);
            if (c == ' ') {
                col++;
                i++;
            } else if (c == '\t') {
                col += 4 - (col % 4);
                i++;
            } else {
                break;
            }
        }
        return line.substring(i);
    }

    private static int leadingCharCount(String text, char ch) {
        int n = 0;
        while (n < text.length() && text.charAt(n) == ch) {
            n++;
        }
        return n;
    }

    private static int skipBlanks(String[] lines, int i, int to) {
        while (i < to && isBlank(lines[i])) {
            i++;
        }
        return i;
    }

    private static boolean isBlank(String line) {
        return line.trim().isEmpty();
    }
}
