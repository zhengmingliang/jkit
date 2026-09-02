/**
 * Created by 郑明亮 on 2022/8/27 11:05.
 */
package com.alianga.jkit.http;

import java.net.URL;
import java.nio.file.Path;

/**
 * <p> 下载参数</p>
 *
 * @author 郑明亮
 * @version 1.0.0
 * @time 2022/8/27 11:05
 */
public class DownloadParam {
    private String fileName;
    private long fileSize;
    private long saveSize;
    private final Status status = Status.WAITING;
    private Path localPath;
    private URL location;
    private String description;
    private URL refLocation;
    private long lastConnectTime;

    /**
     * 下载任务状态
     */
    public enum Status {
        /** 等待下载 */ WAITING("等待"), /** 下载中 */ RUNNING("运行中"), /** 已停止 */ STOPPED("停止"), /** 已完成 */ FINISHED("完成");
        private final String value;

        Status(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
