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
     * 电子表格 {@code <sheet>} 占位不是标准 SQL（数字开头裸标识符已支持）。
     */
    @Test
    public void sheetMarkupStillFails() {
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
