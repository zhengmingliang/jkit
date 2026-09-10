package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code MATCH_RECOGNIZE} 的 {@code SUBSET name = (a, b, …)}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlSubset extends SqlNode {
    private String name;
    private final List<String> members = new ArrayList<String>(2);
    /** 未能结构化时的原文兜底。 */
    private String raw;

    /**
     * @return 子集名，可空
     */
    public String name() {
        return name;
    }

    /**
     * @param name 子集名
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return 模式变量名列表
     */
    public List<String> members() {
        return members;
    }

    /**
     * @return 原文兜底，可空
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
        // 成员为裸字符串
    }
}
