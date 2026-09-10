package com.alianga.jkit.sql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link SqlWall} 可配置规则（对标 Druid WallFilter 子集）。
 *
 * <p>{@link #defaults()} 打开安全关键检查；{@code denyUnion} / {@code denyInformationSchema}
 * 默认关闭，以免误伤合法业务 SQL。{@code selectOnly} 默认关闭。</p>
 *
 * <p>内置开关之外可用 {@link #rules(SqlWallRule...)} 追加自定义检查（在全部内置检查之后执行）。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlWallConfig {
    private boolean denyMultiStatement = true;
    private boolean denyCommentBypass = true;
    private boolean denyAlwaysTrue = true;
    private boolean denyDeleteUpdateWithoutWhere = true;
    private boolean denyDdl = true;
    private boolean denyDangerousFunctions = true;
    private boolean denyIntoOutfile = true;
    private boolean denyUnion;
    private boolean denyInformationSchema;
    private boolean selectOnly;
    private List<SqlWallRule> rules = Collections.emptyList();

    /**
     * @return 默认安全配置
     */
    public static SqlWallConfig defaults() {
        return new SqlWallConfig();
    }

    /**
     * @return 全部关闭（仅手工逐项打开时使用）
     */
    public static SqlWallConfig none() {
        SqlWallConfig c = new SqlWallConfig();
        c.denyMultiStatement = false;
        c.denyCommentBypass = false;
        c.denyAlwaysTrue = false;
        c.denyDeleteUpdateWithoutWhere = false;
        c.denyDdl = false;
        c.denyDangerousFunctions = false;
        c.denyIntoOutfile = false;
        c.denyUnion = false;
        c.denyInformationSchema = false;
        c.selectOnly = false;
        return c;
    }

    /** @return 是否拒绝多语句 */
    public boolean denyMultiStatement() {
        return denyMultiStatement;
    }

    /** @param denyMultiStatement 多语句 @return this */
    public SqlWallConfig denyMultiStatement(boolean denyMultiStatement) {
        this.denyMultiStatement = denyMultiStatement;
        return this;
    }

    /** @return 是否拒绝注释绕过 */
    public boolean denyCommentBypass() {
        return denyCommentBypass;
    }

    /** @param denyCommentBypass 注释绕过 @return this */
    public SqlWallConfig denyCommentBypass(boolean denyCommentBypass) {
        this.denyCommentBypass = denyCommentBypass;
        return this;
    }

    /** @return 是否拒绝永远真条件 */
    public boolean denyAlwaysTrue() {
        return denyAlwaysTrue;
    }

    /** @param denyAlwaysTrue 永远真 @return this */
    public SqlWallConfig denyAlwaysTrue(boolean denyAlwaysTrue) {
        this.denyAlwaysTrue = denyAlwaysTrue;
        return this;
    }

    /** @return 是否拒绝无 WHERE 的 DELETE/UPDATE */
    public boolean denyDeleteUpdateWithoutWhere() {
        return denyDeleteUpdateWithoutWhere;
    }

    /** @param denyDeleteUpdateWithoutWhere 无 WHERE @return this */
    public SqlWallConfig denyDeleteUpdateWithoutWhere(boolean denyDeleteUpdateWithoutWhere) {
        this.denyDeleteUpdateWithoutWhere = denyDeleteUpdateWithoutWhere;
        return this;
    }

    /**
     * 拒绝 DDL：CREATE / DROP / ALTER / TRUNCATE。
     *
     * @return 是否拒绝 DDL
     */
    public boolean denyDdl() {
        return denyDdl;
    }

    /** @param denyDdl DDL @return this */
    public SqlWallConfig denyDdl(boolean denyDdl) {
        this.denyDdl = denyDdl;
        return this;
    }

    /**
     * 拒绝危险函数：SLEEP / BENCHMARK / LOAD_FILE 等。
     *
     * @return 是否拒绝危险函数
     */
    public boolean denyDangerousFunctions() {
        return denyDangerousFunctions;
    }

    /** @param denyDangerousFunctions 危险函数 @return this */
    public SqlWallConfig denyDangerousFunctions(boolean denyDangerousFunctions) {
        this.denyDangerousFunctions = denyDangerousFunctions;
        return this;
    }

    /**
     * 拒绝 {@code SELECT … INTO OUTFILE/DUMPFILE}。
     *
     * @return 是否拒绝 INTO OUTFILE
     */
    public boolean denyIntoOutfile() {
        return denyIntoOutfile;
    }

    /** @param denyIntoOutfile INTO OUTFILE @return this */
    public SqlWallConfig denyIntoOutfile(boolean denyIntoOutfile) {
        this.denyIntoOutfile = denyIntoOutfile;
        return this;
    }

    /**
     * 拒绝 UNION（含合法业务 UNION；默认 false）。
     *
     * @return 是否拒绝 UNION
     */
    public boolean denyUnion() {
        return denyUnion;
    }

    /** @param denyUnion UNION @return this */
    public SqlWallConfig denyUnion(boolean denyUnion) {
        this.denyUnion = denyUnion;
        return this;
    }

    /**
     * 拒绝访问 {@code information_schema}（可选，默认 false）。
     *
     * @return 是否拒绝 information_schema
     */
    public boolean denyInformationSchema() {
        return denyInformationSchema;
    }

    /** @param denyInformationSchema information_schema @return this */
    public SqlWallConfig denyInformationSchema(boolean denyInformationSchema) {
        this.denyInformationSchema = denyInformationSchema;
        return this;
    }

    /**
     * 仅允许 SELECT（含 WITH SELECT）；其它语句一律违规。
     *
     * @return 是否仅 SELECT
     */
    public boolean selectOnly() {
        return selectOnly;
    }

    /** @param selectOnly 仅 SELECT @return this */
    public SqlWallConfig selectOnly(boolean selectOnly) {
        this.selectOnly = selectOnly;
        return this;
    }

    /**
     * @return 自定义规则（默认空列表，只读）
     */
    public List<SqlWallRule> rules() {
        return rules;
    }

    /**
     * 注册自定义规则：在全部内置检查之后、按注册顺序执行。
     *
     * @param rules 规则；null 或空数组清空
     * @return this
     */
    public SqlWallConfig rules(SqlWallRule... rules) {
        if (rules == null || rules.length == 0) {
            this.rules = Collections.emptyList();
        } else {
            this.rules = Collections.unmodifiableList(new ArrayList<SqlWallRule>(Arrays.asList(rules)));
        }
        return this;
    }
}
