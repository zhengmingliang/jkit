package com.alianga.jkit.json.internal.utils;

import com.alianga.jkit.jdk.JDKVersion;
import com.alianga.jkit.jdk.JdkApiAgent;
import com.alianga.jkit.reflect.UnsafeHelper;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.List;

/**
 * 运行环境探测工具，集中缓存 JDK 版本、字节序、字符集及常用类名哈希等只读常量。
 */
public final class EnvUtils {
    /** 当前 JDK 版本号，如 {@code 1.8}、{@code 17}。 */
    public static final float JDK_VERSION = JDKVersion.VERSION;
    /** 当前 JDK 是否低于 1.7。 */
    public static final boolean JDK_7_BELOW;
    /** 当前 JDK 是否为 1.8 及以上。 */
    public static final boolean JDK_8_PLUS;
    /** 当前 JDK 是否为 16 及以上。 */
    public static final boolean JDK_16_PLUS;
    /** 当前 JDK 是否为 9 及以上。 */
    public static final boolean JDK_9_PLUS;
    /** 当前 JDK 是否为 11 及以上。 */
    public static final boolean JDK_11_PLUS;
    /** 当前 JDK 是否为 20 及以上。 */
    public static final boolean JDK_20_PLUS;

    /** 当前平台是否为大端字节序。 */
    public static final boolean BIG_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
    /** 当前平台是否为小端字节序。 */
    public static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    /** 按平台字节序取字符高位字节所需的位移量。 */
    public static final int HI_BYTE_SHIFT;
    /** 按平台字节序取字符低位字节所需的位移量。 */
    public static final int LO_BYTE_SHIFT;
    /** 当前运行参数是否启用了 {@code jdk.incubator.vector} 模块（向量化优化的前置条件）。 */
    public static final boolean SUPPORTED_VECTOR;
    /** 临时目录路径，优先取 {@code java.io.tmpdir}，为空时回退到环境变量 {@code TMP}，均不可用时为 {@code null}。 */
    // java.io.tmpdir/TEMP
    public static final String TMP_DIR;

    /** {@code "java.lang.String"} 的字符串哈希值。 */
    // 'java.lang.String' hashcode
    public static final int STRING_HV = 1195259493;
    /** {@code "int"} 的字符串哈希值。 */
    // 'int' hash
    public static final int INT_HV = 104431;
    /** {@code "java.lang.Integer"} 的字符串哈希值。 */
    // 'java.lang.Integer' hash
    public static final int INTEGER_HV = -2056817302;
    /** {@code "long"} 的字符串哈希值。 */
    // 'long'
    public static final int LONG_PRI_HV = 3327612;
    /** {@code "java.lang.Long"} 的字符串哈希值。 */
    // 'java.lang.Long'
    public static final int LONG_HV = 398795216;
    /** {@code "java.util.HashMap"} 的字符串哈希值。 */
    // 'java.util.HashMap'
    public static final int HASHMAP_HV = -1402722386;
    /** {@code "java.util.LinkHashMap"} 的字符串哈希值。 */
    // 'java.util.LinkHashMap'
    public static final int LINK_HASHMAP_HV = 1258621781;

    /** {@code "java.util.ArrayList"} 的字符串哈希值。 */
    // 'java.util.ArrayList'
    public static final int ARRAY_LIST_HV = -1114099497;
    /** {@code "java.util.HashSet"} 的字符串哈希值。 */
    // 'java.util.HashSet'
    public static final int HASH_SET_HV = -1402716492;

    /** JVM 默认字符集。 */
    public static final Charset CHARSET_DEFAULT = Charset.defaultCharset();
    //    public final static Charset CHARSET_ISO_8859_1 = forCharsetName("ISO_8859_1");
    /** UTF-8 字符集，当前环境不支持时退化为默认字符集。 */
    public static final Charset CHARSET_UTF_8 = forCharsetName("UTF-8");
    /** 优先使用的 UTF-8 字符集，为 {@code null} 时取默认字符集。 */
    public static final Charset CHARSET_UTF8_OR_DEF = CHARSET_UTF_8 == null ? CHARSET_DEFAULT : CHARSET_UTF_8;
    /** 反射得到的 {@code java.lang.StringCoding#hasNegatives} 方法，JDK9 以下或获取失败时为 {@code null}。 */
    public static final Method SC_HAS_NEGATIVES_METHOD;

    static {
        JDK_7_BELOW = JDK_VERSION < 1.7f;
        JDK_8_PLUS = JDK_VERSION >= 1.8f;
        JDK_9_PLUS = JDK_VERSION >= 9;
        JDK_11_PLUS = JDK_VERSION >= 11;
        JDK_16_PLUS = JDK_VERSION >= 16;
        JDK_20_PLUS = JDK_VERSION >= 20;

        if (BIG_ENDIAN) {
            HI_BYTE_SHIFT = 8;
            LO_BYTE_SHIFT = 0;
        } else {
            HI_BYTE_SHIFT = 0;
            LO_BYTE_SHIFT = 8;
        }

        Method scHasNegatives = null;
        if (JDK_9_PLUS) {
            try {
                Class<?> scClass = Class.forName("java.lang.StringCoding");
                scHasNegatives = scClass.getMethod("hasNegatives", new Class[]{byte[].class, int.class, int.class});
                UnsafeHelper.setAccessible(scHasNegatives);
            } catch (Exception e) {
                scHasNegatives = null;
            }
        }
        SC_HAS_NEGATIVES_METHOD = scHasNegatives;

        boolean supportedVector = false;
        if (JDK_VERSION >= 17f) {
            try {
                List<String> inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
                supportedVector = inputArguments.contains("--add-modules=jdk.incubator.vector");
            } catch (Throwable throwable) {
            }
        }
        SUPPORTED_VECTOR = supportedVector;

        // java.io.tmpdir
        String tmpDir = null;
        try {
            tmpDir = System.getProperty("java.io.tmpdir");
            if (tmpDir == null || tmpDir.trim().isEmpty()) {
                tmpDir = System.getenv("TMP");
            }
        } catch (Throwable ignored) {
        }
        TMP_DIR = tmpDir;
    }

    /** JDK API 代理实例，JDK9 及以上使用增强实现，否则使用兼容实现。 */
    public static final JdkApiAgent JDK_AGENT_INSTANCE;

    static {
        JdkApiAgent apiAgent = null;
        if (EnvUtils.JDK_9_PLUS) {
            try {
                Class<?> agentClass =
                        Class.forName("com.alianga.jkit.json.internal.utils.JdkApiAgentJdk9Plus");
                apiAgent = (JdkApiAgent) UnsafeHelper.newInstance(agentClass);
            } catch (Throwable throwable) {
            }
        }
        if (apiAgent == null) {
            apiAgent = new JdkApiAgent();
        }
        JDK_AGENT_INSTANCE = apiAgent;
    }

    private static Charset forCharsetName(String charsetName) {
        try {
            return Charset.forName(charsetName);
        } catch (Throwable throwable) {
            return CHARSET_DEFAULT;
        }
    }

    /**
     * 判断字节区间内是否存在负值字节（即非 ASCII 字符），由 {@link #JDK_AGENT_INSTANCE} 择优实现。
     *
     * @param bytes  待检查的字节数组
     * @param offset 起始下标
     * @param len    检查长度
     * @return 区间内存在负值字节时返回 {@code true}，全为 ASCII 时返回 {@code false}
     */
    public static boolean hasNegatives(byte[] bytes, int offset, int len) {
        return JDK_AGENT_INSTANCE.hasNegatives(bytes, offset, len);
    }

    /**
     * 通过反射调用 {@code java.lang.StringCoding#hasNegatives} 判断区间内是否存在负值字节。
     *
     * @param bytes  待检查的字节数组
     * @param offset 起始下标
     * @param len    检查长度
     * @return 区间内存在负值字节时返回 {@code true}，全为 ASCII 时返回 {@code false}；
     *         当前 JDK 不支持该方法时抛出 {@link UnsupportedOperationException}
     */
    // only supported on JDK9+
    public static boolean hasNegativesReflect(byte[] bytes, int offset, int len) {
        try {
            return (Boolean) SC_HAS_NEGATIVES_METHOD.invoke(null, bytes, offset, len);
        } catch (Exception e) {
            throw new UnsupportedOperationException();
        }
    }
}
