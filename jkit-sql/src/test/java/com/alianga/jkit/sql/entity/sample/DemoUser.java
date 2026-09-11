package com.alianga.jkit.sql.entity.sample;

import com.alianga.jkit.sql.entity.SqlColumn;
import com.alianga.jkit.sql.entity.SqlGenerated;
import com.alianga.jkit.sql.entity.SqlId;
import com.alianga.jkit.sql.entity.SqlTable;
import com.alianga.jkit.sql.entity.SqlTransient;

import java.math.BigDecimal;

/**
 * 扫描与 DDL 生成用样例实体。
 *
 * @author 郑明亮
 */
@SqlTable(name = "demo_user")
public class DemoUser {
    @SqlId
    @SqlGenerated
    private Long id;

    @SqlColumn(name = "user_name", length = 32, nullable = false)
    private String name;

    @SqlColumn(length = 64, unique = true)
    private String email;

    private Integer age;

    @SqlColumn(precision = 10, scale = 2)
    private BigDecimal amount;

    @SqlTransient
    private String cacheOnly;

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

    /**
     * @return amount
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * @param amount amount
     */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
