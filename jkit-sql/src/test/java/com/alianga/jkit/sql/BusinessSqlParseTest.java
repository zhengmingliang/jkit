package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 针对业务复杂 SQL 的解析测试。
 *
 * <p>数据源：{@code jkit-sql/src/test/resources/sqls/1.md}（10 个跨方言业务 SQL，
 * 覆盖 MySQL / Oracle / PostgreSQL / 达梦）；{@code 1.1.md} 为对应初始化 SQL，
 * 可用于在本地数据库实跑验证。</p>
 *
 * <p>本类仅做纯解析层面的单元测试与回归测试（无需真实数据库连接）。每个 SQL 单独一个
 * 方法用于定位问题；{@link #regressionAllBusinessSqls()} 一次性回归全部，防止回退。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class BusinessSqlParseTest {

    /** 1. MySQL：窗口函数 7 天移动平均（含投影别名，验证 parseSimpleSelect2 修复）。 */
    @Test
    public void mysqlMovingAverage7Day() {
        String sql = "SELECT\n"
                + "    date,\n"
                + "    sales_amount,\n"
                + "    AVG(sales_amount) OVER (ORDER BY date ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS moving_average_7day\n"
                + "FROM sales";
        SqlStatement stmt = SQL.parse(sql, SqlDialect.MYSQL);
        assertEquals(SqlStatementType.SELECT, stmt.type());
        assertTrue(stmt.isReadOnly());
        List<String> tables = SQL.tables(stmt);
        assertEquals(1, tables.size());
        assertEquals("sales", tables.get(0));
        SqlSchemaStat stat = SQL.stat(stmt);
        // 投影别名应纳入对外暴露的输出列
        assertTrue(stat.getColumns().contains("sales_amount"));
        assertTrue(stat.getColumns().contains("moving_average_7day"));
        roundTrip(stmt, SqlDialect.MYSQL);
    }

    /** 2. Oracle：CONNECT BY 递归查询员工层次结构。 */
    @Test
    public void oracleConnectByHierarchy() {
        String sql = "SELECT employee_id, first_name, manager_id, LEVEL as hierarchy_level\n"
                + "FROM employees\n"
                + "START WITH manager_id IS NULL\n"
                + "CONNECT BY PRIOR employee_id = manager_id";
        SqlStatement stmt = SQL.parse(sql, SqlDialect.ORACLE);
        assertEquals(SqlStatementType.SELECT, stmt.type());
        assertEquals(1, SQL.tables(stmt).size());
        assertEquals("employees", SQL.tables(stmt).get(0));
        String formatted = SQL.toSqlString(stmt, SqlDialect.ORACLE);
        assertTrue(formatted, formatted.contains("CONNECT BY"));
        assertTrue(formatted, formatted.contains("START WITH"));
        assertTrue(formatted, formatted.contains("PRIOR"));
        roundTrip(stmt, SqlDialect.ORACLE);
    }

    /** 3. PostgreSQL：CTE + RANK() 窗口函数为产品销售排名。 */
    @Test
    public void pgCteRankTop3() {
        String sql = "WITH ranked_sales AS (\n"
                + "    SELECT product_id, sale_date, amount,\n"
                + "           RANK() OVER (PARTITION BY product_id ORDER BY amount DESC) as rank\n"
                + "    FROM sales\n"
                + ")\n"
                + "SELECT * FROM ranked_sales WHERE rank <= 3";
        SqlStatement stmt = SQL.parse(sql, SqlDialect.H2);
        assertEquals(SqlStatementType.SELECT, stmt.type());
        // CTE 不是物理表，只应统计 sales
        assertEquals(1, SQL.tables(stmt).size());
        assertEquals("sales", SQL.tables(stmt).get(0));
        String formatted = SQL.toSqlString(stmt, SqlDialect.H2);
        assertTrue(formatted, formatted.contains("WITH ranked_sales"));
        assertTrue(formatted, formatted.contains("RANK()"));
        assertTrue(formatted, formatted.contains("OVER"));
        roundTrip(stmt, SqlDialect.H2);
    }

    /** 4. 达梦：SUM() OVER 计算订单累计金额。 */
    @Test
    public void dmCumulativeSum() {
        String sql = "SELECT order_id, order_date, amount,\n"
                + "       SUM(amount) OVER (ORDER BY order_date) AS cumulative_sum\n"
                + "FROM orders";
        SqlStatement stmt = SQL.parse(sql, SqlDialect.DAMENG);
        assertEquals(SqlStatementType.SELECT, stmt.type());
        assertEquals("orders", SQL.tables(stmt).get(0));
        SqlSchemaStat stat = SQL.stat(stmt);
        assertTrue(stat.getColumns().contains("cumulative_sum"));
        assertTrue(stat.getColumns().contains("amount"));
        roundTrip(stmt, SqlDialect.DAMENG);
    }

    /** 5. MySQL：LEFT JOIN + GROUP BY + HAVING 聚合分析客户消费。 */
    @Test
    public void mysqlJoinGroupByHaving() {
        String sql = "SELECT c.customer_id, c.name, COUNT(o.order_id) AS order_count, SUM(o.total_amount) AS total_spent\n"
                + "FROM customers c\n"
                + "LEFT JOIN orders o ON c.customer_id = o.customer_id\n"
                + "GROUP BY c.customer_id, c.name\n"
                + "HAVING total_spent > 1000";
        SqlStatement stmt = SQL.parse(sql, SqlDialect.MYSQL);
        assertEquals(SqlStatementType.SELECT, stmt.type());
        assertTrue(SQL.tables(stmt).contains("customers"));
        assertTrue(SQL.tables(stmt).contains("orders"));
        String formatted = SQL.toSqlString(stmt, SqlDialect.MYSQL);
        assertTrue(formatted, formatted.contains("LEFT JOIN"));
        assertTrue(formatted, formatted.contains("GROUP BY"));
        assertTrue(formatted, formatted.contains("HAVING"));
        roundTrip(stmt, SqlDialect.MYSQL);
    }

    /** 6. Oracle：AVG() OVER PARTITION BY 计算与部门均值的差额。 */
    @Test
    public void oracleAvgPartitionDiff() {
        String sql = "SELECT department_id, employee_id, salary,\n"
                + "       salary - AVG(salary) OVER (PARTITION BY department_id) AS diff_from_dept_avg\n"
                + "FROM employees";
        SqlStatement stmt = SQL.parse(sql, SqlDialect.ORACLE);
        assertEquals(SqlStatementType.SELECT, stmt.type());
        assertEquals("employees", SQL.tables(stmt).get(0));
        SqlSchemaStat stat = SQL.stat(stmt);
        assertTrue(stat.getColumns().contains("diff_from_dept_avg"));
        assertTrue(stat.getColumns().contains("salary"));
        roundTrip(stmt, SqlDialect.ORACLE);
    }

    /** 7. PostgreSQL：WITH RECURSIVE 递归 CTE 查询产品类别树。 */
    @Test
    public void pgRecursiveCteCategoryTree() {
        String sql = "WITH RECURSIVE category_tree AS (\n"
                + "    SELECT id, name, parent_id FROM categories WHERE parent_id IS NULL\n"
                + "    UNION ALL\n"
                + "    SELECT c.id, c.name, c.parent_id FROM categories c\n"
                + "    JOIN category_tree ct ON c.parent_id = ct.id\n"
                + ")\n"
                + "SELECT * FROM category_tree";
        SqlStatement stmt = SQL.parse(sql, SqlDialect.H2);
        assertEquals(SqlStatementType.SELECT, stmt.type());
        assertEquals("categories", SQL.tables(stmt).get(0));
        String formatted = SQL.toSqlString(stmt, SqlDialect.H2);
        assertTrue(formatted, formatted.contains("WITH RECURSIVE"));
        assertTrue(formatted, formatted.contains("UNION ALL"));
        roundTrip(stmt, SqlDialect.H2);
    }

    /** 8. 达梦：CASE 模拟 PIVOT 行转列聚合销售数据。 */
    @Test
    public void dmCasePivot() {
        String sql = "SELECT product_id,\n"
                + "       SUM(CASE WHEN month = 'Jan' THEN sales ELSE 0 END) AS jan_sales,\n"
                + "       SUM(CASE WHEN month = 'Feb' THEN sales ELSE 0 END) AS feb_sales,\n"
                + "       SUM(CASE WHEN month = 'Mar' THEN sales ELSE 0 END) AS mar_sales\n"
                + "FROM monthly_sales\n"
                + "GROUP BY product_id";
        SqlStatement stmt = SQL.parse(sql, SqlDialect.DAMENG);
        assertEquals(SqlStatementType.SELECT, stmt.type());
        assertEquals("monthly_sales", SQL.tables(stmt).get(0));
        String formatted = SQL.toSqlString(stmt, SqlDialect.DAMENG);
        assertTrue(formatted, formatted.contains("CASE WHEN"));
        assertTrue(formatted, formatted.contains("GROUP BY"));
        roundTrip(stmt, SqlDialect.DAMENG);
    }

    /** 9. MySQL：CTE + 标量子查询找出异常销售记录。 */
    @Test
    public void mysqlCteWithScalarSubquery() {
        String sql = "WITH daily_sales AS (\n"
                + "    SELECT date, product_id, SUM(amount) as daily_amount\n"
                + "    FROM sales\n"
                + "    GROUP BY date, product_id\n"
                + ")\n"
                + "SELECT date, product_id, daily_amount\n"
                + "FROM daily_sales\n"
                + "WHERE daily_amount > (SELECT AVG(daily_amount) * 2 FROM daily_sales)";
        SqlStatement stmt = SQL.parse(sql, SqlDialect.MYSQL);
        assertEquals(SqlStatementType.SELECT, stmt.type());
        assertEquals("sales", SQL.tables(stmt).get(0));
        String formatted = SQL.toSqlString(stmt, SqlDialect.MYSQL);
        assertTrue(formatted, formatted.contains("WITH daily_sales"));
        assertTrue(formatted, formatted.contains("AVG(daily_amount)"));
        roundTrip(stmt, SqlDialect.MYSQL);
    }

    /** 10. Oracle：MERGE 语句 upsert（更新或插入）。 */
    @Test
    public void oracleMergeUpsert() {
        String sql = "MERGE INTO target_table t\n"
                + "USING source_table s ON (t.id = s.id)\n"
                + "WHEN MATCHED THEN\n"
                + "    UPDATE SET t.value = s.value, t.update_date = SYSDATE\n"
                + "WHEN NOT MATCHED THEN\n"
                + "    INSERT (id, value, create_date) VALUES (s.id, s.value, SYSDATE)";
        SqlStatement stmt = SQL.parse(sql, SqlDialect.ORACLE);
        assertEquals(SqlStatementType.MERGE, stmt.type());
        assertTrue(SQL.tables(stmt).contains("target_table"));
        assertTrue(SQL.tables(stmt).contains("source_table"));
        String formatted = SQL.toSqlString(stmt, SqlDialect.ORACLE);
        assertTrue(formatted, formatted.contains("MERGE INTO"));
        assertTrue(formatted, formatted.contains("WHEN MATCHED"));
        assertTrue(formatted, formatted.contains("WHEN NOT MATCHED"));
        roundTrip(stmt, SqlDialect.ORACLE);
    }

    /**
     * 回归：一次性解析 1.md 全部 10 条 SQL，并验证每条都能「格式化后再次解析」，
     * 防止语法/格式化回退。同时校验表名抽取与语句类型符合预期。
     */
    @Test
    public void regressionAllBusinessSqls() {
        List<Case> cases = allCases();
        for (Case c : cases) {
            // 初次解析
            SqlStatement stmt = SQL.parse(c.sql, c.dialect);
            assertNotNull(c.name, stmt);
            assertEquals(c.name + " type", c.type, stmt.type());
            // 表名抽取（不含 CTE 名）
            for (String t : c.expectedTables) {
                assertTrue(c.name + " should reference table " + t, SQL.tables(stmt).contains(t));
            }
            // 往返：格式化结果必须能被同方言再次解析
            String formatted = SQL.toSqlString(stmt, c.dialect);
            SqlStatement reparsed = SQL.parse(formatted, c.dialect);
            assertEquals(c.name + " round-trip type", c.type, reparsed.type());
            assertEquals(c.name + " round-trip table count",
                    SQL.tables(stmt).size(), SQL.tables(reparsed).size());
        }
    }

    /** 格式化后再次解析，断言不抛异常且类型一致。 */
    private static void roundTrip(SqlStatement stmt, SqlDialect dialect) {
        String formatted = SQL.toSqlString(stmt, dialect);
        SqlStatement reparsed = SQL.parse(formatted, dialect);
        assertNotNull(reparsed);
        assertEquals(stmt.type(), reparsed.type());
    }

    private static final class Case {
        final String name;
        final String sql;
        final SqlDialect dialect;
        final SqlStatementType type;
        final List<String> expectedTables;

        Case(String name, String sql, SqlDialect dialect, SqlStatementType type, String... tables) {
            this.name = name;
            this.sql = sql;
            this.dialect = dialect;
            this.type = type;
            this.expectedTables = Arrays.asList(tables);
        }
    }

    private static List<Case> allCases() {
        return Arrays.asList(
                new Case("1-mysql-window",
                        "SELECT date, sales_amount,\n"
                                + "  AVG(sales_amount) OVER (ORDER BY date ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS moving_average_7day\n"
                                + "FROM sales",
                        SqlDialect.MYSQL, SqlStatementType.SELECT, "sales"),
                new Case("2-oracle-connectby",
                        "SELECT employee_id, first_name, manager_id, LEVEL as hierarchy_level\n"
                                + "FROM employees\n"
                                + "START WITH manager_id IS NULL\n"
                                + "CONNECT BY PRIOR employee_id = manager_id",
                        SqlDialect.ORACLE, SqlStatementType.SELECT, "employees"),
                new Case("3-pg-cte-rank",
                        "WITH ranked_sales AS (\n"
                                + "  SELECT product_id, sale_date, amount,\n"
                                + "    RANK() OVER (PARTITION BY product_id ORDER BY amount DESC) as rank\n"
                                + "  FROM sales)\n"
                                + "SELECT * FROM ranked_sales WHERE rank <= 3",
                        SqlDialect.H2, SqlStatementType.SELECT, "sales"),
                new Case("4-dm-cumsum",
                        "SELECT order_id, order_date, amount,\n"
                                + "  SUM(amount) OVER (ORDER BY order_date) AS cumulative_sum\n"
                                + "FROM orders",
                        SqlDialect.DAMENG, SqlStatementType.SELECT, "orders"),
                new Case("5-mysql-join-having",
                        "SELECT c.customer_id, c.name, COUNT(o.order_id) AS order_count, SUM(o.total_amount) AS total_spent\n"
                                + "FROM customers c\n"
                                + "LEFT JOIN orders o ON c.customer_id = o.customer_id\n"
                                + "GROUP BY c.customer_id, c.name\n"
                                + "HAVING total_spent > 1000",
                        SqlDialect.MYSQL, SqlStatementType.SELECT, "customers", "orders"),
                new Case("6-oracle-avg-partition",
                        "SELECT department_id, employee_id, salary,\n"
                                + "  salary - AVG(salary) OVER (PARTITION BY department_id) AS diff_from_dept_avg\n"
                                + "FROM employees",
                        SqlDialect.ORACLE, SqlStatementType.SELECT, "employees"),
                new Case("7-pg-recursive-cte",
                        "WITH RECURSIVE category_tree AS (\n"
                                + "  SELECT id, name, parent_id FROM categories WHERE parent_id IS NULL\n"
                                + "  UNION ALL\n"
                                + "  SELECT c.id, c.name, c.parent_id FROM categories c\n"
                                + "  JOIN category_tree ct ON c.parent_id = ct.id)\n"
                                + "SELECT * FROM category_tree",
                        SqlDialect.H2, SqlStatementType.SELECT, "categories"),
                new Case("8-dm-case-pivot",
                        "SELECT product_id,\n"
                                + "  SUM(CASE WHEN month = 'Jan' THEN sales ELSE 0 END) AS jan_sales,\n"
                                + "  SUM(CASE WHEN month = 'Feb' THEN sales ELSE 0 END) AS feb_sales,\n"
                                + "  SUM(CASE WHEN month = 'Mar' THEN sales ELSE 0 END) AS mar_sales\n"
                                + "FROM monthly_sales GROUP BY product_id",
                        SqlDialect.DAMENG, SqlStatementType.SELECT, "monthly_sales"),
                new Case("9-mysql-cte-subquery",
                        "WITH daily_sales AS (\n"
                                + "  SELECT date, product_id, SUM(amount) as daily_amount FROM sales GROUP BY date, product_id)\n"
                                + "SELECT date, product_id, daily_amount FROM daily_sales\n"
                                + "WHERE daily_amount > (SELECT AVG(daily_amount) * 2 FROM daily_sales)",
                        SqlDialect.MYSQL, SqlStatementType.SELECT, "sales"),
                new Case("10-oracle-merge",
                        "MERGE INTO target_table t\n"
                                + "USING source_table s ON (t.id = s.id)\n"
                                + "WHEN MATCHED THEN UPDATE SET t.value = s.value, t.update_date = SYSDATE\n"
                                + "WHEN NOT MATCHED THEN INSERT (id, value, create_date) VALUES (s.id, s.value, SYSDATE)",
                        SqlDialect.ORACLE, SqlStatementType.MERGE, "target_table", "source_table")
        );
    }
}
