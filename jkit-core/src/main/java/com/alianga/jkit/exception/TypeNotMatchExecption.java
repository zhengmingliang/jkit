package com.alianga.jkit.exception;

/**
 * @time 2021/9/9 23:30
 */
public class TypeNotMatchExecption extends RuntimeException {
    /**
     * 使用指定的错误描述构造类型不匹配异常
     *
     * @param message 描述类型不匹配原因的错误信息
     */
    public TypeNotMatchExecption(String message) {
        super(message);
    }
}
