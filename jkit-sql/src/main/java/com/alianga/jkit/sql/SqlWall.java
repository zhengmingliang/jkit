package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlJoin;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlNode;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlTableSource;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.ast.SqlWithItem;
import com.alianga.jkit.sql.visitor.SqlVisitorAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * WallFilter 子集：多语句、注释绕过、永远真条件、危险函数、无 WHERE 的 DELETE/UPDATE、
 * DDL、INTO OUTFILE、可选 UNION / information_schema、selectOnly。
 *
 * <p>默认不接入解析路径，仅显式 {@link SQL#wall(String)} 调用。规则由 {@link SqlWallConfig} 控制；
 * 语句级检查实现为 {@link SqlWallRule} 规则链（内置 6 条 + {@link SqlWallConfig#rules(SqlWallRule...)}
 * 自定义追加），新增检查项只需实现接口，不必改本类。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlWall {
    /** 内置规则链（顺序：语句类型 → DDL → 无 WHERE 写 → SELECT 特性 → AST 扫描 → 表策略）。 */
    private static final List<SqlWallRule> BUILTIN_RULES = Arrays.asList(
            new SelectOnlyRule(),
            new DenyDdlRule(),
            new WriteWithoutWhereRule(),
            new SelectFeatureRule(),
            new AstScanRule(),
            new TablePolicyRule());

    private SqlWall() {
    }

    /**
     * @param sql SQL
     * @param dialect 方言（枚举或 {@link SqlDialectWrapper} 自定义能力）
     * @return 检测结果（默认配置）
     */
    public static SqlWallResult check(String sql, SqlDialectSpec dialect) {
        return check(sql, dialect, SqlWallConfig.defaults());
    }

    /**
     * @param sql SQL
     * @param dialect 方言（枚举或 {@link SqlDialectWrapper} 自定义能力）
     * @param config 规则；null 视为 {@link SqlWallConfig#defaults()}
     * @return 检测结果
     */
    public static SqlWallResult check(String sql, SqlDialectSpec dialect, SqlWallConfig config) {
        SqlWallConfig cfg = config == null ? SqlWallConfig.defaults() : config;
        SqlWallViolations violations = new SqlWallViolations();
        if (sql == null || sql.trim().isEmpty()) {
            return new SqlWallResult(violations.codes());
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
            return new SqlWallResult(violations.codes());
        }
        if (cfg.denyMultiStatement() && all.size() > 1) {
            violations.add("multi-statement");
        }
        for (int i = 0; i < all.size(); i++) {
            checkStatement(all.get(i), cfg, violations);
        }
        return new SqlWallResult(violations.codes());
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
        SqlWallViolations violations = new SqlWallViolations();
        if (statement != null) {
            checkStatement(statement, cfg, violations);
        }
        return new SqlWallResult(violations.codes());
    }

    private static void checkStatement(SqlStatement statement, SqlWallConfig cfg, SqlWallViolations violations) {
        for (int i = 0; i < BUILTIN_RULES.size(); i++) {
            BUILTIN_RULES.get(i).check(statement, cfg, violations);
        }
        List<SqlWallRule> custom = cfg.rules();
        for (int i = 0; i < custom.size(); i++) {
            custom.get(i).check(statement, cfg, violations);
        }
    }

    /** selectOnly：非 SELECT 一律违规。 */
    private static final class SelectOnlyRule implements SqlWallRule {
        @Override
        public void check(SqlStatement statement, SqlWallConfig cfg, SqlWallViolations violations) {
            if (cfg.selectOnly() && statement.type() != SqlStatementType.SELECT) {
                violations.add("select-only");
            }
        }
    }

    /** denyDdl：CREATE / DROP / ALTER / TRUNCATE。 */
    private static final class DenyDdlRule implements SqlWallRule {
        @Override
        public void check(SqlStatement statement, SqlWallConfig cfg, SqlWallViolations violations) {
            if (cfg.denyDdl() && isDdl(statement.type())) {
                violations.add("deny-ddl");
            }
        }
    }

    /** 无 WHERE 的 DELETE/UPDATE + WHERE 永远真。 */
    private static final class WriteWithoutWhereRule implements SqlWallRule {
        @Override
        public void check(SqlStatement statement, SqlWallConfig cfg, SqlWallViolations violations) {
            if (statement instanceof SqlDelete) {
                if (cfg.denyDeleteUpdateWithoutWhere() && ((SqlDelete) statement).where() == null) {
                    violations.add("delete-without-where");
                } else if (cfg.denyAlwaysTrue()) {
                    checkAlwaysTrue(((SqlDelete) statement).where(), violations);
                }
            } else if (statement instanceof SqlUpdate) {
                if (cfg.denyDeleteUpdateWithoutWhere() && ((SqlUpdate) statement).where() == null) {
                    violations.add("update-without-where");
                } else if (cfg.denyAlwaysTrue()) {
                    checkAlwaysTrue(((SqlUpdate) statement).where(), violations);
                }
            }
        }
    }

    /** SELECT 特性：WHERE 永远真、INTO OUTFILE/DUMPFILE、可选 UNION。 */
    private static final class SelectFeatureRule implements SqlWallRule {
        @Override
        public void check(SqlStatement statement, SqlWallConfig cfg, SqlWallViolations violations) {
            if (!(statement instanceof SqlSelect)) {
                return;
            }
            SqlSelect select = (SqlSelect) statement;
            if (cfg.denyAlwaysTrue()) {
                checkAlwaysTrue(select.where(), violations);
            }
            if (cfg.denyIntoOutfile() && select.intoOutfile() != null) {
                violations.add("into-outfile");
            }
            if (cfg.denyUnion() && hasUnion(select)) {
                violations.add("deny-union");
            }
        }
    }

    /** 单次 AST 遍历：危险函数 / OR 永远真 / information_schema。 */
    private static final class AstScanRule implements SqlWallRule {
        @Override
        public void check(SqlStatement statement, SqlWallConfig cfg, final SqlWallViolations violations) {
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
                                violations.add("sleep-function");
                            }
                            violations.add("dangerous-function");
                        }
                    }
                    if (checkAlways && node instanceof SqlBinaryExpr) {
                        SqlBinaryExpr bin = (SqlBinaryExpr) node;
                        if (bin.operator() == SqlBinaryOp.OR || bin.operator() == SqlBinaryOp.XOR) {
                            if (SqlEval.isAlwaysTrue(bin.left()) || SqlEval.isAlwaysTrue(bin.right())) {
                                violations.add("always-true-condition");
                            }
                        }
                        if (isTautologyLike(bin)) {
                            violations.add("always-true-condition");
                        }
                    }
                    if (checkInfo && node instanceof SqlTable) {
                        SqlTable table = (SqlTable) node;
                        if (isInformationSchema(table.name())) {
                            violations.add("information-schema");
                        }
                    }
                    return true;
                }
            });
        }
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

    /** 表黑名单 / 白名单 / WHERE 必含列 / 表数量上限。 */
    private static final class TablePolicyRule implements SqlWallRule {
        @Override
        public void check(SqlStatement statement, SqlWallConfig cfg, SqlWallViolations violations) {
            boolean checkDeny = !cfg.denyTables().isEmpty();
            boolean checkAllow = !cfg.allowTables().isEmpty();
            boolean checkReq = !cfg.requireWhereColumns().isEmpty();
            int max = cfg.maxTables();
            if (!checkDeny && !checkAllow && !checkReq && max <= 0) {
                return;
            }
            Set<String> cte = collectCteNames(statement);
            List<SqlTable> physical = collectPhysicalTables(statement, cte);
            if (max > 0 && physical.size() > max) {
                violations.add("too-many-tables");
            }
            for (int i = 0; i < physical.size(); i++) {
                SqlTable table = physical.get(i);
                String simple = tableName(table);
                String qualified = table.name() == null ? "" : table.name().qualifiedName();
                if (checkDeny && (inList(cfg.denyTables(), simple) || inList(cfg.denyTables(), qualified))) {
                    violations.add("deny-table");
                }
                if (checkAllow && !inList(cfg.allowTables(), simple) && !inList(cfg.allowTables(), qualified)) {
                    violations.add("allow-table");
                }
            }
            if (checkReq) {
                checkRequiredColumns(statement, cte, cfg.requireWhereColumns(), violations);
            }
        }
    }

    private static void checkRequiredColumns(SqlStatement statement, final Set<String> cte,
            final List<String> required, final SqlWallViolations violations) {
        statement.accept(new SqlVisitorAdapter() {
            /**
             * {@inheritDoc}
             */
            @Override
            public boolean visit(SqlNode node) {
                if (node instanceof SqlSelect) {
                    SqlSelect select = (SqlSelect) node;
                    if (hasPhysicalFrom(select.from(), cte)) {
                        Set<String> found = new HashSet<String>(4);
                        collectWhereColumns(select.where(), found);
                        collectJoinOnColumns(select.from(), found);
                        collectWhereColumns(select.having(), found);
                        addMissing(required, found, violations);
                    }
                } else if (node instanceof SqlUpdate) {
                    SqlUpdate update = (SqlUpdate) node;
                    if (hasPhysicalFrom(update.table(), cte) || hasPhysicalFrom(update.from(), cte)) {
                        Set<String> found = new HashSet<String>(4);
                        collectWhereColumns(update.where(), found);
                        collectJoinOnColumns(update.table(), found);
                        collectJoinOnColumns(update.from(), found);
                        addMissing(required, found, violations);
                    }
                } else if (node instanceof SqlDelete) {
                    SqlDelete delete = (SqlDelete) node;
                    if (hasPhysicalFrom(delete.table(), cte) || hasPhysicalFrom(delete.from(), cte)) {
                        Set<String> found = new HashSet<String>(4);
                        collectWhereColumns(delete.where(), found);
                        collectJoinOnColumns(delete.table(), found);
                        collectJoinOnColumns(delete.from(), found);
                        addMissing(required, found, violations);
                    }
                }
                return true;
            }
        });
    }

    private static void addMissing(List<String> required, Set<String> found, SqlWallViolations violations) {
        for (int i = 0; i < required.size(); i++) {
            if (!found.contains(required.get(i))) {
                violations.add("missing-where-column");
                return;
            }
        }
    }

    private static boolean hasPhysicalFrom(SqlTableSource src, Set<String> cte) {
        List<SqlTable> tables = new ArrayList<SqlTable>(2);
        collectPhysical(src, tables);
        for (int i = 0; i < tables.size(); i++) {
            if (!isSkippedTable(tables.get(i), cte)) {
                return true;
            }
        }
        return false;
    }

    private static void collectJoinOnColumns(SqlTableSource src, Set<String> found) {
        if (src instanceof SqlJoin) {
            SqlJoin join = (SqlJoin) src;
            collectWhereColumns(join.condition(), found);
            collectJoinOnColumns(join.left(), found);
            collectJoinOnColumns(join.right(), found);
        }
    }

    private static void collectWhereColumns(SqlExpr expr, final Set<String> found) {
        if (expr == null) {
            return;
        }
        expr.accept(new SqlVisitorAdapter() {
            /**
             * {@inheritDoc}
             */
            @Override
            public boolean visit(SqlNode node) {
                if (node instanceof SqlIdentifier) {
                    String simple = ((SqlIdentifier) node).simpleName();
                    if (simple != null && simple.length() > 0) {
                        found.add(simple.toLowerCase(Locale.ROOT));
                    }
                }
                return true;
            }
        });
    }

    private static List<SqlTable> collectPhysicalTables(SqlStatement statement, final Set<String> cte) {
        final List<SqlTable> out = new ArrayList<SqlTable>(4);
        statement.accept(new SqlVisitorAdapter() {
            /**
             * {@inheritDoc}
             */
            @Override
            public boolean visit(SqlNode node) {
                if (node instanceof SqlTable && !isSkippedTable((SqlTable) node, cte)) {
                    out.add((SqlTable) node);
                }
                return true;
            }
        });
        return out;
    }

    private static void collectPhysical(SqlTableSource src, List<SqlTable> out) {
        if (src == null) {
            return;
        }
        if (src instanceof SqlTable) {
            out.add((SqlTable) src);
        } else if (src instanceof SqlJoin) {
            SqlJoin join = (SqlJoin) src;
            collectPhysical(join.left(), out);
            collectPhysical(join.right(), out);
        }
    }

    private static Set<String> collectCteNames(SqlStatement statement) {
        final Set<String> names = new HashSet<String>(4);
        statement.accept(new SqlVisitorAdapter() {
            /**
             * {@inheritDoc}
             */
            @Override
            public boolean visit(SqlNode node) {
                if (node instanceof SqlWithItem) {
                    SqlIdentifier name = ((SqlWithItem) node).name();
                    if (name != null && name.simpleName().length() > 0) {
                        names.add(name.simpleName().toLowerCase(Locale.ROOT));
                    }
                }
                return true;
            }
        });
        return names;
    }

    private static boolean isSkippedTable(SqlTable table, Set<String> cte) {
        String simple = tableName(table);
        if (simple.isEmpty() || "dual".equals(simple)) {
            return true;
        }
        return cte.contains(simple);
    }

    private static String tableName(SqlTable table) {
        if (table == null || table.name() == null) {
            return "";
        }
        String simple = table.name().simpleName();
        return simple == null ? "" : simple.toLowerCase(Locale.ROOT);
    }

    private static boolean inList(List<String> names, String value) {
        if (value == null || value.isEmpty() || names == null || names.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (int i = 0; i < names.size(); i++) {
            if (lower.equals(names.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTautologyLike(SqlBinaryExpr bin) {
        if (bin.operator() != SqlBinaryOp.LIKE && bin.operator() != SqlBinaryOp.ILIKE) {
            return false;
        }
        if (!(bin.right() instanceof SqlLiteral)) {
            return false;
        }
        SqlLiteral lit = (SqlLiteral) bin.right();
        if (lit.kind() != SqlLiteral.Kind.STRING || lit.value() == null) {
            return false;
        }
        String raw = lit.value();
        if (raw.length() >= 2 && raw.charAt(0) == '\'' && raw.charAt(raw.length() - 1) == '\'') {
            raw = raw.substring(1, raw.length() - 1);
        }
        if (raw.isEmpty()) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '%' && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static void checkAlwaysTrue(SqlExpr where, SqlWallViolations violations) {
        if (where == null) {
            return;
        }
        if (SqlEval.isAlwaysTrue(where)) {
            violations.add("always-true-condition");
        }
    }

    /**
     * 检测可能用于截断后续条件的行/块注释（在字符串外）。
     */
    static void checkCommentBypass(String sql, SqlWallViolations violations) {
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
                violations.add("comment-bypass");
                return;
            }
            if (c == '#') {
                violations.add("comment-bypass");
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
                violations.add("comment-bypass");
                return;
            }
            i++;
        }
    }
}
