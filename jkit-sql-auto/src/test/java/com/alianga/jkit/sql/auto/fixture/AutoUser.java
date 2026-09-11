package com.alianga.jkit.sql.auto.fixture;

import com.alianga.jkit.sql.entity.SqlColumn;
import com.alianga.jkit.sql.entity.SqlGenerated;
import com.alianga.jkit.sql.entity.SqlId;
import com.alianga.jkit.sql.entity.SqlTable;

/**
 * 自动建表测试实体。
 *
 * @author 郑明亮
 */
@SqlTable(name = "auto_user", indexes = {"idx_auto_email:email"})
public class AutoUser {
    @SqlId
    @SqlGenerated
    private Long id;

    @SqlColumn(name = "user_name", length = 32, nullable = false)
    private String name;

    @SqlColumn(length = 64, unique = true)
    private String email;

    private Integer age;

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
     * @return email
     */
    public String getEmail() {
        return email;
    }

    /**
     * @param email email
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * @return age
     */
    public Integer getAge() {
        return age;
    }

    /**
     * @param age age
     */
    public void setAge(Integer age) {
        this.age = age;
    }
}
