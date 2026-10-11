package com.alianga.jkit.storage.sync;

/**
 * 双向同步的冲突策略：两侧同名文件内容不一致（且都存在）时如何取舍。
 *
 * @author 郑明亮
 */
public enum ConflictPolicy {
    /**
     * 新者胜（默认）：修改时间双方已知时新者覆盖旧者；
     * 时间未知或相等时无法判定新旧，退化为「保留双份」
     * （另一版本以 {@code xxx.conflict-<时间戳>.<扩展名>} 名字留在两侧）。
     */
    NEWER_WINS,
    /** 左侧胜：左侧内容覆盖右侧（保留左侧修改时间）。 */
    LEFT_WINS,
    /** 右侧胜：右侧内容覆盖左侧（保留右侧修改时间）。 */
    RIGHT_WINS,
    /**
     * 两侧都保留：两侧各自的版本原地不动，对方的版本以
     * {@code xxx.conflict-<侧>-<时间戳>.<扩展名>} 的名字复制一份过来。
     */
    KEEP_BOTH
}
