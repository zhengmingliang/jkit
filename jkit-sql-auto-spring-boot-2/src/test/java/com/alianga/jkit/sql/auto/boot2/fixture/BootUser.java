package com.alianga.jkit.sql.auto.boot2.fixture;

import com.alianga.jkit.sql.entity.SqlColumn;
import com.alianga.jkit.sql.entity.SqlGenerated;
import com.alianga.jkit.sql.entity.SqlId;
import com.alianga.jkit.sql.entity.SqlTable;

/**
 * Boot 2 测试实体。
 *
 * @author 郑明亮
 */
@SqlTable(name = "boot2_user")
public class BootUser {
    @SqlId
    @SqlGenerated
    private Long id;

    @SqlColumn(name = "user_name", length = 32, nullable = false)
    private String name;

    private Integer age;
}
