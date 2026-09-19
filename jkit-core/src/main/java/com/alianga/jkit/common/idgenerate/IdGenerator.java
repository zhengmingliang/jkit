package com.alianga.jkit.common.idgenerate;

import java.io.InputStream;
import java.util.Properties;
import java.util.UUID;

/**
 * 分布式全局ID生成器的配置驱动入口。
 *
 * <p>读取 classpath 下的 {@code generate.properties} 配置（可选），
 * 内部委托给 {@link SnowFlakeIdWorker}。
 *
 * <pre>
 * id.generate.algorithm=snowflake
 * id.generate.snowflake.bit.type=0 # 0=64位 1=53位
 * id.generate.snowflake.instance=0 # 实例ID（worker+datacenter合成）
 * id.generate.snowflake.instance.auto-discover=true
 * </pre>
 */
public final class IdGenerator {
    private static final String ALGORITHM_SNOWFLAKE = "snowflake";

    private static int snowflakeBitType = SnowFlakeIdWorker.BIT_TYPE_64;
    private static boolean snowflakeAutoDiscover;
    private static int snowflakeInstance;

    private static SnowFlakeIdWorker worker;

    static {
        loadConfig();
        if (worker == null) {
            worker = new SnowFlakeIdWorker(snowflakeBitType);
        }
    }

    private IdGenerator() {
    }

    private static void loadConfig() {
        try {
            Properties props = new Properties();
            InputStream is = IdGenerator.class.getResourceAsStream("/generate.properties");
            if (is != null) {
                props.load(is);
                String algorithm = props.getProperty("id.generate.algorithm");
                if (ALGORITHM_SNOWFLAKE.equals(algorithm)) {
                    snowflakeBitType = props.containsKey("id.generate.snowflake.bit.type")
                            ? Integer.parseInt(props.getProperty("id.generate.snowflake.bit.type").trim())
                            : SnowFlakeIdWorker.BIT_TYPE_64;
                    snowflakeAutoDiscover =
                            "true".equals(props.getProperty("id.generate.snowflake.instance.auto-discover"));
                    snowflakeInstance = props.containsKey("id.generate.snowflake.instance")
                            ? Integer.parseInt(props.getProperty("id.generate.snowflake.instance").trim())
                            : 0;
                }
            }
        } catch (Exception ex) {
            // 配置读取失败时回退默认，不阻断启动
            ex.printStackTrace();
        }
    }

    /**
     * 获取当前共享 worker。
     *
     * @return 当前的雪花算法 ID 生成器实例
     */
    public static SnowFlakeIdWorker getInstance() {
        return worker;
    }

    /**
     * 生成唯一ID。
     *
     * @return 由当前 worker 生成的全局唯一 ID
     */
    public static long id() {
        return worker.nextId();
    }

    /**
     * 生成十六进制ID（16字符）。
     *
     * @return 由当前 worker 生成的 16 位十六进制字符串形式的唯一 ID
     */
    public static String hex() {
        return worker.nextHex();
    }

    /**
     * 生成一个 ULID（26 字符，按时间有序）。
     *
     * <p>需要「单 JVM 内严格递增」时用 {@link ULID#nextMonotonic()}。
     *
     * @return ULID 字符串
     * @since 2.0.2
     */
    public static String ulid() {
        return ULID.next();
    }

    /**
     * 生成一个 UUIDv7（毫秒时间戳在高 48 位，索引写入接近顺序追加）。
     *
     * @return UUIDv7 实例
     * @since 2.0.2
     */
    public static UUID uuidV7() {
        return UUIDv7.next();
    }

    /**
     * 生成一个 UUIDv7 的字符串形式。
     *
     * @return UUID 字符串
     * @since 2.0.2
     */
    public static String uuidV7String() {
        return UUIDv7.nextString();
    }

    /**
     * 设置默认实例ID（worker + datacenter 合成）。
     *
     * @param instanceId 实例ID，低 5 位作为 workerId，其余高位作为 datacenterId
     */
    public static void setDefaultInstanceId(int instanceId) {
        worker = new SnowFlakeIdWorker(snowflakeBitType, instanceId & 31, instanceId >>> 5);
    }
}
