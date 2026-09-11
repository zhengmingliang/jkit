package com.alianga.jkit.sql.auto.fixture;

import com.alianga.jkit.sql.entity.SqlColumn;
import com.alianga.jkit.sql.entity.SqlGenerated;
import com.alianga.jkit.sql.entity.SqlId;
import com.alianga.jkit.sql.entity.SqlTable;

/**
 * 引用 {@link AutoOrg} 的员工表。
 *
 * @author 郑明亮
 */
@SqlTable(name = "auto_staff")
public class AutoStaff {
    @SqlId
    @SqlGenerated
    private Long id;

    @SqlColumn(nullable = false, length = 32)
    private String name;

    private AutoOrg org;

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
     * @return name
     */
    public String getName() {
        return name;
    }

    /**
     * @param name name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return org
     */
    public AutoOrg getOrg() {
        return org;
    }

    /**
     * @param org org
     */
    public void setOrg(AutoOrg org) {
        this.org = org;
    }
}
