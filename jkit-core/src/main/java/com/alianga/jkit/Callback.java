package com.alianga.jkit;
/**
 * Created by 郑明亮 on 2019/11/12 15:57.
 */

/**
 * 2019/11/12 15:57 <br>
 * <p>
 * 文件读取回调
 *
 * @author 郑明亮
 * @version 1.0
 */
public interface Callback {
    /**
     * 处理读取到的一批（或一行）文件内容，不包含首行。
     *
     * @param data 本次读取到的文件内容
     */
    void apply(String data);

    /**
     * 处理文件首行内容，读取开始时先被回调一次。
     *
     * @param firstLine 文件的第一行内容，文件为空时可能为 {@code null} 或空字符串
     */
    void getFirstLine(String firstLine);
}
