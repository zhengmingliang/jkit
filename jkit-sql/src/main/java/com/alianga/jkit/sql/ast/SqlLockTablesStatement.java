package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * MySQL {@code LOCK TABLES t READ, u WRITE, …} / {@code UNLOCK TABLES}。
 *
 * <p>抽表清单与锁模式（READ / WRITE / READ LOCAL / LOW_PRIORITY WRITE 等）；
 * 无法结构化时整段进 {@link #raw()}。语句种类仍为 {@link SqlStatementType#OTHER}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlLockTablesStatement extends SqlStatement {
    /**
     * 单表锁项。
     */
    public static final class LockItem {
        private SqlIdentifier table;
        private String alias;
        /** READ / WRITE / READ LOCAL / LOW_PRIORITY WRITE 等。 */
        private String lockMode;

        /**
         * @return 表名，可空
         */
        public SqlIdentifier table() {
            return table;
        }

        /**
         * @param table 表名
         */
        public void setTable(SqlIdentifier table) {
            this.table = table;
        }

        /**
         * @return 别名，可空
         */
        public String alias() {
            return alias;
        }

        /**
         * @param alias 别名
         */
        public void setAlias(String alias) {
            this.alias = alias;
        }

        /**
         * @return 锁模式，可空
         */
        public String lockMode() {
            return lockMode;
        }

        /**
         * @param lockMode 锁模式
         */
        public void setLockMode(String lockMode) {
            this.lockMode = lockMode;
        }
    }

    private boolean unlock;
    private final List<LockItem> items = new ArrayList<LockItem>(2);
    private String raw;

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlStatementType type() {
        return SqlStatementType.OTHER;
    }

    /**
     * @return 是否为 {@code UNLOCK TABLES}
     */
    public boolean unlock() {
        return unlock;
    }

    /**
     * @param unlock 是否 UNLOCK
     */
    public void setUnlock(boolean unlock) {
        this.unlock = unlock;
    }

    /**
     * @return 锁表项列表（UNLOCK 时为空）
     */
    public List<LockItem> items() {
        return items;
    }

    /**
     * @return 整段原文，可空
     */
    public String raw() {
        return raw;
    }

    /**
     * @param raw 原文
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
        for (int i = 0; i < items.size(); i++) {
            LockItem item = items.get(i);
            if (item != null) {
                child(visitor, item.table());
            }
        }
    }
}
