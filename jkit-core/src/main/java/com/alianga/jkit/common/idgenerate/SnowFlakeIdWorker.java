package com.alianga.jkit.common.idgenerate;

import com.alianga.jkit.Systems;
import com.alianga.jkit.log.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 雪花算法分布式ID生成器。
 *
 * <p>支持两种位宽模式：
 * <ul>
 * <li><b>64位模式</b>（默认）：1符号 + 41时间(ms) + 5数据中心 + 5机器 + 12序列，
 * 单实例每毫秒4096个，可用约69年。</li>
 * <li><b>53位模式</b>：1符号 + ?时间(s) + 5数据中心 + 5机器 + 14序列，
 * 用于JavaScript安全整数兼容（{@code Number.MAX_SAFE_INTEGER}），
 * 单实例每秒16384个。</li>
 * </ul>
 *
 * <p>时钟回拨容错：200ms以内阻塞等待，超过则切换备用实例继续工作，不抛异常。
 *
 * <h2>基本用法</h2>
 * <pre>{@code
 * long id = SnowFlakeIdWorker.INSTANCE.nextId();
 * IdInfo info = SnowFlakeIdWorker.INSTANCE.expId(id);
 * }</pre>
 *
 * @author 郑明亮
 * @time 2021/4/29
 */
public class SnowFlakeIdWorker {
    private static final Log log = Log.get(SnowFlakeIdWorker.class);

    // ---- 位宽常量（64位 / 53位） ----

    /** 64位模式 */
    public static final int BIT_TYPE_64 = 0;
    /** 53位模式（JavaScript安全） */
    public static final int BIT_TYPE_53 = 1;

    // ---- epoch ----

    /** Twitter snowflake 纪元（2010-11-04） */
    private static final long TWEPOCH = 1288834974657L;

    /** 回拨最大等待毫秒数 */
    private static final long MAX_WAIT_TIME_MILLIS = 200L;

    /** 进入新时间单位时序列号的随机起始上界（取 [0, 该值) ），用于打散 ID 尾数 */
    private static final long SEQUENCE_RANDOM_BOUND = 3L;

    // ---- 64位默认位分配 ----

    private static final long DEFAULT_INSTANCE_BIT_LEN = 10L;
    private static final long DEFAULT_WORKER_ID_BITS = 5L;
    private static final long DEFAULT_DATACENTER_ID_BITS = 5L;
    private static final long DEFAULT_SEQUENCE_BITS_64 = 12L;

    // ---- 53位默认位分配 ----

    private static final long DEFAULT_SEQUENCE_BITS_53 = 14L;

    // ---- 静态预计算 ----

    private static final long MAX_WORKER_ID = -1L ^ (-1L << DEFAULT_WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = -1L ^ (-1L << DEFAULT_DATACENTER_ID_BITS);

    // ---- 实例字段 ----

    private final int bitType;
    private final int generateLen;
    private final long workerId;
    private final long datacenterId;
    private final long instanceBitLen;
    private final long maxInstance;
    private final long sequenceBits;
    private final long sequenceMask;
    private final long timeLeftShiftBits;
    private final long instanceLeftShiftBits;
    private final long datacenterIdShift;
    private final long workerIdShift;

    private long sequence;
    private long lastTime = -1L;

    /** 全局共享单例（64位，自动发现实例） */
    public static final SnowFlakeIdWorker INSTANCE;

    static {
        INSTANCE = new SnowFlakeIdWorker(BIT_TYPE_64, discoverWorkerId(), discoverDatacenterId());
    }

    // ---- 构造器 ----

    /**
     * 默认构造（64位，实例自动发现）。
     */
    public SnowFlakeIdWorker() {
        this(BIT_TYPE_64, discoverWorkerId(), discoverDatacenterId());
    }

    /**
     * 指定位宽与实例（workerId + datacenterId 自动发现）。
     *
     * @param bitType {@link #BIT_TYPE_64} 或 {@link #BIT_TYPE_53}
     */
    public SnowFlakeIdWorker(int bitType) {
        this(bitType, discoverWorkerId(), discoverDatacenterId());
    }

    /**
     * 完整构造。
     *
     * @param bitType {@link #BIT_TYPE_64} 或 {@link #BIT_TYPE_53}
     * @param workerId 机器ID（0 ~ 31）
     * @param datacenterId 数据中心ID（0 ~ 31）
     */
    public SnowFlakeIdWorker(int bitType, long workerId, long datacenterId) {
        this.bitType = bitType;

        if (bitType == BIT_TYPE_64) {
            this.generateLen = 64;
            this.instanceBitLen = DEFAULT_INSTANCE_BIT_LEN;
            this.sequenceBits = DEFAULT_SEQUENCE_BITS_64;
        } else {
            this.generateLen = 53;
            this.instanceBitLen = DEFAULT_INSTANCE_BIT_LEN;
            this.sequenceBits = DEFAULT_SEQUENCE_BITS_53;
        }

        this.sequenceMask = -1L ^ (-1L << sequenceBits);
        this.maxInstance = (1L << instanceBitLen) - 1;
        this.timeLeftShiftBits = instanceBitLen + sequenceBits;
        this.instanceLeftShiftBits = sequenceBits;
        this.datacenterIdShift = sequenceBits;
        this.workerIdShift = sequenceBits + DEFAULT_DATACENTER_ID_BITS;

        // 校验
        long instanceVal = (datacenterId << DEFAULT_DATACENTER_ID_BITS) | workerId;
        if (instanceVal > maxInstance || instanceVal < 0) {
            throw new IllegalArgumentException(
                    String.format("instance value can't be greater than %d or less than 0", maxInstance));
        }
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException(
                    String.format("workerId can't be greater than %d or less than 0", MAX_WORKER_ID));
        }
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException(
                    String.format("datacenterId can't be greater than %d or less than 0", MAX_DATACENTER_ID));
        }

        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    // ---- 公开 API ----

    /**
     * 生成下一个ID。
     *
     * @return 由时间、实例（数据中心+机器）与序列号拼装出的唯一ID；同一时间单位内序列号耗尽时会自旋到下一个时间单位
     */
    public synchronized long nextId() {
        long currentTime = getTime(System.currentTimeMillis());
        long effectiveInstance = instanceValue();

        if (currentTime < lastTime) {
            long slowTime = lastTime - currentTime;
            long slowTimeMillis = bitType == BIT_TYPE_64 ? slowTime : slowTime * 1000;
            if (slowTimeMillis < MAX_WAIT_TIME_MILLIS) {
                try {
                    Thread.sleep(slowTimeMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted during clock drift wait", e);
                }
            } else {
                effectiveInstance = maxInstance;
                log.warn("Clock drifted back > {}ms, using backup instance {}",
                        MAX_WAIT_TIME_MILLIS, maxInstance);
            }
            currentTime = lastTime;
        }

        if (currentTime == lastTime) {
            sequence = (sequence + 1) & sequenceMask;
            if (sequence == 0) {
                currentTime = nextUnitTime(lastTime);
            }
        } else {
            // 进入新的时间单位时随机起始序列号：固定从 0 开始会让低并发下产出的 ID 尾数恒为 0，
            // 按 ID 取模分库分表时数据会明显倾斜
            sequence = ThreadLocalRandom.current().nextLong(SEQUENCE_RANDOM_BOUND);
        }

        lastTime = currentTime;

        return ((currentTime - getBeginTime()) << timeLeftShiftBits)
                | (effectiveInstance << instanceLeftShiftBits)
                | sequence;
    }

    /**
     * 生成字符串ID。
     *
     * @return {@link #nextId()} 的十进制字符串形式
     */
    public String nextStringId() {
        return Long.toString(nextId());
    }

    /**
     * 生成十六进制ID（16字符，不足左侧补0）。
     *
     * @return {@link #nextId()} 的十六进制字符串，固定 16 个字符，不足时左侧补 0
     */
    public String nextHex() {
        long id = nextId();
        return String.format("%016x", id);
    }

    // ---- ID 反解 ----

    /**
     * 反解ID为可读信息。
     *
     * @param id 由本生成器生成的ID
     * @return 包含偏移时间、时间戳、生成时间、实例值、序列号和位宽的 {@link IdInfo}
     */
    public IdInfo expId(long id) {
        IdInfo info = new IdInfo();
        long time = id >> timeLeftShiftBits;
        long timestamp = time * (bitType == BIT_TYPE_64 ? 1 : 1000) + TWEPOCH;
        long instance = (id >> instanceLeftShiftBits) & maxInstance;
        long seq = id & sequenceMask;

        info.setOffsetTime(time);
        info.setTimestamp(timestamp);
        info.setGenerateTime(new Date(timestamp));
        info.setInstance(instance);
        info.setSequence(seq);
        info.setBit(generateLen);
        return info;
    }

    /**
     * 反解十六进制ID。
     *
     * @param hexId 十六进制形式的ID，如 {@link #nextHex()} 的返回值
     * @return 解析后的 {@link IdInfo}
     */
    public IdInfo expId(String hexId) {
        return expId(Long.parseLong(hexId, 16));
    }

    // ---- getter ----

    /**
     * 获取机器ID。
     *
     * @return 当前实例使用的机器ID（0 ~ 31）
     */
    public long getWorkerId() { return workerId; }
    /**
     * 获取数据中心ID。
     *
     * @return 当前实例使用的数据中心ID（0 ~ 31）
     */
    public long getDatacenterId() { return datacenterId; }
    /**
     * 获取位宽模式。
     *
     * @return {@link #BIT_TYPE_64} 或 {@link #BIT_TYPE_53}
     */
    public int getBitType() { return bitType; }
    /**
     * 获取生成ID的实际位数。
     *
     * @return 64位模式返回 64，53位模式返回 53
     */
    public int getGenerateLen() { return generateLen; }

    // ---- 内部 ----

    private long instanceValue() {
        return (datacenterId << DEFAULT_DATACENTER_ID_BITS) | workerId;
    }

    private long getBeginTime() {
        return bitType == BIT_TYPE_64 ? TWEPOCH : TWEPOCH / 1000;
    }

    private long getTime(long timeMillis) {
        return bitType == BIT_TYPE_64 ? timeMillis : timeMillis / 1000;
    }

    private long nextUnitTime(long lastTime) {
        long time = getTime(System.currentTimeMillis());
        while (time <= lastTime) {
            time = getTime(System.currentTimeMillis());
        }
        return time;
    }

    // ---- 实例自动发现 ----

    private static long discoverWorkerId() {
        try {
            String ip = getLocalHostIP();
            int idx = ip.lastIndexOf('.');
            long ipPart = Integer.parseInt(ip.substring(idx + 1));
            // 掺入进程号：只取 IP 末段会让同机多进程算出相同 workerId，进而并行发出重复 ID
            long pid = Systems.getPid();
            long mixed = pid > 0 ? ipPart * 31L + pid : ipPart;
            return Math.abs(mixed) % (MAX_WORKER_ID + 1);
        } catch (Exception e) {
            log.warn("Failed to discover workerId, using 1", e);
            return 1L;
        }
    }

    private static long discoverDatacenterId() {
        try {
            InetAddress addr = InetAddress.getLocalHost();
            NetworkInterface network = NetworkInterface.getByInetAddress(addr);
            if (network == null) {
                return 1L;
            }
            byte[] mac = network.getHardwareAddress();
            if (mac == null) {
                return 1L;
            }
            long id = ((0x000000FF & (long) mac[mac.length - 2])
                    | (0x0000FF00 & (((long) mac[mac.length - 1]) << 8))) >> 6;
            return id % (MAX_DATACENTER_ID + 1);
        } catch (Exception e) {
            log.warn("Failed to discover datacenterId, using 1", e);
            return 1L;
        }
    }

    private static String getLocalHostIP() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }
}
