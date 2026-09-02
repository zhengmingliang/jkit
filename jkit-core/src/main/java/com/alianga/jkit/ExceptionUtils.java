package com.alianga.jkit;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 异常处理工具类，提供堆栈信息提取与异常链解析能力。
 */
public class ExceptionUtils {
    /**
     * 获取堆栈信息。
     *
     * @param e 异常对象
     * @return 异常的完整堆栈字符串
     */
    public static String getStackTrace(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw, true);
        e.printStackTrace(pw);
        return sw.getBuffer().toString();

    }

    /**
     * 获取堆栈异常信息
     *
     * @param e 异常对象
     * @return 异常堆栈字符串
     */
    public static String getStackTrace(Throwable e) {
        String detail = null;
        StringWriter sw = null;
        PrintWriter pw = null;
        try {
            sw = new StringWriter();
            pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            detail = sw.toString();
        } catch (Exception e1) {
            e1.printStackTrace();
        } finally {
            IOUtils.close(pw);
            IOUtils.close(sw);
        }
        return detail;
    }

    /**
     * 获取异常链中最底层的根异常。
     *
     * @param throwable 待检查的异常，可以为 {@code null}
     * @return 异常链末端的根异常；入参为 {@code null} 时返回 {@code null}
     */
    public static Throwable getRootCause(final Throwable throwable) {
        final List<Throwable> list = getThrowableList(throwable);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    /**
     * <p>Returns the list of <code>Throwable</code> objects in the
     * exception chain.</p>
     *
     * <p>A throwable without cause will return a list containing
     * one element - the input throwable.
     * A throwable with one cause will return a list containing
     * two elements. - the input throwable and the cause throwable.
     * A <code>null</code> throwable will return a list of size zero.</p>
     *
     * <p>This method handles recursive cause structures that might
     * otherwise cause infinite loops. The cause chain is processed until
     * the end is reached, or until the next item in the chain is already
     * in the result set.</p>
     *
     * @param throwable the throwable to inspect, may be null
     * @return the list of throwables, never null
     * @since 2.2
     */
    public static List<Throwable> getThrowableList(Throwable throwable) {
        final List<Throwable> list = new ArrayList<>();
        while (throwable != null && !list.contains(throwable)) {
            list.add(throwable);
            throwable = throwable.getCause();
        }
        return list;
    }
}
