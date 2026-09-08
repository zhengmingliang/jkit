package com.alianga.jkit.sql;

import org.junit.Test;

import static org.junit.Assert.fail;

/**
 * 登记从 common-model 语料中剔除或仍不支持的样本，避免静默 skip。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class CommonModelSqlKnownGapsTest {

    /**
     * 数字开头裸标识符须加引号；电子表格 {@code <sheet>} 占位不是标准 SQL。
     */
    @Test
    public void digitLeadingIdentAndSheetMarkupStillFail() {
        assertStillFails(SqlDialect.MYSQL, "SELECT DISTINCT 1019使用.年 FROM 1019使用");
        assertStillFails(SqlDialect.MYSQL, "select * from <20241230.1>");
    }

    private static void assertStillFails(SqlDialect dialect, String sample) {
        try {
            SQL.parse(sample, dialect);
            fail("expected still unsupported: " + sample);
        } catch (SqlParseException ex) {
            // expected
        }
    }
}
