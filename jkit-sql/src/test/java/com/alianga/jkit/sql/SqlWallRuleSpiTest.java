package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlStatementType;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link SqlWallRule} SPI：自定义规则在全部内置检查之后执行，注册口 {@link SqlWallConfig#rules}。
 *
 * @author 郑明亮
 */
public class SqlWallRuleSpiTest {

    /** 非 SELECT 一律违规的自定义规则。 */
    private static final SqlWallRule NON_SELECT = new SqlWallRule() {
        @Override
        public void check(com.alianga.jkit.sql.ast.SqlStatement statement, SqlWallConfig config,
                SqlWallViolations violations) {
            if (statement.type() != SqlStatementType.SELECT) {
                violations.add("non-select");
            }
        }
    };

    @Test
    public void customRuleFires() {
        SqlWallConfig cfg = SqlWallConfig.defaults().rules(NON_SELECT);
        SqlWallResult r = SQL.wall("INSERT INTO t VALUES (1)", SqlDialect.MYSQL, cfg);
        assertFalse(r.passed());
        assertTrue(r.violations().toString(), r.violations().contains("non-select"));

        assertTrue(SQL.wall("SELECT 1 FROM t", SqlDialect.MYSQL, cfg).passed());
    }

    @Test
    public void customRuleRunsAfterBuiltinsAndKeepsThem() {
        SqlWallConfig cfg = SqlWallConfig.defaults().rules(NON_SELECT);
        SqlWallResult r = SQL.wall("DROP TABLE t", SqlDialect.MYSQL, cfg);
        assertTrue(r.violations().toString(), r.violations().contains("deny-ddl"));
        assertTrue(r.violations().toString(), r.violations().contains("non-select"));
    }

    @Test
    public void customRulesRunPerStatementOfBatch() {
        SqlWallConfig cfg = SqlWallConfig.defaults().rules(NON_SELECT);
        SqlWallResult r = SQL.wall("SELECT 1; DROP TABLE t", SqlDialect.MYSQL, cfg);
        assertTrue(r.violations().toString(), r.violations().contains("multi-statement"));
        assertTrue(r.violations().toString(), r.violations().contains("non-select"));
    }

    @Test
    public void multipleRulesKeepRegistrationOrder() {
        final StringBuilder order = new StringBuilder();
        SqlWallRule first = new SqlWallRule() {
            @Override
            public void check(com.alianga.jkit.sql.ast.SqlStatement statement, SqlWallConfig config,
                    SqlWallViolations violations) {
                order.append('1');
                violations.add("first-rule");
            }
        };
        SqlWallRule second = new SqlWallRule() {
            @Override
            public void check(com.alianga.jkit.sql.ast.SqlStatement statement, SqlWallConfig config,
                    SqlWallViolations violations) {
                order.append('2');
                violations.add("second-rule");
            }
        };
        SqlWallConfig cfg = SqlWallConfig.defaults().rules(first, second);
        SqlWallResult r = SQL.wall("SELECT 1", SqlDialect.MYSQL, cfg);
        assertEquals("12", order.toString());
        assertTrue(r.violations().contains("first-rule"));
        assertTrue(r.violations().contains("second-rule"));
    }

    @Test
    public void duplicateCodesAreDeduped() {
        SqlWallRule noisy = new SqlWallRule() {
            @Override
            public void check(com.alianga.jkit.sql.ast.SqlStatement statement, SqlWallConfig config,
                    SqlWallViolations violations) {
                violations.add("dup");
                violations.add("dup");
                violations.add(null);
            }
        };
        SqlWallResult r = SQL.wall("SELECT 1", SqlDialect.MYSQL, SqlWallConfig.defaults().rules(noisy));
        assertEquals(1, count(r.violations(), "dup"));
    }

    @Test
    public void rulesCanBeClearedAndListIsReadOnly() {
        SqlWallConfig cfg = SqlWallConfig.defaults().rules(NON_SELECT);
        cfg.rules();
        try {
            cfg.rules().add(NON_SELECT);
            fail("rules list must be read-only");
        } catch (UnsupportedOperationException expected) {
            // 预期：注册表只读
        }
        cfg.rules((SqlWallRule[]) null);
        assertTrue(cfg.rules().isEmpty());
        assertTrue(SQL.wall("INSERT INTO t VALUES (1)", SqlDialect.MYSQL, cfg).passed());
    }

    @Test
    public void statementOverloadAlsoAppliesCustomRules() {
        SqlWallConfig cfg = SqlWallConfig.defaults().rules(NON_SELECT);
        SqlWallResult r = SqlWall.check(SQL.parse("INSERT INTO t VALUES (1)"), cfg);
        assertTrue(r.violations().toString(), r.violations().contains("non-select"));
    }

    private static int count(List<String> list, String code) {
        int n = 0;
        for (String s : list) {
            if (code.equals(s)) {
                n++;
            }
        }
        return n;
    }
}
