package com.alianga.jkit.sql.auto;

/**
 * 启动时对表结构的处理模式，对标 JPA {@code spring.jpa.hibernate.ddl-auto}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public enum SqlAutoMode {
    /**
     * 什么都不做。
     */
    NONE,
    /**
     * 只校验：缺表、缺列或类型不兼容时抛错，不改库。
     */
    VALIDATE,
    /**
     * 缺表则建、缺列则加、缺索引则建；默认不改已有列类型、不删列/表。
     */
    UPDATE,
    /**
     * 先删托管表再按实体重建（开发用）。
     */
    CREATE,
    /**
     * 启动时同 {@link #CREATE}，JVM 退出时再删表。
     */
    CREATE_DROP;

    /**
     * 按名称解析，无法识别时返回 {@link #UPDATE}。
     *
     * @param name 名称，空则 UPDATE
     * @return 模式
     */
    public static SqlAutoMode fromName(String name) {
        if (name == null) {
            return UPDATE;
        }
        String n = name.trim();
        if (n.isEmpty()) {
            return UPDATE;
        }
        String key = n.replace('-', '_').replace(' ', '_').toUpperCase();
        if ("VALIDATE".equals(key) || "CHECK".equals(key)) {
            return VALIDATE;
        }
        if ("UPDATE".equals(key) || "UPD".equals(key)) {
            return UPDATE;
        }
        if ("CREATE".equals(key)) {
            return CREATE;
        }
        if ("CREATE_DROP".equals(key) || "CREATEDROP".equals(key)) {
            return CREATE_DROP;
        }
        if ("NONE".equals(key) || "OFF".equals(key) || "FALSE".equals(key)) {
            return NONE;
        }
        return UPDATE;
    }
}
