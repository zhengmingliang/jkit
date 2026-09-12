package com.alianga.jkit.sql.entity.fixture;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;

/**
 * JPA 授权表：两个未命名 {@code @Index}，用于校验生成的索引名不撞车。
 *
 * @author 郑明亮
 */
@Entity
@Table(name = "t_schedule_auth",
        indexes = {@Index(columnList = "resource_id"), @Index(columnList = "permission_id")})
public class JpaAuth {
    @Id
    private String id;
    private String resourceId;
    private String permissionId;

    /**
     * @return id
     */
    public String getId() {
        return id;
    }

    /**
     * @param id id
     */
    public void setId(String id) {
        this.id = id;
    }
}
