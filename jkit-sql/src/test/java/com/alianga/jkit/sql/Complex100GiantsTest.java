package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlMatchRecognize;
import com.alianga.jkit.sql.ast.SqlModelClause;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlTable;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * complex100 剩余巨兽：ClickHouse windowFunnel、Oracle MODEL/MATCH_RECOGNIZE/AS OF、
 * SQL Server FOR SYSTEM_TIME。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class Complex100GiantsTest {

    @Test
    public void clickHouseWindowFunnelParametric() {
        String sql = "SELECT windowFunnel(86400)("
                + "toDateTime(event_time), "
                + "event_name = 'view_product', "
                + "event_name = 'pay_success') AS funnel_step FROM events_log";
        SqlSelect select = (SqlSelect) SQL.parse(sql, SqlDialect.MYSQL);
        SqlSelectItem item = select.selectItems().get(0);
        assertTrue(item.expr() instanceof SqlFunctionExpr);
        SqlFunctionExpr fn = (SqlFunctionExpr) item.expr();
        assertEquals("windowFunnel", fn.name().simpleName());
        assertTrue(fn.hasParameters());
        assertEquals(1, fn.parameters().size());
        assertEquals(3, fn.arguments().size());
    }

    @Test
    public void oracleModelClause() {
        String sql = "SELECT product, y2022, y2023, y2024 FROM sales_view "
                + "MODEL RETURN UPDATED ROWS "
                + "DIMENSION BY (product) "
                + "MEASURES (y2022,y2023,y2024) "
                + "RULES (y2024[ANY] = y2023[CV()] * 1.1)";
        SqlSelect select = (SqlSelect) SQL.parse(sql, SqlDialect.ORACLE);
        SqlModelClause model = select.modelClause();
        assertNotNull(model);
        assertNotNull(model.raw());
        assertTrue(model.raw().toUpperCase().startsWith("MODEL"));
        assertNotNull(model.options());
        assertTrue(model.options().toUpperCase().contains("RETURN"));
        assertEquals(1, model.dimensionBy().size());
        assertEquals(3, model.measures().size());
        assertNotNull(model.rules());
        assertTrue(model.rules().toUpperCase().contains("Y2024"));
        String formatted = SQL.toSqlString(select, SqlDialect.ORACLE);
        assertTrue(formatted.toUpperCase().contains("MODEL"));
        assertTrue(formatted.toUpperCase().contains("DIMENSION"));
    }

    @Test
    public void oracleMatchRecognize() {
        String sql = "SELECT * FROM orders MATCH_RECOGNIZE ( "
                + "PARTITION BY customer_id ORDER BY order_date "
                + "MEASURES A.order_date start_dt, B.order_date end_dt "
                + "ONE ROW PER MATCH "
                + "PATTERN (A B{2,}) "
                + "DEFINE A AS A.total_amount>0, B AS B.total_amount>0 )";
        SqlSelect select = (SqlSelect) SQL.parse(sql, SqlDialect.ORACLE);
        SqlTable table = (SqlTable) select.from();
        SqlMatchRecognize mr = table.matchRecognize();
        assertNotNull(mr);
        assertEquals(1, mr.partitionBy().size());
        assertEquals(1, mr.orderBy().size());
        assertEquals(2, mr.measures().size());
        assertEquals("start_dt", mr.measures().get(0).name());
        assertEquals("end_dt", mr.measures().get(1).name());
        assertNotNull(mr.rowsPerMatch());
        assertTrue(mr.rowsPerMatch().toUpperCase().contains("ONE ROW"));
        assertNotNull(mr.pattern());
        assertTrue(mr.pattern().toUpperCase().contains("B"));
        assertTrue(mr.pattern().contains("{"));
        assertEquals(2, mr.define().size());
        assertEquals("A", mr.define().get(0).name());
        assertEquals("B", mr.define().get(1).name());
        assertTrue(mr.define().get(0).nameFirst());
        String formatted = SQL.toSqlString(select, SqlDialect.ORACLE);
        assertTrue(formatted.toUpperCase().contains("MATCH_RECOGNIZE"));
        assertTrue(formatted.toUpperCase().contains("PARTITION BY"));
        assertTrue(formatted.toUpperCase().contains("DEFINE"));
    }

    @Test
    public void oracleAsOfTimestamp() {
        String sql = "SELECT * FROM orders AS OF TIMESTAMP (SYSTIMESTAMP - INTERVAL '1' HOUR) "
                + "WHERE customer_id=1001";
        SqlSelect select = (SqlSelect) SQL.parse(sql, SqlDialect.ORACLE);
        SqlTable table = (SqlTable) select.from();
        assertNotNull(table.temporalClause());
        assertTrue(table.temporalClause().toUpperCase().contains("AS OF"));
        assertNotNull(select.where());
    }

    @Test
    public void sqlServerForSystemTimeAsOf() {
        String sql = "SELECT * FROM products FOR SYSTEM_TIME AS OF '2024-01-01' WHERE product_id=1";
        SqlSelect select = (SqlSelect) SQL.parse(sql, SqlDialect.SQLSERVER);
        SqlTable table = (SqlTable) select.from();
        assertNotNull(table.temporalClause());
        assertTrue(table.temporalClause().toUpperCase().contains("SYSTEM_TIME"));
        assertNotNull(select.where());
    }
}
