package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider;
import com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProviders;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 函数改写注册表。先登记内置规则，再 {@code ServiceLoader} 调用
 * {@link SqlSchemaConverterProvider#registerFunctions}；后注册覆盖先注册。
 * SPI 的 {@link FunctionRewriteRule#rewrite} 返回 {@code null} 时回落到内置快照。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlFunctionRegistry {
    private static final SqlFunctionRegistry BUILTINS = create();

    private final Map<String, FunctionRewriteRule> rules = new HashMap<String, FunctionRewriteRule>(16);
    private Map<String, FunctionRewriteRule> builtinSnapshot;
    private boolean frozen;

    /**
     * 空表。转换入口用 {@link #builtins()}。
     */
    public SqlFunctionRegistry() {
    }

    /**
     * @return 内置 + SPI（已 freeze）
     */
    public static SqlFunctionRegistry builtins() {
        return BUILTINS;
    }

    /**
     * @param functionName 函数名（大小写不敏感）
     * @param rule 规则
     */
    public void register(String functionName, FunctionRewriteRule rule) {
        if (frozen) {
            throw new IllegalStateException("SqlFunctionRegistry is frozen");
        }
        if (functionName == null || rule == null) {
            return;
        }
        rules.put(functionName.toUpperCase(Locale.ROOT), rule);
    }

    /**
     * 当前生效规则（含 SPI 覆盖）。
     *
     * @param functionName 函数名
     * @return 规则，没有则 null
     */
    public FunctionRewriteRule find(String functionName) {
        if (functionName == null) {
            return null;
        }
        return rules.get(functionName.toUpperCase(Locale.ROOT));
    }

    /**
     * 内置快照（不含 SPI）。SPI 返回 null 时 walker 回落到这里。
     *
     * @param functionName 函数名
     * @return 内置规则，没有则 null
     */
    public FunctionRewriteRule findBuiltin(String functionName) {
        if (functionName == null || builtinSnapshot == null) {
            return null;
        }
        return builtinSnapshot.get(functionName.toUpperCase(Locale.ROOT));
    }

    /**
     * 把当前已登记规则记为内置快照，之后的 {@link #register} 视为 SPI 覆盖。
     * {@link #builtins()} 在加载 SPI 之前调用。
     */
    public void snapshotBuiltins() {
        if (frozen) {
            throw new IllegalStateException("SqlFunctionRegistry is frozen");
        }
        builtinSnapshot = Collections.unmodifiableMap(
                new HashMap<String, FunctionRewriteRule>(rules));
    }

    /**
     * 冻结。
     */
    public void freeze() {
        frozen = true;
    }

    private static SqlFunctionRegistry create() {
        SqlFunctionRegistry r = new SqlFunctionRegistry();
        BuiltinFunctionRewriter builtin = BuiltinFunctionRewriter.INSTANCE;
        String[] names = {
                "IF", "NOW", "CURDATE", "CURTIME",
                "IFNULL", "NVL", "ISNULL",
                "GROUP_CONCAT", "STRING_AGG", "LISTAGG",
                "CONCAT", "CONVERT",
                "LOCATE", "INSTR", "CHARINDEX",
                "LENGTH", "CHAR_LENGTH", "CHARACTER_LENGTH", "LEN",
                "SUBSTRING", "SUBSTR",
                "DATE_ADD", "ADDDATE", "DATE_SUB", "SUBDATE",
                "DATEDIFF", "TIMESTAMPDIFF",
                "FROM_UNIXTIME", "UNIX_TIMESTAMP",
                "STR_TO_DATE", "TO_DATE",
                "DECODE", "NVL2",
                "SUBSTRING_INDEX", "FIND_IN_SET",
                "UUID", "RAND", "LAST_INSERT_ID"
        };
        for (int i = 0; i < names.length; i++) {
            r.register(names[i], builtin);
        }
        r.register("DATE_FORMAT", new DateFormatRewriteRule());
        r.snapshotBuiltins();
        loadProviders(r);
        r.freeze();
        return r;
    }

    private static void loadProviders(SqlFunctionRegistry registry) {
        List<SqlSchemaConverterProvider> providers = SqlSchemaConverterProviders.loadSorted();
        for (int i = 0; i < providers.size(); i++) {
            providers.get(i).registerFunctions(registry);
        }
    }
}
