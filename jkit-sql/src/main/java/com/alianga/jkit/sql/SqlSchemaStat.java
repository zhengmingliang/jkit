package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlAllColumns;
import com.alianga.jkit.sql.ast.SqlCommentOnStatement;
import com.alianga.jkit.sql.ast.SqlCopyStatement;
import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlExplainStatement;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFlushStatement;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlJoin;
import com.alianga.jkit.sql.ast.SqlLoadDataStatement;
import com.alianga.jkit.sql.ast.SqlLockTablesStatement;
import com.alianga.jkit.sql.ast.SqlMaintenanceStatement;
import com.alianga.jkit.sql.ast.SqlNode;
import com.alianga.jkit.sql.ast.SqlOrderByItem;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlShowStatement;
import com.alianga.jkit.sql.ast.SqlSimpleStatement;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlTableHandlerStatement;
import com.alianga.jkit.sql.visitor.SqlVisitorAdapter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表 / 列统计，对标 Druid {@code SchemaStatVisitor} 的常用输出。
 *
 * <p>P0.3：收集 WHERE / JOIN ON / HAVING 条件原文，以及 ORDER BY / GROUP BY 列；
 * 同一张表可合并多种访问类型（如 INSERT…SELECT 同源表记 {@code INSERT+SELECT}）。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlSchemaStat {
    private final Map<String, SqlTableAccess> tables = new LinkedHashMap<String, SqlTableAccess>();
    private final Set<String> columns = new LinkedHashSet<String>();
    private final List<String> conditions = new ArrayList<String>();
    private final List<String> orderByColumns = new ArrayList<String>();
    private final List<String> groupByColumns = new ArrayList<String>();
    private final SqlFormatter compact = new SqlFormatter(false, SqlDialect.MYSQL);

    /**
     * 从语句收集统计。
     *
     * @param statement 语句
     * @return 统计结果
     */
    public static SqlSchemaStat of(SqlStatement statement) {
        SqlSchemaStat stat = new SqlSchemaStat();
        if (statement != null) {
            statement.accept(stat.new Collector(statement.type()));
        }
        return stat;
    }

    /**
     * @return 表名 → 访问类型（可合并多种，按首次出现顺序）
     */
    public Map<String, SqlTableAccess> getTables() {
        return tables;
    }

    /**
     * @return 表名列表（出现顺序）
     */
    public List<String> tableNames() {
        return new ArrayList<String>(tables.keySet());
    }

    /**
     * @return 列名（含表前缀，若有）
     */
    public Set<String> getColumns() {
        return columns;
    }

    /**
     * @return WHERE / JOIN ON / HAVING 条件的紧凑 SQL 片段
     */
    public List<String> getConditions() {
        return conditions;
    }

    /**
     * @return ORDER BY 列（紧凑 SQL）
     */
    public List<String> getOrderByColumns() {
        return orderByColumns;
    }

    /**
     * @return GROUP BY 列（紧凑 SQL）
     */
    public List<String> getGroupByColumns() {
        return groupByColumns;
    }

    private void addCondition(SqlExpr expr) {
        if (expr == null) {
            return;
        }
        String text = compact.format(expr);
        if (text != null && !text.isEmpty()) {
            conditions.add(text);
        }
    }

    private void addExprList(List<String> target, List<? extends SqlExpr> exprs) {
        if (exprs == null) {
            return;
        }
        for (int i = 0; i < exprs.size(); i++) {
            SqlExpr expr = exprs.get(i);
            if (expr == null) {
                continue;
            }
            String text = compact.format(expr);
            if (text != null && !text.isEmpty()) {
                target.add(text);
            }
        }
    }

    private final class Collector extends SqlVisitorAdapter {
        private final Deque<SqlStatementType> accessStack = new ArrayDeque<SqlStatementType>();

        private Collector(SqlStatementType access) {
            accessStack.push(access);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean visit(SqlNode node) {
            if (node instanceof SqlSelect) {
                accessStack.push(SqlStatementType.SELECT);
                SqlSelect select = (SqlSelect) node;
                if (select.intoTable() != null && select.intoTable().name() != null) {
                    addTable(select.intoTable().name(), SqlStatementType.INSERT);
                }
                addCondition(select.where());
                addCondition(select.having());
                addExprList(groupByColumns, select.groupBy());
                List<SqlOrderByItem> orders = select.orderBy();
                for (int i = 0; i < orders.size(); i++) {
                    SqlOrderByItem item = orders.get(i);
                    if (item != null && item.expr() != null) {
                        String text = compact.format(item.expr());
                        if (text != null && !text.isEmpty()) {
                            orderByColumns.add(text);
                        }
                    }
                }
                return true;
            }
            if (node instanceof SqlJoin) {
                addCondition(((SqlJoin) node).condition());
                return true;
            }
            if (node instanceof SqlTable) {
                addTable(((SqlTable) node).name(), accessStack.peek());
                return false;
            }
            if (node instanceof SqlDdlStatement) {
                SqlDdlStatement ddl = (SqlDdlStatement) node;
                for (int i = 0; i < ddl.names().size(); i++) {
                    addTable(ddl.names().get(i), ddl.type());
                }
                for (int i = 0; i < ddl.referencedTables().size(); i++) {
                    addTable(ddl.referencedTables().get(i), SqlStatementType.SELECT);
                }
                return true;
            }
            if (node instanceof SqlExplainStatement) {
                addTable(((SqlExplainStatement) node).name(), accessStack.peek());
                return true;
            }
            if (node instanceof SqlCommentOnStatement) {
                addTable(((SqlCommentOnStatement) node).name(), accessStack.peek());
                return true;
            }
            if (node instanceof SqlShowStatement) {
                addTable(((SqlShowStatement) node).name(), accessStack.peek());
                return true;
            }
            if (node instanceof SqlSimpleStatement) {
                addTable(((SqlSimpleStatement) node).name(), accessStack.peek());
                return true;
            }
            if (node instanceof SqlTableHandlerStatement) {
                addTable(((SqlTableHandlerStatement) node).table(), accessStack.peek());
                return true;
            }
            if (node instanceof SqlLockTablesStatement) {
                SqlLockTablesStatement lock = (SqlLockTablesStatement) node;
                for (int i = 0; i < lock.items().size(); i++) {
                    SqlLockTablesStatement.LockItem item = lock.items().get(i);
                    if (item != null) {
                        addTable(item.table(), accessStack.peek());
                    }
                }
                return true;
            }
            if (node instanceof SqlLoadDataStatement) {
                addTable(((SqlLoadDataStatement) node).table(), accessStack.peek());
                return true;
            }
            if (node instanceof SqlCopyStatement) {
                addTable(((SqlCopyStatement) node).table(), accessStack.peek());
                return true;
            }
            if (node instanceof SqlFlushStatement) {
                SqlFlushStatement flush = (SqlFlushStatement) node;
                for (int i = 0; i < flush.tables().size(); i++) {
                    addTable(flush.tables().get(i), accessStack.peek());
                }
                return true;
            }
            if (node instanceof SqlMaintenanceStatement) {
                SqlMaintenanceStatement maint = (SqlMaintenanceStatement) node;
                for (int i = 0; i < maint.tables().size(); i++) {
                    addTable(maint.tables().get(i), accessStack.peek());
                }
                return true;
            }
            if (node instanceof SqlFunctionExpr) {
                SqlFunctionExpr fn = (SqlFunctionExpr) node;
                for (int i = 0; i < fn.arguments().size(); i++) {
                    if (fn.arguments().get(i) != null) {
                        fn.arguments().get(i).accept(this);
                    }
                }
                if (fn.over() != null) {
                    fn.over().accept(this);
                }
                return false;
            }
            if (node instanceof SqlAllColumns) {
                SqlAllColumns all = (SqlAllColumns) node;
                if (all.owner() != null) {
                    columns.add(all.owner().qualifiedName() + ".*");
                } else {
                    columns.add("*");
                }
                return false;
            }
            if (node instanceof SqlIdentifier) {
                columns.add(((SqlIdentifier) node).qualifiedName());
            }
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void endVisit(SqlNode node) {
            if (node instanceof SqlSelect) {
                accessStack.pop();
            }
        }
    }

    private void addTable(SqlIdentifier name, SqlStatementType type) {
        if (name == null || type == null) {
            return;
        }
        String q = name.qualifiedName();
        if (q.isEmpty()) {
            return;
        }
        SqlTableAccess access = tables.get(q);
        if (access == null) {
            access = new SqlTableAccess();
            tables.put(q, access);
        }
        access.add(type);
    }
}
