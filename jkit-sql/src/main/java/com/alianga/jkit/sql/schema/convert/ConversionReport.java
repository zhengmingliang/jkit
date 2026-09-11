package com.alianga.jkit.sql.schema.convert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 转换可观测性：警告列表与计数。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class ConversionReport {
    private final List<ConversionWarning> warnings;
    private final List<String> extraSql;
    private final int columnsConverted;
    private final int columnsUnchanged;

    /**
     * @param warnings 警告
     * @param extraSql 附录 SQL（CREATE INDEX / SEQUENCE 等）
     * @param columnsConverted 已改写列数
     * @param columnsUnchanged 原样保留列/表约束数
     */
    public ConversionReport(List<ConversionWarning> warnings, List<String> extraSql,
                            int columnsConverted, int columnsUnchanged) {
        if (warnings == null || warnings.isEmpty()) {
            this.warnings = Collections.emptyList();
        } else {
            this.warnings = Collections.unmodifiableList(new ArrayList<ConversionWarning>(warnings));
        }
        if (extraSql == null || extraSql.isEmpty()) {
            this.extraSql = Collections.emptyList();
        } else {
            this.extraSql = Collections.unmodifiableList(new ArrayList<String>(extraSql));
        }
        this.columnsConverted = columnsConverted;
        this.columnsUnchanged = columnsUnchanged;
    }

    /**
     * @param warnings 警告
     * @param columnsConverted 已改写列数
     * @param columnsUnchanged 原样保留列/表约束数
     */
    public ConversionReport(List<ConversionWarning> warnings, int columnsConverted,
                            int columnsUnchanged) {
        this(warnings, Collections.<String>emptyList(), columnsConverted, columnsUnchanged);
    }

    /**
     * @return 空报告
     */
    public static ConversionReport empty() {
        return new ConversionReport(Collections.<ConversionWarning>emptyList(),
                Collections.<String>emptyList(), 0, 0);
    }

    /**
     * @return 警告（不可变）
     */
    public List<ConversionWarning> warnings() {
        return warnings;
    }

    /**
     * @return 附录 SQL（建索引、SEQUENCE 等），不含主 CREATE TABLE
     */
    public List<String> extraSql() {
        return extraSql;
    }

    /**
     * @return 已改写列数
     */
    public int columnsConverted() {
        return columnsConverted;
    }

    /**
     * @return 原样保留数
     */
    public int columnsUnchanged() {
        return columnsUnchanged;
    }

    /**
     * @return 是否含 {@link ConversionWarning.Severity#MANUAL_ACTION_REQUIRED}
     */
    public boolean hasBlockingIssues() {
        for (int i = 0; i < warnings.size(); i++) {
            if (warnings.get(i).severity() == ConversionWarning.Severity.MANUAL_ACTION_REQUIRED) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param min 最低级别，null 视为不检查
     * @return 是否含该级别及以上的警告
     */
    public boolean hasSeverityAtLeast(ConversionWarning.Severity min) {
        if (min == null) {
            return false;
        }
        int threshold = min.ordinal();
        for (int i = 0; i < warnings.size(); i++) {
            if (warnings.get(i).severity().ordinal() >= threshold) {
                return true;
            }
        }
        return false;
    }

    /**
     * 可变收集器。
     */
    public static final class Builder {
        private final List<ConversionWarning> warnings = new ArrayList<ConversionWarning>(4);
        private final List<String> extraSql = new ArrayList<String>(2);
        private int converted;
        private int unchanged;

        /**
         * @param severity 级别
         * @param location 位置
         * @param message 说明
         */
        public void warn(ConversionWarning.Severity severity, String location, String message) {
            warnings.add(new ConversionWarning(severity, location, message));
        }

        /**
         * @param sql 附录语句
         */
        public void extraSql(String sql) {
            if (sql != null && !sql.isEmpty()) {
                extraSql.add(sql);
            }
        }

        /**
         * 计一列已转换。
         */
        public void converted() {
            converted++;
        }

        /**
         * 计一列/约束原样保留。
         */
        public void unchanged() {
            unchanged++;
        }

        /**
         * @return 不可变报告
         */
        public ConversionReport build() {
            return new ConversionReport(warnings, extraSql, converted, unchanged);
        }
    }
}
