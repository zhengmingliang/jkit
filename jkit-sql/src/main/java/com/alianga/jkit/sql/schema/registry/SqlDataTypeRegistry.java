package com.alianga.jkit.sql.schema.registry;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.model.SqlDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 类型映射注册表：以 {@link CanonicalType} 为轴，按方言声明写法。
 *
 * <p>查找走 {@link EnumMap}，构建完成后 {@link #freeze()} 变为只读。
 * 反向查找先精确匹配完整字面量（含 {@code TINYINT(1)}、{@code NUMBER(10)}），
 * 再回落到类型别名。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlDataTypeRegistry {
    private static final SqlDataTypeRegistry BUILTINS = SqlDataTypeRegistryBuiltins.create();

    private final EnumMap<CanonicalType, EnumMap<SqlDialect, DialectTypeForm>> forward =
            new EnumMap<CanonicalType, EnumMap<SqlDialect, DialectTypeForm>>(CanonicalType.class);
    private final EnumMap<SqlDialect, Map<String, CanonicalType>> reverseExact =
            new EnumMap<SqlDialect, Map<String, CanonicalType>>(SqlDialect.class);
    private final EnumMap<SqlDialect, Map<String, CanonicalType>> aliases =
            new EnumMap<SqlDialect, Map<String, CanonicalType>>(SqlDialect.class);
    private final List<LossyMapping> lossyMappings = new ArrayList<LossyMapping>(16);
    private boolean frozen;

    /**
     * 空注册表，供测试或 SPI 在内置表之上叠加。
     */
    public SqlDataTypeRegistry() {
        SqlDialect[] dialects = SqlDialect.values();
        for (int i = 0; i < dialects.length; i++) {
            reverseExact.put(dialects[i], new HashMap<String, CanonicalType>(32));
            aliases.put(dialects[i], new HashMap<String, CanonicalType>(32));
        }
    }

    /**
     * @return 内置注册表（不可变、已通过 {@link RegistryValidator}）
     */
    public static SqlDataTypeRegistry builtins() {
        return BUILTINS;
    }

    /**
     * 注册一个 canonical 类型在某方言的写法。
     *
     * @param type canonical 类型
     * @param dialect 方言
     * @param form 写法
     */
    public void register(CanonicalType type, SqlDialect dialect, DialectTypeForm form) {
        checkMutable();
        if (type == null || dialect == null || form == null || type == CanonicalType.UNKNOWN) {
            return;
        }
        EnumMap<SqlDialect, DialectTypeForm> row = forward.get(type);
        if (row == null) {
            row = new EnumMap<SqlDialect, DialectTypeForm>(SqlDialect.class);
            forward.put(type, row);
        }
        row.put(dialect, form);
        Map<String, CanonicalType> exactMap = reverseExact.get(dialect);
        Map<String, CanonicalType> aliasMap = aliases.get(dialect);
        if (form.placeholders() == 0) {
            String exact = normalize(form.pattern());
            if (!exactMap.containsKey(exact)) {
                exactMap.put(exact, type);
            }
            // 完整字面量 NUMBER(10) 只做精确匹配，不能把基名 NUMBER 抢成 TINYINT/INT
            if (form.pattern().indexOf('(') < 0 && !aliasMap.containsKey(exact)) {
                aliasMap.put(exact, type);
            }
        } else {
            String base = normalize(form.baseName());
            if (!aliasMap.containsKey(base)) {
                aliasMap.put(base, type);
            }
        }
    }

    /**
     * 注册类型别名（仅影响反向查找）。
     *
     * @param dialect 方言
     * @param typeName 别名（如 {@code INTEGER}、{@code VARCHAR2}）
     * @param type canonical
     */
    public void registerAlias(SqlDialect dialect, String typeName, CanonicalType type) {
        checkMutable();
        if (dialect == null || type == null || typeName == null || typeName.isEmpty()) {
            return;
        }
        Map<String, CanonicalType> aliasMap = aliases.get(dialect);
        String key = normalize(typeName);
        if (!aliasMap.containsKey(key)) {
            aliasMap.put(key, type);
        }
        Map<String, CanonicalType> exactMap = reverseExact.get(dialect);
        if (!exactMap.containsKey(key)) {
            exactMap.put(key, type);
        }
    }

    /**
     * 为所有方言注册同一别名。
     *
     * @param typeName 别名
     * @param type canonical
     */
    public void registerAliasAll(String typeName, CanonicalType type) {
        SqlDialect[] dialects = SqlDialect.values();
        for (int i = 0; i < dialects.length; i++) {
            registerAlias(dialects[i], typeName, type);
        }
    }

    /**
     * 声明有损映射，并覆盖该字面量的反向主类型。
     *
     * @param mapping 有损映射
     */
    public void registerLossyMapping(LossyMapping mapping) {
        checkMutable();
        if (mapping == null || mapping.dialect() == null) {
            return;
        }
        lossyMappings.add(mapping);
        reverseExact.get(mapping.dialect()).put(normalize(mapping.literalForm()), mapping.primary());
    }

    /**
     * 冻结为只读。
     */
    public void freeze() {
        frozen = true;
    }

    /**
     * @param type canonical
     * @param dialect 方言
     * @return 写法，未注册时为 null
     */
    public DialectTypeForm form(CanonicalType type, SqlDialect dialect) {
        if (type == null || dialect == null) {
            return null;
        }
        EnumMap<SqlDialect, DialectTypeForm> row = forward.get(type);
        if (row == null) {
            return null;
        }
        return row.get(dialect);
    }

    /**
     * canonical → 目标方言写法。
     *
     * @param type canonical
     * @param dialect 目标方言
     * @param precision 精度，可空
     * @param scale 标度，可空
     * @return 写法；未知类型返回空串
     */
    public String toDialect(CanonicalType type, SqlDialect dialect,
                            Integer precision, Integer scale) {
        DialectTypeForm form = form(type, dialect);
        if (form == null) {
            return "";
        }
        return form.render(precision, scale);
    }

    /**
     * 方言写法 → canonical。
     *
     * @param dialectForm 方言类型文本，如 {@code INT(11)}、{@code VARCHAR(100)}
     * @param dialect 源方言
     * @return canonical，无法识别时为 {@link CanonicalType#UNKNOWN}
     */
    public CanonicalType fromDialect(String dialectForm, SqlDialect dialect) {
        if (dialectForm == null || dialect == null) {
            return CanonicalType.UNKNOWN;
        }
        String norm = normalize(dialectForm);
        if (norm.isEmpty()) {
            return CanonicalType.UNKNOWN;
        }
        CanonicalType exact = reverseExact.get(dialect).get(norm);
        if (exact != null) {
            return exact;
        }
        String base = baseName(norm);
        CanonicalType alias = aliases.get(dialect).get(base);
        if (alias != null) {
            return alias;
        }
        CanonicalType exactBase = reverseExact.get(dialect).get(base);
        if (exactBase != null) {
            return exactBase;
        }
        return CanonicalType.UNKNOWN;
    }

    /**
     * 结构化类型 → canonical（保留 UNSIGNED 等修饰符由调用方处理）。
     *
     * @param dataType 结构化类型
     * @param dialect 源方言
     * @return canonical
     */
    public CanonicalType fromDialect(SqlDataType dataType, SqlDialect dialect) {
        if (dataType == null) {
            return CanonicalType.UNKNOWN;
        }
        String key = lookupKey(dataType);
        CanonicalType found = fromDialect(key, dialect);
        if (found != CanonicalType.UNKNOWN) {
            return found;
        }
        return fromDialect(dataType.rawTypeName(), dialect);
    }

    /**
     * 方言 A 写法 → 方言 B 写法。
     *
     * @param sourceForm 源写法
     * @param from 源方言
     * @param to 目标方言
     * @return 目标写法；无法识别时返回原文
     */
    public String convert(String sourceForm, SqlDialect from, SqlDialect to) {
        SqlDataType parsed = parseForm(sourceForm);
        CanonicalType canonical = fromDialect(parsed, from);
        if (canonical == CanonicalType.UNKNOWN) {
            return sourceForm;
        }
        return toDialect(canonical, to, parsed.precision(), parsed.scale());
    }

    /**
     * @return 已声明的有损映射（不可变）
     */
    public List<LossyMapping> lossyMappings() {
        return Collections.unmodifiableList(lossyMappings);
    }

    /**
     * @param dialect 方言
     * @param literalForm 字面量
     * @return 匹配的有损映射，没有则 null
     */
    public LossyMapping findLossy(SqlDialect dialect, String literalForm) {
        String norm = normalize(literalForm);
        for (int i = 0; i < lossyMappings.size(); i++) {
            LossyMapping m = lossyMappings.get(i);
            if (m.dialect() == dialect && normalize(m.literalForm()).equals(norm)) {
                return m;
            }
        }
        return null;
    }

    /**
     * 归一化类型字面量：大写、压缩空白、去掉括号内外多余空格。
     *
     * @param form 原文
     * @return 归一化结果
     */
    public static String normalize(String form) {
        if (form == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(form.length());
        boolean prevSpace = false;
        for (int i = 0; i < form.length(); i++) {
            char c = form.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                prevSpace = true;
                continue;
            }
            if (c == '(' || c == ')' || c == ',') {
                prevSpace = false;
                sb.append(c);
                continue;
            }
            if (prevSpace && sb.length() > 0) {
                char last = sb.charAt(sb.length() - 1);
                if (last != '(' && last != ',') {
                    sb.append(' ');
                }
            }
            prevSpace = false;
            if (c >= 'a' && c <= 'z') {
                c = (char) (c - 32);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static String baseName(String normalized) {
        int paren = normalized.indexOf('(');
        if (paren < 0) {
            return normalized;
        }
        return normalized.substring(0, paren).trim();
    }

    static String lookupKey(SqlDataType dataType) {
        String name = dataType.rawTypeName() == null ? "" : dataType.rawTypeName();
        if (dataType.precision() != null && dataType.scale() != null) {
            return name + "(" + dataType.precision() + "," + dataType.scale() + ")";
        }
        if (dataType.precision() != null) {
            return name + "(" + dataType.precision() + ")";
        }
        return name;
    }

    static SqlDataType parseForm(String sourceForm) {
        if (sourceForm == null) {
            return SqlDataType.unknown("");
        }
        String trimmed = sourceForm.trim();
        int paren = trimmed.indexOf('(');
        if (paren < 0) {
            return new SqlDataType(trimmed, null, null, Collections.<SqlDataType.TypeAttribute>emptySet());
        }
        String name = trimmed.substring(0, paren).trim();
        int close = trimmed.lastIndexOf(')');
        String inside = close > paren ? trimmed.substring(paren + 1, close).trim() : "";
        Integer precision = null;
        Integer scale = null;
        int comma = inside.indexOf(',');
        try {
            if (comma < 0) {
                if (!inside.isEmpty() && isDigits(inside)) {
                    precision = Integer.valueOf(inside);
                }
            } else {
                String p = inside.substring(0, comma).trim();
                String s = inside.substring(comma + 1).trim();
                if (isDigits(p)) {
                    precision = Integer.valueOf(p);
                }
                if (isDigits(s)) {
                    scale = Integer.valueOf(s);
                }
            }
        } catch (NumberFormatException ignored) {
            precision = null;
            scale = null;
        }
        return new SqlDataType(name, precision, scale, Collections.<SqlDataType.TypeAttribute>emptySet());
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private void checkMutable() {
        if (frozen) {
            throw new IllegalStateException("SqlDataTypeRegistry is frozen");
        }
    }
}
