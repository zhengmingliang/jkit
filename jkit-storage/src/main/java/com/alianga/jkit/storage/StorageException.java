package com.alianga.jkit.storage;

/**
 * 统一存储异常：所有平台、所有操作的失败都以它抛出，携带 {@link StorageErrorCode}、
 * 出错的对象路径与平台原始信息（message 里保留底层原因摘要）。
 *
 * <p>适配器内部捕获的受检异常（IOException 等）一律包装为本异常，上层无需再处理
 * 任何平台特有异常类型。</p>
 *
 * @author 郑明亮
 */
public class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final StorageErrorCode code;
    /** 出错的对象路径（相对 bucket / basePath，"-" 表示与具体路径无关）。 */
    private final String path;

    public StorageException(StorageErrorCode code, String message) {
        this(code, message, "-", null);
    }

    public StorageException(StorageErrorCode code, String message, String path) {
        this(code, message, path, null);
    }

    public StorageException(StorageErrorCode code, String message, String path, Throwable cause) {
        super(message, cause);
        this.code = code == null ? StorageErrorCode.IO_ERROR : code;
        this.path = path == null ? "-" : path;
    }

    public StorageErrorCode code() {
        return code;
    }

    /**
     * 出错对象路径；与具体路径无关的操作返回 "-"。
     *
     * @return 路径或 "-"
     */
    public String path() {
        return path;
    }

    public static StorageException invalidConfig(String message) {
        return new StorageException(StorageErrorCode.INVALID_CONFIG, message);
    }

    public static StorageException notFound(String path) {
        return new StorageException(StorageErrorCode.NOT_FOUND, "对象不存在: " + path, path);
    }

    public static StorageException alreadyExists(String path) {
        return new StorageException(StorageErrorCode.ALREADY_EXISTS, "对象已存在: " + path, path);
    }

    public static StorageException authFailed(String message) {
        return new StorageException(StorageErrorCode.AUTH_FAILED, message);
    }

    public static StorageException connectFailed(String message) {
        return new StorageException(StorageErrorCode.CONNECT_FAILED, message);
    }

    public static StorageException timeout(String message) {
        return new StorageException(StorageErrorCode.TIMEOUT, message);
    }

    public static StorageException conflict(String message, String path) {
        return new StorageException(StorageErrorCode.CONFLICT, message, path);
    }

    public static StorageException io(String message, String path, Throwable cause) {
        return new StorageException(StorageErrorCode.IO_ERROR, message, path, cause);
    }

    public static StorageException serverError(String message, String path) {
        return new StorageException(StorageErrorCode.SERVER_ERROR, message, path);
    }

    public static StorageException unsupported(String message) {
        return new StorageException(StorageErrorCode.UNSUPPORTED, message);
    }
}
