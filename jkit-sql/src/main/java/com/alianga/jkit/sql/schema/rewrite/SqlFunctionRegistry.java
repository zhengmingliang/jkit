package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 函数改写注册表。内置规则之后加载 SPI，后注册覆盖先注册。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlFunctionRegistry {
    private static final SqlFunctionRegistry BUILTINS = create();

    private final Map<String, FunctionRewriteRule> rules = new HashMap<String, FunctionRewriteRule>(16);
    private boolean frozen;

    /**
     * @return 内置 + SPI
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
     * 冻结。
     */
    public void freeze() {
        frozen = true;
    }

    private static SqlFunctionRegistry create() {
        SqlFunctionRegistry r = new SqlFunctionRegistry();
        r.register("DATE_FORMAT", new DateFormatRewriteRule());
        loadProviders(r);
        r.freeze();
        return r;
    }

    private static void loadProviders(SqlFunctionRegistry registry) {
        List<SqlSchemaConverterProvider> providers = new ArrayList<SqlSchemaConverterProvider>();
        for (SqlSchemaConverterProvider p : ServiceLoader.load(SqlSchemaConverterProvider.class)) {
            if (p != null) {
                providers.add(p);
            }
        }
        Collections.sort(providers, new Comparator<SqlSchemaConverterProvider>() {
            /**
             * {@inheritDoc}
             */
            @Override
            public int compare(SqlSchemaConverterProvider a, SqlSchemaConverterProvider b) {
                return Integer.compare(a.priority(), b.priority());
            }
        });
        for (int i = 0; i < providers.size(); i++) {
            providers.get(i).registerFunctions(registry);
        }
    }
}
