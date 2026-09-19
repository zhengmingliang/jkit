package com.alianga.jkit.sql.auto.fixture;

import com.alianga.jkit.sql.entity.SqlColumn;
import com.alianga.jkit.sql.entity.SqlGenerated;
import com.alianga.jkit.sql.entity.SqlId;
import com.alianga.jkit.sql.entity.SqlTable;

/**
 * 表名 / 列名撞数据库保留字的测试实体（order / desc / value 均为常见保留字）。
 *
 * @author 郑明亮
 */
@SqlTable(name = "order", indexes = {"desc"})
public class AutoOrder {
    @SqlId
    @SqlGenerated
    private Long id;

    @SqlColumn(length = 128)
    private String desc;

    @SqlColumn(length = 32)
    private String value;

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
     * @return desc
     */
    public String getDesc() {
        return desc;
    }

    /**
     * @param desc desc
     */
    public void setDesc(String desc) {
        this.desc = desc;
    }

    /**
     * @return value
     */
    public String getValue() {
        return value;
    }

    /**
     * @param value value
     */
    public void setValue(String value) {
        this.value = value;
    }
}
