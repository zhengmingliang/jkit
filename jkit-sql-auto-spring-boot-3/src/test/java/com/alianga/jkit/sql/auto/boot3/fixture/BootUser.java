package com.alianga.jkit.sql.auto.boot3.fixture;

import com.alianga.jkit.sql.entity.SqlColumn;
import com.alianga.jkit.sql.entity.SqlGenerated;
import com.alianga.jkit.sql.entity.SqlId;
import com.alianga.jkit.sql.entity.SqlTable;

/**
 * Boot 3 测试实体。
 *
 * @author 郑明亮
 */
@SqlTable(name = "boot3_user")
public class BootUser {
    @SqlId
    @SqlGenerated
    private Long id;

    @SqlColumn(name = "user_name", length = 32, nullable = false)
    private String name;

    private Integer age;
}
