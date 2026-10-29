package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlLiteral;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 P0-7（SqlBuilder.literal 转义路径统一到 quoteSqlString）与
 * P0-8（SqlWall 新增 denyMysqlExecutableComment 开关）。
 */
public class SqlWallMysqlExecutableCommentTest {

    // ============ P0-7: SqlBuilder.literal 转义一致性 ============

    /**
     * 单引号被转义为 ''（与 SqlInjectRewriter.quoteSqlString 一致）。
     */
    @Test
    public void literalEscapesSingleQuote() {
        SqlLiteral e = (SqlLiteral) SqlBuilder.literal("O'Reilly");
        assertEquals(SqlLiteral.Kind.STRING, e.kind());
        assertEquals("'O''Reilly'", e.value());
    }

    /**
     * 连续多个单引号全部转义。
     */
    @Test
    public void literalEscapesMultipleQuotes() {
        SqlLiteral e = (SqlLiteral) SqlBuilder.literal("'''");
        assertEquals("''''''''", e.value());
    }

    /**
     * null / 数字 / 布尔走原路径，不受转义路径影响。
     */
    @Test
    public void literalHandlesNonStrings() {
        assertEquals(SqlLiteral.Kind.NULL, ((SqlLiteral) SqlBuilder.literal(null)).kind());
        assertEquals(SqlLiteral.Kind.NUMBER, ((SqlLiteral) SqlBuilder.literal(42)).kind());
        assertEquals(SqlLiteral.Kind.BOOLEAN, ((SqlLiteral) SqlBuilder.literal(true)).kind());
    }

    /**
     * SqlExpr 直接透传。
     */
    @Test
    public void literalPassesThroughSqlExpr() {
        SqlExpr inner = SqlLiteral.of(SqlLiteral.Kind.NUMBER, "1");
        assertEquals(inner, SqlBuilder.literal(inner));
    }

    /**
     * 转义结果必须与 SqlInjectRewriter.quoteSqlString 完全一致（统一实现）。
     */
    @Test
    public void literalMatchesInjectRewriterQuoting() {
        String[] samples = {
                "plain", "O'Reilly", "it's", "a''b", "'start", "end'", "'''",
                "mixed \" /;-- string", ""
        };
        for (String s : samples) {
            SqlLiteral e = (SqlLiteral) SqlBuilder.literal(s);
            String expected = SqlInjectRewriter.quoteSqlString(s);
            assertEquals("mismatch for: " + s, expected, e.value());
        }
    }

    // ============ P0-8: denyMysqlExecutableComment 配置 ============

    /**
     * 默认 false：MySQL 可执行注释（版本条件注释）保持向后兼容放行。
     */
    @Test
    public void mysqlExecutableCommentAllowedByDefault() {
        // 原 GoldenCorpus 含此类合法 MySQL dump 语句，必须保持通过
        SqlWallResult r = SQL.wall(
                "/*!40101 SET NAMES utf8 */",
                SqlDialect.MYSQL,
                SqlWallConfig.defaults());
        assertFalse("should not report comment-bypass by default: " + r.violations(),
                r.violations().contains("comment-bypass"));
    }

    /**
     * 开启后：MySQL 可执行注释报 comment-bypass。
     */
    @Test
    public void mysqlExecutableCommentDeniedWhenEnabled() {
        SqlWallConfig cfg = SqlWallConfig.defaults().denyMysqlExecutableComment(true);
        SqlWallResult r = SQL.wall(
                "/*!50000 SELECT*/ 1=1 -- */",
                SqlDialect.MYSQL,
                cfg);
        assertTrue("expected comment-bypass: " + r.violations(),
                r.violations().contains("comment-bypass"));
    }

    /**
     * 开启后，正常 SELECT 仍通过（不误杀）。
     */
    @Test
    public void normalSelectStillPassesWhenEnabled() {
        SqlWallConfig cfg = SqlWallConfig.defaults().denyMysqlExecutableComment(true);
        SqlWallResult r = SQL.wall("SELECT id FROM t WHERE id = 1", SqlDialect.MYSQL, cfg);
        assertTrue("normal select must pass: " + r.violations(), r.passed());
    }

    /**
     * Oracle hint（以 "斜杠 星号 加号" 开头）在开启 denyMysqlExecutableComment 后
     * 仍应放行：它是优化提示不是可执行语句。
     */
    @Test
    public void oracleHintStillAllowedWhenEnabled() {
        SqlWallConfig cfg = SqlWallConfig.defaults().denyMysqlExecutableComment(true);
        SqlWallResult r = SQL.wall(
                "SELECT /*+ INDEX(t idx_id) */ id FROM t",
                SqlDialect.MYSQL,
                cfg);
        assertFalse("oracle hint should not be flagged: " + r.violations(),
                r.violations().contains("comment-bypass"));
    }

    /**
     * 普通块注释（非 hint、非可执行）在默认与开启状态下都应报 comment-bypass（这是原有行为）。
     */
    @Test
    public void plainBlockCommentStillDenied() {
        SqlWallResult r = SQL.wall("SELECT 1 /* plain */ FROM t", SqlDialect.MYSQL, SqlWallConfig.defaults());
        assertTrue("plain block comment should be denied: " + r.violations(),
                r.violations().contains("comment-bypass"));

        SqlWallConfig enabled = SqlWallConfig.defaults().denyMysqlExecutableComment(true);
        SqlWallResult r2 = SQL.wall("SELECT 1 /* plain */ FROM t", SqlDialect.MYSQL, enabled);
        assertTrue(r2.violations().contains("comment-bypass"));
    }

    /**
     * -- 与 # 单行注释仍报 comment-bypass（原有行为不变）。
     */
    @Test
    public void lineCommentsStillDenied() {
        assertTrue(SQL.wall("SELECT 1 -- x", SqlDialect.MYSQL, SqlWallConfig.defaults())
                .violations().contains("comment-bypass"));
        assertTrue(SQL.wall("SELECT 1 # x", SqlDialect.MYSQL, SqlWallConfig.defaults())
                .violations().contains("comment-bypass"));
    }

    /**
     * 字符串字面量内的 -- / # / 斜杠星号不应被误判（原有行为）。
     */
    @Test
    public void commentLikeTokensInStringLiteralNotFlagged() {
        SqlWallConfig cfg = SqlWallConfig.defaults().denyMysqlExecutableComment(true);
        SqlWallResult r = SQL.wall(
                "SELECT 'a -- b # c /* not a comment */ d' FROM t",
                SqlDialect.MYSQL,
                cfg);
        assertFalse("comment-like content in string literal must not be flagged: " + r.violations(),
                r.violations().contains("comment-bypass"));
    }

    /**
     * denyCommentBypass=false 时，即使 denyMysqlExecutableComment=true 也不检查（总开关优先）。
     */
    @Test
    public void denyCommentBypassFalseTakesPriority() {
        SqlWallConfig cfg = SqlWallConfig.defaults()
                .denyCommentBypass(false)
                .denyMysqlExecutableComment(true);
        SqlWallResult r = SQL.wall("/*!50000 SELECT*/ 1=1 -- */", SqlDialect.MYSQL, cfg);
        assertFalse(r.violations().contains("comment-bypass"));
    }
}
