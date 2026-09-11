package com.alianga.jkit.sql.entity.fixture;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * MyBatis-Plus 用户。
 *
 * @author 郑明亮
 */
@TableName("mp_user")
public class MpUser {
    @TableId(value = "uid", type = IdType.AUTO)
    private Long id;
    @TableField("user_name")
    private String name;
    @TableField(exist = false)
    private String cache;

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
}
