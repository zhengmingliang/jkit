package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlSimpleStatement;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link SqlStatementParser} SPI：按前导关键字注册自定义语句解析器，
 * 只兜内建分派未覆盖的关键字（default 分支），经 {@link SqlParseOptions#statementParsers} 生效。
 *
 * @author 郑明亮
 */
public class SqlStatementParserSpiTest {

    /** BACKUP 前缀语句 → 捕获剩余原文的简单语句。 */
    private static SqlStatementParser backupParser() {
        return new SqlStatementParser() {
            @Override
            public SqlStatement parse(SqlParseContext ctx) {
                ctx.next(); // 消费 BACKUP 关键字
                String rest = ctx.consumeRest().trim();
                SqlSimpleStatement stmt = new SqlSimpleStatement();
                stmt.setText(rest.isEmpty() ? "BACKUP" : "BACKUP " + rest);
                return stmt;
            }
        };
    }

    @Test
    public void customKeywordParsesInsteadOfFailing() {
        SqlParseOptions options = SqlParseOptions.defaults()
                .statementParsers(SqlStatementParsers.create().add("BACKUP", backupParser()));
        SqlStatement stmt = SQL.parse("BACKUP DATABASE shop TO DISK='/tmp/shop.bak'",
                SqlDialect.MYSQL, options);
        assertEquals(SqlStatementType.OTHER, stmt.type());
        SqlSimpleStatement simple = (SqlSimpleStatement) stmt;
        assertEquals("BACKUP DATABASE shop TO DISK='/tmp/shop.bak'", simple.text());
    }

    @Test
    public void keywordMatchIsCaseInsensitiveAndTrims() {
        SqlParseOptions options = SqlParseOptions.defaults()
                .statementParsers(SqlStatementParsers.create().add(" backup ", backupParser()));
        SqlStatement stmt = SQL.parse("backup table t", SqlDialect.MYSQL, options);
        assertEquals("BACKUP table t", ((SqlSimpleStatement) stmt).text());
    }

    @Test
    public void unregisteredKeywordStillThrows() {
        try {
            SQL.parse("BACKUP DATABASE shop", SqlDialect.MYSQL, SqlParseOptions.defaults());
            fail("expected SqlParseException");
        } catch (SqlParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("unsupported statement"));
        }
    }

    @Test
    public void builtinKeywordWinsOverRegistry() {
        // 注册 SELECT 也不生效：内建分派优先
        SqlParseOptions options = SqlParseOptions.defaults()
                .statementParsers(SqlStatementParsers.create().add("SELECT", backupParser()));
        SqlStatement stmt = SQL.parse("SELECT 1", SqlDialect.MYSQL, options);
        assertEquals(SqlStatementType.SELECT, stmt.type());
    }

    @Test
    public void customParserWorksInsideBatch() {
        SqlParseOptions options = SqlParseOptions.defaults()
                .statementParsers(SqlStatementParsers.create().add("BACKUP", backupParser()));
        List<SqlStatement> stmts = SQL.parseAll(
                "SELECT 1; BACKUP DATABASE shop TO '/x'; SELECT 2",
                SqlDialect.MYSQL, options);
        assertEquals(3, stmts.size());
        assertEquals(SqlStatementType.SELECT, stmts.get(0).type());
        assertEquals("BACKUP DATABASE shop TO '/x'",
                ((SqlSimpleStatement) stmts.get(1)).text());
        assertEquals(SqlStatementType.SELECT, stmts.get(2).type());
    }

    @Test
    public void customParserMayUseContextHelpers() {
        // 用 isIdent/matchIdent/consumeRest 组合，不吞整段也能分字段取值
        SqlStatementParser parser = new SqlStatementParser() {
            @Override
            public SqlStatement parse(SqlParseContext ctx) {
                assertTrue(ctx.isIdent("SIGNAL"));
                ctx.next();
                boolean hasState = ctx.matchIdent("SQLSTATE");
                SqlSimpleStatement stmt = new SqlSimpleStatement();
                stmt.setText("SIGNAL" + (hasState ? " SQLSTATE" : "") + " " + ctx.consumeRest().trim());
                return stmt;
            }
        };
        SqlParseOptions options = SqlParseOptions.defaults()
                .statementParsers(SqlStatementParsers.create().add("SIGNAL", parser));
        SqlStatement stmt = SQL.parse("SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dup'",
                SqlDialect.MYSQL, options);
        assertEquals("SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dup'",
                ((SqlSimpleStatement) stmt).text());
    }

    @Test
    public void nullResultFailsParse() {
        SqlParseOptions options = SqlParseOptions.defaults()
                .statementParsers(SqlStatementParsers.create().add("BAD", new SqlStatementParser() {
                    @Override
                    public SqlStatement parse(SqlParseContext context) {
                        return null;
                    }
                }));
        try {
            SQL.parse("BAD IDEA", SqlDialect.MYSQL, options);
            fail("expected SqlParseException");
        } catch (SqlParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("returned null"));
        }
    }

    @Test
    public void errorFromCustomParserCarriesPosition() {
        SqlParseOptions options = SqlParseOptions.defaults()
                .statementParsers(SqlStatementParsers.create().add("NOPE", new SqlStatementParser() {
                    @Override
                    public SqlStatement parse(SqlParseContext ctx) {
                        throw ctx.error("nope statement unsupported");
                    }
                }));
        try {
            SQL.parse("NOPE", SqlDialect.MYSQL, options);
            fail("expected SqlParseException");
        } catch (SqlParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("nope statement unsupported"));
            assertTrue("line should start at 1, got " + expected.line(),
                    expected.line() > 0);
        }
    }

    @Test
    public void registryIsPerOptionsAndLaterRegistrationOverrides() {
        SqlStatementParsers registry = SqlStatementParsers.create().add("BACKUP", backupParser());
        SqlParseOptions withBackup = SqlParseOptions.defaults().statementParsers(registry);
        SqlParseOptions withoutBackup = SqlParseOptions.defaults();

        assertEquals(SqlStatementType.OTHER,
                SQL.parse("BACKUP x", SqlDialect.MYSQL, withBackup).type());
        try {
            SQL.parse("BACKUP x", SqlDialect.MYSQL, withoutBackup);
            fail("expected SqlParseException");
        } catch (SqlParseException expected) {
            // 未注册的 options 不受另一份 options 影响
        }

        // 同关键字后注册覆盖先注册
        SqlStatementParsers overridden = SqlStatementParsers.create()
                .add("BACKUP", backupParser())
                .add("BACKUP", new SqlStatementParser() {
                    @Override
                    public SqlStatement parse(SqlParseContext ctx) {
                        ctx.next();
                        ctx.consumeRest();
                        SqlSimpleStatement stmt = new SqlSimpleStatement();
                        stmt.setText("OVERRIDDEN");
                        return stmt;
                    }
                });
        assertEquals("OVERRIDDEN",
                ((SqlSimpleStatement) SQL.parse("BACKUP anything", SqlDialect.MYSQL,
                        SqlParseOptions.defaults().statementParsers(overridden))).text());
    }

    @Test
    public void leftoverInputAfterCustomParseFails() {
        SqlParseOptions options = SqlParseOptions.defaults()
                .statementParsers(SqlStatementParsers.create().add("HALF", new SqlStatementParser() {
                    @Override
                    public SqlStatement parse(SqlParseContext ctx) {
                        ctx.next(); // 只消费关键字，剩余不吞
                        SqlSimpleStatement stmt = new SqlSimpleStatement();
                        stmt.setText("HALF");
                        return stmt;
                    }
                }));
        try {
            SQL.parse("HALF leftover tokens", SqlDialect.MYSQL, options);
            fail("expected SqlParseException");
        } catch (SqlParseException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("left unconsumed input"));
        }
    }

    @Test
    public void registryNullAndViewSemantics() {
        assertTrue(SqlStatementParsers.none().isEmpty());
        assertNull(SqlStatementParsers.none().find("BACKUP"));
        assertNull(SqlStatementParsers.none().find(null));
        SqlStatementParsers registry = SqlStatementParsers.create().add("BACKUP", backupParser());
        assertEquals(1, registry.parsers().size());
        try {
            registry.parsers().put("X", backupParser());
            fail("view should be read-only");
        } catch (UnsupportedOperationException expected) {
            // 只读视图
        }
        try {
            SqlStatementParsers.none().add(null, backupParser());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // keyword 必填
        }
        // options 传 null 注册表 → 关闭
        SqlParseOptions off = SqlParseOptions.defaults().statementParsers(null);
        assertTrue(off.statementParsers().isEmpty());
    }
}
