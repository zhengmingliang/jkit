package com.alianga.jkit.sql.schema.registry;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.model.CanonicalType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 显式声明一组 canonical 类型在某方言下不可逆地收敛到同一写法。
 *
 * <p>反向转换遇到该写法时返回 {@link #primary()}；其余类型只能转入、不能转出到该写法。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class LossyMapping {
    private final SqlDialect dialect;
    private final String literalForm;
    private final CanonicalType primary;
    private final Set<CanonicalType> collapsedFrom;

    /**
     * @param dialect 方言
     * @param literalForm 收敛后的字面量（大小写不敏感）
     * @param primary 反向查找返回的 canonical
     * @param collapsedFrom 收敛到该字面量的全部 canonical（含 primary）
     */
    public LossyMapping(SqlDialect dialect, String literalForm, CanonicalType primary,
                        Set<CanonicalType> collapsedFrom) {
        this.dialect = dialect;
        this.literalForm = literalForm == null ? "" : literalForm;
        this.primary = primary == null ? CanonicalType.UNKNOWN : primary;
        if (collapsedFrom == null || collapsedFrom.isEmpty()) {
            this.collapsedFrom = Collections.emptySet();
        } else {
            this.collapsedFrom = Collections.unmodifiableSet(EnumSet.copyOf(collapsedFrom));
        }
    }

    /**
     * @return 方言
     */
    public SqlDialect dialect() {
        return dialect;
    }

    /**
     * @return 收敛字面量
     */
    public String literalForm() {
        return literalForm;
    }

    /**
     * @return 反向查找主类型
     */
    public CanonicalType primary() {
        return primary;
    }

    /**
     * @return 收敛来源集合
     */
    public Set<CanonicalType> collapsedFrom() {
        return collapsedFrom;
    }
}
