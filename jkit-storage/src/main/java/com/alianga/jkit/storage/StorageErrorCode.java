package com.alianga.jkit.storage;

/**
 * 存储操作错误码：所有平台适配器都把底层差异翻译到这组统一错误上，
 * 上层业务只需要按错误码分支，不必感知 S3 XML 错误、FTP 5xx、HTTP 状态码等细节。
 *
 * @author 郑明亮
 */
public enum StorageErrorCode {
    /** 配置缺失 / 非法（缺 bucket、端口不是数字等）。 */
    INVALID_CONFIG,
    /** 鉴权失败：密钥错误、无权限、签名不匹配。 */
    AUTH_FAILED,
    /** 无法建立连接：DNS 解析失败、连不上、TLS 握手失败。 */
    CONNECT_FAILED,
    /** 连接或读写超时。 */
    TIMEOUT,
    /** 目标不存在：文件 / 目录 / bucket 不存在。 */
    NOT_FOUND,
    /** 目标已存在且操作不允许覆盖（如 mkdir、rename 到已存在目标）。 */
    ALREADY_EXISTS,
    /** 服务端冲突：并发写、前置条件失败（412）、目录非空等。 */
    CONFLICT,
    /** 传输或读写过程中的 I/O 错误。 */
    IO_ERROR,
    /** 服务端限流（429 / 慢速拒绝），可退避重试。 */
    RATE_LIMITED,
    /** 服务端返回了未被归类为以上情形的错误（5xx 等）。 */
    SERVER_ERROR,
    /** 当前平台不支持该操作（如 FastDFS 不支持列表）。 */
    UNSUPPORTED,
    /** 操作被调用方取消。 */
    CANCELLED
}
