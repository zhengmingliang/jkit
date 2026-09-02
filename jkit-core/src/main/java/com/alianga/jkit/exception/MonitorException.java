package com.alianga.jkit.exception;

/**
 * 监控相关处理过程中抛出的运行时异常
 */
public class MonitorException extends RuntimeException {
    /**
     * 创建不带异常信息的监控异常
     */
    public MonitorException() {
    }

    /**
     * 创建带异常信息的监控异常
     *
     * @param message 异常描述信息
     */
    public MonitorException(String message) {
        super(message);
    }

    /**
     * 创建带异常信息和触发原因的监控异常
     *
     * @param message 异常描述信息
     * @param cause 触发该异常的原始异常
     */
    public MonitorException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 根据触发原因创建监控异常
     *
     * @param cause 触发该异常的原始异常
     */
    public MonitorException(Throwable cause) {
        super(cause);
    }

    /**
     * 创建监控异常，并指定异常抑制与堆栈可写开关
     *
     * @param message 异常描述信息
     * @param cause 触发该异常的原始异常
     * @param enableSuppression 是否允许记录被抑制的异常
     * @param writableStackTrace 是否记录异常堆栈信息
     */
    public MonitorException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
