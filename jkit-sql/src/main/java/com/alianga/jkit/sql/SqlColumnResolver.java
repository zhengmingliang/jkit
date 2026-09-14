package com.alianga.jkit.sql;

import java.util.List;

/**
 * 把表名解析成列清单，供 {@link SQL#expandStar} 展开 {@code *} / {@code t.*}。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public interface SqlColumnResolver {
    /**
     * @param tableSimpleName 物理表简单名（不含库名 / schema）
     * @return 列简单名；未知时返回 {@code null}（该星号保持原样）
     */
    List<String> columnsOf(String tableSimpleName);
}
