package com.alianga.jkit.json;

import com.alianga.jkit.StringUtils;
import com.alianga.jkit.io.ByteUtils;
import com.alianga.jkit.json.exceptions.JSONException;
import com.alianga.jkit.json.internal.beans.ArrayQueueMap;
import com.alianga.jkit.json.internal.beans.DateTemplate;
import com.alianga.jkit.json.internal.beans.GregorianDate;
import com.alianga.jkit.json.internal.compiler.MemoryClassLoader;
import com.alianga.jkit.json.internal.utils.EnvUtils;
import com.alianga.jkit.json.options.ReadOption;
import com.alianga.jkit.log.Log;
import com.alianga.jkit.math.NumberUtils;
import com.alianga.jkit.reflect.GenericParameterizedType;
import com.alianga.jkit.reflect.UnsafeHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.sql.Time;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Dictionary;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.Vector;

/**
 * @time 2021/12/7 19:30
 */
class JSONGeneral {
    private static final Log LOG = Log.get(JSONGeneral.class);

    // Format output character pool
    static final char[] FORMAT_OUT_SYMBOL_TABS = "\n\t\t\t\t\t\t\t\t\t\t".toCharArray();
    static final char[] FORMAT_OUT_SYMBOL_SPACES = new char[32];
    static final int FONT_INDENT2_INT32 = EnvUtils.BIG_ENDIAN ? '\n' << 16 | '\t' : '\t' << 16 | '\n';
    static final short FONT_INDENT2_INT16 =
            EnvUtils.BIG_ENDIAN ? (short) ('\n' << 8 | '\t') : (short) ('\t' << 8 | '\n');
    static final int FOTT_INDENT2_INT32 = '\t' << 16 | '\t';
    static final short FOTT_INDENT2_INT16 = (short) ('\t' << 8 | '\t');
    static final long FO_INDENT4_INT64 = EnvUtils.BIG_ENDIAN ? ((long) FONT_INDENT2_INT32) << 32 | FOTT_INDENT2_INT32 :
            ((long) FOTT_INDENT2_INT32) << 32 | FONT_INDENT2_INT32;
    static final int FO_INDENT4_INT32 = EnvUtils.BIG_ENDIAN ? FONT_INDENT2_INT16 << 16 | FOTT_INDENT2_INT16 :
            FOTT_INDENT2_INT16 << 16 | FONT_INDENT2_INT16;

    static {
        Arrays.fill(FORMAT_OUT_SYMBOL_SPACES, ' ');
    }

    /** 空字节数组常量。 */
    protected static final byte[] EMPTY_BYTES = new byte[0];
    /** 空字符数组常量。 */
    protected static final char[] EMPTY_CHARS = new char[0];
    /** 空 JSON 数组的字符表示 {@code []}。 */
    protected static final char[] EMPTY_ARRAY = new char[]{'[', ']'};
    /** 空 JSON 对象的字符表示 <code>{}</code>。 */
    protected static final char[] EMPTY_OBJECT = new char[]{'{', '}'};
    /** 字面量 {@code true} 的 4 字节整型编码，用于一次比较完成字面量匹配。 */
    protected static final int TRUE_INT = JSONMemoryHandle.getInt(new byte[]{'t', 'r', 'u', 'e'}, 0);
    /** 字面量 {@code true} 的 4 字符长整型编码，用于一次比较完成字面量匹配。 */
    protected static final long TRUE_LONG = JSONMemoryHandle.getLong(new char[]{'t', 'r', 'u', 'e'}, 0);
    /** 字面量 {@code false} 去掉首字符后的 {@code alse} 的 4 字节整型编码。 */
    protected static final int ALSE_INT = JSONMemoryHandle.getInt(new byte[]{'a', 'l', 's', 'e'}, 0);
    /** 字面量 {@code false} 去掉首字符后的 {@code alse} 的 4 字符长整型编码。 */
    protected static final long ALSE_LONG = JSONMemoryHandle.getLong(new char[]{'a', 'l', 's', 'e'}, 0);

    /** 字面量 {@code null} 的 4 字节整型编码，用于一次比较完成字面量匹配。 */
    protected static final int NULL_INT = JSONMemoryHandle.getInt(new byte[]{'n', 'u', 'l', 'l'}, 0);
    /** 字面量 {@code null} 的 4 字符长整型编码，用于一次比较完成字面量匹配。 */
    protected static final long NULL_LONG = JSONMemoryHandle.getLong(new char[]{'n', 'u', 'l', 'l'}, 0);

    /** {@link java.time.Duration} 文本前缀 {@code PT} 的 2 字节短整型编码。 */
    protected static final short DURATION_PT_SHORT = JSONMemoryHandle.getShort(new byte[]{'P', 'T'}, 0);
    /** {@link java.time.Duration} 文本前缀 {@code PT} 的 2 字符整型编码。 */
    protected static final int DURATION_PT_INT = JSONMemoryHandle.getInt(new char[]{'P', 'T'}, 0);
    /** 零时长文本 {@code PT0S} 的 4 字节整型编码。 */
    protected static final int DURATION_ZERO_INT = JSONMemoryHandle.getInt(new byte[]{'P', 'T', '0', 'S'}, 0);
    /** 零时长文本 {@code PT0S} 的 4 字符长整型编码。 */
    protected static final long DURATION_ZERO_LONG = JSONMemoryHandle.getLong(new char[]{'P', 'T', '0', 'S'}, 0);

    /** 数值 0 的字节常量。 */
    protected static final byte ZERO = 0;
    /** 逗号分隔符字节常量。 */
    protected static final byte COMMA = ',';
    /** 双引号字节常量。 */
    protected static final byte DOUBLE_QUOTATION = '"';
    /** 冒号字节常量。 */
    protected static final byte COLON_SIGN = ':';
    /** JSON 数组结束符 {@code ]} 的字节常量。 */
    protected static final byte END_ARRAY = ']';
    /** JSON 对象结束符 <code>}</code> 的字节常量。 */
    protected static final byte END_OBJECT = '}';
    /** 空格字节常量。 */
    protected static final byte WHITE_SPACE = ' ';
    /** 转义反斜杠字节常量。 */
    protected static final byte ESCAPE_BACKSLASH = '\\';
    /** 8 个双引号字节组成的掩码，用于按字节并行（SWAR）查找双引号。 */
    protected static final long DOUBLE_QUOTE_MASK = 0x2222222222222222L; // 0xDDDDDDDDDDDDDDDDL
    /** 8 个单引号字节组成的掩码，用于按字节并行（SWAR）查找单引号。 */
    protected static final long SINGLE_QUOTE_MASK = 0x2727272727272727L; // 0xD8D8D8D8D8D8D8D8L

    /** 4 个双引号字符组成的掩码，用于在 {@code char[]} 上按字符并行查找双引号。 */
    protected static final long DOUBLE_QUOTE_CHAR_MASK = 0x0022002200220022L; // 0xFFDDFFDDFFDDFFDDL
    /** 4 个单引号字符组成的掩码，用于在 {@code char[]} 上按字符并行查找单引号。 */
    protected static final long SINGLE_QUOTE_CHAR_MASK = 0x0027002700270027L; // 0xFFD8FFD8FFD8FFD8L

    static final int TYPE_BIGDECIMAL = 1;
    static final int TYPE_BIGINTEGER = 2;
    static final int TYPE_FLOAT = 3;
    static final int TYPE_DOUBLE = 4;

    static final String[] ESCAPE_VALUES = new String[256];
    static final boolean[] NO_ESCAPE_FLAGS = new boolean[256];

    static final String[] MONTH_ABBR = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    static final int[] ESCAPE_CHARS = new int[160];
    static final byte[] HEX_DIGITS_REVERSE =
            {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0, 1, 2, 3, 4,
                    5, 6, 7, 8, 9, -1, -1, -1, -1, -1, -1, -1, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1,
                    -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 10, 11, 12, 13, 14, 15};

    // cache keys
    static final JSONKeyValueMap<String> KEY_32_TABLE = new JSONKeyValueMap<String>(4096);
    static final JSONKeyValueMap<String> KEY_8_TABLE = new JSONKeyValueMap<String>(2048);

    static {
        for (int i = 0; i < 160; i++) {
            ESCAPE_CHARS[i] = i;
        }
        ESCAPE_CHARS['n'] = '\n';
        ESCAPE_CHARS['r'] = '\r';
        ESCAPE_CHARS['t'] = '\t';
        ESCAPE_CHARS['b'] = '\b';
        ESCAPE_CHARS['f'] = '\f';
        ESCAPE_CHARS['u'] = -1;
    }

    /** 直接读取输入流时使用的缓冲区大小，单位字节。 */
    protected static final int DIRECT_READ_BUFFER_SIZE = 8192;
    static final Map<String, TimeZone> GMT_TIME_ZONE_MAP = new ArrayQueueMap<String, TimeZone>(512);

    /** 零时区（{@code GMT+00:00}）时区对象，日期解析的默认基准时区。 */
    // zero zone
    public static final TimeZone ZERO_TIME_ZONE = TimeZone.getTimeZone("GMT+00:00");
    // 36
    static final ThreadLocal<char[]> CACHED_CHARS_36 = new ThreadLocal<char[]>() {
        @Override
        protected char[] initialValue() {
            return new char[36];
        }
    };

    // 36
    static final ThreadLocal<byte[]> CACHED_BYTES_36 = new ThreadLocal<byte[]>() {
        @Override
        protected byte[] initialValue() {
            return new byte[36];
        }
    };

    static final ThreadLocal<double[]> DOUBLE_ARRAY_TL = new ThreadLocal<double[]>() {
        @Override
        protected double[] initialValue() {
            return new double[32];
        }
    };
    static final ThreadLocal<long[]> LONG_ARRAY_TL = new ThreadLocal<long[]>() {
        @Override
        protected long[] initialValue() {
            return new long[32];
        }
    };
    static final ThreadLocal<int[]> INT_ARRAY_TL = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[32];
        }
    };
    static final long[] EMPTY_LONGS = new long[0];
    static final int[] EMPTY_INTS = new int[0];
    static final double[] EMPTY_DOUBLES = new double[0];
    static final String[] EMPTY_STRINGS = new String[0];
    // Default interface or abstract class implementation class configuration
    static final Map<Class<?>, JSONImplInstCreator> DEFAULT_IMPL_INST_CREATOR_MAP =
            new ArrayQueueMap<Class<?>, JSONImplInstCreator>(512);
    /** 反序列化安全访问校验器，用于拦截不受信任的类型。 */
    protected static final JSONSecureTrustedAccess JSON_SECURE_TRUSTED_ACCESS = new JSONSecureTrustedAccess();
    /** 两位 ASCII 数字的查表结果，非法组合对应值为 -1。 */
    protected static final int[] TWO_DIGITS_VALUES = new int[256];
    /** 三位 ASCII 数字（低 4 位拼接为下标）乘以 10 后的查表结果。 */
    protected static final int[] THREE_DIGITS_MUL10 = new int[10 << 8]; // 2560

    /** 当前生效的底层加速工具实现，向量化可用时为向量实现，否则为通用实现。 */
    protected static final JSONUtil JSON_UTIL;
    /** 是否启用了基于 {@code jdk.incubator.vector} 的向量化加速。 */
    protected static final boolean ENABLE_VECTOR;
    /** 是否启用字节码生成（JIT）优化，可通过 {@code -Djkit.json.jit.disabled=true} 关闭。 */
    protected static boolean ENABLE_JIT;
    /** 当前 JDK 是否支持内建候选（{@code @IntrinsicCandidate}）方法加速。 */
    protected static final boolean SUPPORTED_INTRINSIC_CANDIDATE;

    static {
        for (int i = 0; i < ESCAPE_VALUES.length; ++i) {
            switch (i) {
                case '\n':
                    ESCAPE_VALUES[i] = "\\n";
                    break;
                case '\t':
                    ESCAPE_VALUES[i] = "\\t";
                    break;
                case '\r':
                    ESCAPE_VALUES[i] = "\\r";
                    break;
                case '\b':
                    ESCAPE_VALUES[i] = "\\b";
                    break;
                case '\f':
                    ESCAPE_VALUES[i] = "\\f";
                    break;
                case '"':
                    ESCAPE_VALUES[i] = "\\\"";
                    break;
                case '\\':
                    ESCAPE_VALUES[i] = "\\\\";
                    break;
                default:
                    if (i < 32) {
                        ESCAPE_VALUES[i] = toEscapeString(i);
                    }
            }
            NO_ESCAPE_FLAGS[i] = !(i < 32 || i == '"' || i == '\\');
        }

        if (EnvUtils.JDK_VERSION < 25f) {
            String[] availableIDs = TimeZone.getAvailableIDs();
            for (String availableID : availableIDs) {
                GMT_TIME_ZONE_MAP.put(availableID, TimeZone.getTimeZone(availableID));
            }
        }
        TimeZone timeZone = ZERO_TIME_ZONE;
        GMT_TIME_ZONE_MAP.put("GMT+00:00", timeZone);
        GMT_TIME_ZONE_MAP.put("+00:00", timeZone);
        GMT_TIME_ZONE_MAP.put("-00:00", timeZone);
        GMT_TIME_ZONE_MAP.put("+0", timeZone);
        GMT_TIME_ZONE_MAP.put("-0", timeZone);
        try {
            GMT_TIME_ZONE_MAP.put("+08:00", timeZone = TimeZone.getTimeZone("GMT+08:00"));
            GMT_TIME_ZONE_MAP.put("GMT+08:00", timeZone);
        } catch (Throwable throwable) {
        }

        // i（十位） j（个位） k = (i | 0x30) ^ j << 4
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                TWO_DIGITS_VALUES[(i | 0x30) ^ j << 4] = (i < 10 && j < 10) ? i * 10 + j : -1;
            }
        }

        // THREE_DIGITS_MUL10
        for (int i = 0; i < 10; ++i) {
            for (int j = 0; j < 10; ++j) {
                for (int k = 0; k < 10; ++k) {
                    THREE_DIGITS_MUL10[i << 8 | j << 4 | k] = i * 1000 + j * 100 + k * 10 - 48;
                }
            }
        }

        JSONUtil envUtil = new JSONUtil();
        boolean enableVector = false;
        boolean supportedIntrinsicCandidate = false;
        if (EnvUtils.SUPPORTED_VECTOR && !JSONVmOptions.isIncubatorVectorDisabled()) {
            // jdk17 supported jdk.incubator.vector
            try {
                MemoryClassLoader memoryClassLoader = new MemoryClassLoader();
                String hex = StringUtils.fromResource("/bin/vector.txt");
                if (hex != null) {
                    Class<?> utilClass = memoryClassLoader.loadClass("com.alianga.jkit.json.JSONUtilVectorImpl",
                            ByteUtils.hexString2Bytes(hex));
                    JSONUtil vectorUtil = (JSONUtil) UnsafeHelper.newInstance(utilClass);
                    enableVector = vectorUtil.isSupportVectorWellTest();
                    if (enableVector) {
                        envUtil = vectorUtil;
                    }
                    LOG.debug("jkit_json incubator.vector enabled -> {}", enableVector);
                }

            } catch (Throwable throwable) {
                // throwable.printStackTrace();
            }
        } else {
            if (EnvUtils.JDK_16_PLUS) {
                try {
                    supportedIntrinsicCandidate = supportedIntrinsicCandidateTest();
                } catch (Throwable throwable) {
                }
            }
        }
        JSON_UTIL = envUtil;
        ENABLE_VECTOR = enableVector;
        // use vmargs to disabled jit:  -Djkit.json.jit.disabled=true
        ENABLE_JIT = !"true".equalsIgnoreCase(System.getProperty("jkit.json.jit.disabled"));
        SUPPORTED_INTRINSIC_CANDIDATE = supportedIntrinsicCandidate;

        registerImplCreator(EnumSet.class, new JSONImplInstCreator<EnumSet>() {
            @Override
            public EnumSet create(GenericParameterizedType<EnumSet> parameterizedType) {
                Class actualType = parameterizedType.getValueType().getActualType();
                return EnumSet.noneOf(actualType);
            }
        });
        registerImplCreator(EnumMap.class, new JSONImplInstCreator<EnumMap>() {
            @Override
            public EnumMap create(GenericParameterizedType<EnumMap> parameterizedType) {
                Class mapKeyClass = parameterizedType.getMapKeyClass();
                return new EnumMap(mapKeyClass);
            }
        });
    }

    /**
     * 获取2个字节组成的两位数
     *
     * @param h （48-57）
     * @param l （48-57）
     * @return 两个 ASCII 数字字符组成的十进制数（0-99），存在非数字字符时返回 -1
     */
    protected static final int twoDigitsValue(int h, int l) {
        return TWO_DIGITS_VALUES[h ^ (l & 0xf) << 4];
    }

    /**
     * 获取4个字节组成的mask用于数字校验
     *
     * @param buf    字节数组
     * @param offset 起始下标
     * @return 掩码值，4 个字节全部为 ASCII 数字时返回 0，否则非 0 且最高位标记出首个非数字字节的位置
     */
    protected static final int getDigits4Mask(byte[] buf, int offset) {
        final int value = JSONMemoryHandle.getInt(buf, offset);
        // Determine that all 4 bytes are within the range of 0x30-0x39
        // The high bits of the mask obtained by addition and subtraction are all 0, indicating that the judgment is
        // satisfied
        return (((value - 0x30303030) | (value + 0x46464646)) & 0x80808080);
    }

    /**
     * 获取8个字节组成的mask用于数字校验
     *
     * @param buf    字节数组
     * @param offset 起始下标
     * @return 掩码值，8 个字节全部为 ASCII 数字时返回 0，否则非 0 且最高位标记出首个非数字字节的位置
     */
    protected static final long getDigits8Mask(byte[] buf, int offset) {
        final long value = JSONMemoryHandle.JSON_ENDIAN.getLong(buf, offset);
        return (((value - 0x3030303030303030L) | (value + 0x4646464646464646L)) & 0x8080808080808080L);
    }

    /**
     * 从offset开始获取4个字节组成的4位数
     *
     * @param buf    字节数组
     * @param offset 起始下标
     * @return 4 个 ASCII 数字组成的十进制数（0-9999），存在非数字字节时返回 -1
     */
    protected static final int fourDigitsValue(byte[] buf, int offset) {
        final int value = JSONMemoryHandle.JSON_ENDIAN.getInt(buf, offset);
        int mask = ((value - 0x30303030) | (value + 0x46464646)) & 0x80808080;
        if (mask == 0) { // m = 0111_1111 - 0011_1001 -> 0100_0110 -> 0x46
            return THREE_DIGITS_MUL10[(buf[offset] & 0xf) << 8 | (buf[offset + 1] & 0xf) << 4 |
                    (buf[offset + 2] & 0xf)] + buf[offset + 3];
        }
        return -1;
    }

    /**
     * 跳过数字序列返回第一个非数字位置
     * 注: 适合查找长数字串结束位置，短数字串反而会降低性能；
     * 内部调用，确保offset不会越界,否则请判断offset是否有效
     *
     * @param buf
     * @param offset
     * @return
     */
    static final int skipDigits(byte[] buf, int offset) {
        int mask;
        if ((mask = JSONGeneral.getDigits4Mask(buf, offset)) == 0 &&
                (mask = JSONGeneral.getDigits4Mask(buf, offset += 4)) == 0) {
            offset += 4;
            while ((mask = JSONGeneral.getDigits4Mask(buf, offset)) == 0 &&
                    (mask = JSONGeneral.getDigits4Mask(buf, offset += 4)) == 0) {
                offset += 4;
            }
        }
        return offset + (EnvUtils.LITTLE_ENDIAN ? Integer.numberOfTrailingZeros(mask) >> 3 :
                Integer.numberOfLeadingZeros(mask) >> 3);
    }

    static final long withRemDigits4(long val, int mask, byte[] buf, int offset, JSONParseContext context) {
        int rem = (EnvUtils.LITTLE_ENDIAN ? Integer.numberOfTrailingZeros(mask) >> 3 :
                Integer.numberOfLeadingZeros(mask) >> 3);
        context.endIndex = offset + rem;
        switch (rem) {
            case 0:
                return val;
            case 1:
                return val * 10 + (buf[offset] & 0xF);
            case 2:
                return val * 100 + TWO_DIGITS_VALUES[buf[offset] ^ ((buf[offset + 1] & 0xf) << 4)];
            default:
                return val * 1000 + THREE_DIGITS_MUL10[(buf[offset] & 0xf) << 4 | (buf[offset + 1] & 0xf)] +
                        buf[offset + 2];
        }
    }

    static final long withRemDigits8(long val, long mask, byte[] buf, int offset, JSONParseContext context) {
        int rem =
                (EnvUtils.LITTLE_ENDIAN ? Long.numberOfTrailingZeros(mask) >> 3 : Long.numberOfLeadingZeros(mask) >> 3);
        context.endIndex = offset + rem;
        switch (rem) {
            case 0:
                return val;
            case 1:
                return val * 10 + (buf[offset] & 0xF);
            case 2:
                return val * 100 + TWO_DIGITS_VALUES[buf[offset] ^ ((buf[offset + 1] & 0xf) << 4)];
            case 3:
                return val * 1000 + THREE_DIGITS_MUL10[(buf[offset] & 0xf) << 4 | (buf[offset + 1] & 0xf)] +
                        buf[offset + 2];
            case 4:
                return val * 10000 + THREE_DIGITS_MUL10[(buf[offset] & 0xf) << 8 | (buf[offset + 1] & 0xf) << 4 |
                        (buf[offset + 2] & 0xf)] + buf[offset + 3];
            case 5:
                return val * 100000 + (buf[offset] & 0xf) * 10000L +
                        THREE_DIGITS_MUL10[(buf[offset + 1] & 0xf) << 8 | (buf[offset + 2] & 0xf) << 4 |
                                (buf[offset + 3] & 0xf)] + buf[offset + 4];
            case 6:
                return val * 1000000 + (TWO_DIGITS_VALUES[buf[offset] ^ ((buf[offset + 1] & 0xf) << 4)]) * 10000L +
                        THREE_DIGITS_MUL10[(buf[offset + 2] & 0xf) << 8 | (buf[offset + 3] & 0xf) << 4 |
                                (buf[offset + 4] & 0xf)] + buf[offset + 5];
            default: {
                // 7
                int v3 = THREE_DIGITS_MUL10[(buf[offset] & 0xf) << 4 | (buf[offset + 1] & 0xf)] + buf[offset + 2];
                int v4 = THREE_DIGITS_MUL10[(buf[offset + 3] & 0xf) << 8 | (buf[offset + 4] & 0xf) << 4 |
                        (buf[offset + 5] & 0xf)] + buf[offset + 6];
                return val * 10000000L + v3 * 10000L + v4;
            }
        }
    }

    /**
     * this optimization has slightly improved performance for long numbers (suitable for bigdecimal and input byte
     * arrays).
     * short numbers can actually decrease performance.
     *
     * @param buf     字节数组
     * @param offset  数字起始下标
     * @param val     已累计的数值，作为本次解析的高位基数
     * @param context 解析上下文，方法结束时其 {@code endIndex} 指向首个非数字字节位置
     * @return 累计后的数值，即 {@code val} 与后续数字串拼接后的结果
     */
    protected static final long parseDecimalDigits(long val, byte[] buf, int offset, JSONParseContext context) {
        if (val == 0) {
            while (offset < buf.length && buf[offset] == '0') {
                ++offset;
            }
        }
        // 8 + 8 + 4
        int v1;
        int v2;
        int v3;
        int v4;
        int mask32;
        long mask64;

        if ((mask64 = JSONGeneral.getDigits8Mask(buf, offset)) != 0) {
            return withRemDigits8(val, mask64, buf, offset, context);
        }
        v1 = THREE_DIGITS_MUL10[(buf[offset] & 0xf) << 8 | (buf[offset + 1] & 0xf) << 4 | (buf[offset + 2] & 0xf)] +
                buf[offset + 3];
        v2 = THREE_DIGITS_MUL10[(buf[offset + 4] & 0xf) << 8 | (buf[offset + 5] & 0xf) << 4 | (buf[offset + 6] & 0xf)] +
                buf[offset + 7];
        offset += 8;

        if ((mask64 = JSONGeneral.getDigits8Mask(buf, offset)) != 0) {
            return withRemDigits8(val * 100000000 + v1 * 10000L + v2, mask64, buf, offset, context);
        }
        v3 = THREE_DIGITS_MUL10[(buf[offset] & 0xf) << 8 | (buf[offset + 1] & 0xf) << 4 | (buf[offset + 2] & 0xf)] +
                buf[offset + 3];
        v4 = THREE_DIGITS_MUL10[(buf[offset + 4] & 0xf) << 8 | (buf[offset + 5] & 0xf) << 4 | (buf[offset + 6] & 0xf)] +
                buf[offset + 7];
        offset += 8;

        if ((mask32 = JSONGeneral.getDigits4Mask(buf, offset)) != 0) {
            return withRemDigits4(val * 10000000000000000L + v1 * 1000000000000L + v2 * 100000000L + v3 * 10000L + v4,
                    mask32, buf, offset, context);
        }
        // The value must overflow because Java does not have integers of length 20 bits
        // no need to calculate when overflowing, skip parsing directly, even if the parsed value is incorrect
        context.endIndex = skipDigits(buf, offset + 4);
        return val;
    }

//    /**
//     *  Digits -> Integer
//     *
//     * @param buf
//     * @param offset
//     * @return
//     */
//    protected final static int parseIntegerDigits(/*int val, */byte[] buf, final int offset, JSONParseContext
//    context) {
//        int i = offset, v1, v2, mask;
//        if ((mask = JSONGeneral.getDigits4Mask(buf, i)) != 0) {
//            return (int) withRemDigits4(0, mask, buf, i, context);
//        }
//        v1 = THREE_DIGITS_MUL10[(buf[i] & 0xf) << 8 | (buf[i + 1] & 0xf) << 4 | (buf[i + 2] & 0xf)] + buf[i + 3];
//        i += 4;
//        if ((mask = JSONGeneral.getDigits4Mask(buf, i)) != 0) {
//            return (int) withRemDigits4(/*val * 10000L + */v1, mask, buf, i, context);
//        }
//        v2 = THREE_DIGITS_MUL10[(buf[i] & 0xf) << 8 | (buf[i + 1] & 0xf) << 4 | (buf[i + 2] & 0xf)] + buf[i + 3];
//        i += 4;
//        if ((mask = JSONGeneral.getDigits4Mask(buf, i)) != 0) {
//            return (int) withRemDigits4(/*val * 100000000L +*/ v1 * 10000L + v2, mask, buf, i, context);
//        }
//        if (buf[offset] == '0') {
//            i = offset + 1;
//            while (buf[i] == '0') {
//                ++i;
//            }
//            return parseIntegerDigits(buf, i, context);
//        }
//        context.endIndex = skipDigits(buf, i + 4);
//        return 0;
//    }

    /**
     * 获取4个字节组成的4位数
     *
     * @param i （0-9）
     * @param j （0-9）
     * @param k （0-9）
     * @param l （48-57） The cache has been reduced by 48
     * @return 4 位十进制数（0-9999）
     */
    protected static final int fourDigitsValue(int i, int j, int k, int l) {
        return THREE_DIGITS_MUL10[i << 8 | j << 4 | k] + l;
    }

    /**
     * utf8解码
     *
     * @param buf
     * @param offset
     * @param beginByte
     * @param writer
     * @return 返回第一个非负字节所在位置
     */
    static final int decodeUTF8Bytes(byte[] buf, int offset, byte beginByte, JSONCharArrayWriter writer) {
        do {
            // b < 0
            byte b1 = buf[++offset];
            // UTF-8 decode
            int s = beginByte >> 4;
            // 读取字节b的前4位判断需要读取几个字节
            if (s == -2) {
                // 1110 3个字节
                try {
                    // 第1个字节的后4位 + 第2个字节的后6位 + 第3个字节的后6位
                    byte b2 = buf[++offset];
                    int a = ((beginByte & 0xf) << 12) | ((b1 & 0x3f) << 6) | (b2 & 0x3f);
                    writer.writeDirect((char) a);
                } catch (Throwable throwable) {
                    throw new UnsupportedOperationException("utf-8 character error ");
                }
            } else if (s == -3 || s == -4) {
                // (1100 1101) 2 bytes
                try {
                    int a = ((beginByte & 0x1f) << 6) | (b1 & 0x3f);
                    writer.writeDirect((char) a);
                } catch (Throwable throwable) {
                    throw new UnsupportedOperationException("utf-8 character error ");
                }
            } else if (s == -1) {
                // 1111 4个字节
                try {
                    // 第1个字节的后4位 + 第2个字节的后6位 + 第3个字节的后6位 + 第4个字节的后6位
                    byte b2 = buf[++offset];
                    byte b3 = buf[++offset];
                    int a = ((beginByte & 0x7) << 18) | ((b1 & 0x3f) << 12) | ((b2 & 0x3f) << 6) | (b3 & 0x3f);
                    if (Character.isSupplementaryCodePoint(a)) {
                        writer.writeDirect((char) ((a >>> 10)
                                + (Character.MIN_HIGH_SURROGATE - (Character.MIN_SUPPLEMENTARY_CODE_POINT >>> 10))));
                        writer.writeDirect((char) ((a & 0x3ff) + Character.MIN_LOW_SURROGATE));
                    } else {
                        writer.writeDirect((char) a);
                    }
                } catch (Throwable throwable) {
                    throw new UnsupportedOperationException("utf-8 character error ");
                }
            } else {
                throw new UnsupportedOperationException(
                        "utf-8 character error:  " + createErrorContextText(buf, offset - 1));
            }
        } while ((beginByte = buf[++offset]) < 0);
        return offset;
    }

    /**
     * 将字符转换为 {@code \\uXXXX} 形式的 JSON 转义文本。
     *
     * @param ch 字符的码点值
     * @return 形如 {@code \\u000a} 的转义字符串
     */
    public static String toEscapeString(int ch) {
        return String.format("\\u%04x", ch);
    }

    /**
     * 为抽象类或接口注册默认实现的实例创建器，反序列化遇到该类型时用其构造实例。
     *
     * @param parentClass 抽象类或接口类型
     * @param creator     实例创建器
     * @param <T>         目标实例类型
     */
    public static final <T> void registerImplCreator(Class<? extends T> parentClass, JSONImplInstCreator<T> creator) {
        DEFAULT_IMPL_INST_CREATOR_MAP.put(parentClass, creator);
    }

    static final JSONImplInstCreator getJSONImplInstCreator(Class<?> targetClass) {
        return DEFAULT_IMPL_INST_CREATOR_MAP.get(targetClass);
    }

    static final String getCacheKey(char[] buf, int offset, int len, long hashCode, JSONKeyValueMap<String> table) {
        if (len > 32) {
            return new String(buf, offset, len);
        }
        //  len > 0
        String value = table.getValue(buf, offset, offset + len, hashCode);
        if (value == null) {
            value = new String(buf, offset, len);
            table.putValue(value, hashCode, value);
        }
        return value;
    }

    static final String getCacheKey(byte[] bytes, int offset, int len, long hashCode, JSONKeyValueMap<String> table) {
        if (len > 32) {
            return new String(bytes, offset, len);
        }
        String value = table.getValue(bytes, offset, offset + len, hashCode);
        if (value == null) {
            value = new String(bytes, offset, len);
            table.putValue(value, hashCode, value);
        }
        return value;
    }

    static final String getCacheEightCharsKey(char[] buf, int offset, int len, long hashCode,
                                              JSONKeyValueMap<String> table) {
        String value = table.getValueByHash(hashCode);
        if (value == null) {
            value = new String(buf, offset, len);
            table.putExactHashValue(hashCode, value);
        }
        return value;
    }

    static final String getCacheEightBytesKey(byte[] bytes, int offset, int len, long hashCode,
                                              JSONKeyValueMap<String> table) {
        String value = table.getValueByHash(hashCode);
        if (value == null) {
            value = new String(bytes, offset, len);
            table.putExactHashValue(hashCode, value);
        }
        return value;
    }

    /**
     * 转化为2位int数字
     *
     * @param buf    字符数组
     * @param offset 起始下标
     * @return 两位十进制数（0-99），任一字符不是数字时抛出 {@link NumberFormatException}
     */
    protected static final int parseInt2(char[] buf, int offset)
            throws NumberFormatException {
        char c1;
        char c2;
        int i = offset;
        if (NumberUtils.isDigit(c1 = buf[i]) && NumberUtils.isDigit(c2 = buf[++i])) {
            return twoDigitsValue(c1, c2);
        }
        throw new NumberFormatException("2-digit parsing error: \"" + new String(buf, offset, 2));
    }

    /**
     * 转化为2位int数字
     *
     * @param buf    字节数组
     * @param offset 起始下标
     * @return 两位十进制数（0-99），任一字节不是数字时抛出 {@link NumberFormatException}
     */
    protected static final int parseInt2(byte[] buf, int offset)
            throws NumberFormatException {
        byte c1;
        byte c2;
        int i = offset;
        if (NumberUtils.isDigit(c1 = buf[i]) && NumberUtils.isDigit(c2 = buf[++i])) {
            return twoDigitsValue(c1, c2);
        }
        throw new NumberFormatException("2-digit parsing error: \"" + new String(buf, offset, 2));
    }

    /**
     * 转化为4位int数字
     *
     * @param buf    字符数组
     * @param offset 起始下标
     * @return 四位十进制数（0-9999），任一字符不是数字时抛出 {@link NumberFormatException}
     */
    protected static final int parseInt4(char[] buf, int offset)
            throws NumberFormatException {
        char c1;
        char c2;
        char c3;
        char c4;
        int i = offset;
        if (NumberUtils.isDigit(c1 = buf[i]) && NumberUtils.isDigit(c2 = buf[++i]) &&
                NumberUtils.isDigit(c3 = buf[++i]) && NumberUtils.isDigit(c4 = buf[++i])) {
            return fourDigitsValue(c1 & 0xf, c2 & 0xf, c3 & 0xf, c4);
        }
        throw new NumberFormatException("4-digit parsing error: \"" + new String(buf, offset, 4));
    }

    /**
     * 转化为4位int数字
     *
     * @param buf    字节数组
     * @param offset 起始下标
     * @return 四位十进制数（0-9999），任一字节不是数字时抛出 {@link NumberFormatException}
     */
    protected static final int parseInt4(byte[] buf, int offset)
            throws NumberFormatException {
        byte c1;
        byte c2;
        byte c3;
        byte c4;
        int i = offset;
        if (NumberUtils.isDigit(c1 = buf[i]) && NumberUtils.isDigit(c2 = buf[++i]) &&
                NumberUtils.isDigit(c3 = buf[++i]) && NumberUtils.isDigit(c4 = buf[++i])) {
            return fourDigitsValue(c1 & 0xf, c2 & 0xf, c3 & 0xf, c4);
        }
        throw new NumberFormatException("4-digit parsing error: \"" + new String(buf, offset, 4));
    }

    /**
     * n 位数字（ 0 < n < 4）
     *
     * @param bytes
     * @param offset
     * @param n
     * @return
     * @throws NumberFormatException
     */
    static int parseIntWithin3(byte[] bytes, int offset, int n)
            throws NumberFormatException {
        switch (n) {
            case 1:
                byte b = bytes[offset];
                if (NumberUtils.isDigit(b)) {
                    return b & 0xf;
                }
                break;
            case 2:
                return parseInt2(bytes, offset);
            case 3:
                int i = offset;
                byte b1 = bytes[i];
                byte b2 = bytes[++i];
                byte b3 = bytes[++i];
                if (NumberUtils.isDigit(b1) && NumberUtils.isDigit(b2) && NumberUtils.isDigit(b3)) {
                    return fourDigitsValue(0, b1 & 0xf, b2 & 0xf, b3);
                }
            default:
                break;
        }
        throw new NumberFormatException(n + "-digit parsing error: \"" + new String(bytes, offset, n));
    }

    /**
     * n 位数字（ 0 < n < 4）
     *
     * @param buf
     * @param offset
     * @param n
     * @return
     * @throws NumberFormatException
     */
    static int parseIntWithin3(char[] buf, int offset, int n)
            throws NumberFormatException {
        switch (n) {
            case 1:
                char c = buf[offset];
                if (NumberUtils.isDigit(c)) {
                    return c & 0xf;
                }
                break;
            case 2:
                return parseInt2(buf, offset);
            case 3:
                int i = offset;
                char c1 = buf[i];
                char c2 = buf[++i];
                char c3 = buf[++i];
                if (NumberUtils.isDigit(c1) && NumberUtils.isDigit(c2) && NumberUtils.isDigit(c3)) {
                    return fourDigitsValue(0, c1 & 0xf, c2 & 0xf, c3);
                }
                break;
            default:
                break;
        }
        throw new NumberFormatException(n + "-digit parsing error: \"" + new String(buf, offset, n));
    }

//    final static boolean isNoEscape32Bits(byte[] bytes, int offset) {
//        return NO_ESCAPE_FLAGS[bytes[offset] & 0xff]
//                && NO_ESCAPE_FLAGS[bytes[++offset] & 0xff]
//                && NO_ESCAPE_FLAGS[bytes[++offset] & 0xff]
//                && NO_ESCAPE_FLAGS[bytes[++offset] & 0xff];
//    }
//
//    final static boolean isNoEscape64Bits(byte[] bytes, int offset) {
//        return NO_ESCAPE_FLAGS[bytes[offset] & 0xff]
//                && NO_ESCAPE_FLAGS[bytes[++offset] & 0xff]
//                && NO_ESCAPE_FLAGS[bytes[++offset] & 0xff]
//                && NO_ESCAPE_FLAGS[bytes[++offset] & 0xff]
//                && NO_ESCAPE_FLAGS[bytes[++offset] & 0xff]
//                && NO_ESCAPE_FLAGS[bytes[++offset] & 0xff]
//                && NO_ESCAPE_FLAGS[bytes[++offset] & 0xff]
//                && NO_ESCAPE_FLAGS[bytes[++offset] & 0xff];
//    }

    /**
     * 通过unsafe一次性判断4个字节是否需要转义
     *
     * @param value 限于ascii字节编码（所有字节的高位都为0）
     * @return
     */
    static final boolean isNoneEscaped4Bytes(int value) {
        int notBackslashMask = (value ^ 0xA3A3A3A3) + 0x01010101 & 0x80808080;

        // Based on the probability of occurrence, first determine whether it is greater than '"' and not equal to
        // '\\', and return true in advance
        if ((notBackslashMask & value + 0x5D5D5D5D) == 0x80808080) {
            return true;
        }

        // if high-order bits of 4 bytes are all 1 return true, otherwise, return false
        // Not considering negative numbers
        return ((value + 0x60606060) & ((value ^ 0xDDDDDDDD) + 0x01010101) & notBackslashMask) == 0x80808080;
    }

    /**
     * 通过unsafe一次性判断2个字节是否需要转义
     *
     * @param value 限于ascii字节编码（所有字节的高位都为0）
     * @return
     */
    static final boolean isNoneEscaped2Bytes(short value) {
        int notBackslashMask = (value ^ 0xA3A3) + 0x0101 & 0x8080;

        // Based on the probability of occurrence, first determine whether it is greater than '"' and not equal to
        // '\\', and return true in advance
        if ((notBackslashMask & value + 0x5D5D) == 0x8080) {
            return true;
        }

        // if high-order bits of 4 bytes are all 1 return true, otherwise, return false
        // Not considering negative numbers
        return ((value + 0x6060) & ((value ^ 0xDDDD) + 0x0101) & notBackslashMask) == 0x8080;
    }

    /**
     * 通过unsafe获取的8个字节值一次性判断是否需要转义(包含'"'或者‘\\’或者存在小于32的字节)，如果需要转义返回false,否则返回true
     *
     * @param value 限于ascii字节编码（所有字节的高位都为0）
     * @return
     */
    static final boolean isNoneEscaped8Bytes(long value) {
        // if high-order bits of 8 bytes are all 1 return true, otherwise, return false
        // Not considering negative numbers
        long notBackslashMask = (value ^ 0xA3A3A3A3A3A3A3A3L) + 0x0101010101010101L & 0x8080808080808080L;

        // Based on the probability of occurrence, first determine whether it is greater than '"' and not equal to
        // '\\', and return true in advance
        if ((notBackslashMask & value + 0x5D5D5D5D5D5D5D5DL) == 0x8080808080808080L) {
            return true;
        }

        // complete standard verification
        return ((value + 0x6060606060606060L) & ((value ^ 0xDDDDDDDDDDDDDDDDL) + 0x0101010101010101L) &
                notBackslashMask & 0x8080808080808080L) == 0x8080808080808080L;
    }

    /**
     * <p>通过unsafe获取的16个字节值一次性判断是否需要转义(包含'"'或者‘\\’或者存在小于32的字节)，如果返回true则代表一定不存在待转义字节；</p>
     *
     * @param v1 限于ascii字节编码（所有字节的高位都为0）
     * @param v2 限于ascii字节编码（所有字节的高位都为0）
     * @return
     */
    static final boolean isNoneEscaped16Bytes(long v1, long v2) {
        long notBackslashMask = ((v1 ^ 0xA3A3A3A3A3A3A3A3L) + 0x0101010101010101L) &
                ((v2 ^ 0xA3A3A3A3A3A3A3A3L) + 0x0101010101010101L) & 0x8080808080808080L;

        // Based on prediction, prioritize whether all values are greater than '"' and not equal to '\\', which may
        // be faster
        if ((notBackslashMask & (v1 + 0x5D5D5D5D5D5D5D5DL) & (v2 + 0x5D5D5D5D5D5D5D5DL)) ==
                0x8080808080808080L) { // > '"' && != '\\'
            return true;
        }

        // complete standard verification
        long ge32Mask = (v1 + 0x6060606060606060L) & (v2 + 0x6060606060606060L);
        long notQuoteMask =
                ((v1 ^ 0xDDDDDDDDDDDDDDDDL) + 0x0101010101010101L) & ((v2 ^ 0xDDDDDDDDDDDDDDDDL) + 0x0101010101010101L);
        return (ge32Mask & notQuoteMask & notBackslashMask) == 0x8080808080808080L; // >= 32 && != '"' && != '\\'
    }

    /**
     * 一次性判断4个字符是否不存在需要转义的字符
     *
     * @param value
     * @return
     */
    static final boolean isNoneEscaped4Chars(long value) {
//        return ((value + 0x7FE07FE07FE07FE0L) & 0x8000800080008000L) == 0x8000800080008000L // all >= 32
//                && ((value ^ 0xFFDDFFDDFFDDFFDDL) + 0x0001000100010001L & 0x8000800080008000L) ==
//                0x8000800080008000L // != 34
//                && ((value ^ 0xFFA3FFA3FFA3FFA3L) + 0x0001000100010001L & 0x8000800080008000L) ==
//                0x8000800080008000L; // all != 92
        long mask = (value + 0x7FE07FE07FE07FE0L) & ((value ^ 0xFFDDFFDDFFDDFFDDL) + 0x0001000100010001L) &
                ((value ^ 0xFFA3FFA3FFA3FFA3L) + 0x0001000100010001L);
        return (mask & 0x8000800080008000L) == 0x8000800080008000L;
    }

    /**
     * escape next
     *
     * @param buf         字符数组
     * @param next        反斜杠后紧跟的字符
     * @param escapeIndex escape slash index
     * @param writer      转义结果的接收器
     * @return 转义内容处理完成后的下一个待读字符位置
     */
    protected static final int escapeNextChars(char[] buf, char next, int escapeIndex, JSONCharArrayWriter writer) {
        if (next < ESCAPE_CHARS.length) {
            int escapeChar = ESCAPE_CHARS[next];
            if (escapeChar > -1) {
                writer.write((char) escapeChar);
                return escapeIndex + 2;
            } else {
                // \\u
                char c = hex4ToChar(buf, escapeIndex + 2);
                writer.write(c);
                return escapeIndex + 6;
            }
        } else {
            writer.write(next);
            return escapeIndex + 2;
        }
    }

    /**
     * escape next
     *
     * @param bytes
     * @param next
     * @param escapeIndex escape slash index
     * @param writer
     * @return 返回转义内容处理完成后的下一个未知字符位置
     */
    static final int escapeNextBytes(byte[] bytes, byte next, int escapeIndex, JSONCharArrayWriter writer) {
        if (next == 'u') {
            try {
                long c64 = hex4ToLong(bytes, escapeIndex + 2);
                if (c64 > -1) {
                    writer.write((char) c64);
                    return escapeIndex + 6;
                }
            } catch (Throwable throwable) {
            }
            // \\u parse error
            String errorContextTextAt = createErrorContextText(bytes, escapeIndex + 1);
            throw new JSONException(
                    "Syntax error, from pos " + (escapeIndex + 1) + ", context text by '" + errorContextTextAt +
                            "', hex unicode parse error");
        } else {
            writer.write((char) ESCAPE_CHARS[next & 0xFF]);
            return escapeIndex + 2;
        }
    }

    /**
     * 将\\u后面的4个16进制字符转化为int值
     *
     * @param i1 第 1 位十六进制字符或字节
     * @param i2 第 2 位十六进制字符或字节
     * @param i3 第 3 位十六进制字符或字节
     * @param i4 第 4 位十六进制字符或字节
     * @return 4 位十六进制组成的码点值，存在非十六进制字符时结果为负数
     * @throws IndexOutOfBoundsException 入参超出十六进制查表范围时抛出
     */
    protected static final long hex4ToLong(int i1, int i2, int i3, int i4) {
        return hexToLong(i1) << 12 | hexToLong(i2) << 8 | hexToLong(i3) << 4 | hexToLong(i4);
    }

    /**
     * 字符或者字节转十六进制(0-15)
     *
     * @param c 待转换的字符或字节
     * @return <p>'0'-'9' -> 0-9</p>
     * <p>'A'-'F' -> 10-15</p>
     * <p>'a'-'f' -> 10-15</p>
     * <p> other cases -1
     * @throws IndexOutOfBoundsException 入参超出十六进制查表范围时抛出
     */
    protected static final long hexToLong(int c) {
        return HEX_DIGITS_REVERSE[c];
    }

    /**
     * 将\\u后面的4个16进制字符转化为int值
     *
     * @param buf    字符数组
     * @param offset \\u位置后一位
     * @return 转义后的字符；十六进制内容非法时抛出 {@link JSONException}
     */
    protected static char hex4ToChar(char[] buf, int offset) {
        try {
            long r = hex4ToLong(buf, offset);
            if (r > -1) {
                return (char) r;
            }
        } catch (Throwable throwable) {
        }
        // \\u parse error
        String errorContextTextAt = createErrorContextText(buf, offset - 1);
        throw new JSONException("Syntax error, from pos " + offset + ", context text by '" + errorContextTextAt +
                "', hex unicode parse error ");
    }

    /**
     * 计算8个16进制字符组成的long值(实际int值)
     *
     * @param buf    字符数组
     * @param offset 十六进制内容起始下标
     * @return 8 位十六进制组成的数值，存在非十六进制字符时结果为负数
     * @throws IndexOutOfBoundsException 下标越界或字符超出查表范围时抛出
     */
    protected static final long hex8ToLong(char[] buf, int offset) {
        return hexToLong(buf[offset]) << 28 | hexToLong(buf[offset + 1]) << 24 | hexToLong(buf[offset + 2]) << 20 |
                hexToLong(buf[offset + 3]) << 16 | hexToLong(buf[offset + 4]) << 12 | hexToLong(buf[offset + 5]) << 8 |
                hexToLong(buf[offset + 6]) << 4 | hexToLong(buf[offset + 7]);
    }

    /**
     * 计算8个16进制字节组成的long值(实际int值)
     *
     * @param buf    字节数组
     * @param offset 十六进制内容起始下标
     * @return 8 位十六进制组成的数值，存在非十六进制字节时结果为负数
     * @throws IndexOutOfBoundsException 下标越界或字节超出查表范围时抛出
     */
    protected static final long hex8ToLong(byte[] buf, int offset) {
        return hexToLong(buf[offset]) << 28 | hexToLong(buf[offset + 1]) << 24 | hexToLong(buf[offset + 2]) << 20 |
                hexToLong(buf[offset + 3]) << 16 | hexToLong(buf[offset + 4]) << 12 | hexToLong(buf[offset + 5]) << 8 |
                hexToLong(buf[offset + 6]) << 4 | hexToLong(buf[offset + 7]);
    }

    /**
     * 计算4个16进制字节组成的long值(实际short值)
     *
     * @param buf    字节数组
     * @param offset 十六进制内容起始下标
     * @return 4 位十六进制组成的数值，存在非十六进制字节时结果为负数
     * @throws IndexOutOfBoundsException 下标越界或字节超出查表范围时抛出
     */
    protected static final long hex4ToLong(byte[] buf, int offset) {
        return hexToLong(buf[offset]) << 12 | hexToLong(buf[offset + 1]) << 8 | hexToLong(buf[offset + 2]) << 4 |
                hexToLong(buf[offset + 3]);
    }

    /**
     * 计算4个16进制字符组成的long值(实际short值)
     *
     * @param buf    字符数组
     * @param offset 十六进制内容起始下标
     * @return 4 位十六进制组成的数值，存在非十六进制字符时结果为负数
     * @throws IndexOutOfBoundsException 下标越界或字符超出查表范围时抛出
     */
    protected static final long hex4ToLong(char[] buf, int offset) {
        return hexToLong(buf[offset]) << 12 | hexToLong(buf[offset + 1]) << 8 | hexToLong(buf[offset + 2]) << 4 |
                hexToLong(buf[offset + 3]);
    }

    /**
     * 匹配日期
     *
     * @param buf
     * @param from
     * @param to
     * @param dateCls
     * @return
     */
    static final Date matchDate(char[] buf, int from, int to, String timezone, Class<? extends Date> dateCls) {
        int len = to - from;
        String timezoneIdAt = timezone;
        if (len > 19) {
            // yyyy-MM-ddTHH:mm:ss.SSS+XX:YY
            int j = to;
            int ch;
            while (j > from) {
                // Check whether the time zone ID exists in the date
                // Check for '+' or '-' or 'Z'
                if ((ch = buf[--j]) == '.' || ch == ' ') {
                    break;
                }
                if (ch == '+' || ch == '-' || ch == 'Z') {
                    timezoneIdAt = new String(buf, j, to - j);
                    to = j;
                    len = to - from;
                    break;
                }
            }
        }
        switch (len) {
            case 8: {
                // yyyyMMdd
                // HH:mm:ss
                try {
                    if (dateCls != null && Time.class.isAssignableFrom(dateCls)) {
                        int hour = parseInt2(buf, from);
                        int minute = parseInt2(buf, from + 3);
                        int second = parseInt2(buf, from + 6);
                        return parseDate(1970, 1, 1, hour, minute, second, 0, timezoneIdAt, dateCls);
                    } else {
                        int year = parseInt4(buf, from);
                        int month = parseInt2(buf, from + 4);
                        int day = parseInt2(buf, from + 6);
                        return parseDate(year, month, day, 0, 0, 0, 0, timezoneIdAt, dateCls);
                    }
                } catch (Throwable throwable) {
                }
                return null;
            }
            case 10: {
                // yyyy-MM-dd yyyy/MM/dd
                // \d{4}[-/]\d{2}[-/]\d{2}
                try {
                    int year = parseInt4(buf, from);
                    int month = parseInt2(buf, from + 5);
                    int day = parseInt2(buf, from + 8);
                    return parseDate(year, month, day, 0, 0, 0, 0, timezoneIdAt, dateCls);
                } catch (Throwable throwable) {
                }
                return null;
            }
            case 14:
            case 15:
            case 16:
            case 17: {
                // yyyyMMddHHmmss or yyyyMMddhhmmssSSS
                try {
                    int year = parseInt4(buf, from);
                    int month = parseInt2(buf, from + 4);
                    int day = parseInt2(buf, from + 6);
                    int hour = parseInt2(buf, from + 8);
                    int minute = parseInt2(buf, from + 10);
                    int second = parseInt2(buf, from + 12);
                    int millsecond = 0;
                    if (len > 14) {
                        millsecond = parseIntWithin3(buf, from + 14, len - 14);
                    }
                    return parseDate(year, month, day, hour, minute, second, millsecond, timezoneIdAt, dateCls);
                } catch (Throwable throwable) {
                }
                return null;
            }
            case 19:
            case 21:
            case 22:
            case 23: {
                // yyyy-MM-dd HH:mm:ss yyyy/MM/dd HH:mm:ss 19位
                // yyyy-MM-dd HH:mm:ss.SSS? yyyy/MM/dd HH:mm:ss.SSS? 23位
                // \\d{4}[-/]\\d{2}[-/]\\d{2} \\d{2}:\\d{2}:\\d{2}
                try {
                    int year = parseInt4(buf, from);
                    int month = parseInt2(buf, from + 5);
                    int day = parseInt2(buf, from + 8);
                    int hour = parseInt2(buf, from + 11);
                    int minute = parseInt2(buf, from + 14);
                    int second = parseInt2(buf, from + 17);
                    int millsecond = 0;
                    if (len > 20) {
                        millsecond = parseIntWithin3(buf, from + 20, len - 20);
                    }
                    return parseDate(year, month, day, hour, minute, second, millsecond, timezoneIdAt, dateCls);
                } catch (Throwable throwable) {
                }
                return null;
            }
            case 28: {
                /***
                 * dow mon dd hh:mm:ss zzz yyyy 28位
                 * example Sun Jan 02 21:51:14 CST 2020
                 * @see Date#toString()
                 */
                try {
                    int year = parseInt4(buf, from + 24);
                    String monthAbbr = new String(buf, from + 4, 3);
                    int month = getMonthAbbrIndex(monthAbbr) + 1;
                    int day = parseInt2(buf, from + 8);
                    int hour = parseInt2(buf, from + 11);
                    int minute = parseInt2(buf, from + 14);
                    int second = parseInt2(buf, from + 17);
                    return parseDate(year, month, day, hour, minute, second, 0, timezoneIdAt, dateCls);
                } catch (Throwable throwable) {
                }
                return null;
            }
            default:
                if (len > 28) {
                    // yyyy-MM-ddTHH:mm:ss.000000000 -> yyyy-MM-ddTHH:mm:ss.000
                    try {
                        int year = parseInt4(buf, from);
                        int month = parseInt2(buf, from + 5);
                        int day = parseInt2(buf, from + 8);
                        int hour = parseInt2(buf, from + 11);
                        int minute = parseInt2(buf, from + 14);
                        int second = parseInt2(buf, from + 17);
                        int millsecond = parseIntWithin3(buf, from + 20, 3);
                        return parseDate(year, month, day, hour, minute, second, millsecond, timezoneIdAt, dateCls);
                    } catch (Throwable throwable) {
                    }
                }
                return null;
        }
    }

    /**
     * 字符串转日期
     *
     * @param buf          字符数组
     * @param from         开始引号位置
     * @param to           结束引号位置后一位
     * @param pattern      日期格式
     * @param patternType  格式分类
     * @param dateTemplate 日期模板
     * @param timezone     时间钟
     * @param dateCls      日期类型
     * @return 解析出的日期对象，其实际类型由 {@code dateCls} 决定；无法匹配格式时抛出 {@link JSONException}
     */
    protected static Date parseDateValueOfString(char[] buf, int from, int to, String pattern, int patternType,
                                                 DateTemplate dateTemplate, String timezone,
                                                 Class<? extends Date> dateCls) {
        int realFrom = from;
        String timezoneIdAt = timezone;
        try {
            switch (patternType) {
                case 1: {
                    // yyyy-MM-dd HH:mm:ss or yyyy/MM/dd HH:mm:ss
                    int year = parseInt4(buf, from + 1);
                    int month = parseInt2(buf, from + 6);
                    int day = parseInt2(buf, from + 9);
                    int hour = parseInt2(buf, from + 12);
                    int minute = parseInt2(buf, from + 15);
                    int second = parseInt2(buf, from + 18);
                    return parseDate(year, month, day, hour, minute, second, 0, timezoneIdAt, dateCls);
                }
                case 2: {
                    // yyyy-MM-dd yyyy/MM/dd
                    int year = parseInt4(buf, from + 1);
                    int month = parseInt2(buf, from + 6);
                    int day = parseInt2(buf, from + 9);
                    return parseDate(year, month, day, 0, 0, 0, 0, timezoneIdAt, dateCls);
                }
                case 3: {
                    // yyyyMMddHHmmss
                    int year = parseInt4(buf, from + 1);
                    int month = parseInt2(buf, from + 5);
                    int day = parseInt2(buf, from + 7);
                    int hour = parseInt2(buf, from + 9);
                    int minute = parseInt2(buf, from + 11);
                    int second = parseInt2(buf, from + 13);
                    return parseDate(year, month, day, hour, minute, second, 0, timezoneIdAt, dateCls);
                }
                case 4: {
                    TimeZone timeZone = getTimeZone(timezoneIdAt);
                    long time = dateTemplate.parseTime(buf, from + 1, to - from - 2, timeZone);
                    return parseDate(time, dateCls);
                }
                default: {
                    return matchDate(buf, from + 1, to - 1, timezone, dateCls);
                }
            }
        } catch (Throwable throwable) {
            if (throwable instanceof RuntimeException) {
                throw (RuntimeException) throwable;
            }
            String dateSource = new String(buf, from + 1, to - from - 2);
            if (patternType > 0) {
                throw new JSONException(
                        "Syntax error, at pos " + realFrom + ", dateStr " + dateSource + " mismatch date pattern '" +
                                pattern + "'");
            } else {
                throw new JSONException(
                        "Syntax error, at pos " + realFrom + ", dateStr " + dateSource + " mismatch any date format.");
            }
        }
    }

    /**
     * 匹配日期
     *
     * @param buf
     * @param from
     * @param to
     * @param dateCls
     * @return
     */
    static final Date matchDate(byte[] buf, int from, int to, String timezone, Class<? extends Date> dateCls) {
        int len = to - from;
        String timezoneIdAt = timezone;
        if (len > 19) {
            // yyyy-MM-ddTHH:mm:ss.SSS+XX:YY
            int j = to;
            int ch;
            while (j > from) {
                // Check whether the time zone ID exists in the date
                // Check for '+' or '-' or 'Z'
                if ((ch = buf[--j]) == '.' || ch == ' ') {
                    break;
                }
                if (ch == '+' || ch == '-' || ch == 'Z') {
                    timezoneIdAt = new String(buf, j, to - j);
                    to = j;
                    len = to - from;
                    break;
                }
            }
        }
        switch (len) {
            case 8: {
                // yyyyMMdd
                // HH:mm:ss
                try {
                    if (dateCls != null && Time.class.isAssignableFrom(dateCls)) {
                        int hour = parseInt2(buf, from);
                        int minute = parseInt2(buf, from + 3);
                        int second = parseInt2(buf, from + 6);
                        return parseDate(1970, 1, 1, hour, minute, second, 0, timezoneIdAt, dateCls);
                    } else {
                        int year = parseInt4(buf, from);
                        int month = parseInt2(buf, from + 4);
                        int day = parseInt2(buf, from + 6);
                        return parseDate(year, month, day, 0, 0, 0, 0, timezoneIdAt, dateCls);
                    }
                } catch (Throwable throwable) {
                }
                return null;
            }
            case 10: {
                // yyyy-MM-dd yyyy/MM/dd
                // \d{4}[-/]\d{2}[-/]\d{2}
                try {
                    int year = parseInt4(buf, from);
                    int month = parseInt2(buf, from + 5);
                    int day = parseInt2(buf, from + 8);
                    return parseDate(year, month, day, 0, 0, 0, 0, timezoneIdAt, dateCls);
                } catch (Throwable throwable) {
                }
                return null;
            }
            case 14:
            case 15:
            case 16:
            case 17: {
                // yyyyMMddHHmmss or yyyyMMddhhmmssSSS
                try {
                    int year = parseInt4(buf, from);
                    int month = parseInt2(buf, from + 4);
                    int day = parseInt2(buf, from + 6);
                    int hour = parseInt2(buf, from + 8);
                    int minute = parseInt2(buf, from + 10);
                    int second = parseInt2(buf, from + 12);
                    int millsecond = 0;
                    if (len > 14) {
                        millsecond = parseIntWithin3(buf, from + 14, len - 14);
                    }
                    return parseDate(year, month, day, hour, minute, second, millsecond, timezoneIdAt, dateCls);
                } catch (Throwable throwable) {
                }
                return null;
            }
            case 19:
            case 21:
            case 22:
            case 23: {
                // yyyy-MM-dd HH:mm:ss yyyy/MM/dd HH:mm:ss 19位
                // yyyy-MM-dd HH:mm:ss.SSS? yyyy/MM/dd HH:mm:ss.SSS? 23位
                // \\d{4}[-/]\\d{2}[-/]\\d{2} \\d{2}:\\d{2}:\\d{2}
                try {
                    int year = parseInt4(buf, from);
                    int month = parseInt2(buf, from + 5);
                    int day = parseInt2(buf, from + 8);
                    int hour = parseInt2(buf, from + 11);
                    int minute = parseInt2(buf, from + 14);
                    int second = parseInt2(buf, from + 17);
                    int millsecond = 0;
                    if (len > 20) {
                        millsecond = parseIntWithin3(buf, from + 20, len - 20);
                    }
                    return parseDate(year, month, day, hour, minute, second, millsecond, timezoneIdAt, dateCls);
                } catch (Throwable throwable) {
                }
                return null;
            }
            case 28: {
                /***
                 * dow mon dd hh:mm:ss zzz yyyy 28位
                 * example Sun Jan 02 21:51:14 CST 2020
                 * @see Date#toString()
                 */
                try {
                    int year = parseInt4(buf, from + 24);
                    String monthAbbr = new String(buf, from + 4, 3);
                    int month = getMonthAbbrIndex(monthAbbr) + 1;
                    int day = parseInt2(buf, from + 8);
                    int hour = parseInt2(buf, from + 11);
                    int minute = parseInt2(buf, from + 14);
                    int second = parseInt2(buf, from + 17);
                    return parseDate(year, month, day, hour, minute, second, 0, timezoneIdAt, dateCls);
                } catch (Throwable throwable) {
                }
                return null;
            }
            default:
                if (len > 28) {
                    // yyyy-MM-ddTHH:mm:ss.000000000 -> yyyy-MM-ddTHH:mm:ss.000
                    try {
                        int year = parseInt4(buf, from);
                        int month = parseInt2(buf, from + 5);
                        int day = parseInt2(buf, from + 8);
                        int hour = parseInt2(buf, from + 11);
                        int minute = parseInt2(buf, from + 14);
                        int second = parseInt2(buf, from + 17);
                        int millsecond = parseIntWithin3(buf, from + 20, 3);
                        return parseDate(year, month, day, hour, minute, second, millsecond, timezoneIdAt, dateCls);
                    } catch (Throwable throwable) {
                    }
                }
                return null;
        }
    }

    /**
     * 字符串转日期
     *
     * @param bytes        字节数组
     * @param from         开始引号位置
     * @param to           结束引号位置后一位
     * @param pattern      日期格式
     * @param patternType  格式分类
     * @param dateTemplate 日期模板
     * @param timezone     时间钟
     * @param dateCls      日期类型
     * @return 解析出的日期对象，其实际类型由 {@code dateCls} 决定；无法匹配格式时抛出 {@link JSONException}
     */
    protected static Object parseDateValueOfString(byte[] bytes, int from, int to, String pattern, int patternType,
                                                   DateTemplate dateTemplate, String timezone,
                                                   Class<? extends Date> dateCls) {
        int realFrom = from;
        String timezoneIdAt = timezone;
        try {
            switch (patternType) {
                case 0: {
                    return matchDate(bytes, from + 1, to - 1, timezone, dateCls);
                }
                case 1: {
                    // yyyy-MM-dd HH:mm:ss or yyyy/MM/dd HH:mm:ss
                    int year = parseInt4(bytes, from + 1);
                    int month = parseInt2(bytes, from + 6);
                    int day = parseInt2(bytes, from + 9);
                    int hour = parseInt2(bytes, from + 12);
                    int minute = parseInt2(bytes, from + 15);
                    int second = parseInt2(bytes, from + 18);
                    return parseDate(year, month, day, hour, minute, second, 0, timezoneIdAt, dateCls);
                }
                case 2: {
                    // yyyy-MM-dd yyyy/MM/dd
                    int year = parseInt4(bytes, from + 1);
                    int month = parseInt2(bytes, from + 6);
                    int day = parseInt2(bytes, from + 9);
                    return parseDate(year, month, day, 0, 0, 0, 0, timezoneIdAt, dateCls);
                }
                case 3: {
                    // yyyyMMddHHmmss
                    int year = parseInt4(bytes, from + 1);
                    int month = parseInt2(bytes, from + 5);
                    int day = parseInt2(bytes, from + 7);
                    int hour = parseInt2(bytes, from + 9);
                    int minute = parseInt2(bytes, from + 11);
                    int second = parseInt2(bytes, from + 13);
                    return parseDate(year, month, day, hour, minute, second, 0, timezoneIdAt, dateCls);
                }
                default: {
                    TimeZone timeZone = getTimeZone(timezoneIdAt);
                    long time = dateTemplate.parseTime(bytes, from + 1, to - from - 2, timeZone);
                    return parseDate(time, dateCls);
                }
            }
        } catch (Throwable throwable) {
            if (throwable instanceof JSONException) {
                throw (JSONException) throwable;
            }
            String dateSource = new String(bytes, from + 1, to - from - 2);
            if (patternType > 0) {
                throw new JSONException(
                        "Syntax error, at pos " + realFrom + ", dateStr " + dateSource + " mismatch date pattern '" +
                                pattern + "'");
            } else {
                throw new JSONException(
                        "Syntax error, at pos " + realFrom + ", dateStr " + dateSource + " mismatch any date format.");
            }
        }
    }

    /**
     * Starting from offset, search for the first visible byte (>32).
     *
     * @param buf    the byte array to scan
     * @param offset the index to start scanning from
     * @return the index of the first byte greater than {@code ' '}
     */
    protected static final int skipWhiteSpaces(byte[] buf, int offset) {
        // Flattening judgment instead of circular unfolding
        if (buf[offset] <= ' '
                && buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' ' &&
                buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' '
                && buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' ' &&
                buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' '
                && buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' ' &&
                buf[++offset] <= ' ' && buf[++offset] <= ' '
        ) {
            while (buf[++offset] <= ' ') {
                // skip
            }
        }
        return offset;
    }

    /**
     * Starting from offset, search for the first visible char (>32).
     *
     * @param buf    the char array to scan
     * @param offset the index to start scanning from
     * @return the index of the first char greater than {@code ' '}
     */
    protected static final int skipWhiteSpaces(char[] buf, int offset) {
        // Flattening judgment instead of circular unfolding
        if (buf[offset] <= ' '
                && buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' ' &&
                buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' '
                && buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' ' &&
                buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' '
                && buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' ' && buf[++offset] <= ' ' &&
                buf[++offset] <= ' ' && buf[++offset] <= ' '
        ) {
            while (buf[++offset] <= ' ') {
                // skip
            }
        }
        return offset;
    }

    /**
     * Starting from offset, search for the first char that is not digit.
     *
     * @param buf    the char array to scan
     * @param offset the index to start scanning from
     * @return the index of the first char that is not an ascii digit
     */
    protected static final int skipDigits(char[] buf, int offset) {
        // Flattening judgment instead of circular unfolding
        // 16 times
        if (NumberUtils.isDigit(buf[offset])
                && NumberUtils.isDigit(buf[++offset]) && NumberUtils.isDigit(buf[++offset]) &&
                NumberUtils.isDigit(buf[++offset]) && NumberUtils.isDigit(buf[++offset]) &&
                NumberUtils.isDigit(buf[++offset]) && NumberUtils.isDigit(buf[++offset]) &&
                NumberUtils.isDigit(buf[++offset])
                && NumberUtils.isDigit(buf[++offset]) && NumberUtils.isDigit(buf[++offset]) &&
                NumberUtils.isDigit(buf[++offset]) && NumberUtils.isDigit(buf[++offset]) &&
                NumberUtils.isDigit(buf[++offset]) && NumberUtils.isDigit(buf[++offset]) &&
                NumberUtils.isDigit(buf[++offset])
        ) {
            while (NumberUtils.isDigit(buf[++offset])) {
                // skip
            }
        }
        return offset;
    }

    /**
     * 清除注释和空白,返回第一个非空字符 （Clear comments and whitespace and return the first non empty character）
     * 开启注释支持后，支持//.*\n 和 /* *\/ （After enabling comment support, support / /* \N and / **\/）
     *
     * @param buf          字符数组
     * @param beginIndex   开始位置
     * @param parseContext 上下文配置
     * @return 去掉注释后的第一个非空字符位置（Non empty character position after removing comments）
     * @see ReadOption#AllowComment
     */
    protected static final int clearCommentAndWhiteSpaces(char[] buf, int beginIndex, JSONParseContext parseContext) {
        int i = beginIndex;
        int toIndex = parseContext.toIndex;
        if (i >= toIndex) {
            throw new JSONException("Syntax error, unexpected '/', position " + (beginIndex - 1));
        }
        // 注释和 /*注释
        // / or *
        char ch = buf[beginIndex];
        if (ch == '/') {
            // End with newline \ n
            while (i < toIndex && buf[i] != '\n') {
                ++i;
            }
            // continue clear WhiteSpaces
            ch = '\0';
            while (i + 1 < toIndex && (ch = buf[++i]) <= ' ') {
                // skip
            }
            if (ch == '/') {
                // 递归清除
                i = clearCommentAndWhiteSpaces(buf, i + 1, parseContext);
            }
        } else if (ch == '*') {
            // End with */
            char prev = '\0';
            boolean matched = false;
            while (i + 1 < toIndex) {
                ch = buf[++i];
                if (ch == '/' && prev == '*') {
                    matched = true;
                    break;
                }
                prev = ch;
            }
            if (!matched) {
                throw new JSONException("Syntax error, not found the close comment '*/' util the end ");
            }
            // continue clear WhiteSpaces
            ch = '\0';
            while (i + 1 < toIndex && (ch = buf[++i]) <= ' ') {
                // skip
            }
            if (ch == '/') {
                // 递归清除
                i = clearCommentAndWhiteSpaces(buf, i + 1, parseContext);
            }
        } else {
            throw new JSONException("Syntax error, unexpected '" + ch + "', position " + beginIndex);
        }
        return i;
    }

    /**
     * 清除注释和空白
     *
     * @param bytes        字节数组
     * @param beginIndex   开始位置
     * @param parseContext 上下文配置
     * @return 去掉注释后的第一个非空字节位置（Non empty character position after removing comments）
     * @see ReadOption#AllowComment
     */
    protected static int clearCommentAndWhiteSpaces(byte[] bytes, int beginIndex, JSONParseContext parseContext) {
        int i = beginIndex;
        int toIndex = parseContext.toIndex;
        if (i >= toIndex) {
            throw new JSONException("Syntax error, unexpected '/', position " + (beginIndex - 1));
        }
        // 注释和 /*注释
        // / or *
        byte b = bytes[beginIndex];
        if (b == '/') {
            // End with newline \ n
            while (i < toIndex && bytes[i] != '\n') {
                ++i;
            }
            // continue clear WhiteSpaces
            b = '\0';
            while (i + 1 < toIndex && (b = bytes[++i]) <= ' ') {
                // skip
            }
            if (b == '/') {
                // 递归清除
                i = clearCommentAndWhiteSpaces(bytes, i + 1, parseContext);
            }
        } else if (b == '*') {
            // End with */
            byte prev = 0;
            boolean matched = false;
            while (i + 1 < toIndex) {
                b = bytes[++i];
                if (b == '/' && prev == '*') {
                    matched = true;
                    break;
                }
                prev = b;
            }
            if (!matched) {
                throw new JSONException("Syntax error, not found the close comment '*/' util the end ");
            }
            // continue clear WhiteSpaces
            b = '\0';
            while (i + 1 < toIndex && (b = bytes[++i]) <= ' ') {
                // skip
            }
            if (b == '/') {
                // 递归清除
                i = clearCommentAndWhiteSpaces(bytes, i + 1, parseContext);
            }
        } else {
            throw new JSONException("Syntax error, unexpected '" + (char) b + "', position " + beginIndex);
        }
        return i;
    }

    /**
     * 格式化缩进,默认使用\t来进行缩进
     *
     * @param content    JSON 输出器
     * @param level      当前缩进层级，小于 0 时不输出
     * @param formatOut  是否开启格式化输出，为 {@code false} 时不输出
     * @param jsonConfig JSON 配置，提供缩进字符与最大缩进层级
     * @throws IOException 写出失败时抛出
     */
    protected static final void writeFormatOutSymbols(JSONWriter content, int level, boolean formatOut,
                                                      JSONConfig jsonConfig) throws IOException {
        if (formatOut && level > -1) {
            writeFormatOutSymbols(content, level, jsonConfig);
        }
    }

    /**
     * 格式化缩进,默认使用\t来进行缩进(明确标识结束)
     *
     * @param content    JSON 输出器
     * @param level      当前缩进层级，小于 0 或超过最大缩进层级时不输出
     * @param formatOut  是否开启格式化输出，为 {@code false} 时不输出
     * @param jsonConfig JSON 配置，提供缩进字符与最大缩进层级
     * @throws IOException 写出失败时抛出
     */
    protected static final void writeEndFormatOutSymbols(JSONWriter content, int level, boolean formatOut,
                                                         JSONConfig jsonConfig) throws IOException {
        if (formatOut && level > -1) {
            if (level >= jsonConfig.maxIndentLevel) {
                return;
            }
            writeFormatOutSymbols(content, level, jsonConfig);
        }
    }

    /**
     * 输出换行与对应层级的缩进符号，按配置使用空格或制表符。
     *
     * @param content    JSON 输出器
     * @param level      当前缩进层级，超过配置的最大缩进层级时不输出
     * @param jsonConfig JSON 配置，提供缩进字符与最大缩进层级
     * @throws IOException 写出失败时抛出
     */
    protected static final void writeFormatOutSymbols(JSONWriter content, int level, JSONConfig jsonConfig)
            throws IOException {
        if (level > jsonConfig.maxIndentLevel) {
            return;
        }
        boolean formatIndentUseSpace = jsonConfig.isFormatIndentUseSpace();
        if (formatIndentUseSpace) {
            content.writeJSONToken('\n');
            if (level == 0) {
                return;
            }
            int totalSpaceNum = level * jsonConfig.getFormatIndentSpaceNum();
            int symbolSpaceNum = FORMAT_OUT_SYMBOL_SPACES.length;
            while (totalSpaceNum >= symbolSpaceNum) {
                content.write(FORMAT_OUT_SYMBOL_SPACES);
                totalSpaceNum -= symbolSpaceNum;
            }
            while (totalSpaceNum-- > 0) {
                content.write(' ');
            }
        } else {
            char[] symbol = FORMAT_OUT_SYMBOL_TABS;
            final int symbolLen = 11; // symbol.length;
            if (level < symbolLen - 1) {
                switch (level) {
                    case 5:
                        content.writeMemory(FO_INDENT4_INT64, FO_INDENT4_INT32, 4);
                        content.writeMemory2(FOTT_INDENT2_INT32, FOTT_INDENT2_INT16, symbol, 1);
                        break;
                    case 4:
                        content.writeMemory(FO_INDENT4_INT64, FO_INDENT4_INT32, 4);
                        content.writeJSONToken('\t');
                        break;
                    case 3:
                        content.writeMemory(FO_INDENT4_INT64, FO_INDENT4_INT32, 4);
                        break;
                    case 2:
                        content.writeJSONToken('\n');
                        content.writeMemory2(FOTT_INDENT2_INT32, FOTT_INDENT2_INT16, symbol, 1);
                        break;
                    case 1:
                        content.writeMemory2(FONT_INDENT2_INT32, FONT_INDENT2_INT16, symbol, 0);
                        break;
                    case 0:
                        content.writeJSONToken('\n');
                        break;
                    default: {
                        content.write(symbol, 0, level + 1);
                    }
                }
            } else {
                content.write(symbol);
                int appendTabLen = level - (symbolLen - 1);
                while (appendTabLen-- > 0) {
                    content.write('\t');
                }
            }
        }
    }

    private static int getMonthAbbrIndex(String monthAbbr) {
        for (int i = 0, len = MONTH_ABBR.length; i < len; ++i) {
            if (MONTH_ABBR[i].equals(monthAbbr)) {
                return i;
            }
        }
        return -1;
    }

    private static Date parseDate(int year, int month, int day, int hour, int minute, int second, int millsecond,
                                  String timeZoneId, Class<? extends Date> dateCls) {
        TimeZone timeZone = getTimeZone(timeZoneId);
        long timeInMillis = GregorianDate.getTime(year, month, day, hour, minute, second, millsecond, timeZone);
        return parseDate(timeInMillis, dateCls);
    }

    // 获取时钟，默认GMT
    static TimeZone getTimeZone(String timeZoneId) {
        if (timeZoneId != null && timeZoneId.trim().length() > 0) {
            TimeZone timeZone;
            if (GMT_TIME_ZONE_MAP.containsKey(timeZoneId)) {
                timeZone = GMT_TIME_ZONE_MAP.get(timeZoneId);
            } else {
                if (timeZoneId.startsWith("GMT")) {
                    timeZone = TimeZone.getTimeZone(timeZoneId);
                } else {
                    timeZone = TimeZone.getTimeZone("GMT" + timeZoneId);
                }
                if (timeZone != null && timeZone.getRawOffset() != 0) {
                    GMT_TIME_ZONE_MAP.put(timeZoneId, timeZone);
                }
            }
            return timeZone;
        } else {
            return UnsafeHelper.getDefaultTimeZone();
        }
    }

    /**
     * 将时间戳转化为指定类型的日期对象。
     *
     * @param timeInMillis 毫秒时间戳
     * @param dateCls      目标日期类型，支持 {@link Date}、{@code java.sql.Date}、{@code java.sql.Timestamp}
     *                     以及提供 {@code long} 构造器的子类
     * @return 对应类型的日期对象，反射构造失败时返回 {@code null}
     */
    // 将时间戳转化为指定类型的日期对象
    protected static Date parseDate(long timeInMillis, Class<? extends Date> dateCls) {
        if (dateCls == Date.class) {
            return new Date(timeInMillis);
        } else if (dateCls == java.sql.Date.class) {
            return new java.sql.Date(timeInMillis);
        } else if (dateCls == java.sql.Timestamp.class) {
            return new java.sql.Timestamp(timeInMillis);
        } else {
            try {
                Constructor<? extends Date> constructor = dateCls.getConstructor(long.class);
                UnsafeHelper.setAccessible(constructor);
                return constructor.newInstance(timeInMillis);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    /**
     * @param from
     * @param to
     * @param buf
     * @param isUnquotedFieldName
     * @return
     */
    static final Serializable parseKeyOfMap(char[] buf, int from, int to, boolean isUnquotedFieldName) {
        if (isUnquotedFieldName) {
            while ((from < to) && ((buf[from]) <= ' ')) {
                from++;
            }
            while ((to > from) && ((buf[to - 1]) <= ' ')) {
                to--;
            }
            int count = to - from;
            if (count == 4) {
                long lv = JSONMemoryHandle.getLong(buf, from);
                if (lv == NULL_LONG) {
                    return null;
                }
                if (lv == TRUE_LONG) {
                    return true;
                }
            }
            if (count == 5 && buf[from] == 'f' && JSONMemoryHandle.getLong(buf, from + 1) == ALSE_LONG) {
                return false;
            }
            boolean numberFlag = true;
            int pointFlag = 0;
            for (int i = from; i < to; ++i) {
                int c = buf[i];
                if (c == '.') {
                    ++pointFlag;
                } else {
                    if (i != from || c != '-') {
                        if (!NumberUtils.isDigit(c)) {
                            numberFlag = false;
                            break;
                        }
                    }
                }
            }
            if (numberFlag && pointFlag <= 1) {
                if (pointFlag == 1) {
                    return parseFloats(buf, from, from + count, true);
                } else {
                    long val = Long.parseLong(new String(buf, from, count));
                    if (val <= Integer.MAX_VALUE && val >= Integer.MIN_VALUE) {
                        return (int) val;
                    }
                    return val;
                }
            }
            return new String(buf, from, count);
        } else {
            int len = to - from - 2;
            return new String(buf, from + 1, len);
        }
    }

    static final Serializable parseKeyOfMap(byte[] buf, int from, int to, boolean isUnquotedFieldName) {
        if (isUnquotedFieldName) {
            while ((from < to) && ((buf[from]) <= ' ')) {
                from++;
            }
            while ((to > from) && ((buf[to - 1]) <= ' ')) {
                to--;
            }
            int count = to - from;
            if (count == 4) {
                int iv = JSONMemoryHandle.getInt(buf, from);
                if (iv == NULL_INT) {
                    return null;
                }
                if (iv == TRUE_INT) {
                    return true;
                }
            }
            if (count == 5 && buf[from] == 'f' && JSONMemoryHandle.getInt(buf, from + 1) == ALSE_INT) {
                return false;
            }
            boolean numberFlag = true;
            int pointFlag = 0;
            for (int i = from; i < to; ++i) {
                int c = buf[i];
                if (c == '.') {
                    ++pointFlag;
                } else {
                    if (i != from || c != '-') {
                        if (!NumberUtils.isDigit(c)) {
                            numberFlag = false;
                            break;
                        }
                    }
                }
            }
            if (numberFlag && pointFlag <= 1) {
                if (pointFlag == 1) {
                    return parseFloats(buf, from, from + count, true);
                } else {
                    long val = Long.parseLong(new String(buf, from, count));
                    if (val <= Integer.MAX_VALUE && val >= Integer.MIN_VALUE) {
                        return (int) val;
                    }
                    return val;
                }
            }
            return new String(buf, from, count);
        } else {
            int len = to - from - 2;
            return new String(buf, from + 1, len);
        }
    }

    /**
     * 将十六进制字符序列转化为字节数组，非十六进制字符会被跳过。
     *
     * @param chars  十六进制字符数组
     * @param offset 起始下标
     * @param len    读取长度
     * @return 解析出的字节数组，长度为有效十六进制字符对数
     */
    // 16进制字符数组（字符串）转化字节数组
    protected static byte[] hexString2Bytes(char[] chars, int offset, int len) {
        byte[] bytes = new byte[len / 2];
        int byteLength = 0;
        int b = -1;
        for (int i = offset, count = offset + len; i < count; ++i) {
            char ch = Character.toUpperCase(chars[i]);
            int numIndex = ch > '9' ? ch - 55 : ch - 48;
            if (numIndex < 0 || numIndex >= 16) {
                continue;
            }
            if (b == -1) {
                b = numIndex << 4;
            } else {
                b += numIndex;
                bytes[byteLength++] = (byte) b;
                b = -1;
            }
        }
        if (byteLength == bytes.length) {
            return bytes;
        }

        byte[] buffer = new byte[byteLength];
        System.arraycopy(bytes, 0, buffer, 0, byteLength);
        return buffer;
    }

    /**
     * 将十六进制字节序列转化为字节数组，非十六进制字符会被跳过。
     *
     * @param buf    存放十六进制字符的字节数组
     * @param offset 起始下标
     * @param len    读取长度
     * @return 解析出的字节数组，长度为有效十六进制字符对数
     */
    // 16进制字符数组（字符串）转化字节数组
    protected static byte[] hexString2Bytes(byte[] buf, int offset, int len) {
        byte[] bytes = new byte[len / 2];
        int byteLength = 0;
        int b = -1;
        for (int i = offset, count = offset + len; i < count; ++i) {
            char ch = Character.toUpperCase((char) buf[i]);
            int numIndex = ch > '9' ? ch - 55 : ch - 48;
            if (numIndex < 0 || numIndex >= 16) {
                continue;
            }
            if (b == -1) {
                b = numIndex << 4;
            } else {
                b += numIndex;
                bytes[byteLength++] = (byte) b;
                b = -1;
            }
        }
        if (byteLength == bytes.length) {
            return bytes;
        }

        byte[] buffer = new byte[byteLength];
        System.arraycopy(bytes, 0, buffer, 0, byteLength);
        return buffer;
    }

    /**
     * 读取两个连续字节并转换为两位十进制数。
     *
     * @param buf    字节数组
     * @param offset 起始下标
     * @return 两位十进制数（0-99），任一字节不是 ASCII 数字时返回 -1
     */
    protected static final int digits2Bytes(byte[] buf, int offset) {
        return JSONMemoryHandle.JSON_ENDIAN.digits2Bytes(buf, offset);
    }

    /**
     * 读取两个连续字符并转换为两位十进制数。
     *
     * @param buf    字符数组
     * @param offset 起始下标
     * @return 两位十进制数（0-99），任一字符不是 ASCII 数字时返回 -1
     */
    protected static final int digits2Chars(char[] buf, int offset) {
        return JSONMemoryHandle.JSON_ENDIAN.digits2Chars(buf, offset);
    }

    /**
     * 一次性从流中最多读取 {@code maxLen} 个字符，读取完成后关闭流。
     *
     * @param is     输入流，方法结束时一定会被关闭
     * @param maxLen 单次读取的最大字符数
     * @return 实际读取到的字符数组，其长度为本次读取到的字符数
     * @throws IOException 读取或关闭流失败时抛出
     */
    // 读取流中的最大限度（maxLen）的字符数组(只读取一次)
    protected static final char[] readOnceInputStream(InputStream is, int maxLen) throws IOException {
        try {
            char[] buf = new char[maxLen];
            InputStreamReader streamReader = new InputStreamReader(is);
            int len = streamReader.read(buf);
            if (len != maxLen) {
                char[] tmp = new char[len];
                System.arraycopy(buf, 0, tmp, 0, len);
                buf = tmp;
            }
            streamReader.close();
            return buf;
        } catch (RuntimeException rx) {
            throw rx;
        } finally {
            is.close();
        }
    }

    /**
     * 统一处理解析过程中捕获的异常：越界异常转换为带上下文文本的 {@link JSONException}，
     * 其他运行时异常原样抛出，受检异常则被忽略。
     *
     * @param ex      捕获到的异常
     * @param buf     解析中的字符数组，用于生成错误上下文
     * @param toIndex 解析结束下标
     */
    protected static final void handleCatchException(Throwable ex, char[] buf, int toIndex) {
        // There is only one possibility to control out of bounds exceptions when indexing toindex
        if (ex instanceof IndexOutOfBoundsException) {
            String errorContextTextAt = createErrorContextText(buf, toIndex);
            throw new JSONException("Syntax error, context text by '" + errorContextTextAt +
                    "', JSON format error, and the end token may be missing, such as '\"' or ', ' or '}' or ']'.", ex);
        }
        if (ex instanceof RuntimeException) {
            throw (RuntimeException) ex;
        }
    }

    /**
     * 统一处理解析过程中捕获的异常：越界异常转换为带上下文文本的 {@link JSONException}，
     * 其他运行时异常原样抛出，受检异常则被忽略。
     *
     * @param ex      捕获到的异常
     * @param bytes   解析中的字节数组，用于生成错误上下文
     * @param toIndex 解析结束下标
     */
    protected static final void handleCatchException(Throwable ex, byte[] bytes, int toIndex) {
        // There is only one possibility to control out of bounds exceptions when indexing toindex
        if (ex instanceof IndexOutOfBoundsException) {
            String errorContextTextAt = createErrorContextText(bytes, toIndex);
            throw new JSONException("Syntax error, context text by '" + errorContextTextAt +
                    "', JSON format error, and the end token may be missing, such as '\"' or ', ' or '}' or ']'.", ex);
        }
        if (ex instanceof RuntimeException) {
            throw (RuntimeException) ex;
        }
    }

    /**
     * 抛出「出现意外字符，期望两种字符之一」的语法异常，返回值仅用于让调用处保持表达式形式。
     *
     * @param buf            解析中的字节数组，用于生成错误上下文
     * @param offset         出错位置
     * @param unexpectedChar 实际读到的字符
     * @param expectedChar   期望的字符
     * @param orExpectedChar 期望的备选字符
     * @param <T>            调用处期望的返回类型
     * @return 该方法始终抛出 {@link JSONException}，不会正常返回
     */
    protected static final <T> T throwUnexpectedException(byte[] buf, int offset, int unexpectedChar, char expectedChar,
                                                          char orExpectedChar) {
        String errorContextTextAt = createErrorContextText(buf, offset);
        throw new JSONException(
                "Syntax error, at pos " + offset + ", context text by '" + errorContextTextAt + "', unexpected '" +
                        (char) unexpectedChar + "', expected '" + expectedChar + "' or '" + orExpectedChar + "'");
    }

    /**
     * 抛出「出现意外字符，期望指定字符」的语法异常，返回值仅用于让调用处保持表达式形式。
     *
     * @param buf            解析中的字节数组，用于生成错误上下文
     * @param offset         出错位置
     * @param unexpectedChar 实际读到的字符
     * @param expectedChar   期望的字符
     * @param <T>            调用处期望的返回类型
     * @return 该方法始终抛出 {@link JSONException}，不会正常返回
     */
    protected static final <T> T throwUnexpectedException(byte[] buf, int offset, int unexpectedChar,
                                                          char expectedChar) {
        String errorContextTextAt = createErrorContextText(buf, offset);
        throw new JSONException(
                "Syntax error, at pos " + offset + ", context text by '" + errorContextTextAt + "', unexpected '" +
                        (char) unexpectedChar + "', expected '" + expectedChar + "'");
    }

    /**
     * 抛出「出现意外字符，期望两种字符之一」的语法异常，返回值仅用于让调用处保持表达式形式。
     *
     * @param buf            解析中的字符数组，用于生成错误上下文
     * @param offset         出错位置
     * @param unexpectedChar 实际读到的字符
     * @param expectedChar   期望的字符
     * @param orExpectedChar 期望的备选字符
     * @param <T>            调用处期望的返回类型
     * @return 该方法始终抛出 {@link JSONException}，不会正常返回
     */
    protected static final <T> T throwUnexpectedException(char[] buf, int offset, int unexpectedChar, char expectedChar,
                                                          char orExpectedChar) {
        String errorContextTextAt = createErrorContextText(buf, offset);
        throw new JSONException(
                "Syntax error, at pos " + offset + ", context text by '" + errorContextTextAt + "', unexpected '" +
                        (char) unexpectedChar + "', expected '" + expectedChar + "' or '" + orExpectedChar + "'");
    }

    /**
     * 抛出「出现意外字符，期望指定字符」的语法异常，返回值仅用于让调用处保持表达式形式。
     *
     * @param buf            解析中的字符数组，用于生成错误上下文
     * @param offset         出错位置
     * @param unexpectedChar 实际读到的字符
     * @param expectedChar   期望的字符
     * @param <T>            调用处期望的返回类型
     * @return 该方法始终抛出 {@link JSONException}，不会正常返回
     */
    protected static final <T> T throwUnexpectedException(char[] buf, int offset, int unexpectedChar,
                                                          char expectedChar) {
        String errorContextTextAt = createErrorContextText(buf, offset);
        throw new JSONException(
                "Syntax error, at pos " + offset + ", context text by '" + errorContextTextAt + "', unexpected '" +
                        (char) unexpectedChar + "', expected '" + expectedChar + "'");
    }

    /**
     * 判断日期格式所属的分类，供快速解析路径选择使用。
     *
     * @param pattern 日期格式串
     * @return 1 表示 {@code yyyy-MM-dd HH:mm:ss} 一类，2 表示 {@code yyyy-MM-dd} 一类，
     *         3 表示 {@code yyyyMMddHHmmss}，4 表示其他自定义格式，{@code pattern} 为 {@code null} 时返回 0
     */
    // 常规日期格式分类
    protected static final int getPatternType(String pattern) {
        if (pattern != null) {
            if (pattern.equalsIgnoreCase("yyyy-MM-dd HH:mm:ss")
                    || pattern.equalsIgnoreCase("yyyy/MM/dd HH:mm:ss")
                    || pattern.equalsIgnoreCase("yyyy-MM-ddTHH:mm:ss")
            ) {
                return 1;
            } else if (pattern.equalsIgnoreCase("yyyy-MM-dd") || pattern.equalsIgnoreCase("yyyy/MM/dd")) {
                return 2;
            } else if (pattern.equalsIgnoreCase("yyyyMMddHHmmss")) {
                return 3;
            } else {
                return 4;
            }
        }
        return 0;
    }

    /** 集合分类：可用 {@link ArrayList} 承载。 */
    protected static final int COLLECTION_ARRAYLIST_TYPE = 1;
    /** 集合分类：可用 {@link HashSet} 承载。 */
    protected static final int COLLECTION_HASHSET_TYPE = 2;
    /** 集合分类：需要其他集合实现承载。 */
    protected static final int COLLECTION_OTHER_TYPE = 3;

    /**
     * 判断集合类型所属的分类，供反序列化选择实例化方式。
     *
     * @param actualType 集合的实际类型
     * @return {@link #COLLECTION_ARRAYLIST_TYPE}、{@link #COLLECTION_HASHSET_TYPE} 或
     *         {@link #COLLECTION_OTHER_TYPE} 之一
     */
    protected static final int getCollectionType(Class<?> actualType) {
        if (actualType == List.class || actualType == ArrayList.class || actualType.isAssignableFrom(ArrayList.class)) {
            return COLLECTION_ARRAYLIST_TYPE;
        } else if (actualType == Set.class || actualType == HashSet.class ||
                actualType.isAssignableFrom(HashSet.class)) {
            return COLLECTION_HASHSET_TYPE;
        } else {
            return COLLECTION_OTHER_TYPE;
        }
    }

    /**
     * 创建指定集合类型的空实例，初始容量取 0。
     *
     * @param collectionCls 集合类型，可以是接口或具体实现类
     * @return 新创建的空集合实例
     */
    protected static final Collection createCollectionInstance(Class<?> collectionCls) {
        return createCollectionInstance(collectionCls, 0);
    }

    /**
     * 创建指定集合类型的空实例。
     *
     * <p>{@code List}、{@code Collection} 与 {@code ArrayList} 使用给定初始容量；{@code Set} 系列忽略容量；
     * 其他具体类通过无参构造创建；不支持的集合接口会抛出 {@link UnsupportedOperationException}。</p>
     *
     * @param collectionCls       集合类型，可以是接口或具体实现类
     * @param capacityIfSupported 支持指定容量时使用的初始容量
     * @return 新创建的空集合实例
     */
    protected static final Collection createCollectionInstance(Class<?> collectionCls, int capacityIfSupported) {
        if (collectionCls.isInterface()) {
            if (collectionCls == List.class || collectionCls == Collection.class) {
                return new ArrayList<Object>(capacityIfSupported);
            } else if (collectionCls == Set.class) {
                return new HashSet<Object>();
            } else {
                throw new UnsupportedOperationException("Unsupported for collection type '" + collectionCls +
                        "', Please specify an implementation class");
            }
        } else {
            if (collectionCls == HashSet.class) {
                return new HashSet<Object>();
            } else if (collectionCls == Vector.class) {
                return new Vector<Object>();
            } else if (collectionCls == ArrayList.class || collectionCls == Object.class) {
                return new ArrayList<Object>(capacityIfSupported);
            } else {
                try {
                    return (Collection<Object>) collectionCls.newInstance();
                } catch (Exception e) {
                    throw new JSONException("create Collection instance error, class " + collectionCls);
                }
            }
        }
    }

    // create map
    static final Map createMapInstance(GenericParameterizedType genericParameterizedType) {
        Class<? extends Map> mapCls = genericParameterizedType.getActualType();
        Map map = createCommonMapInstance(mapCls);
        if (map != null) {
            return map;
        }
        JSONImplInstCreator implInstCreator = getJSONImplInstCreator(mapCls);
        if (implInstCreator != null) {
            return (Map) implInstCreator.create(genericParameterizedType);
        }
        try {
            return (Map) UnsafeHelper.newInstance(mapCls);
        } catch (Exception e) {
            throw new JSONException("create map error for " + mapCls);
        }
    }

    static final Map createMapInstance(Class<? extends Map> mapCls) {
        Map map = createCommonMapInstance(mapCls);
        if (map != null) {
            return map;
        }
        try {
            return (Map) UnsafeHelper.newInstance(mapCls);
        } catch (Exception e) {
            throw new JSONException("create map error for " + mapCls);
        }
    }

    static final Map createCommonMapInstance(Class<? extends Map> targetCls) {
        Class<?> mapCls = targetCls;
        if (mapCls == Map.class || mapCls == null || mapCls == LinkedHashMap.class) {
            return new LinkedHashMap();
        }
        if (mapCls == HashMap.class) {
            return new HashMap();
        }
        if (mapCls == Hashtable.class || mapCls == Dictionary.class) {
            return new Hashtable();
        }
        if (mapCls == AbstractMap.class) {
            return new LinkedHashMap();
        }
        if (mapCls == TreeMap.class || mapCls == SortedMap.class) {
            return new TreeMap();
        }
        return null;
    }

    /**
     * 截取出错位置前后各 18 个字符并用 {@code ^} 标记出错点，用于异常信息展示。
     *
     * @param buf 解析中的字符数组
     * @param at  出错位置
     * @return 带 {@code ^} 标记的上下文文本，截取过程发生异常时返回空字符串
     */
    protected static String createErrorContextText(char[] buf, int at) {
        try {
            int len = buf.length;
            char[] text = new char[40];
            int count;
            int begin = Math.max(at - 18, 0);
            System.arraycopy(buf, begin, text, 0, count = at - begin);
            text[count++] = '^';
            int end = Math.min(len, at + 18);
            System.arraycopy(buf, at, text, count, end - at);
            count += end - at;
            return new String(text, 0, count);
        } catch (Throwable throwable) {
            return "";
        }
    }

    /**
     * 截取出错位置前后各 18 个字节并用 {@code ^} 标记出错点，用于异常信息展示。
     *
     * @param bytes 解析中的字节数组
     * @param at    出错位置
     * @return 带 {@code ^} 标记的上下文文本，截取过程发生异常时返回空字符串
     */
    protected static String createErrorContextText(byte[] bytes, int at) {
        try {
            int len = bytes.length;
            byte[] text = new byte[40];
            int count;
            int begin = Math.max(at - 18, 0);
            System.arraycopy(bytes, begin, text, 0, count = at - begin);
            text[count++] = '^';
            int end = Math.min(len, at + 18);
            System.arraycopy(bytes, at, text, count, end - at);
            count += end - at;
            return new String(text, 0, count);
        } catch (Throwable throwable) {
            return "";
        }
    }

    /**
     * 获取解析上下文中复用的字符缓冲写出器，尚未创建时惰性创建并回写到上下文。
     *
     * @param parseContext 解析上下文
     * @return 上下文持有的字符缓冲写出器，不会为 {@code null}
     */
    protected static final JSONCharArrayWriter getContextWriter(JSONParseContext parseContext) {
        JSONCharArrayWriter jsonWriter = parseContext.getContextWriter();
        if (jsonWriter == null) {
            parseContext.setContextWriter(jsonWriter = new JSONCharArrayWriter());
        }
        return jsonWriter;
    }

    /**
     * 获取字符串底层的字符数组，避免拷贝开销。
     *
     * @param value 目标字符串
     * @return 字符串对应的字符数组
     */
    protected static final char[] getChars(String value) {
        return UnsafeHelper.getChars(value);
    }

    /**
     * get value
     *
     * @param value the string to unwrap
     * @return the internal value of the string, i.e. its {@code char[]} or {@code byte[]} backing array
     */
    protected static final Object getStringValue(String value) {
        return JSONMemoryHandle.getStringValue(value.toString());
    }

    /**
     * 是否启用JDK16+字符串的indexOf方法加速(@IntrinsicCandidate)
     *
     * @return JDK 为 16 及以上且未通过虚拟机参数禁用时返回 {@code true}，否则返回 {@code false}
     */
    protected static final boolean supportedIntrinsicCandidateTest() {
        if (!EnvUtils.JDK_16_PLUS) {
            return false;
        }
        if (JSONVmOptions.isIntrinsicCandidateDisabled()) {
            return false;
        }
        return true;
    }

    static final double parseFloats(byte[] buf, final int offset, final int endIndex, boolean doubleFlag) {
        try {
            int i = offset;
            int zeroIndex = 0;
            int decimalPointIndex = endIndex;
            int cnt = 0;
            int decimalCount = 0;
            int zeroDecimalCount =
                                0;
            int c;
            int v;
            int e10 = 0;
            long value = 0L;
            boolean negative;
            boolean expNegative = false;
            // 清除前置的空白
            while ((c = buf[i]) <= ' ') {
                ++i;
            }
            if ((negative = c == '-') || c == '+') {
                c = buf[++i];
            }
            label_double_al:
            {
                // 清除处理前置0
                if (c == '0') {
                    while (++i < endIndex && (c = buf[i]) == '0') {
                        // skip
                    }
                    if (i == endIndex) {
                        return negative ? -0.0 : 0.0;
                    }
                    zeroIndex = i;
                }
                while (NumberUtils.isDigit(c)) {
                    value = value * 10 + (c & 0xF);
                    ++cnt;
                    if (++i == endIndex) {
                        break label_double_al;
                    }
                    c = buf[i];
                }
                if (c == '.') {
                    decimalPointIndex = i;
                    c = buf[++i];
                    if (cnt == 0 && c == '0') {
                        ++decimalCount;
                        while (++i < endIndex && (c = buf[i]) == '0') {
                            ++decimalCount;
                        }
                        if (i == endIndex) {
                            return negative ? -0.0 : 0.0;
                        }
                        zeroIndex = i;
                        zeroDecimalCount = decimalCount;
                    }
                    if (NumberUtils.isDigit(c)) {
                        value = value * 10 + (c & 0xF);
                        ++cnt;
                        ++decimalCount;
                        ++i;
                    }
                    while (i < endIndex - 1 && (v = JSONMemoryHandle.JSON_ENDIAN.digits2Bytes(buf, i)) != -1) {
                        value = value * 100 + v;
                        cnt += 2;
                        decimalCount += 2;
                        i += 2;
                    }
                    if (i < endIndex && NumberUtils.isDigit(c = buf[i])) {
                        value = value * 10 + (c & 0xF);
                        ++cnt;
                        ++decimalCount;
                        if (++i == endIndex) {
                            break label_double_al;
                        }
                        c = buf[i];
                    }
                    if (i == endIndex) {
                        break label_double_al;
                    }
                }
                // 清除后置的空白
                if (c <= ' ') {
                    while (++i < endIndex && (c = buf[i]) <= ' ') {
                        // skip
                    }
                    if (i == endIndex) {
                        break label_double_al;
                    }
                }
                if (c == 'e' || c == 'E') {
                    if (++i == endIndex) {
                        break label_double_al;
                    }
                    c = buf[i];
                    if ((expNegative = c == '-') || c == '+') {
                        if (++i == endIndex) {
                            break label_double_al;
                        }
                        c = buf[i];
                    }
                    if (NumberUtils.isDigit(c)) {
                        e10 = (c & 0xF);
                        while (++i < endIndex && NumberUtils.isDigit(c = buf[i])) {
                            e10 = e10 * 10 + (c & 0xF);
                        }
                        if (i == endIndex) {
                            break label_double_al;
                        }
                    }
                    if (c <= ' ') {
                        while (++i < endIndex && (c = buf[i]) <= ' ') {
                            // skip
                        }
                    }
                    if (i == endIndex) {
                        break label_double_al;
                    }
                }
                switch (c) {
                    case 'L':
                    case 'l':
                    case 'F':
                    case 'f':
                    case 'D':
                    case 'd': {
                        // 清除后置的空白
                        while (++i < endIndex && (c = buf[i]) <= ' ') {
                            // skip
                        }
                        if (i == endIndex) {
                            break label_double_al;
                        }
                        throw new JSONException("error floats input: \"" + new String(buf) + "\"");
                    }
                    default: {
                        throw new JSONException("error floats input: \"" + new String(buf) + "\"");
                    }
                }
            }

            // end
            if (cnt > 18 && (cnt > 19 || value < 0)) {
                // Compatible with double in abnormal length
                // Get the top 18 significant digits
                value = 0;
                cnt = 0;
                int j = zeroIndex;
                decimalCount = zeroDecimalCount;
                for (; j < i; ++j) {
                    if (NumberUtils.isDigit(c = buf[j])) {
                        if (cnt++ < 18) {
                            value = value * 10 + (c & 0xF);
                        }
                        if (j > decimalPointIndex) {
                            ++decimalCount;
                        }
                    } else {
                        if (c == '.') {
                            decimalPointIndex = j;
                        } else if (c == 'e' || c == 'E') {
                            break;
                        }
                    }
                    if (cnt >= 18 && decimalCount > 0) {
                        break;
                    }
                }
                decimalCount -= cnt - 18;
            }
            if (doubleFlag) {
                double dv = NumberUtils.scientificToIEEEDouble(value,
                        expNegative ? e10 + decimalCount : decimalCount - e10);
                return negative ? -dv : dv;
            } else {
                float fv =
                        NumberUtils.scientificToIEEEFloat(value, expNegative ? e10 + decimalCount : decimalCount - e10);
                return negative ? -fv : fv;
            }
        } catch (Throwable throwable) {
            throw throwable instanceof JSONException ? (JSONException) throwable :
                    new JSONException("For input string: \"" + new String(buf) + "\"");
        }
    }

    static final double parseFloats(char[] buf, final int offset, final int endIndex, boolean doubleFlag) {
        try {
            int i = offset;
            int zeroIndex = 0;
            int decimalPointIndex = endIndex;
            int cnt = 0;
            int decimalCount = 0;
            int zeroDecimalCount =
                                0;
            int c;
            int v;
            int e10 = 0;
            long value = 0L;
            boolean negative;
            boolean expNegative = false;
            // 清除前置的空白
            while ((c = buf[i]) <= ' ') {
                ++i;
            }
            if ((negative = c == '-') || c == '+') {
                c = buf[++i];
            }
            label_double_al:
            {
                // 清除处理前置0
                if (c == '0') {
                    while (++i < endIndex && (c = buf[i]) == '0') {
                        // skip
                    }
                    if (i == endIndex) {
                        return negative ? -0.0 : 0.0;
                    }
                    zeroIndex = i;
                }
                while (NumberUtils.isDigit(c)) {
                    value = value * 10 + (c & 0xF);
                    ++cnt;
                    if (++i == endIndex) {
                        break label_double_al;
                    }
                    c = buf[i];
                }
                if (c == '.') {
                    decimalPointIndex = i;
                    c = buf[++i];
                    if (cnt == 0 && c == '0') {
                        ++decimalCount;
                        while (++i < endIndex && (c = buf[i]) == '0') {
                            ++decimalCount;
                        }
                        if (i == endIndex) {
                            return negative ? -0.0 : 0.0;
                        }
                        zeroIndex = i;
                        zeroDecimalCount = decimalCount;
                    }
                    if (NumberUtils.isDigit(c)) {
                        value = value * 10 + (c & 0xF);
                        ++cnt;
                        ++decimalCount;
                        ++i;
                    }
                    while (i < endIndex - 1 && (v = JSONMemoryHandle.JSON_ENDIAN.digits2Chars(buf, i)) != -1) {
                        value = value * 100 + v;
                        cnt += 2;
                        decimalCount += 2;
                        i += 2;
                    }
                    if (i < endIndex && NumberUtils.isDigit(c = buf[i])) {
                        value = value * 10 + (c & 0xF);
                        ++cnt;
                        ++decimalCount;
                        if (++i == endIndex) {
                            break label_double_al;
                        }
                        c = buf[i];
                    }
                    if (i == endIndex) {
                        break label_double_al;
                    }
                }
                // 清除后置的空白
                if (c <= ' ') {
                    while (++i < endIndex && (c = buf[i]) <= ' ') {
                        // skip
                    }
                    if (i == endIndex) {
                        break label_double_al;
                    }
                }
                if (c == 'e' || c == 'E') {
                    if (++i == endIndex) {
                        break label_double_al;
                    }
                    c = buf[i];
                    if ((expNegative = c == '-') || c == '+') {
                        if (++i == endIndex) {
                            break label_double_al;
                        }
                        c = buf[i];
                    }
                    if (NumberUtils.isDigit(c)) {
                        e10 = (c & 0xF);
                        while (++i < endIndex && NumberUtils.isDigit(c = buf[i])) {
                            e10 = e10 * 10 + (c & 0xF);
                        }
                        if (i == endIndex) {
                            break label_double_al;
                        }
                    }
                    if (c <= ' ') {
                        while (++i < endIndex && (c = buf[i]) <= ' ') {
                            // skip
                        }
                    }
                    if (i == endIndex) {
                        break label_double_al;
                    }
                }
                switch (c) {
                    case 'L':
                    case 'l':
                    case 'F':
                    case 'f':
                    case 'D':
                    case 'd': {
                        // 清除后置的空白
                        while (++i < endIndex && (c = buf[i]) <= ' ') {
                            // skip
                        }
                        if (i == endIndex) {
                            break label_double_al;
                        }
                        throw new JSONException("error floats input: \"" + new String(buf) + "\"");
                    }
                    default: {
                        throw new JSONException("error floats input: \"" + new String(buf) + "\"");
                    }
                }
            }

            // end
            if (cnt > 18 && (cnt > 19 || value < 0)) {
                // Compatible with double in abnormal length
                // Get the top 18 significant digits
                value = 0;
                cnt = 0;
                int j = zeroIndex;
                decimalCount = zeroDecimalCount;
                for (; j < i; ++j) {
                    if (NumberUtils.isDigit(c = buf[j])) {
                        if (cnt++ < 18) {
                            value = value * 10 + (c & 0xF);
                        }
                        if (j > decimalPointIndex) {
                            ++decimalCount;
                        }
                    } else {
                        if (c == '.') {
                            decimalPointIndex = j;
                        } else if (c == 'e' || c == 'E') {
                            break;
                        }
                    }
                    if (cnt >= 18 && decimalCount > 0) {
                        break;
                    }
                }
                decimalCount -= cnt - 18;
            }
            if (doubleFlag) {
                double dv = NumberUtils.scientificToIEEEDouble(value,
                        expNegative ? e10 + decimalCount : decimalCount - e10);
                return negative ? -dv : dv;
            } else {
                float fv =
                        NumberUtils.scientificToIEEEFloat(value, expNegative ? e10 + decimalCount : decimalCount - e10);
                return negative ? -fv : fv;
            }
        } catch (Throwable throwable) {
            throw throwable instanceof JSONException ? (JSONException) throwable :
                    new JSONException("For input string: \"" + new String(buf) + "\"");
        }
    }
}
