package com.alianga.jkit.sql.entity.fixture;

import com.alianga.jkit.sql.entity.SqlId;
import com.alianga.jkit.sql.entity.SqlTable;

/**
 * 使用非 Hibernate 包下的 {@code @Comment}。
 *
 * @author 郑明亮
 */
@Comment("自定义表注释")
@SqlTable(name = "any_cmt")
public class AnyCommentUser {
    @SqlId
    private Long id;
    @Comment("自定义列注释")
    private String name;

    /**
     * @return id
     */
    public Long getId() {
        return id;
    }

    /**
     * @param id id
     */
    public void setId(Long id) {
        this.id = id;
    }
}
