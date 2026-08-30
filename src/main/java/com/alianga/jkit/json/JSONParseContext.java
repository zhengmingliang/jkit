package com.alianga.jkit.json;

import com.alianga.jkit.json.options.ReadOption;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * json解析上下文配置
 *
 * @time 2021/12/19 14:47
 */
public class JSONParseContext {
    JSONParseContext() {
    }

    /***
     * 解析上下文结束位置
     */
    public int endIndex;

    /**
     * JSON写入器
     * （Character builder）
     */
    JSONCharArrayWriter writer;

    /**
     * 从16进制字符串中转化为字节数组
     */
    public boolean byteArrayFromHexString;

    /**
     * 是否将未知的枚举类型解析为null
     */
    public boolean unknownEnumAsNull;

    /**
     * 允许key字段使用单引号
     */
    public boolean allowSingleQuotes;

    /**
     * 允许key字段没有双引号
     */
    public boolean allowUnquotedFieldNames;

    /**
     * 允许注释
     */
    public boolean allowComment;

    /**
     * 是否允许对象属性或者集合元素中最后一个元素后面是逗号（非JSON标准格式）
     */
    public boolean allowLastEndComma;

    /**
     * 接口或者抽象类无法实例化时启用属性默认值
     */
    public boolean useDefaultFieldInstance;

    /**
     * 使用BigDecimal作为默认number解析
     */
    public boolean useBigDecimalAsDefault;

    /**
     * 禁用cache key
     */
    public boolean disableCacheMapKey;
    /**
     * 未匹配到目标类型的空字符串（{@code ""}）是否解析为 null
     */
    public boolean unMatchedEmptyAsNull;
    /**
     * 严格模式，开启后字段名匹配除比较哈希值外还会逐字符比较，避免哈希碰撞导致的误匹配
     */
    public boolean strictMode;
    int toIndex;
    boolean multiple;
    boolean escape = true;
    int escapeOffset = -1;
    private String[] strings;
    /**
     * 最近一次解析的数组（集合）元素个数
     */
    protected int elementSize;

    // 开启校验模式（调用validate方法时）
    boolean validate;
    boolean validateFail;

    static JSONParseContext of(ReadOption[] readOptions) {
        JSONParseContext parseContext = new JSONParseContext();
        JSONOptions.readOptions(readOptions, parseContext);
        return parseContext;
    }

    void setIgnoreEscapeCheck() {
        escape = false;
    }

    void setContextWriter(JSONCharArrayWriter writer) {
        this.writer = writer;
    }

    JSONCharArrayWriter getContextWriter() {
        if (writer != null) {
            writer.clear();
        }
        return writer;
    }

    String[] getContextStrings() {
        if (strings == null) {
            strings = new String[32];
        }
        return strings;
    }

    void clear() {
        if (writer != null) {
            writer.reset();
            writer = null;
        }
        strings = null;
    }

    final boolean checkEscapeBackslashJDK16(String input, int fromIndex, int endIndex) {
        if (!escape || endIndex < escapeOffset) {
            return false;
        }
        if (fromIndex > escapeOffset) {
            escapeOffset = input.indexOf('\\', fromIndex);
            escape = escapeOffset > -1;
            if (!escape) {
                return false;
            }
        }
        return endIndex > escapeOffset;
    }

//    final boolean checkEscapeBackslashJDK9(String input, int fromIndex, int endIndex) {
//        if(!escape || endIndex < escapeOffset) return false;
//        if(fromIndex > escapeOffset) {
//            escapeOffset = input.indexOf("\\", fromIndex);
//            escape = escapeOffset > -1;
//            if(!escape) return false;
//        }
//        return endIndex > escapeOffset;
//    }

//    final boolean checkEscapeBackslashJDK9(String input, byte[] bytes, int fromIndex, int endIndex) {
//        if(!escape || endIndex < escapeOffset) return false;
//        if(fromIndex > escapeOffset) {
//            // input.indexOf("\\", fromIndex);
//            escapeOffset = JSONGeneral.indexOfTokenUseUnsafeJDK9(input, "\\", bytes, fromIndex, '\\', JSONGeneral
//            .BACKSLASH_MASK);
//            escape = escapeOffset > -1;
//            if(!escape) return false;
//        }
//        return endIndex > escapeOffset;
//    }

    final int getEscapeOffset() {
        return escapeOffset;
    }

    JSONKeyValueMap<String> getTable32() {
        return JSONGeneral.KEY_32_TABLE;
    }

    JSONKeyValueMap<String> getTable8() {
        return JSONGeneral.KEY_8_TABLE;
    }

    /**
     * 从长度不超过 8 的字符片段中获取缓存的 key 字符串。
     *
     * @param buf       字符缓冲区
     * @param offset    key 的起始下标
     * @param len       key 的字符长度
     * @param hashValue 由 key 内容计算出的哈希值
     * @return 缓存中已存在的 key 实例，不存在时创建并放入缓存后返回
     */
    protected final String getCacheEightCharsKey(char[] buf, int offset, int len, long hashValue) {
        return JSONGeneral.getCacheEightCharsKey(buf, offset, len, hashValue, getTable8());
    }

    /**
     * 从字符片段中获取缓存的 key 字符串。
     *
     * @param buf       字符缓冲区
     * @param offset    key 的起始下标
     * @param len       key 的字符长度
     * @param hashValue 由 key 内容计算出的哈希值
     * @return 缓存中已存在的 key 实例，不存在时创建并放入缓存后返回
     */
    protected final String getCacheKey(char[] buf, int offset, int len, long hashValue) {
        return JSONGeneral.getCacheKey(buf, offset, len, hashValue, getTable32());
    }

    /**
     * 从长度不超过 8 的字节片段中获取缓存的 key 字符串。
     *
     * @param bytes     字节缓冲区
     * @param offset    key 的起始下标
     * @param len       key 的字节长度
     * @param hashValue 由 key 内容计算出的哈希值
     * @return 缓存中已存在的 key 实例，不存在时创建并放入缓存后返回
     */
    protected final String getCacheEightBytesKey(byte[] bytes, int offset, int len, long hashValue) {
        return JSONGeneral.getCacheEightBytesKey(bytes, offset, len, hashValue, getTable8());
    }

    /**
     * 从字节片段中获取缓存的 key 字符串。
     *
     * @param bytes     字节缓冲区
     * @param offset    key 的起始下标
     * @param len       key 的字节长度
     * @param hashValue 由 key 内容计算出的哈希值
     * @return 缓存中已存在的 key 实例，不存在时创建并放入缓存后返回
     */
    protected final String getCacheKey(byte[] bytes, int offset, int len, long hashValue) {
        return JSONGeneral.getCacheKey(bytes, offset, len, hashValue, getTable32());
    }

    /**
     * 创建解析 JSON 对象时使用的默认 Map 实现。
     *
     * @return 初始容量为 10 的空 {@link LinkedHashMap}
     */
    public Map<Serializable, Object> defaultMap() {
        return new LinkedHashMap<Serializable, Object>(10);
    }

    /**
     * 创建解析 JSON 数组时使用的默认 List 实现。
     *
     * @return 初始容量为 10 的空 {@link ArrayList}
     */
    public List<?> defaultList() {
        return new ArrayList<Object>(10);
    }
}
