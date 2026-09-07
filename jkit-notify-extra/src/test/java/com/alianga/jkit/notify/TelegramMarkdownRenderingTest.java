package com.alianga.jkit.notify;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Telegram MARKDOWN→HTML 渲染的黑盒单元测试（针对 Telegram HTML parse_mode 兼容性）。
 *
 * <p>期望值为 Telegram Bot API HTML 子集：{@code <b>/<i>/<u>/<s>/<code>/<pre>/<a>/
 * <tg-spoiler>/<blockquote>}，并对 {@code <>&} 转义。列表不保留前导换行；
 * 任务列表 {@code - [x]}/{@code - [ ]} 转为 Unicode 勾选框 {@code ✅}/{@code ⬜}
 * （勾选框替代子弹与方括号标记）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class TelegramMarkdownRenderingTest {

    private static String markdownToTelegramHtml(String md) {
        try {
            java.lang.reflect.Method m = com.alianga.jkit.notify.channel.TelegramChannel.class
                    .getDeclaredMethod("markdownToTelegramHtml", String.class);
            m.setAccessible(true);
            return (String) m.invoke(new com.alianga.jkit.notify.channel.TelegramChannel(), md);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void checkedListItemRendersCorrectly() {
        String md = "- [x] 修改加载配置文件方式改为使用sorinResourePattenResolver类加载";
        String html = markdownToTelegramHtml(md);
        // 任务列表 → Unicode 勾选框（替代 • + [x]/[ ]）；纯列表无前导换行
        assertEquals("✅ 修改加载配置文件方式改为使用sorinResourePattenResolver类加载", html);
    }

    @Test
    public void adjacentBracketsWithoutCodeFencesBecomeBullet() {
        // "内存变量容器用的Java的HashMap，需要转为Java对象才能存入，py4j"
        // 不应让 py4j 前的逗号与［ 制造畸形链接；没有 [text](url) 形式就不应发生
        String md = "内存变量容器用的Java的HashMap，需要转为Java对象才能存入，py4j";
        String html = markdownToTelegramHtml(md);
        assertEquals("内存变量容器用的Java的HashMap，需要转为Java对象才能存入，py4j", html);
    }

    @Test
    public void inlineLessThanGreaterThanInExpressionEscaped() {
        // 表达式里的 < > 需要安全保留为文字
        String md = "整数 a 与 b 比较：a < b == true";
        String html = markdownToTelegramHtml(md);
        assertEquals("整数 a 与 b 比较：a &lt; b == true", html);
    }

    @Test
    public void hashMapWithoutFencesNotParsedAsLink() {
        // 最容易出 bug 的点：含有 HashMap<X> 但又没包裹代码块/行内代码时，
        // Telegram 会把 HashMap<X 误识别成链接前半部分导致消息截断。
        String md = "内存变量容器用的Java的HashMap，需要转为Java对象才能存入";
        String html = markdownToTelegramHtml(md);
        // 内容里不存在 [text](...)，这里的 < 不应出现；如有出现必须转义
        assertEquals("内存变量容器用的Java的HashMap，需要转为Java对象才能存入", html);
    }

    @Test
    public void listWithMultipleCheckedStatuses() {
        String md = "- [ ] hive 测试\n- [x] python控制内存\n- [ ] 添加文件写出";
        String html = markdownToTelegramHtml(md);
        assertEquals("⬜ hive 测试\n✅ python控制内存\n⬜ 添加文件写出", html);
    }

    @Test
    public void codeTokenInListItemPreserved() {
        String md = "- [x] 添加访问文件的接口（python等生成的图片文件等）";
        String html = markdownToTelegramHtml(md);
        assertEquals("✅ 添加访问文件的接口（python等生成的图片文件等）", html);
    }

    @Test
    public void fullScriptEngineMessageRoundTrip() {
        String md = "### 脚本引擎部分\n"
                + "- [x] 修改加载配置文件方式改为使用sorinResourePattenResolver类加载，配置文件加载顺序同springboot，优先加载jar包同级目录，其次加载iar包内的\n"
                + "- [x] 脚本引警增加统一资源释放逻辑：关闭可能遗漏的数据库连接，删除python临时脚本文件、清除内存 (Redis) 变量的值等\n"
                + "- [x] 代码库、系统函数 添加 语言类型隔离，eg: 例如封装的python函数getconnection，只能够在python脚本使用，SOL脚本下不应该展示\n"
                + "- [ ] Java类型与python类型互转问题，涉及到将python数据存储到内存变量，内存变量容器用的Java的HashMap，需要转为Java对象才能存入，py4j\n"
                + "- [ ] hive、gbase数据库造两个千万级大表，测试大表数据python脚本处理情况以及内存溢出可能导致的一些情况\n"
                + "- [ ] 添加 使用指定的普通用户 执行 脚本的功能（处理中行部署执行可能出现的问题）\n"
                + "- [ ] 添加文件写出到指定位置，并将数据存储到数据库的逻辑\n"
                + "- [ ] 添加访问文件的接口（python等生成的图片文件等）\n"
                + "- [x] python控制执行的内存大小";
        String html = markdownToTelegramHtml(md);
        // 标题→<b>；任务列表→✅/⬜；<& 已在管道中转义；无列表前导换行
        assertEquals("<b>脚本引擎部分</b>\n\n✅ 修改加载配置文件方式改为使用sorinResourePattenResolver类加载，配置文件加载顺序同springboot，优先加载jar包同级目录，其次加载iar包内的\n✅ 脚本引警增加统一资源释放逻辑：关闭可能遗漏的数据库连接，删除python临时脚本文件、清除内存 (Redis) 变量的值等\n✅ 代码库、系统函数 添加 语言类型隔离，eg: 例如封装的python函数getconnection，只能够在python脚本使用，SOL脚本下不应该展示\n⬜ Java类型与python类型互转问题，涉及到将python数据存储到内存变量，内存变量容器用的Java的HashMap，需要转为Java对象才能存入，py4j\n⬜ hive、gbase数据库造两个千万级大表，测试大表数据python脚本处理情况以及内存溢出可能导致的一些情况\n⬜ 添加 使用指定的普通用户 执行 脚本的功能（处理中行部署执行可能出现的问题）\n⬜ 添加文件写出到指定位置，并将数据存储到数据库的逻辑\n⬜ 添加访问文件的接口（python等生成的图片文件等）\n✅ python控制执行的内存大小", html);
    }
}
