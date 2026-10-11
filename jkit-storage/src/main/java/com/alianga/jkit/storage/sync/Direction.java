package com.alianga.jkit.storage.sync;

/**
 * 同步方向。
 *
 * @author 郑明亮
 */
public enum Direction {
    /** 单向：source → target（默认，现状语义）。 */
    ONE_WAY,
    /** 双向：两侧互为补充，缺失侧补齐，冲突按 {@link ConflictPolicy} 处理。 */
    BIDIRECTIONAL
}
