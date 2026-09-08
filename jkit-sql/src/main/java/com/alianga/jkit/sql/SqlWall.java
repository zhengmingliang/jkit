package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlNode;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.visitor.SqlVisitorAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * WallFilter 子集：多语句、注释绕过、永远真条件、SLEEP、无 WHERE 的 DELETE/UPDATE。
 *
 * <p>默认不接入解析路径，仅显式 {@link SQL#wall(String)} 调用。</p>
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlWall {
    private SqlWall() {
    }

    /**
     * @param sql SQL
     * @param dialect 方言
     * @return 检测结果
     */
    public static SqlWallResult check(String sql, SqlDialect dialect) {
        List<String> violations = new ArrayList<String>(4);
        if (sql == null || sql.trim().isEmpty()) {
            return new SqlWallResult(violations);
        }
        checkCommentBypass(sql, violations);
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        List<SqlStatement> all;
        try {
            all = SQL.parseAll(sql, d);
        } catch (SqlParseException ex) {
            violations.add("parse-error");
            return new SqlWallResult(violations);
        }
        if (all.size() > 1) {
            violations.add("multi-statement");
        }
        for (int i = 0; i < all.size(); i++) {
            checkStatement(all.get(i), violations);
        }
        return new SqlWallResult(violations);
    }

    /**
     * 对已解析的单条语句做 AST 侧检查（不含多语句 / 注释绕过，那些依赖原文）。
     *
     * @param statement 语句
     * @return 检测结果
     */
    public static SqlWallResult check(SqlStatement statement) {
        List<String> violations = new ArrayList<String>(4);
        if (statement != null) {
            checkStatement(statement, violations);
        }
        return new SqlWallResult(violations);
    }

    private static void checkStatement(SqlStatement statement, List<String> violations) {
        if (statement instanceof SqlDelete) {
            if (((SqlDelete) statement).where() == null) {
                add(violations, "delete-without-where");
            } else {
                checkAlwaysTrue(((SqlDelete) statement).where(), violations);
            }
        } else if (statement instanceof SqlUpdate) {
            if (((SqlUpdate) statement).where() == null) {
                add(violations, "update-without-where");
            } else {
                checkAlwaysTrue(((SqlUpdate) statement).where(), violations);
            }
        } else if (statement instanceof SqlSelect) {
            checkAlwaysTrue(((SqlSelect) statement).where(), violations);
        }
        statement.accept(new SqlVisitorAdapter() {
            @Override
            public boolean visit(SqlNode node) {
                if (node instanceof SqlFunctionExpr) {
                    SqlFunctionExpr fn = (SqlFunctionExpr) node;
                    if (fn.name() != null && "SLEEP".equalsIgnoreCase(fn.name().simpleName())) {
                        add(violations, "sleep-function");
                    }
                }
                if (node instanceof SqlBinaryExpr) {
                    SqlBinaryExpr bin = (SqlBinaryExpr) node;
                    if (bin.operator() == SqlBinaryOp.OR) {
                        if (SqlEval.isAlwaysTrue(bin.left()) || SqlEval.isAlwaysTrue(bin.right())) {
                            add(violations, "always-true-condition");
                        }
                    }
                }
                return true;
            }
        });
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
