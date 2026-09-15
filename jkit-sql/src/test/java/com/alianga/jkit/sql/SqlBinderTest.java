package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlStatement;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.alianga.jkit.sql.SQL.parseExpr;
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
    public void numericTableIdentUsesMysqlQuotesAndCommentWraps() {
        Map<String, Object> vals = new LinkedHashMap<String, Object>();
        vals.put("name", "bob");
        vals.put("nameKey", parseExpr("LENGTH(name)"));
        vals.put("age", 20);
        vals.put("table", 10086);
        SqlParseOptions opt = SqlParseOptions.defaults()
                .placeholders(SqlPlaceholders.create().hashBrace().commonModelTemplates());
        SqlStatement statement = SQL.parse(
                "SELECT * FROM #{table} WHERE name = :name AND age = :age and "
                        + ":nameKey > 2 or nick_name = @name@",
                SqlDialect.MYSQL, opt);
        SqlStatement bound = SQL.bindNamed(statement, SqlDialect.MYSQL, vals);
        bound.addComment("我是注释");
        String sql2 = bound.toString();
        assertTrue("MySQL default toString must backtick numeric table: " + sql2, sql2.contains("`10086`"));
        assertFalse("must not use ANSI double quotes: " + sql2, sql2.contains("\"10086\""));
        assertTrue(sql2, sql2.contains("/*") && sql2.contains("我是注释") && sql2.contains("*/"));
        assertFalse("bare comment must not prefix the statement: " + sql2,
                sql2.startsWith("我是注释"));
        assertTrue(sql2.toUpperCase(), sql2.toUpperCase().contains("SELECT"));
        assertTrue(sql2, sql2.contains("'bob'"));
        assertTrue(sql2, sql2.contains("LENGTH(name)"));
        SQL.parse(sql2, SqlDialect.MYSQL);

        String mysql = SQL.toSqlString(bound, SqlDialect.MYSQL);
        assertTrue(mysql, mysql.contains("`10086`"));
        String oracle = SQL.toSqlString(bound, SqlDialect.ORACLE);
        assertTrue(oracle, oracle.contains("\"10086\""));
        String sqlserver = SQL.toSqlString(bound, SqlDialect.SQLSERVER);
        assertTrue(sqlserver, sqlserver.contains("[10086]"));
    }

    @Test
    public void namedTemplatePlaceholdersAndFormula() {
        Map<String, Object> vals = new LinkedHashMap<String, Object>();
        vals.put("name", "bob");
        vals.put("nameKey", parseExpr("LENGTH(name)"));
        vals.put("age", 20);
        vals.put("table", "users");
        SqlParseOptions opt = SqlParseOptions.defaults()
                .placeholders(SqlPlaceholders.create().commonModelTemplates().mybatis());
        String sql = SQL.bindNamed(
                "SELECT * FROM #{table} WHERE name = :name AND age = :age AND "
                        + ":nameKey > 2 OR nick_name = @name@",
                SqlDialect.MYSQL, opt, vals);
        assertTrue(sql, sql.contains("FROM users") || sql.contains("FROM `users`"));
        assertFalse("table name must not be a string literal", sql.contains("FROM 'users'"));
        assertTrue(sql, sql.contains("name = 'bob'"));
        assertTrue(sql, sql.contains("age = 20"));
        assertTrue(sql, sql.contains("LENGTH(name) > 2"));
        assertTrue(sql, sql.contains("nick_name = 'bob'"));
        assertFalse(sql, sql.contains("#{table}"));
        assertFalse(sql, sql.contains("@name@"));
        assertFalse(sql, sql.contains(":name"));
        SQL.parse(sql, SqlDialect.MYSQL);
    }

    @Test
    public void numericTablePlaceholderIsQuotedIdent() {
        Map<String, Object> vals = Collections.<String, Object>singletonMap("table", 20);
        SqlParseOptions opt = SqlParseOptions.defaults()
                .placeholders(SqlPlaceholders.create().mybatis());
        String sql = SQL.bindNamed("SELECT * FROM #{table}", SqlDialect.MYSQL, opt, vals);
        assertTrue(sql, sql.contains("`20`"));
        assertFalse(sql, sql.contains("#{table}"));
    }

    @Test
    public void tablePlaceholderRejectsSqlInjection() {
        Map<String, Object> vals = Collections.<String, Object>singletonMap("table", "t; DROP TABLE x");
        SqlParseOptions opt = SqlParseOptions.defaults()
                .placeholders(SqlPlaceholders.create().mybatis());
        String sql = SQL.bindNamed("SELECT * FROM #{table}", SqlDialect.MYSQL, opt, vals);
        assertEquals(1, SQL.parseAll(sql).size());
        assertTrue(sql, sql.contains("`"));
        assertFalse(sql.toUpperCase().contains("DROP TABLE X") && !sql.contains("`"));
    }

    @Test
    public void mybatisJdbcTypeBindsByPropertyName() {
        Map<String, Object> vals = new LinkedHashMap<String, Object>();
        vals.put("id", 7);
        vals.put("table", "t_user");
        SqlParseOptions opt = SqlParseOptions.defaults()
                .placeholders(SqlPlaceholders.create().mybatis());
        String sql = SQL.bindNamed(
                "SELECT * FROM ${table} WHERE id = #{id, jdbcType=INTEGER}",
                SqlDialect.MYSQL, opt, vals);
        assertTrue(sql, sql.contains("FROM t_user") || sql.contains("FROM `t_user`"));
        assertTrue(sql, sql.contains("id = 7"));
        assertFalse(sql, sql.contains("#{id"));
        assertFalse(sql, sql.contains("${table}"));
        SQL.parse(sql, SqlDialect.MYSQL);
    }

    @Test
    public void unknownTemplateIdentLeftAlone() {
        SqlParseOptions opt = SqlParseOptions.defaults()
                .placeholders(SqlPlaceholders.create().atWrapped());
        Map<String, Object> vals = Collections.<String, Object>singletonMap("age", 1);
        String sql = SQL.bindNamed("SELECT * FROM t WHERE x = @missing@ AND age = @age@",
                SqlDialect.MYSQL, opt, vals);
        assertTrue(sql, sql.contains("@missing@"));
        assertTrue(sql, sql.contains("age = 1"));
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
        SqlDialect[] asNumber = new SqlDialect[] {
                SqlDialect.MYSQL, SqlDialect.HIVE, SqlDialect.ORACLE, SqlDialect.ORACLE12,
                SqlDialect.DAMENG, SqlDialect.SQLSERVER, SqlDialect.SQLITE, SqlDialect.DB2
        };
        for (int i = 0; i < asNumber.length; i++) {
            SqlDialect d = asNumber[i];
            String yes = SQL.bind("SELECT * FROM t WHERE ok = ?", d, true);
            String no = SQL.bind("SELECT * FROM t WHERE ok = ?", d, false);
            assertTrue(d.name() + " true: " + yes, yes.contains("ok = 1"));
            assertFalse(d.name() + " true must not be TRUE: " + yes, yes.toUpperCase().contains("TRUE"));
            assertTrue(d.name() + " false: " + no, no.contains("ok = 0"));
            assertFalse(d.name() + " false must not be FALSE: " + no, no.toUpperCase().contains("FALSE"));
            SQL.parse(yes, d);
            SQL.parse(no, d);
        }
        SqlDialect[] asKeyword = new SqlDialect[] {
                SqlDialect.POSTGRES, SqlDialect.H2, SqlDialect.ANSI, SqlDialect.PRESTO,
                SqlDialect.CLICKHOUSE
        };
        for (int i = 0; i < asKeyword.length; i++) {
            SqlDialect d = asKeyword[i];
            String yes = SQL.bind("SELECT * FROM t WHERE ok = ?", d, true);
            String no = SQL.bind("SELECT * FROM t WHERE ok = ?", d, false);
            assertTrue(d.name() + " true: " + yes, yes.toUpperCase().contains("TRUE"));
            assertTrue(d.name() + " false: " + no, no.toUpperCase().contains("FALSE"));
            SQL.parse(yes, d);
            SQL.parse(no, d);
        }
        assertEquals("every dialect classified", SqlDialect.values().length,
                asNumber.length + asKeyword.length);
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
    public void formulaNowInWhere() {
        String sql = SQL.bind("SELECT * FROM t WHERE ts > ?", parseExpr("NOW()"));
        assertTrue(sql, sql.contains("ts > NOW()"));
        assertFalse(sql, sql.contains("'NOW()'"));
        SQL.parse(sql);
    }

    @Test
    public void formulaDateAdd() {
        String sql = SQL.bind("SELECT * FROM t WHERE ts > ?",
                parseExpr("DATE_ADD(NOW(), INTERVAL 7 DAY)"));
        assertTrue(sql, sql.toUpperCase().contains("DATE_ADD"));
        assertTrue(sql, sql.contains("INTERVAL"));
        assertFalse(sql, sql.contains("'DATE_ADD"));
        SQL.parse(sql);
    }

    @Test
    public void formulaArithmeticAndColumn() {
        String sql = SQL.bind("SELECT * FROM t WHERE age > ? AND created = ?",
                parseExpr("age + 1"), parseExpr("created_at"));
        assertTrue(sql, sql.contains("age > age + 1") || sql.contains("age > (age + 1)"));
        assertTrue(sql, sql.contains("created = created_at") || sql.contains("created=created_at"));
        SQL.parse(sql);
    }

    @Test
    public void stringNowStaysQuotedNotFormula() {
        String sql = SQL.bind("SELECT * FROM t WHERE ts > ?", "NOW()");
        assertTrue(sql, sql.contains("'NOW()'"));
        assertFalse("string must not become a function call", sql.contains("ts > NOW()"));
    }

    @Test
    public void namedFormula() {
        Map<String, Object> vals = new LinkedHashMap<String, Object>();
        vals.put("expr", parseExpr("NOW()"));
        String sql = SQL.bindNamed("SELECT * FROM t WHERE ts > :expr", vals);
        assertTrue(sql, sql.contains("ts > NOW()"));
        assertFalse(sql, sql.contains("'NOW()'"));
    }

    @Test
    public void formulaInSelectListAndInsertAndUpdate() {
        String select = SQL.bind("SELECT ?", parseExpr("NOW()"));
        assertTrue(select, select.contains("SELECT NOW()"));
        String insert = SQL.bind("INSERT INTO t (ts) VALUES (?)", parseExpr("NOW()"));
        assertTrue(insert, insert.contains("VALUES (NOW())") || insert.contains("VALUES(NOW())"));
        String update = SQL.bind("UPDATE t SET ts = ? WHERE id = ?", parseExpr("NOW()"), 1);
        assertTrue(update, update.contains("ts = NOW()"));
        assertTrue(update, update.contains("id = 1"));
        SQL.parse(select);
        SQL.parse(insert);
        SQL.parse(update);
    }

    @Test
    public void formulaInFunctionArgAndBetween() {
        String fn = SQL.bind("SELECT COALESCE(?, 0) FROM t", parseExpr("age + 1"));
        assertTrue(fn, fn.contains("COALESCE(age + 1, 0)") || fn.contains("COALESCE((age + 1), 0)"));
        String between = SQL.bind("SELECT * FROM t WHERE ts BETWEEN ? AND ?",
                parseExpr("DATE_SUB(NOW(), INTERVAL 1 DAY)"), parseExpr("NOW()"));
        assertTrue(between, between.toUpperCase().contains("DATE_SUB"));
        assertTrue(between, between.contains("NOW()"));
        SQL.parse(fn);
        SQL.parse(between);
    }

    @Test
    public void formulaMixedWithLiteral() {
        String sql = SQL.bind("SELECT * FROM t WHERE name = ? AND ts > ?",
                "alice", parseExpr("NOW()"));
        assertTrue(sql, sql.contains("name = 'alice'"));
        assertTrue(sql, sql.contains("ts > NOW()"));
        SQL.parse(sql);
    }

    @Test
    public void formulaInInList() {
        String sql = SQL.bind("SELECT * FROM t WHERE id IN ?",
                Arrays.asList(1, parseExpr("id + 1"), 3));
        assertTrue(sql, sql.contains("id + 1") || sql.contains("id+1"));
        assertTrue(sql, sql.contains("1"));
        SQL.parse(sql);
    }

    @Test
    public void formulaRoundTripDialects() {
        SqlDialect[] dialects = new SqlDialect[] {
                SqlDialect.MYSQL, SqlDialect.POSTGRES, SqlDialect.ORACLE, SqlDialect.H2,
                SqlDialect.SQLSERVER, SqlDialect.DAMENG, SqlDialect.SQLITE
        };
        for (int i = 0; i < dialects.length; i++) {
            SqlDialect d = dialects[i];
            String sql = SQL.bind("SELECT * FROM t WHERE ts > ?", d, parseExpr("NOW()"));
            assertTrue(d.name() + " " + sql, sql.contains("NOW()"));
            assertFalse(d.name() + " quoted " + sql, sql.contains("'NOW()'"));
            SQL.parse(sql, d);
        }
    }

    @Test
    public void quoteSqlStringNeverUsesBackslash() {
        String q = SqlBinder.quoteSqlString("a\\b'c");
        assertEquals("'a\\b''c'", q);
        assertFalse(q.contains("\\'"));
    }
}
