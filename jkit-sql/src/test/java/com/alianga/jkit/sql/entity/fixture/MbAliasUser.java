package com.alianga.jkit.sql.entity.fixture;

import com.baomidou.mybatisplus.annotation.TableId;

import org.apache.ibatis.type.Alias;

/**
 * MyBatis {@code Alias} 作表名回退，Plus {@code TableId} 标实体。
 *
 * @author 郑明亮
 */
@Alias("mb_alias_user")
public class MbAliasUser {
    @TableId
    private Long id;
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
