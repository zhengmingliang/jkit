package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code SET} 语句：多赋值 / 会话变量 / {@code SET NAMES} / {@code SET CHARACTER SET} 等。
 *
 * <p>{@link #scope()} 为 SESSION/GLOBAL/LOCAL（可空）；
 * {@link #setKind()} 为 {@code NAMES}/{@code CHARACTER SET}/{@code PASSWORD}/空（普通赋值）；
 * 赋值列表进 {@link #assignments()}；{@code SET PASSWORD …} 等残余进 {@link #raw()}。
 * 语句种类为 {@link SqlStatementType#SET}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlSetStatement extends SqlStatement {
    /**
     * 单条赋值 {@code name [=] value}。
     */
    public static final class Assignment {
        private SqlIdentifier name;
        private SqlExpr value;
        /** true 表示写入时带 {@code =}（NAMES/CHARACTER SET 通常为 false）。 */
        private boolean equalsSign = true;

        /**
         * @return 变量/名字，可空
         */
        public SqlIdentifier name() {
            return name;
        }

        /**
         * @param name 变量名
         */
        public void setName(SqlIdentifier name) {
            this.name = name;
        }

        /**
         * @return 值，可空
         */
        public SqlExpr value() {
            return value;
        }

        /**
         * @param value 值
         */
        public void setValue(SqlExpr value) {
            this.value = value;
        }

        /**
         * @return 是否带等号
         */
        public boolean equalsSign() {
            return equalsSign;
        }

        /**
         * @param equalsSign 是否带等号
         */
        public void setEqualsSign(boolean equalsSign) {
            this.equalsSign = equalsSign;
        }
    }

    /** SESSION / GLOBAL / LOCAL，可空。 */
    private String scope;
    /** NAMES / CHARACTER SET / PASSWORD，可空表示普通赋值。 */
    private String setKind;
    private final List<Assignment> assignments = new ArrayList<Assignment>(2);
    /** PASSWORD 等残余原文，可空。 */
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.SET;
    }

    /**
     * @return SESSION / GLOBAL / LOCAL，可空
     */
    public String scope() {
        return scope;
    }

    /**
     * @param scope 作用域
     */
    public void setScope(String scope) {
        this.scope = scope;
    }

    /**
     * @return NAMES / CHARACTER SET / PASSWORD，可空
     */
    public String setKind() {
        return setKind;
    }

    /**
     * @param setKind 特殊 SET 种类
     */
    public void setSetKind(String setKind) {
        this.setKind = setKind;
    }

    /**
     * @return 赋值列表
     */
    public List<Assignment> assignments() {
        return assignments;
    }

    /**
     * @return 残余原文，可空
     */
    public String raw() {
        return raw;
    }

    /**
     * @param raw 残余原文
     */
    public void setRaw(String raw) {
        this.raw = raw;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        super.acceptChildren(visitor);
        for (int i = 0; i < assignments.size(); i++) {
            Assignment a = assignments.get(i);
            if (a != null) {
                child(visitor, a.name());
                child(visitor, a.value());
            }
        }
    }
}
