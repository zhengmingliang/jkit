package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlStatement;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link SQL#bind}：把常量安全填进占位符，覆盖各方言回写。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class SqlBinderTest {

    @Test
    public void positionalStringAndNumber() {
        String sql = SQL.bind("SELECT * FROM t WHERE name = ? AND age = ?", "alice", 18);
        assertTrue(sql, sql.contains("name = 'alice'"));
        assertTrue(sql, sql.contains("age = 18"));
        assertEquals(1, SQL.parseAll(sql).size());
    }

    @Test
    public void injectionStaysInsideString() {
        String payload = "'; DROP TABLE t; --";
        String sql = SQL.bind("SELECT * FROM t WHERE name = ?", payload);
        assertTrue(sql, sql.contains("'''; DROP TABLE t; --'"));
        assertEquals("must remain a single statement", 1, SQL.parseAll(sql).size());
        assertFalse(sql.toUpperCase().contains("DROP TABLE T;") && SQL.parseAll(sql).size() > 1);
    }

    @Test
    public void nullBecomesNull() {
        String sql = SQL.bind("SELECT * FROM t WHERE name = ?", new Object[] {null});
        assertTrue(sql, sql.contains("name = NULL"));
    }

    @Test
    public void namedBinds() {
        Map<String, Object> vals = new LinkedHashMap<String, Object>();
        vals.put("name", "bob");
        vals.put("age", 20);
        String sql = SQL.bindNamed("SELECT * FROM t WHERE name = :name AND age = :age", vals);
        assertTrue(sql, sql.contains("'bob'"));
        assertTrue(sql, sql.contains("age = 20"));
    }

    @Test
    public void mixedPositionalAndNamed() {
        Map<String, Object> named = Collections.<String, Object>singletonMap("n", "x");
        SqlStatement stmt = SQL.bind(
                SQL.parse("SELECT * FROM t WHERE id = ? AND name = :n"),
                SqlDialect.MYSQL, new Object[] {1}, named);
        String sql = SQL.toSqlString(stmt);
        assertTrue(sql, sql.contains("id = 1"));
        assertTrue(sql, sql.contains("name = 'x'"));
    }

    @Test
    public void inListFromCollection() {
        String sql = SQL.bind("SELECT * FROM t WHERE id IN ?", Arrays.asList(1, 2, 3));
        assertTrue(sql, sql.contains("IN (1, 2, 3)") || sql.contains("IN(1, 2, 3)"));
        SQL.parse(sql);
    }

    @Test
    public void inListFromArray() {
        String sql = SQL.bind("SELECT * FROM t WHERE id IN ?", new int[] {4, 5});
        assertTrue(sql, sql.contains("4") && sql.contains("5"));
    }

    @Test
    public void emptyInRejected() {
        try {
            SQL.bind("SELECT * FROM t WHERE id IN ?", Collections.emptyList());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("empty"));
        }
    }

    @Test
    public void tooFewValuesRejected() {
        try {
            SQL.bind("SELECT * FROM t WHERE a = ? AND b = ?", 1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not enough"));
        }
    }

    @Test
    public void missingNamedRejected() {
        try {
            SQL.bindNamed("SELECT * FROM t WHERE name = :name", Collections.<String, Object>emptyMap());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("missing named"));
        }
    }

    @Test
    public void cloneDoesNotMutateOriginal() {
        SqlStatement orig = SQL.parse("SELECT * FROM t WHERE id = ?");
        String before = SQL.toSqlString(orig);
        SQL.bind(orig, SqlDialect.MYSQL, 9);
        assertEquals(before, SQL.toSqlString(orig));
    }

    @Test
    public void booleanLiteralByDialect() {
        String mysql = SQL.bind("SELECT * FROM t WHERE ok = ?", SqlDialect.MYSQL, true);
        assertTrue(mysql, mysql.contains("ok = 1"));
        String pg = SQL.bind("SELECT * FROM t WHERE ok = ?", SqlDialect.POSTGRES, true);
        assertTrue(pg, pg.toUpperCase().contains("TRUE"));
    }

    @Test
    public void allDialectsRoundTripInjection() {
        SqlDialect[] dialects = SqlDialect.values();
        String payload = "O'Reilly'; DROP TABLE t; --";
        for (int i = 0; i < dialects.length; i++) {
            SqlDialect d = dialects[i];
            String sql = SQL.bind("SELECT * FROM t WHERE name = ?", d, payload);
            assertEquals(d.name(), 1, SQL.parseAll(sql, d).size());
            SQL.parse(sql, d);
            assertTrue(d.name() + " " + sql, sql.contains("''"));
        }
    }

    @Test
    public void quoteSqlStringNeverUsesBackslash() {
        String q = SqlBinder.quoteSqlString("a\\b'c");
        assertEquals("'a\\b''c'", q);
        assertFalse(q.contains("\\'"));
    }
}
