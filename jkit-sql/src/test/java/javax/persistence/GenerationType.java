package javax.persistence;

/**
 * 测试桩：JPA {@code GenerationType}。
 *
 * @author 郑明亮
 */
public enum GenerationType {
    /** 由提供方选择。 */
    AUTO,
    /** 数据库 IDENTITY。 */
    IDENTITY,
    /** 序列。 */
    SEQUENCE,
    /** 表。 */
    TABLE,
    /** UUID（Jakarta 3.1）。 */
    UUID
}
