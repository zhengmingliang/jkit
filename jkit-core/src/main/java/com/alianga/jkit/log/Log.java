package com.alianga.jkit.log;

import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于 JDK 内置 {@link java.util.logging} 的极简日志门面，零第三方依赖。
 * <p>
 * 用法与 SLF4J 类似：
 * <pre>
 * private static final Log log = Log.get(Xxx.class);
 * log.info("user = {}", user);
 * log.error("读写失败", exception);
 * </pre>
 * 支持 {@code {}} 占位符；若最后一个参数是 {@link Throwable}，则作为异常堆栈输出。
 *
 * @since 1.0.0
 */
public class Log {
    private final Logger logger;

    private Log(Logger logger) {
        this.logger = logger;
        if (logger != null) {
            Logger parent = logger.getParent();
            if (parent != null) {
                Handler[] handlers = parent.getHandlers();
                if (handlers != null) {
                    for (Handler handler : handlers) {
                        handler.setFormatter(new LocaleFormatter(Locale.ENGLISH));
                    }
                }
            }
            Handler[] handlers = logger.getHandlers();
            for (Handler handler : handlers) {
                handler.setFormatter(new LocaleFormatter(Locale.ENGLISH));
            }
        }
    }

    /**
     * 以指定类的全限定名作为名称获取日志对象。
     *
     * @param clazz 日志所属的类
     * @return 以该类全限定名为名称的日志对象
     */
    public static Log get(Class<?> clazz) {
        return new Log(Logger.getLogger(clazz.getName()));
    }

    /**
     * 以指定名称获取日志对象。
     *
     * @param name 日志名称
     * @return 以该名称创建的日志对象
     */
    public static Log get(String name) {
        return new Log(Logger.getLogger(name));
    }

    /**
     * 判断 trace 级别（对应 {@link Level#FINER}）日志是否开启。
     *
     * @return 已开启时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isTraceEnabled() {
        return logger.isLoggable(Level.FINER);
    }

    /**
     * 判断 debug 级别（对应 {@link Level#FINE}）日志是否开启。
     *
     * @return 已开启时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isDebugEnabled() {
        return logger.isLoggable(Level.FINE);
    }

    /**
     * 判断 info 级别（对应 {@link Level#INFO}）日志是否开启。
     *
     * @return 已开启时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isInfoEnabled() {
        return logger.isLoggable(Level.INFO);
    }

    /**
     * 判断 warn 级别（对应 {@link Level#WARNING}）日志是否开启。
     *
     * @return 已开启时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isWarnEnabled() {
        return logger.isLoggable(Level.WARNING);
    }

    /**
     * 判断 error 级别（对应 {@link Level#SEVERE}）日志是否开启。
     *
     * @return 已开启时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isErrorEnabled() {
        return logger.isLoggable(Level.SEVERE);
    }

    /**
     * 输出 trace 级别日志（对应 {@link Level#FINER}）。
     *
     * @param format 日志模板，支持 {@code {}} 占位符
     * @param args   占位符参数，最后一个参数为 {@link Throwable} 时作为异常堆栈输出
     */
    public void trace(String format, Object... args) {
        log(Level.FINER, format, args);
    }

    /**
     * 输出 debug 级别日志（对应 {@link Level#FINE}）。
     *
     * @param format 日志模板，支持 {@code {}} 占位符
     * @param args   占位符参数，最后一个参数为 {@link Throwable} 时作为异常堆栈输出
     */
    public void debug(String format, Object... args) {
        log(Level.FINE, format, args);
    }

    /**
     * 输出 info 级别日志（对应 {@link Level#INFO}）。
     *
     * @param format 日志模板，支持 {@code {}} 占位符
     * @param args   占位符参数，最后一个参数为 {@link Throwable} 时作为异常堆栈输出
     */
    public void info(String format, Object... args) {
        log(Level.INFO, format, args);
    }

    /**
     * 输出 warn 级别日志（对应 {@link Level#WARNING}）。
     *
     * @param format 日志模板，支持 {@code {}} 占位符
     * @param args   占位符参数，最后一个参数为 {@link Throwable} 时作为异常堆栈输出
     */
    public void warn(String format, Object... args) {
        log(Level.WARNING, format, args);
    }

    /**
     * 输出 error 级别日志（对应 {@link Level#SEVERE}）。
     *
     * @param format 日志模板，支持 {@code {}} 占位符
     * @param args   占位符参数，最后一个参数为 {@link Throwable} 时作为异常堆栈输出
     */
    public void error(String format, Object... args) {
        log(Level.SEVERE, format, args);
    }

    private void log(Level level, String format, Object... args) {
        if (!logger.isLoggable(level)) {
            return;
        }
        Throwable thrown = null;
        if (args != null && args.length > 0 && args[args.length - 1] instanceof Throwable) {
            thrown = (Throwable) args[args.length - 1];
            Object[] rest = new Object[args.length - 1];
            System.arraycopy(args, 0, rest, 0, rest.length);
            args = rest;
        }
        String message = format == null ? "" : format(format, args);
        if (thrown != null) {
            logger.log(level, message, thrown);
        } else {
            logger.log(level, message);
        }
    }

    /**
     * 将模板中的 {@code {}} 占位符替换为参数，其余字符（包括 {@code %}、{@code \n} 等）原样保留。
     */
    private static String format(String template, Object... args) {
        if (args == null || args.length == 0) {
            return template;
        }
        StringBuilder sb = new StringBuilder(template.length() + 32);
        int argIndex = 0;
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{' && i + 1 < template.length() && template.charAt(i + 1) == '}' && argIndex < args.length) {
                sb.append(args[argIndex++]);
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
