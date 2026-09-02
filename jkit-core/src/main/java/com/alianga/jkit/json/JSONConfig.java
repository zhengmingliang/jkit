package com.alianga.jkit.json;

import com.alianga.jkit.json.options.WriteOption;

import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * JSON 序列化配置。
 *
 * <p> 以 {@code defaultXxx} 静态字段提供全局默认值，新建实例时作为初始配置；
 * 也可通过 {@link WriteOption} 批量设置，或使用 setter 逐项设置。
 *
 * <p> 开启 {@code skipCircularReference} 时，实例内部还会临时记录已序列化对象的 identityHashCode 状态，
 * 因此这类实例不宜跨线程共享，使用完可调用 {@link #clear()} 释放。
 */
public final class JSONConfig {
    private static boolean defaultFullProperty;
    private static String defaultDateFormatPattern;
    private static boolean defaultFormatIndentUseSpace;
    private static int defaultFormatIndentSpaceNum = 4;
    private static boolean defaultWriteEnumAsOrdinal;

    /**
     * 设置全局默认的“输出全属性”开关，影响之后新建的配置实例。
     *
     * @param defaultFullProperty 为 {@code true} 时默认输出全部属性（包含 null 值属性）
     */
    public static void setDefaultFullProperty(boolean defaultFullProperty) {
        JSONConfig.defaultFullProperty = defaultFullProperty;
    }

    /**
     * 设置全局默认的日期格式，影响之后新建的配置实例。
     *
     * @param defaultDateFormatPattern 日期格式串，如 {@code yyyy-MM-dd HH:mm:ss}
     */
    public static void setDefaultDateFormatPattern(String defaultDateFormatPattern) {
        JSONConfig.defaultDateFormatPattern = defaultDateFormatPattern;
    }

    /**
     * 设置全局默认的缩进方式，影响之后新建的配置实例。
     *
     * @param defaultFormatIndentUseSpace 为 {@code true} 时默认使用空格缩进，否则使用制表符
     */
    public static void setDefaultFormatIndentUseSpace(boolean defaultFormatIndentUseSpace) {
        JSONConfig.defaultFormatIndentUseSpace = defaultFormatIndentUseSpace;
    }

    /**
     * 设置全局默认的缩进空格数，影响之后新建的配置实例。
     *
     * @param defaultFormatIndentSpaceNum 缩进空格数，实例初始化时不小于 1
     */
    public static void setDefaultFormatIndentSpaceNum(int defaultFormatIndentSpaceNum) {
        JSONConfig.defaultFormatIndentSpaceNum = defaultFormatIndentSpaceNum;
    }

    /**
     * 设置全局默认的枚举序列化方式，影响之后新建的配置实例。
     *
     * @param defaultWriteEnumAsOrdinal 为 {@code true} 时默认把枚举序列化为 ordinal 序号，否则序列化为名称
     */
    public static void setDefaultWriteEnumAsOrdinal(boolean defaultWriteEnumAsOrdinal) {
        JSONConfig.defaultWriteEnumAsOrdinal = defaultWriteEnumAsOrdinal;
    }

    /**
     * 格式化输出
     */
    public boolean formatOut;

    /**
     * 格式化输出(冒号补一个空格)
     */
    public boolean formatOutColonSpace;

    /**
     * 格式化缩进使用空格模式
     */
    public boolean formatIndentUseSpace = defaultFormatIndentUseSpace;

    /**
     * 缩进空格数量,默认4个空格
     */
    int formatIndentSpaceNum = Math.max(defaultFormatIndentSpaceNum, 1);

    /**
     * 最大缩进级别（默认不限制,最小为1）
     */
    int maxIndentLevel = Integer.MAX_VALUE;

    /**
     * 输出全属性
     */
    boolean fullProperty = defaultFullProperty;

    /**
     * 以yyyy-MM-dd HH:mm:ss 格式化日期对象
     */
    boolean dateFormat;

    /**
     * 是否序列化日期为时间戳
     */
    boolean writeDateAsTime;

    /**
     * 是否序列化日期为时间戳
     */
    boolean writeEnumAsOrdinal = defaultWriteEnumAsOrdinal;

    /**
     * 是否将数字类序列化位字符串
     */
    boolean writeNumberAsString;

    /**
     * 跳过循环序列化
     */
    boolean skipCircularReference;

    /**
     * 日期格式默认： yyyy-MM-dd HH:mm:ss
     */
    private String dateFormatPattern = defaultDateFormatPattern;

    /**
     * 是否将byte[]数组按数组序列化
     * Serialize byte [] array to Base64
     */
    boolean bytesArrayToNative;

    /**
     * 是否将byte[]数组序列化为16进制字符串
     */
    boolean bytesArrayToHex;

    /**
     * 忽略转义检查
     */
    boolean ignoreEscapeCheck;

    /**
     * 跳过没有属性Field的getter方法序列化
     */
    boolean skipGetterOfNoneField;

    /**
     * 自动关闭流
     */
    boolean autoCloseStream = true;

    /**
     * 允许map的key根据实际类型序列化而不是双引号包围
     */
    boolean allowUnquotedMapKey;

    /**
     * 使用字段序列化
     */
    boolean useFields;

    /**
     * 是否驼峰转下划线
     */
    boolean camelCaseToUnderline;

    /**
     * 是否序列化类型
     */
    boolean writeClassName;

    /**
     * 指定时区
     */
    TimeZone timezone;

    private Map<Integer, Integer> identityHashCodes;

    /**
     * 创建配置实例，各开关取当前的全局默认值。
     */
    public JSONConfig() {
    }

    /**
     * 创建配置实例并应用指定的序列化选项。
     *
     * @param writeOptions 序列化选项，可变参数
     */
    public JSONConfig(WriteOption... writeOptions) {
        JSONOptions.writeOptions(writeOptions, this);
    }

    /**
     * 按指定序列化选项创建配置实例。
     *
     * @param options 序列化选项，可变参数
     * @return 已应用这些选项的新配置实例
     */
    public static JSONConfig of(WriteOption... options) {
        return new JSONConfig(options);
    }

    /**
     * 创建用于格式化输出的配置实例，冒号后补一个空格。
     *
     * @return 已开启格式化输出（{@link WriteOption#FormatOutColonSpace}）的新配置实例
     */
    public static JSONConfig formatOf() {
        return new JSONConfig(WriteOption.FormatOutColonSpace);
    }

    /**
     * 判断格式化缩进是否使用空格。
     *
     * @return 使用空格缩进时返回 {@code true}，使用制表符时返回 {@code false}
     */
    public boolean isFormatIndentUseSpace() {
        return formatIndentUseSpace;
    }

    /**
     * 设置格式化缩进是否使用空格。
     *
     * @param formatIndentUseSpace 为 {@code true} 时使用空格缩进，否则使用制表符
     */
    public void setFormatIndentUseSpace(boolean formatIndentUseSpace) {
        this.formatIndentUseSpace = formatIndentUseSpace;
    }

    /**
     * 获取格式化缩进的空格数量。
     *
     * @return 缩进空格数量，默认 4
     */
    public int getFormatIndentSpaceNum() {
        return formatIndentSpaceNum;
    }

    /**
     * 设置格式化缩进的空格数量。
     *
     * @param formatIndentSpaceNum 缩进空格数量，小于 1 时按 1 处理
     */
    public void setFormatIndentSpaceNum(int formatIndentSpaceNum) {
        this.formatIndentSpaceNum = Math.max(formatIndentSpaceNum, 1);
    }

    /**
     * 设置格式化缩进的空格数量。
     *
     * @param formatIndentSpaceNum 缩进空格数量，小于 1 时按 1 处理
     * @return 当前对象，便于链式调用
     */
    public JSONConfig formatIndentSpaceNum(int formatIndentSpaceNum) {
        setFormatIndentSpaceNum(formatIndentSpaceNum);
        return this;
    }

    /**
     * 设置格式化输出的最大缩进级别，超过该级别的层级不再换行缩进。
     *
     * @param maxIndentLevel 最大缩进级别，小于 1 时按 1 处理
     */
    public void setMaxIndentLevel(int maxIndentLevel) {
        this.maxIndentLevel = Math.max(maxIndentLevel, 1);
    }

    /**
     * 设置格式化输出的最大缩进级别，超过该级别的层级不再换行缩进。
     *
     * @param maxFormatOutIndentLevel 最大缩进级别，小于 1 时按 1 处理
     * @return 当前对象，便于链式调用
     */
    public JSONConfig maxIndentLevel(int maxFormatOutIndentLevel) {
        setMaxIndentLevel(maxFormatOutIndentLevel);
        return this;
    }

    /**
     * 判断是否把日期序列化为时间戳。
     *
     * @return 序列化为时间戳时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isWriteDateAsTime() {
        return writeDateAsTime;
    }

    /**
     * 设置是否把日期序列化为时间戳。
     *
     * @param writeDateAsTime 为 {@code true} 时把日期输出为毫秒时间戳
     */
    public void setWriteDateAsTime(boolean writeDateAsTime) {
        this.writeDateAsTime = writeDateAsTime;
    }

    /**
     * 获取日期格式串。
     *
     * @return 日期格式串，未配置时为 {@code null}（此时按默认的 {@code yyyy-MM-dd HH:mm:ss} 处理）
     */
    public String getDateFormatPattern() {
        return dateFormatPattern;
    }

    /**
     * 设置日期格式串。
     *
     * @param dateFormatPattern 日期格式串，如 {@code yyyy-MM-dd HH:mm:ss}
     */
    public void setDateFormatPattern(String dateFormatPattern) {
        this.dateFormatPattern = dateFormatPattern;
    }

    /**
     * 获取日期序列化使用的时区。
     *
     * @return 指定的时区，未配置时为 {@code null}（此时使用系统默认时区）
     */
    public TimeZone getTimezone() {
        return timezone;
    }

    /**
     * 设置日期序列化使用的时区。
     *
     * @param timezone 时区，为 {@code null} 时使用系统默认时区
     */
    public void setTimezone(TimeZone timezone) {
        this.timezone = timezone;
    }

    /**
     * 判断是否跳过循环引用。
     *
     * @return 开启循环引用检测并跳过时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isSkipCircularReference() {
        return skipCircularReference;
    }

    /**
     * 设置是否跳过循环引用，开启后序列化过程会记录对象的 identityHashCode 状态。
     *
     * @param skipCircularReference 为 {@code true} 时检测并跳过循环引用
     */
    public void setSkipCircularReference(boolean skipCircularReference) {
        this.skipCircularReference = skipCircularReference;
    }

    /**
     * 判断 {@code byte[]} 是否按原生数组序列化（而不是转 Base64 字符串）。
     *
     * @return 按原生数组序列化时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isBytesArrayToNative() {
        return bytesArrayToNative;
    }

    /**
     * 设置 {@code byte[]} 是否按原生数组序列化（而不是转 Base64 字符串）。
     *
     * @param bytesArrayToNative 为 {@code true} 时按数字数组输出
     */
    public void setBytesArrayToNative(boolean bytesArrayToNative) {
        this.bytesArrayToNative = bytesArrayToNative;
    }

    /**
     * 判断是否格式化输出。
     *
     * @return 开启格式化（换行缩进）输出时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isFormatOut() {
        return formatOut;
    }

    /**
     * 设置是否格式化输出。
     *
     * @param formatOut 为 {@code true} 时按换行缩进格式化输出
     */
    public void setFormatOut(boolean formatOut) {
        this.formatOut = formatOut;
    }

    /**
     * 判断格式化输出时冒号后是否补一个空格。
     *
     * @return 冒号后补空格时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isFormatOutColonSpace() {
        return formatOutColonSpace;
    }

    /**
     * 设置格式化输出时冒号后是否补一个空格。
     *
     * @param formatOutColonSpace 为 {@code true} 时在键值分隔的冒号后补一个空格
     */
    public void setFormatOutColonSpace(boolean formatOutColonSpace) {
        this.formatOutColonSpace = formatOutColonSpace;
    }

    /**
     * 判断是否输出全属性（包含值为 null 的属性）。
     *
     * @return 输出全属性时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isFullProperty() {
        return fullProperty;
    }

    /**
     * 设置是否输出全属性（包含值为 null 的属性）。
     *
     * @param fullProperty 为 {@code true} 时输出全部属性
     */
    public void setFullProperty(boolean fullProperty) {
        this.fullProperty = fullProperty;
    }

    /**
     * 判断是否按格式串格式化日期对象。
     *
     * @return 开启日期格式化时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isDateFormat() {
        return dateFormat;
    }

    /**
     * 设置是否按格式串格式化日期对象（默认 {@code yyyy-MM-dd HH:mm:ss}）。
     *
     * @param dateFormat 为 {@code true} 时把日期输出为格式化字符串
     */
    public void setDateFormat(boolean dateFormat) {
        this.dateFormat = dateFormat;
    }

    /**
     * 判断 {@code byte[]} 是否序列化为 16 进制字符串。
     *
     * @return 序列化为 16 进制字符串时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isBytesArrayToHex() {
        return bytesArrayToHex;
    }

    /**
     * 设置 {@code byte[]} 是否序列化为 16 进制字符串。
     *
     * @param bytesArrayToHex 为 {@code true} 时输出 16 进制字符串
     */
    public void setBytesArrayToHex(boolean bytesArrayToHex) {
        this.bytesArrayToHex = bytesArrayToHex;
    }

    /**
     * 判断是否忽略字符串的转义检查。
     *
     * @return 忽略转义检查时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isIgnoreEscapeCheck() {
        return ignoreEscapeCheck;
    }

    /**
     * 设置是否忽略字符串的转义检查，确认内容无需转义时可提升性能。
     *
     * @param ignoreEscapeCheck 为 {@code true} 时跳过转义字符检查
     */
    public void setIgnoreEscapeCheck(boolean ignoreEscapeCheck) {
        this.ignoreEscapeCheck = ignoreEscapeCheck;
    }

    /**
     * 判断是否跳过没有对应字段的 getter 方法。
     *
     * @return 跳过无字段 getter 时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isSkipGetterOfNoneField() {
        return skipGetterOfNoneField;
    }

    /**
     * 设置是否跳过没有对应字段的 getter 方法。
     *
     * @param skipGetterOfNoneField 为 {@code true} 时不序列化没有同名字段的 getter
     */
    public void setSkipGetterOfNoneField(boolean skipGetterOfNoneField) {
        this.skipGetterOfNoneField = skipGetterOfNoneField;
    }

    /**
     * 判断序列化到流后是否自动关闭流。
     *
     * @return 自动关闭时返回 {@code true}，否则返回 {@code false}；默认为 {@code true}
     */
    public boolean isAutoCloseStream() {
        return autoCloseStream;
    }

    /**
     * 设置序列化到流后是否自动关闭流。
     *
     * @param autoCloseStream 为 {@code true} 时写完自动关闭输出流
     */
    public void setAutoCloseStream(boolean autoCloseStream) {
        this.autoCloseStream = autoCloseStream;
    }

    /**
     * 判断是否允许 map 的 key 不加双引号（按实际类型输出）。
     *
     * @return 允许不加双引号时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isAllowUnquotedMapKey() {
        return allowUnquotedMapKey;
    }

    /**
     * 设置是否允许 map 的 key 不加双引号（按实际类型输出）。
     *
     * @param allowUnquotedMapKey 为 {@code true} 时 key 按实际类型序列化而不是双引号包围
     */
    public void setAllowUnquotedMapKey(boolean allowUnquotedMapKey) {
        this.allowUnquotedMapKey = allowUnquotedMapKey;
    }

    /**
     * 判断是否直接使用字段（而非 getter）进行序列化。
     *
     * @return 使用字段序列化时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isUseFields() {
        return useFields;
    }

    /**
     * 设置是否直接使用字段（而非 getter）进行序列化。
     *
     * @param useFields 为 {@code true} 时按字段取值序列化
     */
    public void setUseFields(boolean useFields) {
        this.useFields = useFields;
    }

    /**
     * 判断输出的属性名是否由驼峰转下划线。
     *
     * @return 驼峰转下划线时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isCamelCaseToUnderline() {
        return camelCaseToUnderline;
    }

    /**
     * 设置输出的属性名是否由驼峰转下划线。
     *
     * @param camelCaseToUnderline 为 {@code true} 时把属性名从驼峰转为下划线风格
     */
    public void setCamelCaseToUnderline(boolean camelCaseToUnderline) {
        this.camelCaseToUnderline = camelCaseToUnderline;
    }

    /**
     * 判断枚举是否序列化为 ordinal 序号。
     *
     * @return 序列化为序号时返回 {@code true}，序列化为名称时返回 {@code false}
     */
    public boolean isWriteEnumAsOrdinal() {
        return writeEnumAsOrdinal;
    }

    /**
     * 判断数字是否序列化为字符串。
     *
     * @return 数字输出为字符串时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isWriteNumberAsString() {
        return writeNumberAsString;
    }

    /**
     * 设置数字是否序列化为字符串。
     *
     * @param writeNumberAsString 为 {@code true} 时把数字类型输出为带双引号的字符串
     */
    public void setWriteNumberAsString(boolean writeNumberAsString) {
        this.writeNumberAsString = writeNumberAsString;
    }

    /**
     * 设置枚举是否序列化为 ordinal 序号。
     *
     * @param writeEnumAsOrdinal 为 {@code true} 时输出枚举序号，否则输出枚举名称
     */
    public void setWriteEnumAsOrdinal(boolean writeEnumAsOrdinal) {
        this.writeEnumAsOrdinal = writeEnumAsOrdinal;
    }

    /**
     * 判断是否输出对象的类型信息。
     *
     * @return 输出类型信息时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isWriteClassName() {
        return writeClassName;
    }

    /**
     * 设置是否输出对象的类型信息。
     *
     * @param writeClassName 为 {@code true} 时在序列化结果中写入类名
     */
    public void setWriteClassName(boolean writeClassName) {
        this.writeClassName = writeClassName;
    }

    void setStatus(int hashcode, int status) {
        if (skipCircularReference) {
            Map<Integer, Integer> hashCodeStatus = getOrSetIdentityHashCodes();
            hashCodeStatus.put(hashcode, status);
        }
    }

    private synchronized Map<Integer, Integer> getOrSetIdentityHashCodes() {
        if (identityHashCodes == null) {
            identityHashCodes = new HashMap<Integer, Integer>();
        }
        return identityHashCodes;
    }

    int getStatus(int hashcode) {
        Map<Integer, Integer> hashCodeStatus = getOrSetIdentityHashCodes();
        if (hashCodeStatus.containsKey(hashcode)) {
            return hashCodeStatus.get(hashcode);
        }
        return -1;
    }

    /**
     * 清理循环引用检测过程中记录的对象状态；未开启循环引用检测时不做任何处理。
     */
    public void clear() {
        if (skipCircularReference) {
            if (identityHashCodes != null) {
                identityHashCodes.clear();
            }
        }
    }
}
