package com.alianga.jkit.json;

/**
 * JSON Schema 校验结果，包含是否通过、错误信息以及出错的节点路径。
 */
public class JSONSchemaResult {
    /** 校验通过且无附加信息的结果常量。 */
    public static final JSONSchemaResult SUCCESS = new JSONSchemaResult(true, null);
    /** 校验通过但表示跳过校验的结果常量。 */
    public static final JSONSchemaResult SUCCESS_SKIP = new JSONSchemaResult(true, "skip");
    /** 校验失败的通用结果常量。 */
    public static final JSONSchemaResult FAILURE = new JSONSchemaResult(false, "failure");
    /** 因类型不匹配导致校验失败的结果常量。 */
    public static final JSONSchemaResult TYPE_NOT_MATCH = new JSONSchemaResult(false, "type not match");

    private final boolean success;
    private final String message;
    private final String path;

    /**
     * 构造一个不带节点路径的校验结果。
     *
     * @param success 是否校验通过
     * @param message 校验信息，校验通过时可以为 {@code null}
     */
    public JSONSchemaResult(boolean success, String message) {
        this(success, message, null);
    }

    /**
     * 构造一个带节点路径的校验结果。
     *
     * @param success 是否校验通过
     * @param message 校验信息，校验通过时可以为 {@code null}
     * @param path    出错的 JSON 节点路径，可以为 {@code null}
     */
    public JSONSchemaResult(boolean success, String message, String path) {
        this.success = success;
        this.message = message;
        this.path = path;
    }

    /**
     * 创建一个校验失败的结果。
     *
     * @param message 失败原因
     * @return 新建的失败结果，其节点路径为 {@code null}
     */
    public static JSONSchemaResult fail(String message) {
        return new JSONSchemaResult(false, message);
    }

    /**
     * 创建一个带节点路径的校验失败结果。
     *
     * @param message 失败原因
     * @param path    出错的 JSON 节点路径
     * @return 新建的失败结果
     */
    public static JSONSchemaResult fail(String message, String path) {
        return new JSONSchemaResult(false, message, path);
    }

    /**
     * 判断校验是否通过。
     *
     * @return 校验通过时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 获取校验信息。
     *
     * @return 当前的校验信息，校验通过且未设置信息时为 {@code null}
     */
    public String getMessage() {
        return message;
    }

    /**
     * 获取出错的 JSON 节点路径。
     *
     * @return 当前的节点路径，未设置时为 {@code null}
     */
    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        if (success) {
            return "JSONSchemaResult{success=true}";
        }
        if (path == null) {
            return "JSONSchemaResult{" +
                    "success=" + success +
                    ", message='" + message + '\'' +
                    '}';
        }
        return "JSONSchemaResult{" +
                "success=" + success +
                ", message='" + message + '\'' +
                ", path='" + path + '\'' +
                '}';
    }
}
