package com.alianga.jkit.http;

/**
 * 上传文件的相关信息。
 *
 * @author 郑明亮
 * @version 1.0
 */
public class UploadInfo {
    /**
     * 上传文件时作为请求的 key 值
     */
    private String key = "file";
    /**
     * 上传文件的绝对路径
     */
    private String filePath;
    /**
     * 上传文件的文件名称
     */
    private String fileName;
    /**
     * 上传文件类型（MIME），为空时按文件探测
     */
    private String mediaType;

    /**
     * @param filePath 本地文件路径
     */
    public UploadInfo(String filePath) {
        this.filePath = filePath;
    }

    /**
     * @param key 表单字段名
     * @param filePath 本地文件路径
     */
    public UploadInfo(String key, String filePath) {
        this.key = key;
        this.filePath = filePath;
    }

    /**
     * @param key 表单字段名
     * @param filePath 本地文件路径
     * @param fileName 上传时使用的文件名
     */
    public UploadInfo(String key, String filePath, String fileName) {
        this.key = key;
        this.filePath = filePath;
        this.fileName = fileName;
    }

    /**
     * @return 表单字段名
     */
    public String getKey() {
        return key;
    }

    /**
     * @param key 表单字段名
     */
    public void setKey(String key) {
        this.key = key;
    }

    /**
     * @return 本地文件路径
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * @param filePath 本地文件路径
     */
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    /**
     * @return 上传文件名
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * @param fileName 上传文件名
     */
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    /**
     * @return MIME 类型
     */
    public String getMediaType() {
        return mediaType;
    }

    /**
     * @param mediaType MIME 类型
     */
    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }
}
