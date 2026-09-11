package com.alianga.jkit.sql.entity.fixture;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;

/**
 * JPA 组织。
 *
 * @author 郑明亮
 */
@Entity
@Table(name = "jpa_org", indexes = {@Index(name = "idx_org_code", columnList = "code")})
public class JpaOrg {
    @Id
    private Long id;
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
