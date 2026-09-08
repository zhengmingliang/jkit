package com.alianga.jkit.sql.ast;

import com.alianga.jkit.sql.visitor.SqlVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * 窗口定义：{@code OVER (PARTITION BY ... ORDER BY ... ROWS/RANGE ...)}，
 * 以及括号内继承 {@code OVER (w ORDER BY ...)} / {@code WINDOW w2 AS (w)}。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlOverExpr extends SqlExpr {
    private SqlIdentifier windowName;
    private SqlIdentifier existingWindowName;
    private final List<SqlExpr> partitionBy = new ArrayList<SqlExpr>(2);
    private final List<SqlOrderByItem> orderBy = new ArrayList<SqlOrderByItem>(2);
    private String frameUnit;
    private String frameStart;
    private String frameEnd;

    /**
     * @return 命名窗口，与括号定义互斥
     */
    public SqlIdentifier windowName() {
        return windowName;
    }

    /**
     * @param windowName 窗口名
     */
    public void setWindowName(SqlIdentifier windowName) {
        this.windowName = windowName;
    }

    /**
     * @return 括号内继承的已有窗口名，如 {@code (w ORDER BY b)} 中的 {@code w}；与 {@link #windowName()}（无括号的 {@code OVER w}）不同
     * @since 2.1.0
     */
    public SqlIdentifier existingWindowName() {
        return existingWindowName;
    }

    /**
     * @param existingWindowName 继承的窗口名
     * @since 2.1.0
     */
    public void setExistingWindowName(SqlIdentifier existingWindowName) {
        this.existingWindowName = existingWindowName;
    }

    /**
     * @return PARTITION BY
     */
    public List<SqlExpr> partitionBy() {
        return partitionBy;
    }

    /**
     * @return ORDER BY
     */
    public List<SqlOrderByItem> orderBy() {
        return orderBy;
    }

    /**
     * @return ROWS 或 RANGE，可空
     */
    public String frameUnit() {
        return frameUnit;
    }

    /**
     * @param frameUnit ROWS / RANGE
     */
    public void setFrameUnit(String frameUnit) {
        this.frameUnit = frameUnit;
    }

    /**
     * @return 帧起点原文，如 {@code UNBOUNDED PRECEDING}
     */
    public String frameStart() {
        return frameStart;
    }

    /**
     * @param frameStart 起点
     */
    public void setFrameStart(String frameStart) {
        this.frameStart = frameStart;
    }

    /**
     * @return 帧终点原文
     */
    public String frameEnd() {
        return frameEnd;
    }

    /**
     * @param frameEnd 终点
     */
    public void setFrameEnd(String frameEnd) {
        this.frameEnd = frameEnd;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void acceptChildren(SqlVisitor visitor) {
        child(visitor, windowName);
        child(visitor, existingWindowName);
        children(visitor, partitionBy);
        children(visitor, orderBy);
    }
}
