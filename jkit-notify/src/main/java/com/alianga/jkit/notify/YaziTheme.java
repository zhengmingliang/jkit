package com.alianga.jkit.notify;

/**
 * YAZI（速查）主题的样式表。
 *
 * <p>选择器与数值对照 quickref.cn 线上 {@code style/style.css}：页面底色
 * {@code #f1f5f9}、白色圆角卡片、绿色 H3 标签 {@code #228e6c}、链接
 * {@code #0284c7}、行内码 {@code #059669}、表格隐藏表头与首列代码绿色、
 * 快捷键键帽、GitHub Pretty Lights 代码配色。
 *
 * <p>参考站点用 refs-cli 把标题层级重排成 H1 页头 / H2 分区 / H3 卡片网格；
 * DOM 结构由 {@link YaziLayout} 生成，本类只负责对应的版式规则。所有选择器
 * 都收在 {@code .jkit-yazi} 根节点下，避免影响同一页面里的其他内容。
 *
 * <p>复刻范围是 Markdown 正文容器。参考站点的顶栏、悬浮目录、搜索框、评论与页脚
 * 属于 refs-cli 的站点外壳，不由 Markdown 渲染产生，因此不在主题内。
 *
 * @author 郑明亮
 * @since 2.0.5
 */
final class YaziTheme {
    /**
     * 参考站点正文字体栈。
     */
    static final String FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,"
            + "sans-serif,'Apple Color Emoji','Segoe UI Emoji'";

    /**
     * 参考站点 H2 字体栈。
     */
    private static final String H2_FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Oxygen,"
            + "Ubuntu,Cantarell,'Fira Sans','Droid Sans','Helvetica Neue',sans-serif";

    /**
     * 参考站点等宽字体栈。
     */
    static final String MONO = "ui-monospace,SFMono-Regular,'SF Mono',Menlo,Consolas,"
            + "'Liberation Mono',monospace";

    private static final String LAYOUT_CSS = baseCss() + headingCss() + cardCss() + listCss()
            + tableCss() + codeCss();

    private YaziTheme() {
    }

    /**
     * 主题完整样式表（含响应式断点）。
     *
     * @return 以 {@code .jkit-yazi} 为根的 CSS 文本
     */
    static String css() {
        return LAYOUT_CSS + responsiveCss();
    }

    /**
     * 与断点无关的版式规则。
     *
     * @return CSS 文本
     */
    static String layoutCss() {
        return LAYOUT_CSS;
    }

    /**
     * 桌面 / 平板 / 手机断点规则。
     *
     * @return CSS 文本
     */
    static String responsiveCss() {
        return "@media (min-width:1024px){.jkit-yazi .h2wrap-body{display:grid;"
                + "grid-template-columns:repeat(3,minmax(0,1fr));gap:.95rem}"
                + ".jkit-yazi .h2wrap-body > .wrap{margin-bottom:0}}"
                + "@media (min-width:375px) and (max-width:1024px){"
                + ".jkit-yazi .wrap-header.h1wrap{margin-top:1.6rem;margin-bottom:2rem}"
                + ".jkit-yazi .h1wrap > h1{font-size:2rem;margin-bottom:2rem}"
                + ".jkit-yazi .h2wrap > h2{font-size:1.3rem}"
                + ".jkit-yazi .h3wrap > .wrap-body{overflow:auto}"
                + ".jkit-yazi .h1wrap-body{gap:2rem}"
                + ".jkit-yazi table td + td,.jkit-yazi table th + th{padding-top:0!important}"
                + ".jkit-yazi table.auto-wrap{display:block;overflow:auto}"
                + ".jkit-yazi table.auto-wrap thead{display:none}"
                + ".jkit-yazi table.auto-wrap td,.jkit-yazi table.auto-wrap th{display:block;"
                + "text-align:left!important}}"
                + "@media (max-width:480px){div.jkit-md{padding:.75rem!important;"
                + "font-size:16px!important}"
                + ".jkit-yazi .h1wrap > h1{font-size:2rem!important;margin-bottom:2rem!important}"
                + ".jkit-yazi .h2wrap > h2{font-size:1.3rem!important}"
                + ".jkit-yazi h3{font-size:.75rem!important}"
                + ".jkit-yazi h4,.jkit-yazi h5,.jkit-yazi h6{font-size:.925rem!important}"
                + ".jkit-yazi pre{font-size:.925rem!important;padding:1rem!important}"
                + ".jkit-yazi table td,.jkit-yazi table th{font-size:.925rem!important;"
                + "padding:9px 14px!important}"
                + ".jkit-yazi blockquote{padding:.75rem 1rem!important}}";
    }

    private static String baseCss() {
        return ".jkit-yazi{font-family:" + FONT + ";font-size:16px;line-height:normal;"
                + "color:#24292f;background:transparent;-webkit-text-size-adjust:100%;"
                + "word-wrap:break-word}"
                + ".jkit-yazi *,.jkit-yazi *::before,.jkit-yazi *::after{box-sizing:border-box}"
                + ".jkit-yazi p{margin:0}"
                + ".jkit-yazi a{color:#0284c7;text-decoration:underline;text-decoration-thickness:2px;"
                + "text-underline-offset:4px;text-decoration-color:transparent;overflow-wrap:normal;"
                + "background-image:linear-gradient(transparent,transparent 6px,#34495e 6px,#34495e);"
                + "background-position:bottom;background-size:100% 6px;background-repeat:repeat-x}"
                + ".jkit-yazi a:hover{text-decoration-color:#10b981}"
                + ".jkit-yazi strong{font-weight:700}"
                + ".jkit-yazi em,.jkit-yazi sup,.jkit-yazi sub{color:#6e7781}"
                + ".jkit-yazi del{color:inherit}"
                + ".jkit-yazi img{display:inline;max-width:100%;height:auto;margin:0;border-radius:0}"
                + ".jkit-yazi hr{margin:0;border:0;border-bottom:1px solid #d0d7de}"
                + ".jkit-yazi blockquote{margin:0;padding:.75rem 1rem;border:0;background:none;"
                + "color:inherit}"
                + ".jkit-yazi blockquote p{margin:0}";
    }

    private static String headingCss() {
        return ".jkit-yazi .wrap-header.h1wrap{text-align:center;margin-top:4.6rem;margin-bottom:3rem}"
                + ".jkit-yazi .wrap-header.h1wrap .wrap-body{color:#6e7781;max-width:850px;margin:0 auto}"
                + ".jkit-yazi .h1wrap > h1{display:inline-flex;align-items:center;margin:0 0 3rem;"
                + "font-size:3rem;line-height:1;font-weight:400;color:#24292f}"
                + ".jkit-yazi .h1wrap > h1 a{color:inherit;background:none;text-decoration:none}"
                + ".jkit-yazi .h1wrap-body{display:flex;flex-direction:column;gap:3rem;"
                + "padding-bottom:1.75rem}"
                + ".jkit-yazi .h2wrap > h2{position:relative;margin:0 0 24px;padding:0;"
                + "font-family:" + H2_FONT + ";font-size:30px;line-height:1.2;font-weight:200;"
                + "color:#24292f}"
                + ".jkit-yazi .h2wrap > h2 a{color:inherit;background:none;text-decoration:none}"
                + ".jkit-yazi .h2wrap > h2 a::after{content:'#';padding-right:.5rem;color:#10b981}"
                + ".jkit-yazi .wrap-header.h2wrap > .wrap-body{color:#94a3b8;margin-bottom:24px}"
                + ".jkit-yazi .h2wrap > .wrap-body > ul{list-style:initial;margin:0 0 0 1.2rem;"
                + "padding:0}"
                + ".jkit-yazi .h2wrap > .wrap-body > ul > li{margin:0;padding:0}"
                + ".jkit-yazi .wrap-header.h3wrap{width:100%;flex:1;z-index:0}"
                + ".jkit-yazi .h3wrap > h3{position:sticky;top:0;float:right;z-index:10;"
                + "margin:-24px 0 0;padding:4px 12px;border-radius:0 .5rem;background:#228e6c;"
                + "color:#e2e8f0;font-size:.75rem;line-height:1rem;font-weight:400;"
                + "letter-spacing:.05em}"
                + ".jkit-yazi .h3wrap > h3 a{color:inherit;background:none;text-decoration:none}"
                + ".jkit-yazi .h3wrap > h3 a::after{content:'#';position:absolute;left:-18px;"
                + "font-size:1rem;color:#10b981;display:none}"
                + ".jkit-yazi .h2wrap-body > .wrap:hover .h3wrap > h3 a::after{display:initial}"
                + ".jkit-yazi .h3wrap > .wrap-body{display:flex;flex-direction:column;"
                + "height:100%;width:100%;z-index:0}";
    }

    private static String cardCss() {
        StringBuilder css = new StringBuilder(1024);
        css.append(".jkit-yazi .h2wrap-body{font-size:.925rem;margin:0}")
                .append(".jkit-yazi .h2wrap-body > .wrap{position:relative;display:flex;")
                .append("flex-direction:column;margin-bottom:1rem;padding-top:1.5rem;")
                .append("border-radius:.5rem;background:#fff;")
                .append("box-shadow:0 6px 8px rgba(102,119,136,.03),0 1px 2px rgba(102,119,136,.3)}");
        for (int i = 2; i <= 10; i++) {
            css.append(".jkit-yazi .col-span-").append(i).append("{grid-column:span ")
                    .append(i).append('}');
            css.append(".jkit-yazi .row-span-").append(i).append("{grid-row:span ")
                    .append(i).append('}');
        }
        for (int i = 1; i <= 4; i++) {
            css.append(".jkit-yazi .cols-").append(i).append("{grid-template-columns:repeat(")
                    .append(i).append(",minmax(0,1fr))!important}");
        }
        // 卡片正文里的段落：浅灰底、上下内边距；单段落卡片去掉底色
        css.append(".jkit-yazi .h3wrap > .wrap-body > p{margin:0;width:100%;padding:.65rem 1rem;")
                .append("color:#6e7781;background:rgba(236,236,236,.58)}")
                .append(".jkit-yazi .h3wrap > .wrap-body > p:only-child{background:transparent!important;")
                .append("border-radius:.5rem}")
                .append(".jkit-yazi .h3wrap > .wrap-body > p:first-child::before{content:'-';")
                .append("position:absolute;top:0;left:0;width:100%;line-height:1.5rem;")
                .append("color:rgba(30,41,59,0);background:rgba(248,249,250,.33);")
                .append("border-radius:.5rem .5rem 0 0}")
                .append(".jkit-yazi .h3wrap > .wrap-body > p:not(:first-child):last-child{")
                .append("margin-top:auto;border-radius:0 0 .5rem .5rem}")
                .append(".jkit-yazi .h3wrap > .wrap-body > :not(:first-child):last-child{")
                .append("border-radius:0 0 .5rem .5rem}")
                .append(".jkit-yazi .wrap-body > h4,.jkit-yazi .wrap-body > h5,")
                .append(".jkit-yazi .wrap-body > h6{margin:0;padding:.25rem 1rem;")
                .append("border-top:1px solid transparent;background:rgba(175,184,193,.2);")
                .append("color:#24292f;font-size:.925rem;font-weight:400;text-align:center}");
        return css.toString();
    }

    private static String listCss() {
        return ".jkit-yazi .h2wrap-body ul,.jkit-yazi .h2wrap-body ol,"
                + ".jkit-yazi .h2wrap-body dl{display:grid;grid-template-columns:repeat(1,minmax(0,1fr));"
                + "list-style:none;list-style-position:outside;margin:.5rem 0;padding:0}"
                + ".jkit-yazi .h2wrap-body li{position:relative;padding:9px 9px 9px 26px;"
                + "border-bottom:1px solid rgba(236,236,236,.58)}"
                + ".jkit-yazi .h2wrap-body ul:not(.style-none) > li::before{content:'';"
                + "position:absolute;left:13px;top:18px;display:inline-block;width:4px;height:4px;"
                + "border-radius:50%;background:#556677}"
                + ".jkit-yazi .h2wrap-body ul:last-child > li:last-child,"
                + ".jkit-yazi .h2wrap-body ol:last-child > li:last-child{border-bottom:0}";
    }

    private static String tableCss() {
        return ".jkit-yazi table{display:table;width:100%;max-width:none;margin:0;"
                + "border-collapse:collapse;border-color:inherit;text-indent:0;overflow:visible}"
                + ".jkit-yazi table tr + tr{border-top:1px solid rgba(236,236,236,.58)}"
                + ".jkit-yazi table td,.jkit-yazi table th{padding:9px 14px;border:0;text-align:left}"
                + ".jkit-yazi table tr th:last-child,.jkit-yazi table tr td:last-child{"
                + "text-align:right}"
                + ".jkit-yazi table thead{display:none;border-bottom:1px solid rgba(85,102,119,.3)}"
                + ".jkit-yazi table thead th{white-space:nowrap;background:none;color:#10b981;"
                + "font-weight:400}"
                + ".jkit-yazi table.show-header thead{display:table-header-group}"
                + ".jkit-yazi table.left-align,.jkit-yazi table.left-align tr td:last-child,"
                + ".jkit-yazi table.left-align tr th:last-child{text-align:left}"
                + ".jkit-yazi table.shortcuts td:first-child,"
                + ".jkit-yazi table.code-nowrap td:first-child > code{white-space:nowrap}"
                + ".jkit-yazi table td:first-child > code{color:#059669;white-space:pre-wrap}"
                + ".jkit-yazi table td > del,.jkit-yazi table td:first-child > del > code,"
                + ".jkit-yazi .h3wrap-body p > del > code{color:#cf222e}"
                + ".jkit-yazi table.shortcuts td:not(:last-child) > code,"
                + ".jkit-yazi table.shortcuts-last td:last-child > code,"
                + ".jkit-yazi ul.shortcuts li > code,.jkit-yazi kbd{display:inline-block;"
                + "padding:.1em .54em;border:1px solid #d0d7de;border-radius:3px;"
                + "background:rgba(175,184,193,.2);color:#6e7781;"
                + "font-family:Arial,Helvetica,sans-serif;font-size:12px;line-height:1.5;"
                + "letter-spacing:.075rem;white-space:pre-wrap}";
    }

    private static String codeCss() {
        return ".jkit-yazi pre{margin:0;padding:1rem;overflow:hidden;border:0;border-radius:0;"
                + "background:transparent;color:#24292f;font-family:" + MONO + ";"
                + "font-size:.925rem;line-height:1.5;white-space:pre;word-wrap:normal;tab-size:4}"
                + ".jkit-yazi pre:only-child{height:100%}"
                + ".jkit-yazi pre code,.jkit-yazi code.code-highlight{display:block;overflow-x:auto;"
                + "padding:0;border:0;border-radius:0;background:transparent;color:inherit;"
                + "font-family:" + MONO + ";font-size:inherit;white-space:pre}"
                + ".jkit-yazi code{font-family:" + MONO + ";font-size:1em;padding:0;border:0;"
                + "border-radius:0;background:none;color:inherit}"
                + ".jkit-yazi .wrap-body p > code,.jkit-yazi .wrap-body ul li > code,"
                + ".jkit-yazi .wrap-body tbody td code{color:#059669}";
    }
}
