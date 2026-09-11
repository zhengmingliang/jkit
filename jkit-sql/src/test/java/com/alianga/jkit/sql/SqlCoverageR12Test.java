package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlMerge;
import com.alianga.jkit.sql.ast.SqlMergeWhen;
import com.alianga.jkit.sql.ast.SqlModelClause;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlWithItem;
import org.junit.Assert;
import org.junit.Test;

/**
 * R12：MODEL / MERGE / PIVOT / PARTITION BY / SEARCH·CYCLE / INSERT WHEN 等覆盖。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SqlCoverageR12Test {

    @Test
    public void modelRulesSequentialOrderAndLiteralMeasureAlias() {
        String sql = "select country, prod, year, s from sales_view_ref "
                + "model partition by (country) dimension by (prod, year) "
                + "measures (sale s) ignore nav unique dimension "
                + "rules upsert sequential order ("
                + "s[prod='mouse pad', year=2001] = s['mouse pad', 1999] + s['mouse pad', 2000]"
                + ") order by country, prod, year";
        SqlSelect select = (SqlSelect) SQL.parse(sql, SqlDialect.ORACLE);
        Assert.assertNotNull(select.modelClause());
        SqlModelClause model = select.modelClause();
        Assert.assertTrue(model.rulesModifiers() != null
                && model.rulesModifiers().toUpperCase().contains("ORDER"));
        Assert.assertFalse(model.measures().isEmpty());
    }

    @Test
    public void modelMeasuresLiteralAsAlias() {
        String sql = "select key, num_val, m_1 from t "
                + "model dimension by (key) measures (num_val, 0 as m_1) "
                + "rules (m_1[1] = num_val[1] * 10) order by key";
        SqlSelect select = (SqlSelect) SQL.parse(sql, SqlDialect.ORACLE);
        Assert.assertNotNull(select.modelClause());
        Assert.assertEquals(2, select.modelClause().measures().size());
    }

    @Test
    public void modelAfterWhere() {
        String sql = "select key, dummy from t where key = 1 "
                + "model dimension by (key) measures ((select dummy from dual) as dummy) "
                + "rules ()";
        SqlSelect select = (SqlSelect) SQL.parse(sql, SqlDialect.ORACLE);
        Assert.assertNotNull(select.where());
        Assert.assertNotNull(select.modelClause());
    }

    @Test
    public void mergeUpdateDeleteWhereAndInsertWhere() {
        String sql = "merge into bonuses d using (select employee_id from employees) s "
                + "on (d.employee_id = s.employee_id) "
                + "when matched then update set d.bonus = s.salary "
                + "delete where (s.salary > 8000) "
                + "when not matched then insert (d.employee_id, d.bonus) "
                + "values (s.employee_id, s.salary) where (s.salary <= 8000)";
        SqlMerge merge = (SqlMerge) SQL.parse(sql, SqlDialect.ORACLE);
        Assert.assertEquals(2, merge.whens().size());
        SqlMergeWhen matched = merge.whens().get(0);
        Assert.assertNotNull(matched.update());
        Assert.assertTrue(matched.delete());
        Assert.assertNotNull(matched.deleteWhere());
        SqlMergeWhen notMatched = merge.whens().get(1);
        Assert.assertNotNull(notMatched.insert());
        Assert.assertNotNull(notMatched.insertWhere());
    }

    @Test
    public void pivotAfterParenthesizedSubquery() {
        String sql = "select value from (("
                + "select 'a' v1, 'e' v2 from dual"
                + ") unpivot (value for value_type in (v1, v2)))";
        SqlStatement stmt = SQL.parse(sql, SqlDialect.ORACLE);
        Assert.assertTrue(stmt instanceof SqlSelect);
        String out = SQL.format(stmt);
        Assert.assertTrue(out.toUpperCase().contains("UNPIVOT"));
    }

    @Test
    public void oraclePartitionByOuterJoin() {
        String sql = "select times.time_id, product, quantity from inventory "
                + "partition by (product) "
                + "right outer join times on (times.time_id = inventory.time_id)";
        SqlSelect select = (SqlSelect) SQL.parse(sql, SqlDialect.ORACLE);
        Assert.assertNotNull(select.from());
        // 左表应带 PARTITION BY
        Assert.assertTrue(SQL.format(select).toUpperCase().contains("PARTITION BY"));
    }

    @Test
    public void insertWhenWithoutAllFirst() {
        String sql = "insert when mod(object_id, 2) = 1 then "
                + "into t1 (x, y) values (s.nextval, object_id) "
                + "when mod(object_id, 2) = 0 then "
                + "into t2 (x, y) values (s.nextval, created) "
                + "select object_id, created from all_objects";
        SqlInsert insert = (SqlInsert) SQL.parse(sql, SqlDialect.ORACLE);
        Assert.assertEquals(2, insert.branches().size());
        Assert.assertNotNull(insert.query());
    }

    @Test
    public void groupingSetsCommaGroupingSets() {
        String sql = "select fact_1_id, fact_2_id, sum(sales_value) "
                + "from dimension_tab "
                + "group by grouping sets(fact_1_id, fact_2_id), grouping sets(fact_3_id, fact_4_id)";
        SqlSelect select = (SqlSelect) SQL.parse(sql, SqlDialect.ORACLE);
        Assert.assertNotNull(select.groupByExtension());
        Assert.assertTrue(select.groupByExtension().toUpperCase().contains("GROUPING SETS"));
        Assert.assertTrue(select.groupByExtension().contains(","));
    }

    @Test
    public void withSearchDepthFirstAndCycle() {
        String sql = "with emp_count (eid, emp_last, mgr_id) as ("
                + "select employee_id, last_name, manager_id from employees "
                + "union all "
                + "select e.employee_id, e.last_name, e.manager_id from emp_count r, employees e "
                + "where e.employee_id = r.mgr_id) "
                + "search depth first by emp_last set order1 "
                + "cycle eid set is_cycle to 'y' default 'n' "
                + "select emp_last, eid from emp_count order by order1";
        SqlSelect select = (SqlSelect) SQL.parse(sql, SqlDialect.ORACLE);
        Assert.assertNotNull(select.withItems());
        Assert.assertFalse(select.withItems().isEmpty());
        SqlWithItem item = select.withItems().get(0);
        Assert.assertNotNull(item.searchClause());
        Assert.assertTrue(item.searchClause().toUpperCase().contains("SEARCH"));
        Assert.assertNotNull(item.cycleClause());
        Assert.assertTrue(item.cycleClause().toUpperCase().contains("CYCLE"));
    }

    @Test
    public void tableSubqueryAndIntervalQualifier() {
        SqlStatement t = SQL.parse(
                "select title from table(select courses from department where name = 'history')",
                SqlDialect.ORACLE);
        Assert.assertTrue(t instanceof SqlSelect);
        SqlStatement iv = SQL.parse(
                "select (systimestamp - order_date) day(9) to second from orders where order_id = 1",
                SqlDialect.ORACLE);
        Assert.assertTrue(iv instanceof SqlSelect);
    }

    @Test
    public void oracleFloatSuffixLiterals() {
        SqlSelect select = (SqlSelect) SQL.parse(
                "select 25f, 0.5d, 1.D, .5M, 1.DM from dual", SqlDialect.ORACLE);
        Assert.assertEquals(5, select.selectItems().size());
    }

    @Test
    public void insertValuesRecordAndInUnionAndIsOf() {
        Assert.assertTrue(SQL.parse("insert into t values trec", SqlDialect.ORACLE) instanceof SqlInsert);
        SqlStatement s = SQL.parse(
                "select 1 from dual where id in ((select 1 from dual) union (select 2 from dual))",
                SqlDialect.ORACLE);
        Assert.assertTrue(s instanceof SqlSelect);
        s = SQL.parse("select * from t where value(p) is of type (only employee_t)", SqlDialect.ORACLE);
        Assert.assertTrue(s instanceof SqlSelect);
        s = SQL.parse("update t set a = 1 where current of c1", SqlDialect.ORACLE);
        Assert.assertNotNull(s);
    }

    @Test
    public void sampleAliasAfterSeedAndXmlExtractMethod() {
        SqlSelect select = (SqlSelect) SQL.parse(
                "select 1 as c1 from dual sample block (10, 1) seed (1) o", SqlDialect.ORACLE);
        Assert.assertNotNull(select.from());
        select = (SqlSelect) SQL.parse(
                "select extract(value(t), '/x/y').getclobval() from dual", SqlDialect.ORACLE);
        Assert.assertEquals(1, select.selectItems().size());
    }

    @Test
    public void spacedOpWithBlockCommentAndParenUnionSelectItem() {
        SQL.parse("select * from dual where 1 < > 2 and 1 ! = 2 and 1 ^ /*aaa*/ = 2", SqlDialect.ORACLE);
        SqlSelect select = (SqlSelect) SQL.parse(
                "select ((select 'y' from dual) union (select 'n' from dual)) as yes_no from dual",
                SqlDialect.ORACLE);
        Assert.assertEquals(1, select.selectItems().size());
    }
}
