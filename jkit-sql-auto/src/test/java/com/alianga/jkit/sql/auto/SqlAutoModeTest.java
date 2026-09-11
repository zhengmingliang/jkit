package com.alianga.jkit.sql.auto;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link SqlAutoMode} 名称解析。
 *
 * @author 郑明亮
 */
public class SqlAutoModeTest {

    @Test
    public void fromNameAliases() {
        assertEquals(SqlAutoMode.UPDATE, SqlAutoMode.fromName(null));
        assertEquals(SqlAutoMode.UPDATE, SqlAutoMode.fromName(""));
        assertEquals(SqlAutoMode.UPDATE, SqlAutoMode.fromName("update"));
        assertEquals(SqlAutoMode.VALIDATE, SqlAutoMode.fromName("validate"));
        assertEquals(SqlAutoMode.VALIDATE, SqlAutoMode.fromName("check"));
        assertEquals(SqlAutoMode.CREATE, SqlAutoMode.fromName("CREATE"));
        assertEquals(SqlAutoMode.CREATE_DROP, SqlAutoMode.fromName("create-drop"));
        assertEquals(SqlAutoMode.CREATE_DROP, SqlAutoMode.fromName("create_drop"));
        assertEquals(SqlAutoMode.NONE, SqlAutoMode.fromName("none"));
        assertEquals(SqlAutoMode.NONE, SqlAutoMode.fromName("off"));
        assertEquals(SqlAutoMode.UPDATE, SqlAutoMode.fromName("whatever"));
    }
}
