package com.alianga.jkit.notify;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Markdown → HTML 的视觉令牌与样式生成（零依赖）。
 *
 * <p>主题不写死整段 CSS，而是声明一组令牌（主色 / 底色 / 字体 / 标题样式 / 引用样式 / 代码块样式 /
 * 表格样式 / 代码高亮配色），由本类统一生成两种产物：
 * <ul>
 *   <li>{@link #css(boolean)}：放进 {@code <head><style>} 的完整样式表，供浏览器与多数邮件客户端使用；</li>
 *   <li>{@link #inlineStyles()}：标签名 → {@code style} 属性值的映射，供
 *       {@link HtmlInliner} 把样式内联到每个标签上，兼容剥离 {@code <style>} 的邮件客户端与微信。</li>
 * </ul>
 *
 * <p>两者来自同一套令牌，切换主题时观感一致；个别主题可通过 {@code extraCss} 追加装饰性补丁
 * （渐变分割线、Mac 窗口圆点等），这些补丁只在 {@code <style>} 模式下生效——内联模式无法表达伪元素。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
final class MarkdownStyle {
    /**
     * 无衬线字体栈：优先系统 UI 字体，中文回退到苹方 / 雅黑。
     */
    static final String SANS = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,"
            + "'PingFang SC','Hiragino Sans GB','Microsoft YaHei',sans-serif";

    /**
     * 等宽字体栈，代码块与行内代码使用。
     */
    static final String MONO = "ui-monospace,SFMono-Regular,Menlo,Consolas,'Courier New',monospace";

    /**
     * 衬线字体栈，麦色等文风主题使用。
     */
    static final String SERIF = "Georgia,'Times New Roman','Songti SC','SimSun',serif";

    /**
     * 正文容器 div 的 class 名。排版宽度与内边距都挂在这层容器上，
     * 因为邮件客户端通常会剥掉 {@code <body>}。
     */
    static final String CONTAINER_CLASS = "jkit-md";

    /**
     * 桌面端正文栏内边距：上下留白，左右只留一点，避免正文被压窄。
     */
    static final String DESKTOP_PADDING = "24px 16px";

    /**
     * 移动端正文栏内边距：左右基本不留白，把宽度让给正文。
     */
    static final String MOBILE_PADDING = "12px 8px";

    /**
     * 标题装饰风格。
     */
    enum Title {
        /**
         * 无装饰，仅字号与颜色区分层级。
         */
        PLAIN,
        /**
         * 一二级标题左侧竖条。
         */
        BAR,
        /**
         * 一二级标题下方横线。
         */
        UNDERLINE,
        /**
         * 一级标题居中 + 上下留白（橙心 / 姹紫 / 麦色等排版型主题）。
         */
        CENTER
    }

    /**
     * 引用块风格。
     */
    enum Quote {
        /**
         * 左侧竖条。
         */
        BAR,
        /**
         * 整块底色填充 + 圆角。
         */
        FILL,
        /**
         * 竖条 + 引号装饰（引号为伪元素，仅 {@code <style>} 模式可见）。
         */
        QUOTE
    }

    /**
     * 代码块风格。
     */
    enum Code {
        /**
         * 纯底色，无边框。
         */
        FLAT,
        /**
         * 底色 + 1px 描边。
         */
        BORDER,
        /**
         * macOS 窗口风格：顶部三个圆点（伪元素，仅 {@code <style>} 模式可见）。
         */
        MAC
    }

    /**
     * 表格风格。
     */
    enum Table {
        /**
         * 全网格，表头带底色。
         */
        GRID,
        /**
         * 横向分割线 + 偶数行斑马纹。
         */
        STRIPED,
        /**
         * 极简：仅表头加粗下划线与行分隔线。
         */
        MINIMAL
    }

    private String primary = "#0969da";
    private String accent = "#0969da";
    private String text = "#24292f";
    private String muted = "#57606a";
    private String background = "#ffffff";
    private String panel = "#f6f8fa";
    private String border = "#d0d7de";
    private String font = SANS;
    private String mono = MONO;
    private int fontSize = 16;
    private double lineHeight = 1.75;
    private int maxWidth = 820;
    private int radius = 6;
    private Title title = Title.UNDERLINE;
    private Quote quote = Quote.BAR;
    private Code code = Code.FLAT;
    private Table table = Table.GRID;
    private String codeText = "#24292e";
    private String codeKeyword = "#cf222e";
    private String codeString = "#0a3069";
    private String codeComment = "#6e7781";
    private String codeNumber = "#0550ae";
    private String extraCss = "";

    private MarkdownStyle() {
    }

    /**
     * 取主题对应的样式（同一主题复用同一实例）。
     *
     * @param theme 主题
     * @return 样式令牌
     */
    static MarkdownStyle of(MarkdownTheme theme) {
        MarkdownStyle cached = theme.styleOf();
        if (cached != null) {
            return cached;
        }
        MarkdownStyle built = build(theme);
        theme.store(built);
        return built;
    }

    private static MarkdownStyle base() {
        return new MarkdownStyle();
    }

    private static MarkdownStyle build(MarkdownTheme theme) {
        switch (theme) {
            case LARK:
                return lark();
            case ORANGE_HEART:
                return orangeHeart();
            case RAINBOW:
                return rainbow();
            case LAPIS:
                return lapis();
            case PHYCAT:
                return phycat();
            case BLUE:
                return blue();
            case VUE:
                return vue();
            case GREEN:
                return green();
            case WHEAT:
                return wheat();
            case AYER:
                return ayer();
            case PURPLE:
                return purple();
            default:
                return base();
        }
    }

    private static MarkdownStyle lark() {
        MarkdownStyle s = base();
        s.primary = "#3370ff";
        s.accent = "#3370ff";
        s.panel = "#f4f8ff";
        s.border = "#d6e4ff";
        s.muted = "#5c6b8a";
        s.title = Title.BAR;
        s.quote = Quote.FILL;
        s.code = Code.BORDER;
        s.radius = 8;
        s.codeKeyword = "#0b5fd0";
        s.codeString = "#0a5d8a";
        return s;
    }

    private static MarkdownStyle orangeHeart() {
        MarkdownStyle s = base();
        s.primary = "#e8590c";
        s.accent = "#ff922b";
        s.panel = "#fff6ec";
        s.border = "#ffd9b0";
        s.muted = "#8a5a2b";
        s.title = Title.CENTER;
        s.quote = Quote.QUOTE;
        s.code = Code.FLAT;
        s.table = Table.STRIPED;
        s.radius = 10;
        s.codeKeyword = "#d9480f";
        s.codeString = "#a04000";
        return s;
    }

    private static MarkdownStyle rainbow() {
        MarkdownStyle s = base();
        s.primary = "#d6336c";
        s.accent = "#f06595";
        s.panel = "#fff5f8";
        s.border = "#ffd6e4";
        s.muted = "#8a5a6d";
        s.title = Title.UNDERLINE;
        s.quote = Quote.FILL;
        s.code = Code.BORDER;
        s.table = Table.STRIPED;
        s.radius = 10;
        s.codeKeyword = "#c2255c";
        s.codeString = "#a61e4d";
        s.extraCss = "hr{border:0;height:3px;border-radius:3px;"
                + "background:linear-gradient(90deg,#ff5f6d,#ffc371,#47cf73,#3f8efc)}";
        return s;
    }

    private static MarkdownStyle lapis() {
        MarkdownStyle s = base();
        s.primary = "#0c8599";
        s.accent = "#22b8cf";
        s.panel = "#eefbfb";
        s.border = "#b8e6e6";
        s.muted = "#5c7a7a";
        s.title = Title.BAR;
        s.quote = Quote.QUOTE;
        s.code = Code.FLAT;
        s.table = Table.MINIMAL;
        s.radius = 4;
        s.codeKeyword = "#0b7285";
        s.codeString = "#087f5b";
        return s;
    }

    private static MarkdownStyle phycat() {
        MarkdownStyle s = base();
        s.primary = "#e67700";
        s.accent = "#fcc419";
        s.background = "#fffdf5";
        s.panel = "#fff9e6";
        s.border = "#ffe9a8";
        s.text = "#4a3f10";
        s.muted = "#8a7b3f";
        s.title = Title.BAR;
        s.quote = Quote.FILL;
        s.code = Code.FLAT;
        s.radius = 12;
        s.codeKeyword = "#d9480f";
        s.codeString = "#9c6500";
        return s;
    }

    private static MarkdownStyle blue() {
        MarkdownStyle s = base();
        s.primary = "#0f4c81";
        s.accent = "#1c6ea4";
        s.panel = "#eef3fa";
        s.border = "#c8d7ea";
        s.muted = "#5a6b80";
        s.title = Title.UNDERLINE;
        s.quote = Quote.BAR;
        s.code = Code.MAC;
        s.table = Table.GRID;
        s.codeKeyword = "#0b4f9c";
        s.codeString = "#0a5d8a";
        return s;
    }

    private static MarkdownStyle vue() {
        MarkdownStyle s = base();
        s.primary = "#35495e";
        s.accent = "#42b883";
        s.panel = "#f0faf5";
        s.border = "#c3ebd8";
        s.muted = "#5f6d7a";
        s.title = Title.BAR;
        s.quote = Quote.FILL;
        s.code = Code.MAC;
        s.table = Table.GRID;
        s.radius = 8;
        s.codeKeyword = "#42b883";
        s.codeString = "#219a6b";
        return s;
    }

    private static MarkdownStyle green() {
        MarkdownStyle s = base();
        s.primary = "#2f9e44";
        s.accent = "#51cf66";
        s.panel = "#ebfbee";
        s.border = "#b2f2bb";
        s.muted = "#5c7a63";
        s.title = Title.UNDERLINE;
        s.quote = Quote.BAR;
        s.code = Code.FLAT;
        s.table = Table.STRIPED;
        s.codeKeyword = "#2b8a3e";
        s.codeString = "#087f5b";
        return s;
    }

    private static MarkdownStyle wheat() {
        MarkdownStyle s = base();
        s.primary = "#a16207";
        s.accent = "#ca8a04";
        s.background = "#fdf6e3";
        s.panel = "#f7efdd";
        s.border = "#e3d5b8";
        s.text = "#4b3f2f";
        s.muted = "#867a63";
        s.font = SERIF;
        s.title = Title.CENTER;
        s.quote = Quote.FILL;
        s.code = Code.BORDER;
        s.table = Table.MINIMAL;
        s.radius = 4;
        s.codeKeyword = "#a16207";
        s.codeString = "#854d0e";
        return s;
    }

    private static MarkdownStyle ayer() {
        MarkdownStyle s = base();
        s.primary = "#61afef";
        s.accent = "#61afef";
        s.background = "#1f2430";
        s.panel = "#2a2f3a";
        s.border = "#3a4150";
        s.text = "#d7dae0";
        s.muted = "#8b93a1";
        s.title = Title.BAR;
        s.quote = Quote.BAR;
        s.code = Code.MAC;
        s.table = Table.GRID;
        s.codeText = "#abb2bf";
        s.codeKeyword = "#c678dd";
        s.codeString = "#98c379";
        s.codeComment = "#7f848e";
        s.codeNumber = "#d19a66";
        return s;
    }

    private static MarkdownStyle purple() {
        MarkdownStyle s = base();
        s.primary = "#7048e8";
        s.accent = "#9775fa";
        s.panel = "#f6f2ff";
        s.border = "#ddd0ff";
        s.muted = "#6b5f8a";
        s.title = Title.CENTER;
        s.quote = Quote.QUOTE;
        s.code = Code.BORDER;
        s.table = Table.STRIPED;
        s.radius = 10;
        s.codeKeyword = "#6741d9";
        s.codeString = "#5f3dc4";
        return s;
    }

    /**
     * 主色（标题、链接）。
     *
     * @return 十六进制颜色
     */
    String primary() {
        return primary;
    }

    /**
     * 强调色（标题装饰、引用竖条）。
     *
     * @return 十六进制颜色
     */
    String accent() {
        return accent;
    }

    /**
     * 正文颜色。
     *
     * @return 十六进制颜色
     */
    String text() {
        return text;
    }

    /**
     * 次要文字颜色。
     *
     * @return 十六进制颜色
     */
    String muted() {
        return muted;
    }

    /**
     * 页面底色。
     *
     * @return 十六进制颜色
     */
    String background() {
        return background;
    }

    /**
     * 代码块 / 表头底色。
     *
     * @return 十六进制颜色
     */
    String panel() {
        return panel;
    }

    /**
     * 边框颜色。
     *
     * @return 十六进制颜色
     */
    String border() {
        return border;
    }

    /**
     * 代码高亮：默认文本色。
     *
     * @return 十六进制颜色
     */
    String codeText() {
        return codeText;
    }

    /**
     * 代码高亮：关键字色。
     *
     * @return 十六进制颜色
     */
    String codeKeyword() {
        return codeKeyword;
    }

    /**
     * 代码高亮：字符串色。
     *
     * @return 十六进制颜色
     */
    String codeString() {
        return codeString;
    }

    /**
     * 代码高亮：注释色。
     *
     * @return 十六进制颜色
     */
    String codeComment() {
        return codeComment;
    }

    /**
     * 代码高亮：数字与属性名色。
     *
     * @return 十六进制颜色
     */
    String codeNumber() {
        return codeNumber;
    }

    /**
     * 生成放进行内 {@code <style>} 标签的样式表。
     *
     * @param responsive 是否附带移动端 / 桌面端媒体查询
     * @return CSS 文本
     */
    String css(boolean responsive) {
        StringBuilder css = new StringBuilder(2048);
        css.append("html{-webkit-text-size-adjust:100%;background:").append(background).append("}");
        // body 只铺满视口底色，不能带 max-width。文档壳会给 body 写内联 margin:0
        // （让底色铺满），若这里再限宽，限宽留下、居中外边距被盖掉，栏就贴在页面左边。
        css.append("body{margin:0;padding:0;background:").append(background)
                .append(";color:").append(text)
                .append(";font-family:").append(font)
                .append(";font-size:").append(fontSize).append("px")
                .append(";line-height:").append(lineHeight).append("}");
        // 邮件客户端（Gmail / QQ 邮箱等）普遍会剥掉 <body>，只把正文塞进自己的容器，
        // 所以栏宽、内边距、居中必须挂在这层 div 上
        css.append("div.").append(CONTAINER_CLASS).append("{").append(containerBase()).append("}");
        appendHeadingCss(css);
        css.append("p{margin:.9em 0}");
        css.append("a{color:").append(primary).append(";text-decoration:underline;overflow-wrap:anywhere}");
        css.append("strong{font-weight:600}");
        css.append("em{font-style:italic}");
        css.append("del{color:").append(muted).append("}");
        css.append("ul,ol{padding-left:1.7em;margin:.8em 0}");
        css.append("li{margin:.35em 0}");
        css.append("ul ul{list-style-type:circle}");
        css.append("ul ul ul{list-style-type:square}");
        appendQuoteCss(css);
        appendCodeCss(css);
        appendTableCss(css);
        css.append("hr{border:0;border-top:1px solid ").append(border).append(";margin:1.6em 0}");
        css.append("img{display:block;max-width:100%;height:auto;margin:1.4em auto;")
                .append("border-radius:").append(radius).append("px}");
        if (responsive) {
            // 桌面端同样要 !important：内联模式下 pre 带 style 属性，普通规则压不过。
            // 容器的宽度与内边距不在这里写：它们由 containerInline() 内联给出（含主题自身的
            // maxWidth 令牌），硬编码会盖掉个别主题的宽度设定
            css.append("@media (min-width:768px){")
                    // 只改左右内边距、下内边距与行高：上内边距不能动，MAC 风格要留 36px 给圆点
                    .append("pre{padding-left:18px !important;padding-right:18px !important;")
                    .append("padding-bottom:14px !important;line-height:1.7 !important}")
                    .append("}");
            // 内联模式下行内 style 属性的优先级高于样式表，媒体查询必须加 !important 才能压过它，
            // 否则移动端字号/内边距的适配会被内联样式完全吃掉（内联时尤其明显）。
            // pre 只改左右内边距：MAC 风格的 36px 上内边距要留给圆点，不能被简写 padding 覆盖。
            css.append("@media (max-width:480px){")
                    .append("div.").append(CONTAINER_CLASS)
                    .append("{padding:").append(MOBILE_PADDING).append(" !important;font-size:")
                    .append(Math.max(fontSize - 1, 12)).append("px !important}")
                    .append("h1{font-size:1.45em !important}")
                    .append("h2{font-size:1.25em !important}")
                    .append("h3{font-size:1.12em !important}")
                    .append("h4,h5,h6{font-size:1em !important}")
                    .append("pre{font-size:12px !important;padding-left:10px !important;")
                    .append("padding-right:10px !important}")
                    .append("th,td{font-size:14px !important;padding:6px 8px !important}")
                    .append("table{-webkit-overflow-scrolling:touch}")
                    .append("blockquote{padding-left:")
                    .append(quote == Quote.QUOTE ? "2.2em" : ".9em")
                    .append(" !important;padding-right:.7em !important}")
                    .append("img{max-width:100% !important}")
                    .append("}");
        }
        css.append(extraCss);
        return css.toString();
    }

    /**
     * 正文容器 div 的基础样式（栏宽、居中、字体）。
     *
     * @return 样式声明，不含选择符与大括号
     */
    private String containerBase() {
        return "margin:0 auto;padding:16px;max-width:" + maxWidth + "px;"
                + "background:" + background + ";"
                + "color:" + text + ";"
                + "font-family:" + font + ";"
                + "font-size:" + fontSize + "px;"
                + "line-height:" + lineHeight + ";"
                + "word-wrap:break-word;overflow-wrap:anywhere";
    }

    /**
     * 正文容器 div 的内联样式，供 {@code wrapHtmlDocument} 直接写进 {@code style} 属性。
     *
     * <p>内联样式无法响应断点，这里取桌面端取值；移动端由样式表里带 {@code !important} 的
     * 媒体查询覆盖（{@code !important} 的优先级高于普通内联声明）。
     *
     * @return style 属性文本
     */
    String containerInline() {
        return "max-width:" + maxWidth + "px;margin:0 auto;padding:" + DESKTOP_PADDING
                + ";background:" + background + ";color:" + text
                + ";font-family:" + font + ";font-size:" + fontSize + "px;"
                + "line-height:" + lineHeight
                + ";word-wrap:break-word;overflow-wrap:anywhere";
    }

    private void appendHeadingCss(StringBuilder css) {
        css.append("h1,h2,h3,h4,h5,h6{line-height:1.4;margin:1.4em 0 .7em;font-weight:600;")
                .append("color:").append(primary).append("}");
        css.append("h1{font-size:1.7em}h2{font-size:1.4em}h3{font-size:1.2em}")
                .append("h4{font-size:1.08em}h5{font-size:1em}h6{font-size:.95em;color:")
                .append(muted).append("}");
        switch (title) {
            case BAR:
                css.append("h1,h2{border-left:5px solid ").append(accent)
                        .append(";padding-left:12px}");
                break;
            case UNDERLINE:
                css.append("h1,h2{border-bottom:2px solid ").append(accent)
                        .append(";padding-bottom:.3em}");
                break;
            case CENTER:
                css.append("h1{text-align:center;border-bottom:2px solid ").append(accent)
                        .append(";padding-bottom:.5em;margin-top:.4em}");
                css.append("h2{text-align:center;color:").append(text).append("}");
                break;
            default:
                break;
        }
    }

    private void appendQuoteCss(StringBuilder css) {
        css.append("blockquote p{margin:.5em 0}");
        switch (quote) {
            case FILL:
                css.append("blockquote{margin:1em 0;padding:.7em 1em;background:").append(panel)
                        .append(";border-radius:").append(radius).append("px;color:").append(muted)
                        .append("}");
                break;
            case QUOTE:
                // 引号装饰放在左侧留白里（padding-left 2.6em），不与正文抢位置
                css.append("blockquote{margin:1em 0;padding:.4em 1em .4em 2.6em;")
                        .append("border-left:4px solid ").append(accent)
                        .append(";color:").append(muted).append(";position:relative}");
                css.append("blockquote::before{content:\"\\201C\";position:absolute;left:.5em;")
                        .append("top:.15em;font-size:2.1em;line-height:1;color:").append(accent)
                        .append(";opacity:.32}");
                break;
            default:
                css.append("blockquote{margin:1em 0;padding:.2em 0 .2em 1em;border-left:4px solid ")
                        .append(accent).append(";background:").append(panel)
                        .append(";color:").append(muted).append("}");
                break;
        }
    }

    private void appendCodeCss(StringBuilder css) {
        css.append("pre{margin:1em 0;padding:12px 14px;background:").append(panel)
                .append(";color:").append(codeText)
                .append(";font-family:").append(mono)
                .append(";font-size:13px;line-height:1.6;white-space:pre;overflow:auto;")
                .append("box-sizing:border-box;border-radius:").append(radius).append("px}");
        css.append("code{font-family:").append(mono).append(";background:").append(panel)
                .append(";color:").append(codeText)
                .append(";padding:.15em .4em;border-radius:4px;font-size:.9em}");
        css.append("pre code{background:none;padding:0;font-size:100%;color:inherit}");
        switch (code) {
            case BORDER:
                css.append("pre{border:1px solid ").append(border).append("}");
                break;
            case MAC:
                // 顶部留出 36px 给三个圆点（直径 11px + 上边距 13px），否则首行代码会压在圆点上
                css.append("pre{position:relative;padding:36px 14px 12px;border:1px solid ")
                        .append(border).append("}");
                css.append("pre::before{content:\"\";position:absolute;top:13px;left:14px;")
                        .append("width:11px;height:11px;border-radius:50%;background:#ff5f56;")
                        .append("box-shadow:20px 0 0 #ffbd2e,40px 0 0 #27c93f}");
                break;
            default:
                break;
        }
    }

    private void appendTableCss(StringBuilder css) {
        css.append("table{margin:1em 0;border-collapse:collapse;max-width:100%;")
                .append("display:block;overflow-x:auto}");
        switch (table) {
            case STRIPED:
                css.append("th,td{border:0;border-bottom:1px solid ").append(border)
                        .append(";padding:8px 12px;text-align:left}");
                css.append("th{background:").append(panel).append(";font-weight:600}");
                css.append("tbody tr:nth-child(even){background:").append(panel).append("}");
                break;
            case MINIMAL:
                css.append("th,td{border:0;padding:8px 12px;text-align:left}");
                css.append("th{border-bottom:2px solid ").append(accent).append(";font-weight:600}");
                css.append("td{border-bottom:1px solid ").append(border).append("}");
                break;
            default:
                css.append("th,td{border:1px solid ").append(border)
                        .append(";padding:8px 12px;text-align:left}");
                css.append("th{background:").append(panel).append(";font-weight:600}");
                break;
        }
    }

    /**
     * 生成标签名 → {@code style} 属性值的映射，供内联模式使用。
     *
     * <p>内联模式无法表达 {@code :hover}、伪元素与后代选择器，因此表头底色、斑马纹等
     * 只能退化成对 {@code th} / {@code td} 逐个上色；表格斑马纹在内联模式下改为表头与单元格统一底色。
     *
     * @return 标签名到样式文本的映射（仅包含需要上色的标签）
     */
    Map<String, String> inlineStyles() {
        Map<String, String> styles = new LinkedHashMap<String, String>();
        styles.put("h1", headingInline("1.7em", true));
        styles.put("h2", headingInline("1.4em", true));
        styles.put("h3", headingInline("1.2em", false));
        styles.put("h4", headingInline("1.08em", false));
        styles.put("h5", headingInline("1em", false));
        styles.put("h6", "margin:1.4em 0 .7em;padding:0;line-height:1.4;font-weight:600;"
                + "font-size:.95em;color:" + muted);
        styles.put("p", "margin:.9em 0");
        styles.put("a", "color:" + primary + ";text-decoration:underline");
        styles.put("strong", "font-weight:600");
        styles.put("del", "color:" + muted + ";text-decoration:line-through");
        styles.put("ul", "padding-left:1.7em;margin:.8em 0");
        styles.put("ol", "padding-left:1.7em;margin:.8em 0");
        styles.put("li", "margin:.35em 0");
        styles.put("blockquote", quoteInline());
        styles.put("pre", preInline());
        styles.put("code", "font-family:" + mono + ";background:" + panel + ";color:" + codeText
                + ";padding:.15em .4em;border-radius:4px;font-size:.9em");
        styles.put("table", "margin:1em 0;border-collapse:collapse;max-width:100%");
        styles.put("th", "padding:8px 12px;text-align:left;font-weight:600;background:" + panel
                + ";border:1px solid " + border);
        styles.put("td", "padding:8px 12px;text-align:left;border:1px solid " + border);
        styles.put("hr", "border:0;border-top:1px solid " + border + ";margin:1.6em 0");
        styles.put("img", "display:block;max-width:100%;height:auto;margin:1.4em auto;"
                + "border-radius:" + radius + "px");
        return styles;
    }

    private String headingInline(String size, boolean major) {
        StringBuilder style = new StringBuilder("margin:1.4em 0 .7em;padding:0;line-height:1.4;")
                .append("font-weight:600;font-size:").append(size).append(";color:").append(primary);
        if (major && title == Title.BAR) {
            style.append(";border-left:5px solid ").append(accent).append(";padding-left:12px");
        } else if (major && title == Title.UNDERLINE) {
            style.append(";border-bottom:2px solid ").append(accent).append(";padding-bottom:.3em");
        } else if (major && title == Title.CENTER) {
            style.append(";text-align:center;border-bottom:2px solid ").append(accent)
                    .append(";padding-bottom:.5em");
        }
        return style.toString();
    }

    private String quoteInline() {
        StringBuilder style = new StringBuilder("margin:1em 0;color:").append(muted);
        if (quote == Quote.FILL) {
            style.append(";padding:.7em 1em;background:").append(panel)
                    .append(";border-radius:").append(radius).append("px");
        } else if (quote == Quote.QUOTE) {
            // 左侧留白给引号装饰，与 appendQuoteCss 的 2.6em 保持一致
            style.append(";padding:.4em 1em .4em 2.6em;border-left:4px solid ").append(accent);
        } else {
            style.append(";padding:.2em 0 .2em 1em;border-left:4px solid ").append(accent)
                    .append(";background:").append(panel);
        }
        return style.toString();
    }

    private String preInline() {
        // MAC 风格顶部要留出圆点的位置；内联 style 会盖掉样式表里的 padding-top，
        // 所以这里必须直接给出完整 padding，否则圆点会压在首行代码上
        String padding = code == Code.MAC ? "36px 14px 12px" : "12px 14px";
        StringBuilder style = new StringBuilder("margin:1em 0;padding:").append(padding)
                .append(";background:").append(panel).append(";color:").append(codeText)
                .append(";font-family:").append(mono)
                .append(";font-size:13px;line-height:1.6;white-space:pre;overflow:auto;")
                .append("border-radius:").append(radius).append("px");
        if (code == Code.BORDER || code == Code.MAC) {
            style.append(";border:1px solid ").append(border);
        }
        return style.toString();
    }
}
