package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlStatementType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 单表访问类型集合。同一张表可同时被写入与读取（如 {@code INSERT INTO t SELECT * FROM t}）。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlTableAccess {
    private final Set<SqlStatementType> types = new LinkedHashSet<SqlStatementType>(2);

    /**
     * 追加一种访问类型（去重，保留首次出现顺序）。
     *
     * @param type 访问类型
     */
    public void add(SqlStatementType type) {
        if (type != null) {
            types.add(type);
        }
    }

    /**
     * @return 不可变视图，按首次出现顺序
     */
    public Set<SqlStatementType> types() {
        return Collections.unmodifiableSet(types);
    }

    /**
     * @param type 类型
     * @return 是否包含该访问类型
     */
    public boolean contains(SqlStatementType type) {
        return types.contains(type);
    }

    /**
     * @return 首次记录的类型；空集合时为 null
     */
    public SqlStatementType primary() {
        if (types.isEmpty()) {
            return null;
        }
        return types.iterator().next();
    }

    /**
     * @return 是否含读（SELECT）
     */
    public boolean isRead() {
        return types.contains(SqlStatementType.SELECT);
    }

    /**
     * @return 是否含写（非 SELECT 的访问）
     */
    public boolean isWrite() {
        for (SqlStatementType type : types) {
            if (type != SqlStatementType.SELECT) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        if (types.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (SqlStatementType type : types) {
            if (!first) {
                sb.append('+');
            }
            sb.append(type.name());
            first = false;
        }
        return sb.toString();
    }
}
