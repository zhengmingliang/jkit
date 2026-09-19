package com.alianga.jkit.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * 保留字注册表：按方言探测、自动引号与一次性告警回调。
 *
 * @author 郑明亮
 */
public class SqlReservedWordsTest {

    @Test
    public void detectsCommonCollidingKeywordsPerDialect() {
        assertTrue(SqlReservedWords.isKeyword(SqlDialect.MYSQL, "order"));
        assertTrue(SqlReservedWords.isKeyword(SqlDialect.MYSQL, "ORDER"));
        assertTrue(SqlReservedWords.isKeyword(SqlDialect.MYSQL, "desc"));
        // LEVEL / SORT 非 MySQL 保留字，但 GBase 8a（归并为 MYSQL 方言）保留
        assertTrue(SqlReservedWords.isKeyword(SqlDialect.MYSQL, "level"));
        assertTrue(SqlReservedWords.isKeyword(SqlDialect.MYSQL, "sort"));
        assertTrue(SqlReservedWords.isKeyword(SqlDialect.POSTGRES, "returning"));
        assertTrue(SqlReservedWords.isKeyword(SqlDialect.ORACLE, "rownum"));
        assertTrue(SqlReservedWords.isKeyword(SqlDialect.ORACLE12, "rownum"));
        assertTrue(SqlReservedWords.isKeyword(SqlDialect.SQLSERVER, "identity"));
        assertTrue(SqlReservedWords.isKeyword(SqlDialect.H2, "value"));
        assertTrue(SqlReservedWords.isKeyword(SqlDialect.DAMENG, "rownum"));
        // 非保留的普通名字不收录，保持可折叠
        assertFalse(SqlReservedWords.isKeyword(SqlDialect.MYSQL, "user_name"));
        assertFalse(SqlReservedWords.isKeyword(SqlDialect.POSTGRES, "created_at"));
    }

    @Test
    public void protectQuotesOnlyKeywordsByDefault() {
        assertEquals("`order`", SqlReservedWords.protect(SqlDialect.MYSQL, "order", false, true));
        assertEquals("`desc`", SqlReservedWords.protect(SqlDialect.MYSQL, "desc", false, true));
        assertEquals("user_name", SqlReservedWords.protect(SqlDialect.MYSQL, "user_name", false, true));
        // 全量引号语义不受 keywordAware 影响
        assertEquals("`user_name`",
                SqlReservedWords.protect(SqlDialect.MYSQL, "user_name", true, true));
        // 未开启 keywordAware 时不加引号
        assertEquals("order", SqlReservedWords.protect(SqlDialect.MYSQL, "order", false, false));
    }

    @Test
    public void listenerFiresOncePerIdentifier() {
        final List<String> fired = new ArrayList<String>();
        SqlReservedWords.protect(SqlDialect.MYSQL, "order", false, true,
                (dialect, name) -> fired.add(dialect.name() + ":" + name));
        assertEquals(1, fired.size());
        assertEquals("MYSQL:order", fired.get(0));
        // 同一标识符第二次不再回调（去重），但仍然加引号
        String again = SqlReservedWords.protect(SqlDialect.MYSQL, "order", false, true,
                (dialect, name) -> fired.add(dialect.name() + ":" + name));
        assertEquals(1, fired.size());
        assertEquals("`order`", again);
    }

    @Test
    public void nullAndEmptyPassThrough() {
        assertNull(SqlReservedWords.protect(SqlDialect.MYSQL, null, false, true));
        assertEquals("", SqlReservedWords.protect(SqlDialect.MYSQL, "", false, true));
        assertFalse(SqlReservedWords.isKeyword(SqlDialect.MYSQL, ""));
    }
}
