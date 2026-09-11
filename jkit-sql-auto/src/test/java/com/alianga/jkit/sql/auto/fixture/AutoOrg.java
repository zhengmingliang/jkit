package com.alianga.jkit.sql.auto.fixture;

import com.alianga.jkit.sql.entity.SqlColumn;
import com.alianga.jkit.sql.entity.SqlGenerated;
import com.alianga.jkit.sql.entity.SqlId;
import com.alianga.jkit.sql.entity.SqlTable;

/**
 * 被引用表，用于外键排序。
 *
 * @author 郑明亮
 */
@SqlTable(name = "auto_org")
public class AutoOrg {
    @SqlId
    @SqlGenerated
    private Long id;

    @SqlColumn(nullable = false, length = 16)
    private String code;

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

    /**
     * @return code
     */
    public String getCode() {
        return code;
    }

    /**
     * @param code code
     */
    public void setCode(String code) {
        this.code = code;
    }
}
