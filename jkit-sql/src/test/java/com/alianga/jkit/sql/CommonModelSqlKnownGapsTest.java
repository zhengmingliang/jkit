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
     * 默认关闭模板占位时，电子表格 {@code <sheet>} 仍应失败；
     * 启用见 {@link SqlTemplatePlaceholderTest} / {@link SqlPlaceholders#angle()}。
     */
    @Test
    public void sheetMarkupStillFailsByDefault() {
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
