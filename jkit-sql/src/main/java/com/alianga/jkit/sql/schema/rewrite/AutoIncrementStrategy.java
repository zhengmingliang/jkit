package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlDialectSpec;
import com.alianga.jkit.sql.schema.convert.ConversionReport;
import com.alianga.jkit.sql.schema.convert.ConversionWarning;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConvertOptions;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.model.ColumnConstraint;

/**
 * 自增约束 → 目标方言语法。无法完整表达时写入 {@code MANUAL_ACTION_REQUIRED}，不静默丢弃。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class AutoIncrementStrategy {
    private AutoIncrementStrategy() {
    }

    /**
     * 自增改写结果。
     */
    public static final class Result {
        private final String clause;
        private final String overrideType;
        private final boolean dropped;
        private final boolean afterPrimaryKey;

        Result(String clause, String overrideType, boolean dropped) {
            this(clause, overrideType, dropped, false);
        }

        Result(String clause, String overrideType, boolean dropped, boolean afterPrimaryKey) {
            this.clause = clause;
            this.overrideType = overrideType;
            this.dropped = dropped;
            this.afterPrimaryKey = afterPrimaryKey;
        }

        /**
         * @return 跟在类型后的自增子句，可空
         */
        public String clause() {
            return clause;
        }

        /**
         * @return 覆盖类型名（如 SERIAL），可空
         */
        public String overrideType() {
            return overrideType;
        }

        /**
         * @return 是否因目标方言无法表达而丢弃（已告警）
         */
        public boolean dropped() {
            return dropped;
        }

        /**
         * 自增子句是否必须排在 {@code PRIMARY KEY} 之后（SQLite 的 {@code AUTOINCREMENT}
         * 只能写成 {@code INTEGER PRIMARY KEY AUTOINCREMENT}，写反了建表报错）。
         *
         * @return true 时渲染器把子句放到 PRIMARY KEY 后面
         */
        public boolean afterPrimaryKey() {
            return afterPrimaryKey;
        }
    }

    /**
     * @param auto 源自增约束
     * @param type 列 canonical 类型
     * @param columnName 列名（告警用）
     * @param target 目标方言
     * @param options 选项
     * @param report 报告
     * @return 改写结果
     */
    public static Result apply(ColumnConstraint.AutoIncrement auto, CanonicalType type,
                               String columnName, SqlDialectSpec target,
                               SqlSchemaConvertOptions options, ConversionReport.Builder report) {
        if (auto == null) {
            return new Result(null, null, false);
        }
        String loc = columnName == null ? "" : columnName;
        SqlDialect family = target == null ? SqlDialect.MYSQL : target.typeFamily();
        switch (family) {
            case MYSQL:
            case H2:
                return new Result("AUTO_INCREMENT", null, false);
            case POSTGRES:
                return postgres(auto, type, options);
            case ORACLE12:
            case ANSI:
            case DB2:
                return generated(auto);
            case DAMENG:
                // 达梦：IDENTITY 必须写在 PRIMARY KEY 之后，写成 NOT NULL IDENTITY PRIMARY KEY 会语法错
                return new Result("IDENTITY", null, false, true);
            case ORACLE:
                report.warn(ConversionWarning.Severity.MANUAL_ACTION_REQUIRED, loc,
                        "Oracle \u226411g 无法自动生成 IDENTITY，需手工创建 SEQUENCE + TRIGGER");
                return new Result(null, null, true);
            case SQLSERVER:
                return sqlServer(auto);
            case SQLITE:
                // SQLite 只允许 INTEGER PRIMARY KEY AUTOINCREMENT，顺序写反会报语法错
                return new Result("AUTOINCREMENT", null, false, true);
            case HIVE:
            case CLICKHOUSE:
            case PRESTO:
            default:
                report.warn(ConversionWarning.Severity.MANUAL_ACTION_REQUIRED, loc,
                        target + " 无通用自增语法，已去掉 AUTO_INCREMENT/IDENTITY");
                return new Result(null, null, true);
        }
    }

    private static Result postgres(ColumnConstraint.AutoIncrement auto, CanonicalType type,
                                   SqlSchemaConvertOptions options) {
        if (options != null
                && options.postgresIdentityStyle()
                == SqlSchemaConvertOptions.PostgresIdentityStyle.SERIAL) {
            String serial = "SERIAL";
            if (type == CanonicalType.BIGINT) {
                serial = "BIGSERIAL";
            } else if (type == CanonicalType.SMALLINT || type == CanonicalType.TINYINT
                    || type == CanonicalType.YEAR) {
                serial = "SMALLSERIAL";
            }
            return new Result(null, serial, false);
        }
        return generated(auto);
    }

    private static Result generated(ColumnConstraint.AutoIncrement auto) {
        String mode = "ALWAYS";
        if (auto.mode() == ColumnConstraint.AutoIncrement.IdentityMode.BY_DEFAULT) {
            mode = "BY DEFAULT";
        }
        return new Result("GENERATED " + mode + " AS IDENTITY", null, false);
    }

    private static Result sqlServer(ColumnConstraint.AutoIncrement auto) {
        int seed = auto.seed() == null ? 1 : auto.seed().intValue();
        int inc = auto.increment() == null ? 1 : auto.increment().intValue();
        return new Result("IDENTITY(" + seed + "," + inc + ")", null, false);
    }
}
