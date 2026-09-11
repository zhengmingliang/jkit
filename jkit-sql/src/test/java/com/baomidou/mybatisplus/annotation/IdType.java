package com.baomidou.mybatisplus.annotation;

/**
 * 测试桩：MyBatis-Plus {@code IdType}。
 *
 * @author 郑明亮
 */
public enum IdType {
    /** 数据库自增。 */
    AUTO,
    /** 不处理。 */
    NONE,
    /** 自行输入。 */
    INPUT,
    /** 雪花/assign。 */
    ASSIGN_ID,
    /** UUID。 */
    ASSIGN_UUID
}
