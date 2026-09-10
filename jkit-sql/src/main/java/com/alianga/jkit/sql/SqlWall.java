package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlNode;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.visitor.SqlVisitorAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * WallFilter 子集：多语句、注释绕过、永远真条件、危险函数、无 WHERE 的 DELETE/UPDATE、
 * DDL、INTO OUTFILE、可选 UNION / information_schema、selectOnly。
 *
 * <p>默认不接入解析路径，仅显式 {@link SQL#wall(String)} 调用。规则由 {@link SqlWallConfig} 控制。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlWall {
    private SqlWall() {
    }

    /**
     * @param sql SQL
     * @param dialect 方言
     * @return 检测结果（默认配置）
     */
    public static SqlWallResult check(String sql, SqlDialectSpec dialect) {
        return check(sql, dialect, SqlWallConfig.defaults());
    }

    /**
     * @param sql SQL
     * @param dialect 方言
     * @param config 规则；null 视为 {@link SqlWallConfig#defaults()}
     * @return 检测结果
     */
    public static SqlWallResult check(String sql, SqlDialectSpec dialect, SqlWallConfig config) {
        SqlWallConfig cfg = config == null ? SqlWallConfig.defaults() : config;
        List<String> violations = new ArrayList<String>(4);
        if (sql == null || sql.trim().isEmpty()) {
            return new SqlWallResult(violations);
        }
        if (cfg.denyCommentBypass()) {
            checkCommentBypass(sql, violations);
        }
        SqlDialectSpec d = dialect == null ? SqlDialect.MYSQL : dialect;
        List<SqlStatement> all;
        try {
            all = SQL.parseAll(sql, d);
        } catch (SqlParseException ex) {
            violations.add("parse-error");
            return new SqlWallResult(violations);
        }
        if (cfg.denyMultiStatement() && all.size() > 1) {
            violations.add("multi-statement");
        }
        for (int i = 0; i < all.size(); i++) {
            checkStatement(all.get(i), cfg, violations);
        }
        return new SqlWallResult(violations);
    }

    /**
     * 对已解析的单条语句做 AST 侧检查（不含多语句 / 注释绕过，那些依赖原文）。
     *
     * @param statement 语句
     * @return 检测结果（默认配置）
     */
    public static SqlWallResult check(SqlStatement statement) {
        return check(statement, SqlWallConfig.defaults());
    }

    /**
     * @param statement 语句
     * @param config 规则；null 视为 defaults
     * @return 检测结果
     */
    public static SqlWallResult check(SqlStatement statement, SqlWallConfig config) {
        SqlWallConfig cfg = config == null ? SqlWallConfig.defaults() : config;
        List<String> violations = new ArrayList<String>(4);
        if (statement != null) {
            checkStatement(statement, cfg, violations);
        }
        return new SqlWallResult(violations);
    }

    private static void checkStatement(SqlStatement statement, SqlWallConfig cfg, List<String> violations) {
        SqlStatementType type = statement.type();
        if (cfg.selectOnly() && type != SqlStatementType.SELECT) {
            add(violations, "select-only");
        }
        if (cfg.denyDdl() && isDdl(type)) {
            add(violations, "deny-ddl");
        }
        if (statement instanceof SqlDelete) {
            if (cfg.denyDeleteUpdateWithoutWhere() && ((SqlDelete) statement).where() == null) {
                add(violations, "delete-without-where");
            } else if (cfg.denyAlwaysTrue()) {
                checkAlwaysTrue(((SqlDelete) statement).where(), violations);
            }
        } else if (statement instanceof SqlUpdate) {
            if (cfg.denyDeleteUpdateWithoutWhere() && ((SqlUpdate) statement).where() == null) {
                add(violations, "update-without-where");
            } else if (cfg.denyAlwaysTrue()) {
                checkAlwaysTrue(((SqlUpdate) statement).where(), violations);
            }
        } else if (statement instanceof SqlSelect) {
            SqlSelect select = (SqlSelect) statement;
            if (cfg.denyAlwaysTrue()) {
                checkAlwaysTrue(select.where(), violations);
            }
            if (cfg.denyIntoOutfile() && select.intoOutfile() != null) {
                add(violations, "into-outfile");
            }
            if (cfg.denyUnion() && hasUnion(select)) {
                add(violations, "deny-union");
            }
        }
        final boolean checkFn = cfg.denyDangerousFunctions();
        final boolean checkAlways = cfg.denyAlwaysTrue();
        final boolean checkInfo = cfg.denyInformationSchema();
        if (!checkFn && !checkAlways && !checkInfo) {
            return;
        }
        statement.accept(new SqlVisitorAdapter() {
            @Override
            public boolean visit(SqlNode node) {
                if (checkFn && node instanceof SqlFunctionExpr) {
                    SqlFunctionExpr fn = (SqlFunctionExpr) node;
                    if (fn.name() != null && isDangerousFunction(fn.name().simpleName())) {
                        String simple = fn.name().simpleName().toUpperCase(Locale.ROOT);
                        if ("SLEEP".equals(simple)) {
                            add(violations, "sleep-function");
                        }
                        add(violations, "dangerous-function");
                    }
                }
                if (checkAlways && node instanceof SqlBinaryExpr) {
                    SqlBinaryExpr bin = (SqlBinaryExpr) node;
                    if (bin.operator() == SqlBinaryOp.OR) {
                        if (SqlEval.isAlwaysTrue(bin.left()) || SqlEval.isAlwaysTrue(bin.right())) {
                            add(violations, "always-true-condition");
                        }
                    }
                }
                if (checkInfo && node instanceof SqlTable) {
                    SqlTable table = (SqlTable) node;
                    if (isInformationSchema(table.name())) {
                        add(violations, "information-schema");
                    }
                }
                return true;
            }
        });
    }

    private static boolean isDdl(SqlStatementType type) {
        return type == SqlStatementType.CREATE
                || type == SqlStatementType.DROP
                || type == SqlStatementType.ALTER
                || type == SqlStatementType.TRUNCATE;
    }

    private static boolean hasUnion(SqlSelect select) {
        for (SqlSelect cur = select; cur != null; cur = cur.union()) {
            if (cur.union() != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDangerousFunction(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toUpperCase(Locale.ROOT);
        return "SLEEP".equals(n)
                || "BENCHMARK".equals(n)
                || "LOAD_FILE".equals(n);
    }

    private static boolean isInformationSchema(SqlIdentifier name) {
        if (name == null) {
            return false;
        }
        String q = name.qualifiedName();
        if (q == null) {
            return false;
        }
        String lower = q.toLowerCase(Locale.ROOT);
        return lower.startsWith("information_schema.")
                || "information_schema".equals(lower)
                || lower.contains(".information_schema.");
    }

    private static void checkAlwaysTrue(SqlExpr where, List<String> violations) {
        if (where == null) {
            return;
        }
        if (SqlEval.isAlwaysTrue(where)) {
            add(violations, "always-true-condition");
        }
    }

    private static void add(List<String> violations, String code) {
        if (!violations.contains(code)) {
            violations.add(code);
        }
    }

    /**
     * 检测可能用于截断后续条件的行/块注释（在字符串外）。
     */
    static void checkCommentBypass(String sql, List<String> violations) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (inSingle) {
                if (c == '\\' && i + 1 < n) {
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    inSingle = false;
                }
                i++;
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                }
                i++;
                continue;
            }
            if (inBacktick) {
                if (c == '`') {
                    inBacktick = false;
                }
                i++;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                i++;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                i++;
                continue;
            }
            if (c == '`') {
                inBacktick = true;
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                add(violations, "comment-bypass");
                return;
            }
            if (c == '#') {
                add(violations, "comment-bypass");
                return;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                if (i + 2 < n) {
                    char third = sql.charAt(i + 2);
                    if (third == '!' || third == '+') {
                        int end = sql.indexOf("*/", i + 3);
                        i = end < 0 ? n : end + 2;
                        continue;
                    }
                }
                add(violations, "comment-bypass");
                return;
            }
            i++;
        }
    }
}
