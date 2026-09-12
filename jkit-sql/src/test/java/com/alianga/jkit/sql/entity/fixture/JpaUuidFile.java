package com.alianga.jkit.sql.entity.fixture;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * 字符串 UUID 主键：对应 {@code @GeneratedValue(generator = "system-uuid")}，
 * 不能生成数据库 IDENTITY。
 *
 * @author 郑明亮
 */
@Entity
@Table(name = "file_storage")
public class JpaUuidFile {
    @Id
    @Column(name = "id", length = 32)
    @GeneratedValue(generator = "system-uuid")
    private String id;

    @Column(name = "source_desc")
    private String desc;

    @Column(name = "source_name")
    private String fileSourceName;

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
