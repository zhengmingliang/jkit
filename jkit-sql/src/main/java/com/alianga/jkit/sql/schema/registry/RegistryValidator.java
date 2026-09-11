package com.alianga.jkit.sql.schema.registry;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.model.CanonicalType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * 注册表启动期 / CI 自检：每个方言的每个 canonical 都必须有写法，
 * 未声明的 reverse 碰撞会失败。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class RegistryValidator {
    private RegistryValidator() {
    }

    /**
     * 校验注册表。
     *
     * @param registry 注册表，不可空
     * @return 校验结果
     */
    public static ValidationResult validate(SqlDataTypeRegistry registry) {
        List<String> errors = new ArrayList<String>();
        if (registry == null) {
            errors.add("registry is null");
            return new ValidationResult(errors);
        }
        SqlDialect[] dialects = SqlDialect.values();
        CanonicalType[] types = CanonicalType.values();
        for (int d = 0; d < dialects.length; d++) {
            SqlDialect dialect = dialects[d];
            Map<String, EnumSet<CanonicalType>> rendered =
                    new java.util.LinkedHashMap<String, EnumSet<CanonicalType>>();
            for (int t = 0; t < types.length; t++) {
                CanonicalType type = types[t];
                if (type == CanonicalType.UNKNOWN) {
                    continue;
                }
                DialectTypeForm form = registry.form(type, dialect);
                if (form == null) {
                    errors.add("missing form: " + type + " / " + dialect);
                    continue;
                }
                Integer precision = type.requiresPrecision() ? Integer.valueOf(10) : null;
                Integer scale = type.requiresScale() ? Integer.valueOf(2) : null;
                String literal = SqlDataTypeRegistry.normalize(form.render(precision, scale));
                EnumSet<CanonicalType> group = rendered.get(literal);
                if (group == null) {
                    group = EnumSet.of(type);
                    rendered.put(literal, group);
                } else {
                    group.add(type);
                }
            }
            checkCollisions(registry, dialect, rendered, errors);
        }
        checkLossyMappings(registry, errors);
        return new ValidationResult(errors);
    }

    private static void checkCollisions(SqlDataTypeRegistry registry, SqlDialect dialect,
                                        Map<String, EnumSet<CanonicalType>> rendered,
                                        List<String> errors) {
        for (Map.Entry<String, EnumSet<CanonicalType>> e : rendered.entrySet()) {
            EnumSet<CanonicalType> group = e.getValue();
            if (group.size() < 2) {
                continue;
            }
            LossyMapping mapping = registry.findLossy(dialect, e.getKey());
            if (mapping == null) {
                errors.add("undeclared reverse collision on " + dialect + " `" + e.getKey()
                        + "`: " + group);
                continue;
            }
            if (!mapping.collapsedFrom().containsAll(group)) {
                errors.add("lossy mapping on " + dialect + " `" + e.getKey()
                        + "` does not cover " + group + ", declared " + mapping.collapsedFrom());
            }
            if (!group.contains(mapping.primary())) {
                errors.add("lossy mapping primary " + mapping.primary()
                        + " is not in collision group " + group + " on " + dialect
                        + " `" + e.getKey() + "`");
            }
        }
    }

    private static void checkLossyMappings(SqlDataTypeRegistry registry, List<String> errors) {
        List<LossyMapping> all = registry.lossyMappings();
        for (int i = 0; i < all.size(); i++) {
            LossyMapping m = all.get(i);
            if (m.dialect() == null || m.primary() == null || m.primary() == CanonicalType.UNKNOWN) {
                errors.add("invalid lossy mapping: " + m.literalForm());
                continue;
            }
            if (!m.collapsedFrom().contains(m.primary())) {
                errors.add("lossy mapping primary not in collapsedFrom: " + m.dialect()
                        + " `" + m.literalForm() + "`");
            }
        }
    }

    /**
     * 校验结果。
     */
    public static final class ValidationResult {
        private final List<String> errors;

        ValidationResult(List<String> errors) {
            this.errors = Collections.unmodifiableList(new ArrayList<String>(errors));
        }

        /**
         * @return 是否通过
         */
        public boolean ok() {
            return errors.isEmpty();
        }

        /**
         * @return 错误列表
         */
        public List<String> errors() {
            return errors;
        }

        /**
         * 失败则抛 {@link IllegalStateException}。
         */
        public void throwIfInvalid() {
            if (!ok()) {
                StringBuilder sb = new StringBuilder("SqlDataTypeRegistry validation failed:");
                for (int i = 0; i < errors.size(); i++) {
                    sb.append('\n').append(" - ").append(errors.get(i));
                }
                throw new IllegalStateException(sb.toString());
            }
        }
    }
}
