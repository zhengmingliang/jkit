package com.alianga.jkit.json;

/**
 * 不同JDK环境下字符串结构抽象处理
 *
 * @time 2022/8/21 19:26
 */
public interface CharSource {
    /**
     * 返回输入字符串
     *
     * @return 构造该字符源时传入的原始字符串
     */
    String input();

    /**
     * 构建子串
     *
     * @param bytes      原始字符串对应的字节数组
     * @param beginIndex 子串起始下标（包含）
     * @param endIndex   子串结束下标（不包含）
     * @return 按当前编码结构从字节数组中还原出的子串
     */
    String substring(byte[] bytes, int beginIndex, int endIndex);

    /**
     * 将字串写入writer
     *
     * @param writer 目标字符输出器
     * @param buf    原始字符串对应的字节数组
     * @param offset 写出内容在字符串中的起始偏移量
     * @param len    写出的字符（或字节）长度
     */
    void writeString(JSONCharArrayWriter writer, byte[] buf, int offset, int len);
}
