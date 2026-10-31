package com.alianga.jkit.storage.sync;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 同步排除规则的段边界语义。
 *
 * <p>历史 bug：排除项按裸前缀匹配，排除 {@code tmp} 会连 {@code tmp2/}、
 * {@code tmpfile.txt} 一起误伤——用户以为只排了一个目录，实际少同步了一片。
 * 回归锁：命中自身或子孙才排除，带不带尾随斜杠写法等效。</p>
 *
 * @author 郑明亮
 */
public class SyncExcludesTest {

    @Test
    public void parseNormalizesPrefixes() {
        assertEquals(Arrays.asList("logs", "tmp"), SyncExcludes.parse("logs/, tmp "));
        assertEquals(Arrays.asList("a"), SyncExcludes.parse("a///"));
        assertEquals(Collections.emptyList(), SyncExcludes.parse(""));
        assertEquals(Collections.emptyList(), SyncExcludes.parse(null));
        assertEquals(Collections.emptyList(), SyncExcludes.parse(" , ,, "));
    }

    @Test
    public void segmentBoundaryMatching() {
        // 命中自身与子孙
        assertTrue(SyncExcludes.matches("tmp", "tmp"));
        assertTrue(SyncExcludes.matches("tmp/a.txt", "tmp"));
        assertTrue(SyncExcludes.matches("tmp/deep/b.txt", "tmp"));
        // 不误伤同前缀的兄弟
        assertFalse("tmp2 是另一个目录，不能被 tmp 排除", SyncExcludes.matches("tmp2/a.txt", "tmp"));
        assertFalse(SyncExcludes.matches("tmpfile.txt", "tmp"));
        assertFalse("前缀出现在路径中段不算命中", SyncExcludes.matches("a/tmp/b.txt", "tmp"));
    }

    @Test
    public void anyMatchCoversList() {
        List<String> prefixes = SyncExcludes.parse("logs/,tmp,node_modules");
        assertTrue(SyncExcludes.anyMatch("tmp/x", prefixes));
        assertTrue(SyncExcludes.anyMatch("logs", prefixes));
        assertTrue(SyncExcludes.anyMatch("node_modules/pkg/index.js", prefixes));
        assertFalse(SyncExcludes.anyMatch("src/main.java", prefixes));
        assertFalse(SyncExcludes.anyMatch("tmp2/x", prefixes));
    }

    @Test
    public void emptyInputsAreSafe() {
        assertFalse(SyncExcludes.matches(null, "tmp"));
        assertFalse(SyncExcludes.matches("tmp", null));
        assertFalse(SyncExcludes.matches("tmp", ""));
        assertFalse(SyncExcludes.anyMatch("tmp/x", Collections.<String>emptyList()));
        assertFalse(SyncExcludes.anyMatch("tmp/x", null));
    }

    @Test
    public void syncOptionsExcludePrefixesComposeWithFilter() {
        SyncOptions options = SyncOptions.incremental()
                .excludePrefixes("logs/, tmp");
        assertEquals(Arrays.asList("logs", "tmp"), options.excludePrefixes());
        assertFalse(options.accepts("tmp"));
        assertFalse(options.accepts("tmp/deep/a.txt"));
        // tmp2 是另一个目录，不能被误伤
        assertTrue(options.accepts("tmp2/a.txt"));
        assertTrue(options.accepts("src/main.java"));

        // 与 filter 叠加：两者都通过才参与
        options.filter(path -> path.endsWith(".java"));
        assertFalse(options.accepts("tmp/a.java"));
        assertFalse(options.accepts("src/readme.md"));
        assertTrue(options.accepts("src/Main.java"));

        // 空输入清空排除清单
        SyncOptions none = SyncOptions.incremental().excludePrefixes(" , ");
        assertTrue(none.excludePrefixes().isEmpty());
        assertTrue(none.accepts("tmp/a.txt"));
    }
}
