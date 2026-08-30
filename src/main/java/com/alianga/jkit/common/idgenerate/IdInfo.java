package com.alianga.jkit.common.idgenerate;

import java.util.Date;

/**
 * 反解后的雪花ID信息。
 */
public class IdInfo {
    /** 时间位偏移量 */
    private long offsetTime;

    /** 时间戳（毫秒） */
    private long timestamp;

    /** 创建时间 */
    private Date generateTime;

    /** 实例（数据中心位+机器位） */
    private long instance;

    /** 序列值 */
    private long sequence;

    /** 算法位数（64 或 53） */
    private int bit;

    /**
     * 获取时间位偏移量。
     *
     * @return 反解得到的时间位偏移量（相对起始纪元的毫秒差）
     */
    public long getOffsetTime() {
        return offsetTime;
    }

    /**
     * 设置时间位偏移量。
     *
     * @param offsetTime 时间位偏移量
     */
    public void setOffsetTime(long offsetTime) {
        this.offsetTime = offsetTime;
    }

    /**
     * 获取生成该ID时的时间戳。
     *
     * @return 生成时刻的毫秒时间戳
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 设置生成该ID时的时间戳。
     *
     * @param timestamp 毫秒时间戳
     */
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * 获取生成该ID的时间。
     *
     * @return 生成时间，未设置时为 {@code null}
     */
    public Date getGenerateTime() {
        return generateTime;
    }

    /**
     * 设置生成该ID的时间。
     *
     * @param generateTime 生成时间
     */
    public void setGenerateTime(Date generateTime) {
        this.generateTime = generateTime;
    }

    /**
     * 获取实例标识。
     *
     * @return 实例标识，由数据中心位与机器位组合而成
     */
    public long getInstance() {
        return instance;
    }

    /**
     * 设置实例标识。
     *
     * @param instance 实例标识（数据中心位+机器位）
     */
    public void setInstance(long instance) {
        this.instance = instance;
    }

    /**
     * 获取同一毫秒内的序列值。
     *
     * @return 序列值
     */
    public long getSequence() {
        return sequence;
    }

    /**
     * 设置同一毫秒内的序列值。
     *
     * @param sequence 序列值
     */
    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    /**
     * 获取生成该ID所使用的算法位数。
     *
     * @return 算法位数，取值为 64 或 53
     */
    public int getBit() {
        return bit;
    }

    /**
     * 设置生成该ID所使用的算法位数。
     *
     * @param bit 算法位数（64 或 53）
     */
    public void setBit(int bit) {
        this.bit = bit;
    }

    @Override
    public String toString() {
        return "IdInfo{" +
                "offsetTime=" + offsetTime +
                ", timestamp=" + timestamp +
                ", generateTime=" + generateTime +
                ", instance=" + instance +
                ", sequence=" + sequence +
                ", bit=" + bit +
                '}';
    }
}
