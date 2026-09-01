package com.alianga.jkit;

import com.alianga.jkit.log.Log;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 控制台彩色打印工具，使用 ANSI 转义序列为文本着色，并同步写入日志。
 */
public class Print {
    private static final Log log = Log.get(Print.class);
    /**
     * 启用日志打印
     */
    public static boolean enableLog = true;

//    正常normal
//    警告 warning

    /*  样式：

    0  空样式

    1  粗体

    4  下划线

    7  反色

    颜色1：

    30  黑色

    31  红色

    32  绿色

    33  黄色

    34  蓝色

    35  紫色

    36  浅蓝

    37  灰色

    背景颜色：

    40-47 和颜色顺序相同

    颜色2：

    90-97  比颜色1更鲜艳一些，我也不太清楚为什么又两种
    * */

    /**
     * 将内容包装为普通提示样式（绿色）。
     *
     * @param object 待着色的内容
     * @return 带绿色 ANSI 颜色控制符的字符串
     */
    public static String wrapNormal(Object object) {
        return wrapGreen(object);
    }

    /**
     * 将内容包装为警告样式（黄色）。
     *
     * @param object 待着色的内容
     * @return 带黄色 ANSI 颜色控制符的字符串
     */
    public static String wrapWarning(Object object) {
        return wrapYellow(object);
    }

    /**
     * 将内容包装为错误样式（红色）。
     *
     * @param object 待着色的内容
     * @return 带红色 ANSI 颜色控制符的字符串
     */
    public static String wrapError(Object object) {
        return wrapRed(object);
    }

    /**
     * 黑色
     *
     * @param object 待着色的内容，为 {@link Supplier}、{@link Throwable} 或
     *               {@link Optional} 时会先取出其实际值
     * @return See the method result described above.
     */
    public static String wrapBlack(Object object) {
        object = getObject(object);
        return "\033[30;1m" + object + "\033[0m";
    }

    /**
     * 红色
     *
     * @param object 待着色的内容，为 {@link Supplier}、{@link Throwable} 或
     *               {@link Optional} 时会先取出其实际值
     * @return See the method result described above.
     */
    public static String wrapRed(Object object) {
        object = getObject(object);
        return "\033[31;1m" + object + "\033[0m";
    }

    /**
     * 绿色
     *
     * @param object 待着色的内容，为 {@link Supplier}、{@link Throwable} 或
     *               {@link Optional} 时会先取出其实际值
     * @return See the method result described above.
     */
    public static String wrapGreen(Object object) {
        object = getObject(object);
        return "\033[32;1m" + object + "\033[0m";
    }

    /**
     * 黄色
     *
     * @param object 待着色的内容，为 {@link Supplier}、{@link Throwable} 或
     *               {@link Optional} 时会先取出其实际值
     * @return See the method result described above.
     */
    public static String wrapYellow(Object object) {
        object = getObject(object);
        return "\033[33;1m" + object + "\033[0m";
    }

    /**
     * 蓝色
     *
     * @param object 待着色的内容，为 {@link Supplier}、{@link Throwable} 或
     *               {@link Optional} 时会先取出其实际值
     * @return See the method result described above.
     */
    public static String wrapBlue(Object object) {
        object = getObject(object);
        return "\033[34;1m" + object + "\033[0m";
    }

    /**
     * 紫色
     *
     * @param object 待着色的内容，为 {@link Supplier}、{@link Throwable} 或
     *               {@link Optional} 时会先取出其实际值
     * @return See the method result described above.
     */
    public static String wrapPurple(Object object) {
        object = getObject(object);
        return "\033[35;1m" + object + "\033[0m";
    }

    /**
     * 浅蓝
     *
     * @param object 待着色的内容，为 {@link Supplier}、{@link Throwable} 或
     *               {@link Optional} 时会先取出其实际值
     * @return See the method result described above.
     */
    public static String wrapLightBlue(Object object) {
        object = getObject(object);
        return "\033[36;1m" + object + "\033[0m";
    }

    /**
     * 灰色
     *
     * @param object 待着色的内容，为 {@link Supplier}、{@link Throwable} 或
     *               {@link Optional} 时会先取出其实际值
     * @return See the method result described above.
     */
    public static String wrapGrey(Object object) {
        object = getObject(object);
        return "\033[37;1m" + object + "\033[0m";
    }

    private static Object getObject(Object object) {
        if (object != null) {
            if (object instanceof Supplier) {
                object = ((Supplier<?>) object).get();
            } else if (object instanceof Throwable) {
                object = ((Throwable) object).getMessage();
            } else if (object instanceof Optional) {
                object = ((Optional<?>) object).isPresent() ? ((Optional<?>) object).get() : "null";
            }
        }
        return object;
    }

    /**
     * 以绿色输出普通信息到标准输出，同时以 info 级别记录日志。
     *
     * @param object 待输出的内容
     */
    public static void normal(Object object) {
        String print = wrapNormal(object);
        System.out.println(print);
        if (enableLog) {
            log.info(print);
        }
    }

    /**
     * 以黄色输出警告信息到标准输出，同时以 warn 级别记录日志。
     *
     * @param object 待输出的内容
     */
    public static void warning(Object object) {
        String print = wrapWarning(object);
        System.out.println(print);
        if (enableLog) {
            log.warn(print);
        }
    }

    /**
     * 以红色输出错误信息到标准输出，同时以 error 级别记录日志。
     *
     * @param object 待输出的内容
     */
    public static void error(Object object) {
        String print = wrapError(object);
        System.out.println(print);
        if (enableLog) {
            log.error(print);
        }
    }

}
