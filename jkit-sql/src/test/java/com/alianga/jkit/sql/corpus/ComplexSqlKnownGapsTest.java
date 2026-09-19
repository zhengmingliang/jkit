package com.alianga.jkit.sql.corpus;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 复杂 SQL 语料上已关闭的缺口：防止回退。尚未支持的能力登记在方法注释里，禁止静默 skip。
 *
 * <p>仍开放（不阻塞 L1/L2/L3 parse）：{@code DATE_TRUNC}/{@code DATEFROMPARTS} 双向、
 * {@code PERIOD_DIFF}/{@code MONTHS_BETWEEN}、{@code STDDEV_SAMP}↔{@code STDEV}、
 * Oracle 递归 CTE 列清单生成。这些不影响「转换后再 parse」，但会影响真库语义。</p>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class ComplexSqlKnownGapsTest {

    /**
     * {@code DATE(col)} 不得回写成无括号类型字面量。
     */
    @Test
    public void dateFunctionKeepsParens() {
        String out = SQL.toSqlString(SQL.parse("SELECT DATE(o.order_date) FROM orders o",
                SqlDialect.MYSQL), SqlDialect.MYSQL);
        assertTrue(out, out.toUpperCase().contains("DATE("));
        assertFalse(out, out.toUpperCase().contains("DATE O.ORDER_DATE"));
    }

    /**
     * SQL Server {@code GETDATE()} 转到 MySQL 为 {@code NOW()}。
     */
    @Test
    public void getDateToMysql() {
        String mysql = SQL.convert("SELECT GETDATE()", SqlDialect.SQLSERVER, SqlDialect.MYSQL);
        assertFalse(mysql, mysql.toUpperCase().contains("GETDATE"));
        assertTrue(mysql, mysql.toUpperCase().contains("NOW"));
    }
}
