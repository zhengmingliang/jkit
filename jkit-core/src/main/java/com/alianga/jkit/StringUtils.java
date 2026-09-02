package com.alianga.jkit;

import com.alianga.jkit.beans.ObjectUtils;
import com.alianga.jkit.collection.ArrayUtils;
import com.alianga.jkit.collection.Collections;
import com.alianga.jkit.json.internal.utils.RegexUtils;
import com.alianga.jkit.reflect.UnsafeHelper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author 郑明亮
 * @time 2017年2月1日 下午8:48:58
 * @description <p>字符串相关工具类 </p>
 */
public class StringUtils {
    private static final String FOLDER_SEPARATOR = "/";

    private static final String WINDOWS_FOLDER_SEPARATOR = "\\";

    private static final String TOP_PATH = "..";

    private static final String CURRENT_PATH = ".";

    private static final char EXTENSION_SEPARATOR = '.';

    /**
     * 字符被截断后缀拼接内容
     */
    private static final String TRUNCATION_SUFFIX = " (truncated)...";
    /**
     * 字符未找到时，返回的下标值
     */
    public static final int INDEX_NOT_FOUND = -1;
    /**
     * The empty String {@code ""}.
     *
     * @since 1.4.2
     */
    public static final String EMPTY = "";

    private StringUtils() {
        throw new UnsupportedOperationException("Can not be instantiated...");
    }

    /**
     * @param str Unicode编码文本
     * @return 解码后的文本
     * @author 郑明亮
     * @time 2017年1月23日 上午9:58:07
     * @description <p> 将Unicode编码的文本进行解码</p>
     */
    public static String unicodeToString(String str) {
        Pattern pattern = Pattern.compile("(\\\\u(\\p{XDigit}{4}))");
        Matcher matcher = pattern.matcher(str);
        char ch;
        while (matcher.find()) {
            ch = (char) Integer.parseInt(matcher.group(2), 16);
            str = str.replace(matcher.group(1), ch + "");
        }
        return str;
    }

    /**
     * @param s 想要进行编码的文本
     * @return Unicode编码 文本
     * @author 郑明亮
     * @time 2017年1月23日 上午9:57:20
     * @description <p>将中文转换为Unicode编码 </p>
     */
    public static String getUnicode(String s) {
        try {
            StringBuffer out = new StringBuffer();
            byte[] bytes = s.getBytes("unicode");
            for (int i = 0; i < bytes.length - 1; i += 2) {
                out.append("\\u");
                String str = Integer.toHexString(bytes[i + 1] & 0xff);
                for (int j = str.length(); j < 2; j++) {
                    out.append("0");
                }
                String str1 = Integer.toHexString(bytes[i] & 0xff);
                out.append(str1);
                out.append(str);

            }
            return out.toString();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 将 inStr 转为 UTF-8 的编码形式
     *
     * @param inStr 输入字符串
     * @return UTF - 8 的编码形式的字符串
     * @throws UnsupportedEncodingException 字符集不受支持时抛出
     */
    public static String ISO2UTF(String inStr) throws UnsupportedEncodingException {
        String outStr = "";
        if (inStr != null) {
            outStr = new String(inStr.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        }
        return outStr;
    }

    /**
     * 判断是否为 {@code null} 或长度为 0 的字符序列
     *
     * <p>只按「长度是否为 0」判断，<b>不</b>去除首尾空白：全是空格的字符串不算空。
     * 需要「空白也算空」的语义请用 {@link #isBlank(CharSequence)}。
     * 非字符序列的对象一律视为非空。</p>
     *
     * @param s 待校验对象，可为 {@code null}
     * @return {@code true}: 为 {@code null} 或长度为 0 {@code false}: 不为空
     */
    public static boolean isEmpty(Object s) {
        if (s == null) {
            return true;
        }
        // 按长度判断而非与 "" 比较，否则空的 StringBuilder 等非 String 字符序列会被误判为非空
        if (s instanceof CharSequence) {
            return ((CharSequence) s).length() == 0;
        }
        return false;
    }

    /**
     * 判断字符序列是否既不为 {@code null}、长度也不为 0
     *
     * @param s 待校验字符序列，可为 {@code null}
     * @return {@code false}: 为 {@code null} 或长度为 0 {@code true}: 不为空
     * @author 郑明亮
     * @time 2017年2月6日 上午11:13:48
     */
    public static boolean isNotEmpty(CharSequence s) {
        return !isEmpty(s);
    }

    /**
     * <p>Checks if a CharSequence is empty (""), null or whitespace only.</p>
     *
     * <p>Whitespace is defined by {@link Character#isWhitespace(char)}.</p>
     *
     * <pre>
     * StringUtils.isBlank(null) = true
     * StringUtils.isBlank("") = true
     * StringUtils.isBlank(" ") = true
     * StringUtils.isBlank("bob") = false
     * StringUtils.isBlank("  bob  ") = false
     * </pre>
     *
     * @param cs the CharSequence to check, may be null
     * @return {@code true} if the CharSequence is null, empty or whitespace only
     * @since 2.0
     * @since 3.0 Changed signature from isBlank(String) to isBlank(CharSequence)
     */
    public static boolean isBlank(final CharSequence cs) {
        int strLen;
        if (cs == null || (strLen = cs.length()) == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if (!Character.isWhitespace(cs.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * <p>Checks if a CharSequence is not empty (""), not null and not whitespace only.</p>
     *
     * <p>Whitespace is defined by {@link Character#isWhitespace(char)}.</p>
     *
     * <pre>
     * StringUtils.isNotBlank(null) = false
     * StringUtils.isNotBlank("") = false
     * StringUtils.isNotBlank(" ") = false
     * StringUtils.isNotBlank("bob") = true
     * StringUtils.isNotBlank("  bob  ") = true
     * </pre>
     *
     * @param cs the CharSequence to check, may be null
     * @return {@code true} if the CharSequence is
     * not empty and not null and not whitespace only
     * @since 2.0
     * @since 3.0 Changed signature from isNotBlank(String) to isNotBlank(CharSequence)
     */
    public static boolean isNotBlank(final CharSequence cs) {
        return !isBlank(cs);
    }

    /**
     * <p>Checks if any of the CharSequences are empty ("") or null or whitespace only.</p>
     *
     * <p>Whitespace is defined by {@link Character#isWhitespace(char)}.</p>
     *
     * <pre>
     * StringUtils.isAnyBlank((String) null) = true
     * StringUtils.isAnyBlank((String[]) null) = false
     * StringUtils.isAnyBlank(null, "foo") = true
     * StringUtils.isAnyBlank(null, null) = true
     * StringUtils.isAnyBlank("", "bar") = true
     * StringUtils.isAnyBlank("bob", "") = true
     * StringUtils.isAnyBlank("  bob  ", null) = true
     * StringUtils.isAnyBlank(" ", "bar") = true
     * StringUtils.isAnyBlank(new String[] {}) = false
     * StringUtils.isAnyBlank(new String[]{""}) = true
     * StringUtils.isAnyBlank("foo", "bar") = false
     * </pre>
     *
     * @param css the CharSequences to check, may be null or empty
     * @return {@code true} if any of the CharSequences are empty or null or whitespace only
     * @since 3.2
     */
    public static boolean isAnyBlank(final CharSequence... css) {
        if (ArrayUtils.isEmpty(css)) {
            return false;
        }
        for (final CharSequence cs : css) {
            if (isBlank(cs)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <p>Checks if none of the CharSequences are empty (""), null or whitespace only.</p>
     *
     * <p>Whitespace is defined by {@link Character#isWhitespace(char)}.</p>
     *
     * <pre>
     * StringUtils.isNoneBlank((String) null) = false
     * StringUtils.isNoneBlank((String[]) null) = true
     * StringUtils.isNoneBlank(null, "foo") = false
     * StringUtils.isNoneBlank(null, null) = false
     * StringUtils.isNoneBlank("", "bar") = false
     * StringUtils.isNoneBlank("bob", "") = false
     * StringUtils.isNoneBlank("  bob  ", null) = false
     * StringUtils.isNoneBlank(" ", "bar") = false
     * StringUtils.isNoneBlank(new String[] {}) = true
     * StringUtils.isNoneBlank(new String[]{""}) = false
     * StringUtils.isNoneBlank("foo", "bar") = true
     * </pre>
     *
     * @param css the CharSequences to check, may be null or empty
     * @return {@code true} if none of the CharSequences are empty or null or whitespace only
     * @since 3.2
     */
    public static boolean isNoneBlank(final CharSequence... css) {
        return !isAnyBlank(css);
    }

    /**
     * <p>Checks if all of the CharSequences are empty (""), null or whitespace only.</p>
     *
     * <p>Whitespace is defined by {@link Character#isWhitespace(char)}.</p>
     *
     * <pre>
     * StringUtils.isAllBlank(null) = true
     * StringUtils.isAllBlank(null, "foo") = false
     * StringUtils.isAllBlank(null, null) = true
     * StringUtils.isAllBlank("", "bar") = false
     * StringUtils.isAllBlank("bob", "") = false
     * StringUtils.isAllBlank("  bob  ", null) = false
     * StringUtils.isAllBlank(" ", "bar") = false
     * StringUtils.isAllBlank("foo", "bar") = false
     * StringUtils.isAllBlank(new String[] {}) = true
     * </pre>
     *
     * @param css the CharSequences to check, may be null or empty
     * @return {@code true} if all of the CharSequences are empty or null or whitespace only
     * @since 3.6
     */
    public static boolean isAllBlank(final CharSequence... css) {
        if (ArrayUtils.isEmpty(css)) {
            return true;
        }
        for (final CharSequence cs : css) {
            if (isNotBlank(cs)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断是否存在为 {@code null} 或长度为 0 的字符序列
     *
     * <p>与 {@link #isAnyBlank(CharSequence...)} 的区别是本方法不把纯空白当作空。</p>
     *
     * <pre>
     * StringUtils.isAnyEmpty((CharSequence) null) = true
     * StringUtils.isAnyEmpty((CharSequence[]) null) = false
     * StringUtils.isAnyEmpty(new String[] {}) = false
     * StringUtils.isAnyEmpty("", "bar") = true
     * StringUtils.isAnyEmpty(" ", "bar") = false
     * StringUtils.isAnyEmpty("foo", "bar") = false
     * </pre>
     *
     * @param css 待校验的字符序列，数组本身可为 {@code null} 或空
     * @return 任一为 {@code null} 或长度为 0 时返回 {@code true}；数组为 {@code null} 或空时返回 {@code false}
     */
    public static boolean isAnyEmpty(final CharSequence... css) {
        if (ArrayUtils.isEmpty(css)) {
            return false;
        }
        for (final CharSequence cs : css) {
            if (isEmpty(cs)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否全部既不为 {@code null} 长度也不为 0
     *
     * @param css 待校验的字符序列，数组本身可为 {@code null} 或空
     * @return 全部非空时返回 {@code true}；数组为 {@code null} 或空时返回 {@code true}
     */
    public static boolean isNoneEmpty(final CharSequence... css) {
        return !isAnyEmpty(css);
    }

    /**
     * 判断是否全部为 {@code null} 或长度为 0
     *
     * @param css 待校验的字符序列，数组本身可为 {@code null} 或空
     * @return 全部为空时返回 {@code true}；数组为 {@code null} 或空时返回 {@code true}
     */
    public static boolean isAllEmpty(final CharSequence... css) {
        if (ArrayUtils.isEmpty(css)) {
            return true;
        }
        for (final CharSequence cs : css) {
            if (isNotEmpty(cs)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断字符序列是否包含指定子序列
     *
     * <p>任一入参为 {@code null} 时返回 {@code false}。</p>
     *
     * @param seq 被搜索的字符序列，可为 {@code null}
     * @param searchSeq 待查找的子序列，可为 {@code null}
     * @return 包含时返回 {@code true}
     */
    public static boolean contains(final CharSequence seq, final CharSequence searchSeq) {
        if (seq == null || searchSeq == null) {
            return false;
        }
        return indexOf(seq, searchSeq, 0) >= 0;
    }

    /**
     * 判断字符序列是否包含给定字符中的任意一个
     *
     * <p>正确处理代理对：只有完整匹配到一个增补字符时才算命中，
     * 不会因为高低代理项单独相等而误报。</p>
     *
     * @param cs 被搜索的字符序列，可为 {@code null}
     * @param searchChars 待查找的字符集合，可为 {@code null} 或空
     * @return 命中任一字符时返回 {@code true}
     */
    public static boolean containsAny(final CharSequence cs, final char... searchChars) {
        if (isEmpty(cs) || searchChars == null || searchChars.length == 0) {
            return false;
        }
        final int csLength = cs.length();
        final int searchLength = searchChars.length;
        final int csLast = csLength - 1;
        final int searchLast = searchLength - 1;
        for (int i = 0; i < csLength; i++) {
            final char ch = cs.charAt(i);
            for (int j = 0; j < searchLength; j++) {
                if (searchChars[j] != ch) {
                    continue;
                }
                if (!Character.isHighSurrogate(ch)) {
                    return true;
                }
                if (j == searchLast) {
                    // 待查集合里只有高代理项而没有配对的低代理项，无法构成完整字符
                    return true;
                }
                if (i < csLast && searchChars[j + 1] == cs.charAt(i + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断字符序列是否包含给定子序列中的任意一个
     *
     * @param cs 被搜索的字符序列，可为 {@code null}
     * @param searchCharSequences 待查找的子序列集合，可为 {@code null} 或空
     * @return 命中任一子序列时返回 {@code true}
     */
    public static boolean containsAny(final CharSequence cs, final CharSequence... searchCharSequences) {
        if (isEmpty(cs) || ArrayUtils.isEmpty(searchCharSequences)) {
            return false;
        }
        for (final CharSequence searchCharSequence : searchCharSequences) {
            if (contains(cs, searchCharSequence)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 忽略大小写查找子序列首次出现的位置
     *
     * @param str 被搜索的字符序列，可为 {@code null}
     * @param searchStr 待查找的子序列，可为 {@code null}
     * @return 首次出现的下标；任一入参为 {@code null} 或未找到时返回 -1
     */
    public static int indexOfIgnoreCase(final CharSequence str, final CharSequence searchStr) {
        return indexOfIgnoreCase(str, searchStr, 0);
    }

    /**
     * 从指定位置开始忽略大小写查找子序列
     *
     * @param str 被搜索的字符序列，可为 {@code null}
     * @param searchStr 待查找的子序列，可为 {@code null}
     * @param startPos 起始下标，为负数时按 0 处理
     * @return 首次出现的下标；任一入参为 {@code null} 或未找到时返回 -1
     */
    public static int indexOfIgnoreCase(final CharSequence str, final CharSequence searchStr, int startPos) {
        if (str == null || searchStr == null) {
            return INDEX_NOT_FOUND;
        }
        if (startPos < 0) {
            startPos = 0;
        }
        final int endLimit = str.length() - searchStr.length() + 1;
        if (startPos > endLimit) {
            return INDEX_NOT_FOUND;
        }
        if (searchStr.length() == 0) {
            return startPos;
        }
        for (int i = startPos; i < endLimit; i++) {
            if (regionMatches(str, true, i, searchStr, 0, searchStr.length())) {
                return i;
            }
        }
        return INDEX_NOT_FOUND;
    }

    /**
     * 去除首尾空白，结果为空时返回 {@code null}
     *
     * <p>空白的定义与 {@link #isBlank(CharSequence)} 一致（{@link Character#isWhitespace(char)}）。</p>
     *
     * @param str 待处理字符串，可为 {@code null}
     * @return 去除首尾空白后的字符串；入参为 {@code null} 或去除后为空时返回 {@code null}
     */
    public static String trimToNull(final String str) {
        String trimmed = trimWhitespace(str);
        return isEmpty(trimmed) ? null : trimmed;
    }

    /**
     * 字符串为 {@code null} 或长度为 0 时返回默认值
     *
     * <p>与 {@link #defaultString(String, String)} 的区别是本方法也会把长度为 0 的字符串替换为默认值。</p>
     *
     * @param str 待判断的字符串，可为 {@code null}
     * @param defaultStr 默认值，可为 {@code null}
     * @return {@code str} 非空时返回其本身，否则返回 {@code defaultStr}
     */
    public static String defaultIfEmpty(final String str, final String defaultStr) {
        return isEmpty(str) ? defaultStr : str;
    }

    /**
     * 字符串为 {@code null} 或纯空白时返回默认值
     *
     * @param str 待判断的字符串，可为 {@code null}
     * @param defaultStr 默认值，可为 {@code null}
     * @return {@code str} 非空白时返回其本身，否则返回 {@code defaultStr}
     */
    public static String defaultIfBlank(final String str, final String defaultStr) {
        return isBlank(str) ? defaultStr : str;
    }

    /**
     * 只替换第一次出现的子串
     *
     * @param text 源字符串，可为 {@code null}
     * @param searchString 待替换的子串，可为 {@code null}
     * @param replacement 替换成的内容，可为 {@code null}
     * @return 替换后的字符串；任一入参为空或未找到时原样返回 {@code text}
     */
    public static String replaceOnce(final String text, final String searchString, final String replacement) {
        if (isEmpty(text) || isEmpty(searchString) || replacement == null) {
            return text;
        }
        int index = text.indexOf(searchString);
        if (index == INDEX_NOT_FOUND) {
            return text;
        }
        return text.substring(0, index) + replacement + text.substring(index + searchString.length());
    }

    /**
     * 按区域比较两个字符序列，供忽略大小写查找复用
     *
     * @param cs 被比较的字符序列
     * @param ignoreCase 是否忽略大小写
     * @param csOffset {@code cs} 的起始下标
     * @param substring 用于比较的字符序列
     * @param substringOffset {@code substring} 的起始下标
     * @param length 参与比较的长度
     * @return 区域内容一致时返回 {@code true}
     */
    private static boolean regionMatches(final CharSequence cs, final boolean ignoreCase, final int csOffset,
                                         final CharSequence substring, final int substringOffset, final int length) {
        if (cs instanceof String && substring instanceof String) {
            return ((String) cs).regionMatches(ignoreCase, csOffset, (String) substring, substringOffset, length);
        }
        int index1 = csOffset;
        int index2 = substringOffset;
        int tmpLen = length;
        final int srcLen = cs.length() - csOffset;
        final int otherLen = substring.length() - substringOffset;
        if (csOffset < 0 || substringOffset < 0 || length < 0 || srcLen < length || otherLen < length) {
            return false;
        }
        while (tmpLen-- > 0) {
            final char c1 = cs.charAt(index1++);
            final char c2 = substring.charAt(index2++);
            if (c1 == c2) {
                continue;
            }
            if (!ignoreCase) {
                return false;
            }
            if (Character.toUpperCase(c1) != Character.toUpperCase(c2)
                    && Character.toLowerCase(c1) != Character.toLowerCase(c2)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 查找子序列首次出现的位置，供 {@link #contains(CharSequence, CharSequence)} 复用
     *
     * @param cs 被搜索的字符序列
     * @param searchChar 待查找的子序列
     * @param start 起始下标
     * @return 首次出现的下标，未找到返回 -1
     */
    private static int indexOf(final CharSequence cs, final CharSequence searchChar, final int start) {
        if (cs instanceof String) {
            return ((String) cs).indexOf(searchChar.toString(), start);
        }
        return cs.toString().indexOf(searchChar.toString(), start);
    }

    /**
     * <p>Checks if the CharSequence contains only lowercase characters.</p>
     *
     * <p>{@code null} will return {@code false}.
     * An empty CharSequence (length()=0) will return {@code false}.</p>
     *
     * <pre>
     * StringUtils.isAllLowerCase(null) = false
     * StringUtils.isAllLowerCase("") = false
     * StringUtils.isAllLowerCase("  ") = false
     * StringUtils.isAllLowerCase("abc") = true
     * StringUtils.isAllLowerCase("abC") = false
     * StringUtils.isAllLowerCase("ab c") = false
     * StringUtils.isAllLowerCase("ab1c") = false
     * StringUtils.isAllLowerCase("ab/c") = false
     * </pre>
     *
     * @param cs the CharSequence to check, may be null
     * @return {@code true} if only contains lowercase characters, and is non-null
     * @since 1.4.2
     */
    public static boolean isAllLowerCase(final CharSequence cs) {
        if (cs == null || isEmpty(cs)) {
            return false;
        }
        final int sz = cs.length();
        for (int i = 0; i < sz; i++) {
            if (!Character.isLowerCase(cs.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * <p>Checks if the CharSequence contains only uppercase characters.</p>
     *
     * <p>{@code null} will return {@code false}.
     * An empty String (length()=0) will return {@code false}.</p>
     *
     * <pre>
     * StringUtils.isAllUpperCase(null) = false
     * StringUtils.isAllUpperCase("") = false
     * StringUtils.isAllUpperCase("  ") = false
     * StringUtils.isAllUpperCase("ABC") = true
     * StringUtils.isAllUpperCase("aBC") = false
     * StringUtils.isAllUpperCase("A C") = false
     * StringUtils.isAllUpperCase("A1C") = false
     * StringUtils.isAllUpperCase("A/C") = false
     * </pre>
     *
     * @param cs the CharSequence to check, may be null
     * @return {@code true} if only contains uppercase characters, and is non-null
     * @since 2.5
     * @since 3.0 Changed signature from isAllUpperCase(String) to isAllUpperCase(CharSequence)
     */
    public static boolean isAllUpperCase(final CharSequence cs) {
        if (cs == null || isEmpty(cs)) {
            return false;
        }
        final int sz = cs.length();
        for (int i = 0; i < sz; i++) {
            if (!Character.isUpperCase(cs.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * <p>Checks if the CharSequence contains mixed casing of both uppercase and lowercase characters.</p>
     *
     * <p>{@code null} will return {@code false}. An empty CharSequence ({@code length()=0}) will return
     * {@code false}.</p>
     *
     * <pre>
     * StringUtils.isMixedCase(null) = false
     * StringUtils.isMixedCase("") = false
     * StringUtils.isMixedCase("ABC") = false
     * StringUtils.isMixedCase("abc") = false
     * StringUtils.isMixedCase("aBc") = true
     * StringUtils.isMixedCase("A c") = true
     * StringUtils.isMixedCase("A1c") = true
     * StringUtils.isMixedCase("a/C") = true
     * StringUtils.isMixedCase("aC\t") = true
     * </pre>
     *
     * @param cs the CharSequence to check, may be null
     * @return {@code true} if the CharSequence contains both uppercase and lowercase characters
     * @since 1.4.2
     */
    public static boolean isMixedCase(final CharSequence cs) {
        if (isEmpty(cs) || cs.length() == 1) {
            return false;
        }
        boolean containsUppercase = false;
        boolean containsLowercase = false;
        final int sz = cs.length();
        for (int i = 0; i < sz; i++) {
            if (containsUppercase && containsLowercase) {
                return true;
            } else if (Character.isUpperCase(cs.charAt(i))) {
                containsUppercase = true;
            } else if (Character.isLowerCase(cs.charAt(i))) {
                containsLowercase = true;
            }
        }
        return containsUppercase && containsLowercase;
    }

    // Defaults
    //-----------------------------------------------------------------------

    /**
     * <p>Returns either the passed in String,
     * or if the String is {@code null}, an empty String ("").</p>
     *
     * <pre>
     * StringUtils.defaultString(null) = ""
     * StringUtils.defaultString("") = ""
     * StringUtils.defaultString("bat") = "bat"
     * </pre>
     *
     * @param str the String to check, may be null
     * @return the passed in String, or the empty String if it
     * was {@code null}
     * @see String#valueOf(Object)
     */
    public static String defaultString(final String str) {
        return defaultString(str, EMPTY);
    }

    /**
     * <p>Returns either the passed in String, or if the String is
     * {@code null}, the value of {@code defaultStr}.</p>
     *
     * <pre>
     * StringUtils.defaultString(null, "NULL") = "NULL"
     * StringUtils.defaultString("", "NULL") = ""
     * StringUtils.defaultString("bat", "NULL") = "bat"
     * </pre>
     *
     * @param str the String to check, may be null
     * @param defaultStr the default String to return
     * if the input is {@code null}, may be null
     * @return the passed in String, or the default if it was {@code null}
     * @see String#valueOf(Object)
     */
    public static String defaultString(final String str, final String defaultStr) {
        return str == null ? defaultStr : str;
    }

    /**
     * 判断字符串是否为null或全为空格
     *
     * @param s 待校验字符串
     * @return {@code true}: null或全空格 {@code false}: 不为null且不全空格
     */
    public static boolean isSpace(String s) {
        return (s == null || s.trim().length() == 0);
    }

    /**
     * 判断两字符串是否相等
     *
     * @param a 待校验字符串a
     * @param b 待校验字符串b
     * @return {@code true}: 相等{@code false}: 不相等
     */
    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) {
            return true;
        }
        int length;
        if (a != null && b != null && (length = a.length()) == b.length()) {
            if (a instanceof String && b instanceof String) {
                return a.equals(b);
            } else {
                for (int i = 0; i < length; i++) {
                    if (a.charAt(i) != b.charAt(i)) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 判断两字符串忽略大小写是否相等
     *
     * <p>两者同时为 {@code null} 视为相等；只有一方为 {@code null} 视为不相等。</p>
     *
     * @param a 待校验字符串a，可为 {@code null}
     * @param b 待校验字符串b，可为 {@code null}
     * @return {@code true}: 相等{@code false}: 不相等
     */
    public static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            // 同为 null 即相等，避免调用方还要额外判空
            return a == b;
        }
        return a.length() == b.length() && a.regionMatches(true, 0, b, 0, b.length());
    }

    /**
     * null转为长度为0的字符串
     *
     * @param s 待转字符串
     * @return s为null转为长度为0字符串，否则不改变
     */
    public static String null2Length0(String s) {
        return s == null ? "" : s;
    }

    /**
     * 返回字符串长度
     *
     * @param s 字符串
     * @return null返回0，其他返回自身长度
     */
    public static int length(CharSequence s) {
        return s == null ? 0 : s.length();
    }

    /**
     * 首字母大写
     *
     * @param s 待转字符串
     * @return 首字母大写字符串
     */
    public static String upperFirstLetter(String s) {
        if (isEmpty(s) || !Character.isLowerCase(s.charAt(0))) {
            return s;
        }
        return (char) (s.charAt(0) - 32) + s.substring(1);
    }

    /**
     * 首字母小写
     *
     * @param s 待转字符串
     * @return 首字母小写字符串
     */
    public static String lowerFirstLetter(String s) {
        if (isEmpty(s) || !Character.isUpperCase(s.charAt(0))) {
            return s;
        }
        return (char) (s.charAt(0) + 32) + s.substring(1);
    }

    /**
     * 反转字符串
     *
     * @param s 待反转字符串
     * @return 反转字符串
     */
    public static String reverse(String s) {
        int len = length(s);
        if (len <= 1) {
            return s;
        }
        int mid = len >> 1;
        char[] chars = s.toCharArray();
        char c;
        for (int i = 0; i < mid; ++i) {
            c = chars[i];
            chars[i] = chars[len - i - 1];
            chars[len - i - 1] = c;
        }
        return new String(chars);
    }

    /**
     * 转化为半角字符
     *
     * @param s 待转字符串
     * @return 半角字符串
     */
    public static String toDBC(String s) {
        if (isEmpty(s)) {
            return s;
        }
        char[] chars = s.toCharArray();
        for (int i = 0, len = chars.length; i < len; i++) {
            if (chars[i] == 12288) {
                chars[i] = ' ';
            } else if (65281 <= chars[i] && chars[i] <= 65374) {
                chars[i] = (char) (chars[i] - 65248);
            } else {
                chars[i] = chars[i];
            }
        }
        return new String(chars);
    }

    /**
     * 转化为全角字符
     *
     * @param s 待转字符串
     * @return 全角字符串
     */
    public static String toSBC(String s) {
        if (isEmpty(s)) {
            return s;
        }
        char[] chars = s.toCharArray();
        for (int i = 0, len = chars.length; i < len; i++) {
            if (chars[i] == ' ') {
                chars[i] = (char) 12288;
            } else if (33 <= chars[i] && chars[i] <= 126) {
                chars[i] = (char) (chars[i] + 65248);
            } else {
                chars[i] = chars[i];
            }
        }
        return new String(chars);
    }

    /**
     * @param amountString 浮点型金额字符串
     * @return 整型数字符串
     * @author 郑明亮
     * @time 2017年1月12日 下午1:16:39
     * @description <p>去掉浮点类型的小数点，并进行四舍五入 </p>
     * <p>应用场景：将浮点型转换为整型数据，并保留1位小数</p>
     */
    public static String removeAmountPoint(String amountString) {
        String noPointString = "";
        if (amountString == null) {
            return noPointString;
        } else {
            Integer amount;
            if (amountString.indexOf(".") != INDEX_NOT_FOUND) {
                String pointNum = amountString.substring(amountString.indexOf("."));
                amount = Integer.parseInt(amountString.replace(pointNum, ""));
                if (pointNum.length() > 2) {
                    int num = Integer.parseInt(pointNum.substring(1, 2));
                    if (num >= 5) {
                        amount++;

                    }
                }
                noPointString = "" + amount;
            } else {
                return amountString;
            }
            return noPointString;
        }

    }

    /**
     * @param amount 金额
     * @param pattern 将数字转换为指定格式
     * <p>如 amount： 10000000 ，当传入金额为null时，默认为0 </p>
     * <p>如 pattern： \u00A5,###.00 ，当pattern为null时，使用默认pattern ,即"\u00A5,##0.00" </p>
     * <p>如 return： ¥10,000,000 </p>
     * @return 格式化后的数字字符串
     * @throws ParseException 金额字符串无法解析为数字时抛出
     * @author 郑明亮
     * @time 2017年1月13日 下午4:18:14
     */
    public static String formateAmount(Object amount, String pattern) throws ParseException {
        if (amount == null) { //当传入金额为null时，默认为0
            return formateAmount(0, pattern);
        }
        if (pattern == null) { //当pattern为null时，使用默认pattern
            pattern = "\u00A5,##0.00";
        }
        if (amount instanceof String) {
            return formateAmount(Double.parseDouble(amount.toString()), pattern);
        }
        DecimalFormat format = new DecimalFormat(pattern);
        String number = format.format(amount);
        return number;
    }

    /**
     * 判断字符序列是否有长度（非 null 且长度大于 0，空白字符也算有长度）。
     *
     * @param str 待判断的字符序列，可为 {@code null}
     * @return 非 {@code null} 且长度大于 0 时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean hasLength(CharSequence str) {
        return (str != null && str.length() > 0);
    }

    /**
     * Replace all occurrences of a substring within a string with
     * another string.
     *
     * @param inString {@code String} to examine
     * @param oldPattern {@code String} to replace
     * @param newPattern {@code String} to insert
     * @return a {@code String} with the replacements
     */
    public static String replace(String inString, String oldPattern, String newPattern) {
        if (!hasLength(inString) || !hasLength(oldPattern) || newPattern == null) {
            return inString;
        }
        StringBuilder sb = new StringBuilder();
        int pos = 0; // our position in the old string
        int index = inString.indexOf(oldPattern);
        // the index of an occurrence we've found, or -1
        int patLen = oldPattern.length();
        while (index >= 0) {
            sb.append(inString, pos, index);
            sb.append(newPattern);
            pos = index + patLen;
            index = inString.indexOf(oldPattern, pos);
        }
        sb.append(inString.substring(pos));
        // remember to append any characters to the right of a match
        return sb.toString();
    }

    /**
     * 用另一个字符串替换字符串中所有出现的子字符串.
     *
     * @param text {@code String} 要进行替换字符的文本
     * @param searchChar {@code char} 要查找去替换的字符
     * @param replacement {@code String} 要替换为的字符串
     * @return a {@code String} 返回替换为新字符的字符串
     * @since 1.3.3
     */
    public static String replace(String text, char searchChar, String replacement) {
        if (!hasLength(text) || replacement == null) {
            return text;
        }
        int index = text.indexOf(searchChar);
        if (index == INDEX_NOT_FOUND) {
            return text;
        }
        char[] chars = text.toCharArray();
        int length = text.length();
        if (replacement.length() > 1) {
            length += 10;
        }
        StringBuilder builder = new StringBuilder(length);
        builder.append(text, 0, index).append(replacement);
        for (int i = index + 1; i < chars.length; i++) {
            if (chars[i] == searchChar) {
                builder.append(replacement);
            } else {
                builder.append(chars[i]);
            }
        }

        return builder.toString();
    }

    /**
     * 规范化路径，抑制 {@code "path/.."} 与内部的 {@code "."} 片段。
     *
     * <p>结果便于路径比较；注意 Windows 分隔符 {@code "\"} 会被替换为 {@code "/"}。
     *
     * <p>这是全库路径规范化的唯一实现，{@link FileUtils#cleanPath(String)} 转调此方法。
     *
     * <p><strong>注意</strong>：不要依赖本方法解决安全问题，请用其他机制预防路径遍历。
     *
     * @param path 原始路径
     * @return 规范化后的路径
     */
    public static String cleanPath(String path) {
        if (!hasLength(path)) {
            return path;
        }

        String normalizedPath = replace(path, WINDOWS_FOLDER_SEPARATOR, FOLDER_SEPARATOR);
        String pathToUse = normalizedPath;

        // 没有 '.' 时无需规范化，直接短路
        if (pathToUse.indexOf('.') == INDEX_NOT_FOUND) {
            return pathToUse;
        }

        // 先剥离前缀，避免把它当作第一个路径片段。这样 "file:core/../core/io/Resource.class"
        // 中的 ".." 只会消掉 "core"，而保留 "file:" 前缀。
        int prefixIndex = pathToUse.indexOf(':');
        String prefix = "";
        if (prefixIndex != INDEX_NOT_FOUND) {
            prefix = pathToUse.substring(0, prefixIndex + 1);
            if (prefix.contains(FOLDER_SEPARATOR)) {
                prefix = "";
            } else {
                pathToUse = pathToUse.substring(prefixIndex + 1);
            }
        }
        if (pathToUse.startsWith(FOLDER_SEPARATOR)) {
            prefix = prefix + FOLDER_SEPARATOR;
            pathToUse = pathToUse.substring(1);
        }

        String[] pathArray = delimitedListToStringArray(pathToUse, FOLDER_SEPARATOR);
        // 片段数不会超过 pathArray，常见情况下正好相等
        Deque<String> pathElements = new ArrayDeque<String>(pathArray.length);
        int tops = 0;

        for (int i = pathArray.length - 1; i >= 0; i--) {
            String element = pathArray[i];
            if (CURRENT_PATH.equals(element)) {
                // 指向当前目录，丢弃
            } else if (TOP_PATH.equals(element)) {
                tops++;
            } else {
                if (tops > 0) {
                    // 与前面记录的 ".." 相互抵消
                    tops--;
                } else {
                    pathElements.addFirst(element);
                }
            }
        }

        // 所有片段都保留下来了，直接短路
        if (pathArray.length == pathElements.size()) {
            return normalizedPath;
        }
        // 多余的 ".." 需要保留
        for (int i = 0; i < tops; i++) {
            pathElements.addFirst(TOP_PATH);
        }
        // 如果什么都不剩，至少显式指向当前路径
        if (pathElements.size() == 1 && pathElements.getLast().isEmpty() && !prefix.endsWith(FOLDER_SEPARATOR)) {
            pathElements.addFirst(CURRENT_PATH);
        }

        final String joined = collectionToDelimitedString(pathElements, FOLDER_SEPARATOR);
        return prefix.isEmpty() ? joined : prefix + joined;
    }

    /**
     * Compare two paths after normalization of them.
     *
     * @param path1 first path for comparison
     * @param path2 second path for comparison
     * @return whether the two paths are equivalent after normalization
     */
    public static boolean pathEquals(String path1, String path2) {
        return cleanPath(path1).equals(cleanPath(path2));
    }

    /**
     * Delete any character in a given {@code String}.
     *
     * @param inString the original {@code String}
     * @param charsToDelete a set of characters to delete.
     * E.g. "az\n" will delete 'a's, 'z's and new lines.
     * @return the resulting {@code String}
     */
    public static String deleteAny(String inString, String charsToDelete) {
        if (!hasLength(inString) || !hasLength(charsToDelete)) {
            return inString;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < inString.length(); i++) {
            char c = inString.charAt(i);
            if (charsToDelete.indexOf(c) == INDEX_NOT_FOUND) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Turn given source {@code String} array into sorted array.
     *
     * @param array the source array
     * @return the sorted array (never {@code null})
     */
    public static String[] sortStringArray(String[] array) {
        if (Objects.isEmpty(array)) {
            return new String[0];
        }
        Arrays.sort(array);
        return array;
    }

    /**
     * Copy the given {@code Collection} into a {@code String} array.
     * <p>The {@code Collection} must contain {@code String} elements only.
     *
     * @param collection the {@code Collection} to copy
     * @return the {@code String} array ({@code null} if the supplied
     * {@code Collection} was {@code null})
     */
    public static String[] toStringArray(Collection<String> collection) {
        if (collection == null) {
            return null;
        }
        return collection.toArray(new String[collection.size()]);
    }

    /**
     * Take a {@code String} that is a delimited list and convert it into a
     * {@code String} array.
     * <p>A single {@code delimiter} may consist of more than one character,
     * but it will still be considered as a single delimiter string, rather
     * than as bunch of potential delimiter characters, in contrast to
     *
     * @param str the input {@code String}
     * @param delimiter the delimiter between elements (this is a single delimiter,
     * rather than a bunch individual delimiter characters)
     * @return an array of the tokens in the list
     */
    public static String[] delimitedListToStringArray(String str, String delimiter) {
        return delimitedListToStringArray(str, delimiter, null);
    }

    /**
     * Take a {@code String} that is a delimited list and convert it into
     * a {@code String} array.
     * <p>A single {@code delimiter} may consist of more than one character,
     * but it will still be considered as a single delimiter string, rather
     * than as bunch of potential delimiter characters, in contrast to
     *
     * @param str the input {@code String}
     * @param delimiter the delimiter between elements (this is a single delimiter,
     * rather than a bunch individual delimiter characters)
     * @param charsToDelete a set of characters to delete; useful for deleting unwanted
     * line breaks: e.g. "\r\n\f" will delete all new lines and line feeds in a {@code String}
     * @return an array of the tokens in the list
     */
    public static String[] delimitedListToStringArray(String str, String delimiter, String charsToDelete) {
        if (str == null) {
            return new String[0];
        }
        if (delimiter == null) {
            return new String[]{str};
        }
        List<String> result = new ArrayList<String>();
        if ("".equals(delimiter)) {
            for (int i = 0; i < str.length(); i++) {
                result.add(deleteAny(str.substring(i, i + 1), charsToDelete));
            }
        } else {
            int pos = 0;
            int delPos;
            while ((delPos = str.indexOf(delimiter, pos)) != INDEX_NOT_FOUND) {
                result.add(deleteAny(str.substring(pos, delPos), charsToDelete));
                pos = delPos + delimiter.length();
            }
            if (str.length() > 0 && pos <= str.length()) {
                // Add rest of String, but not in case of empty input.
                result.add(deleteAny(str.substring(pos), charsToDelete));
            }
        }
        return toStringArray(result);
    }

    /**
     * 将 {@link Collection} 转换为有范围的{@code String} (e.g. CSV).
     * <p>对于{@code toString()} 的实现很有用.
     *
     * @param coll 要转换的 {@code Collection}
     * @param delim 分隔符 (一般用 ",")
     * @param prefix 每个元素的前缀 {@code String}
     * @param suffix 每个元素的后缀 {@code String}
     * @return the delimited {@code String}
     */
    public static String collectionToDelimitedString(Collection<?> coll, String delim, String prefix, String suffix) {
        if (Collections.isEmpty(coll)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Iterator<?> it = coll.iterator();
        while (it.hasNext()) {
            sb.append(prefix).append(it.next()).append(suffix);
            if (it.hasNext()) {
                sb.append(delim);
            }
        }
        return sb.toString();
    }

    /**
     * 将 {@link Collection} 转换为有范围的{@code String} (e.g. CSV).
     * <p>Useful for {@code toString()} implementations.
     *
     * @param coll the {@code Collection} to convert
     * @param delim the delimiter to use (typically a ",")
     * @return the delimited {@code String}
     */
    public static String collectionToDelimitedString(Collection<?> coll, String delim) {
        return collectionToDelimitedString(coll, delim, "", "");
    }

    /**
     * Check whether the given {@code String} contains actual <em>text</em>.
     * <p>More specifically, this method returns {@code true} if the
     * {@code String} is not {@code null}, its length is greater than 0,
     * and it contains at least one non-whitespace character.
     *
     * @param str the {@code String} to check (may be {@code null})
     * @return {@code true} if the {@code String} is not {@code null}, its
     * length is greater than 0, and it does not contain whitespace only
     * @see Character#isWhitespace
     */
    public static boolean hasText(String str) {
        return (str != null && !str.isEmpty() && containsText(str));
    }

    private static boolean containsText(CharSequence str) {
        int strLen = str.length();
        for (int i = 0; i < strLen; i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清理数据（去除掉文本前后不需要的引号）
     *
     * @param text 文本
     * @return {@link String}
     */
    public static String cleanValue(String text) {
        if (text == null) {
            return null;
        }

        if (text.startsWith("\"")) {
            text = text.substring(1);
        }
        if (text.endsWith("\"")) {
            text = text.substring(0, text.length() - 1);
        }

        return text;
    }

    /**
     * 清理数据（去除掉文本前后不需要的数据）
     *
     * @param text 文本
     * @param prefix 前缀，将把该前缀从{@param text}文本中移除掉
     * @param suffix 后缀，将把该后缀从{@param text}文本中移除掉
     * @return {@link String}
     */
    public static String cleanValue(String text, String prefix, String suffix) {
        if (text == null) {
            return null;
        }

        if (text.startsWith(prefix)) {
            text = text.substring(prefix.length());
        }
        if (text.endsWith(suffix)) {
            text = text.substring(0, text.length() - suffix.length());
        }

        return text;
    }

    /**
     * 返回给定的模板字符串，每次出现的“%s”都替换为args中的相应参数值；或者，如果占位符和参数计数不匹配，则返回该字符串的尽力而为形式。在正常情况下不会引发异常。
     *
     *
     * <p><b>注意:</b> 仅识别精确的两个字符占位符序列 {@code "%s"}
     *
     * @param template 一个包含0个或多个 {@code "%s"} 占位符的字符串. {@code null} 会被转换为字符串 {@code "null"}.
     * @param args 要替换到消息模板中的参数. 指定的第一个参数将替换模板中第一次出现的 {@code "%s"} ,后面参数依次类推. 如果是 {@code null} 则会转换位字符串 {@code
     * "null"};
     * 非null的值将会通过 {@link Object#toString()} 转换为字符串.
     * @return 占位符替换后的字符串；参数多于占位符时，多余参数以 {@code [a, b]} 形式追加在末尾
     * @since 1.4.1
     */
    public static String lenientFormat(
             String template, Object... args) {
        template = String.valueOf(template); // null -> "null"

        if (args == null) {
            args = new Object[]{"(Object[])null"};
        } else {
            for (int i = 0; i < args.length; i++) {
                args[i] = lenientToString(args[i]);
            }
        }

        // start substituting the arguments into the '%s' placeholders
        StringBuilder builder = new StringBuilder(template.length() + 16 * args.length);
        int templateStart = 0;
        int i = 0;
        while (i < args.length) {
            int placeholderStart = template.indexOf("%s", templateStart);
            if (placeholderStart == INDEX_NOT_FOUND) {
                break;
            }
            builder.append(template, templateStart, placeholderStart);
            builder.append(args[i++]);
            templateStart = placeholderStart + 2;
        }
        builder.append(template, templateStart, template.length());

        // if we run out of placeholders, append the extra args in square braces
        if (i < args.length) {
            builder.append(" [");
            builder.append(args[i++]);
            while (i < args.length) {
                builder.append(", ");
                builder.append(args[i++]);
            }
            builder.append(']');
        }

        return builder.toString();
    }

    private static String lenientToString(Object o) {
        try {
            return String.valueOf(o);
        } catch (Exception e) {
            // Default toString() behavior - see Object.toString()
            String objectToString =
                    o.getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(o));
            return "<" + objectToString + " threw " + e.getClass().getName() + ">";
        }
    }

    // Substring
    //-----------------------------------------------------------------------

    /**
     * <p>Gets a substring from the specified String avoiding exceptions.</p>
     *
     * <p>A negative start position can be used to start {@code n}
     * characters from the end of the String.</p>
     *
     * <p>A {@code null} String will return {@code null}.
     * An empty ("") String will return "".</p>
     *
     * <pre>
     * StringUtils.substring(null, *) = null
     * StringUtils.substring("", *) = ""
     * StringUtils.substring("abc", 0) = "abc"
     * StringUtils.substring("abc", 2) = "c"
     * StringUtils.substring("abc", 4) = ""
     * StringUtils.substring("abc", -2) = "bc"
     * StringUtils.substring("abc", -4) = "abc"
     * </pre>
     *
     * @param str the String to get the substring from, may be null
     * @param start the position to start from, negative means
     * count back from the end of the String by this many characters
     * @return substring from start position, {@code null} if null String input
     */
    public static String substring(final String str, int start) {
        if (str == null) {
            return null;
        }

        // handle negatives, which means last n characters
        if (start < 0) {
            start = str.length() + start; // remember start is negative
        }

        if (start < 0) {
            start = 0;
        }
        if (start > str.length()) {
            return EMPTY;
        }

        return str.substring(start);
    }

    /**
     * <p>Gets a substring from the specified String avoiding exceptions.</p>
     *
     * <p>A negative start position can be used to start/end {@code n}
     * characters from the end of the String.</p>
     *
     * <p>The returned substring starts with the character in the {@code start}
     * position and ends before the {@code end} position. All position counting is
     * zero-based -- i.e., to start at the beginning of the string use
     * {@code start = 0}. Negative start and end positions can be used to
     * specify offsets relative to the end of the String.</p>
     *
     * <p>If {@code start} is not strictly to the left of {@code end}, ""
     * is returned.</p>
     *
     * <pre>
     * StringUtils.substring(null, *, *) = null
     * StringUtils.substring("", * , *) = "";
     * StringUtils.substring("abc", 0, 2) = "ab"
     * StringUtils.substring("abc", 2, 0) = ""
     * StringUtils.substring("abc", 2, 4) = "c"
     * StringUtils.substring("abc", 4, 6) = ""
     * StringUtils.substring("abc", 2, 2) = ""
     * StringUtils.substring("abc", -2, -1) = "b"
     * StringUtils.substring("abc", -4, 2) = "ab"
     * </pre>
     *
     * @param str the String to get the substring from, may be null
     * @param start the position to start from, negative means
     * count back from the end of the String by this many characters
     * @param end the position to end at (exclusive), negative means
     * count back from the end of the String by this many characters
     * @return substring from start position to end position,
     * {@code null} if null String input
     */
    public static String substring(final String str, int start, int end) {
        if (str == null) {
            return null;
        }

        // handle negatives
        if (end < 0) {
            end = str.length() + end; // remember end is negative
        }
        if (start < 0) {
            start = str.length() + start; // remember start is negative
        }

        // check length next
        if (end > str.length()) {
            end = str.length();
        }

        // if start is greater than end, return ""
        if (start > end) {
            return EMPTY;
        }

        if (start < 0) {
            start = 0;
        }
        if (end < 0) {
            end = 0;
        }

        return str.substring(start, end);
    }

    // SubStringAfter/SubStringBefore
    //-----------------------------------------------------------------------

    /**
     * <p>Gets the substring before the first occurrence of a separator.
     * The separator is not returned.</p>
     *
     * <p>A {@code null} string input will return {@code null}.
     * An empty ("") string input will return the empty string.
     * A {@code null} separator will return the input string.</p>
     *
     * <p>If nothing is found, the string input is returned.</p>
     *
     * <pre>
     * StringUtils.substringBefore(null, *) = null
     * StringUtils.substringBefore("", *) = ""
     * StringUtils.substringBefore("abc", "a") = ""
     * StringUtils.substringBefore("abcba", "b") = "a"
     * StringUtils.substringBefore("abc", "c") = "ab"
     * StringUtils.substringBefore("abc", "d") = "abc"
     * StringUtils.substringBefore("abc", "") = ""
     * StringUtils.substringBefore("abc", null) = "abc"
     * </pre>
     *
     * @param str the String to get a substring from, may be null
     * @param separator the String to search for, may be null
     * @return the substring before the first occurrence of the separator,
     * {@code null} if null String input
     * @since 2.0
     */
    public static String substringBefore(final String str, final String separator) {
        if (isEmpty(str) || separator == null) {
            return str;
        }
        if (separator.isEmpty()) {
            return EMPTY;
        }
        final int pos = str.indexOf(separator);
        if (pos == INDEX_NOT_FOUND) {
            return str;
        }
        return str.substring(0, pos);
    }

    /**
     * <p>Gets the substring after the first occurrence of a separator.
     * The separator is not returned.</p>
     *
     * <p>A {@code null} string input will return {@code null}.
     * An empty ("") string input will return the empty string.
     * A {@code null} separator will return the empty string if the
     * input string is not {@code null}.</p>
     *
     * <p>If nothing is found, the empty string is returned.</p>
     *
     * <pre>
     * StringUtils.substringAfter(null, *) = null
     * StringUtils.substringAfter("", *) = ""
     * StringUtils.substringAfter(*, null) = ""
     * StringUtils.substringAfter("abc", "a") = "bc"
     * StringUtils.substringAfter("abcba", "b") = "cba"
     * StringUtils.substringAfter("abc", "c") = ""
     * StringUtils.substringAfter("abc", "d") = ""
     * StringUtils.substringAfter("abc", "") = "abc"
     * </pre>
     *
     * @param str the String to get a substring from, may be null
     * @param separator the String to search for, may be null
     * @return the substring after the first occurrence of the separator,
     * {@code null} if null String input
     * @since 2.0
     */
    public static String substringAfter(final String str, final String separator) {
        if (isEmpty(str)) {
            return str;
        }
        if (separator == null) {
            return EMPTY;
        }
        final int pos = str.indexOf(separator);
        if (pos == INDEX_NOT_FOUND) {
            return EMPTY;
        }
        return str.substring(pos + separator.length());
    }

    /**
     * <p>Gets the substring before the last occurrence of a separator.
     * The separator is not returned.</p>
     *
     * <p>A {@code null} string input will return {@code null}.
     * An empty ("") string input will return the empty string.
     * An empty or {@code null} separator will return the input string.</p>
     *
     * <p>If nothing is found, the string input is returned.</p>
     *
     * <pre>
     * StringUtils.substringBeforeLast(null, *) = null
     * StringUtils.substringBeforeLast("", *) = ""
     * StringUtils.substringBeforeLast("abcba", "b") = "abc"
     * StringUtils.substringBeforeLast("abc", "c") = "ab"
     * StringUtils.substringBeforeLast("a", "a") = ""
     * StringUtils.substringBeforeLast("a", "z") = "a"
     * StringUtils.substringBeforeLast("a", null) = "a"
     * StringUtils.substringBeforeLast("a", "") = "a"
     * </pre>
     *
     * @param str the String to get a substring from, may be null
     * @param separator the String to search for, may be null
     * @return the substring before the last occurrence of the separator,
     * {@code null} if null String input
     * @since 2.0
     */
    public static String substringBeforeLast(final String str, final String separator) {
        if (isEmpty(str) || isEmpty(separator)) {
            return str;
        }
        final int pos = str.lastIndexOf(separator);
        if (pos == INDEX_NOT_FOUND) {
            return str;
        }
        return str.substring(0, pos);
    }

    /**
     * <p>Gets the substring after the last occurrence of a separator.
     * The separator is not returned.</p>
     *
     * <p>A {@code null} string input will return {@code null}.
     * An empty ("") string input will return the empty string.
     * An empty or {@code null} separator will return the empty string if
     * the input string is not {@code null}.</p>
     *
     * <p>If nothing is found, the empty string is returned.</p>
     *
     * <pre>
     * StringUtils.substringAfterLast(null, *) = null
     * StringUtils.substringAfterLast("", *) = ""
     * StringUtils.substringAfterLast(*, "") = ""
     * StringUtils.substringAfterLast(*, null) = ""
     * StringUtils.substringAfterLast("abc", "a") = "bc"
     * StringUtils.substringAfterLast("abcba", "b") = "a"
     * StringUtils.substringAfterLast("abc", "c") = ""
     * StringUtils.substringAfterLast("a", "a") = ""
     * StringUtils.substringAfterLast("a", "z") = ""
     * </pre>
     *
     * @param str the String to get a substring from, may be null
     * @param separator the String to search for, may be null
     * @return the substring after the last occurrence of the separator,
     * {@code null} if null String input
     * @since 2.0
     */
    public static String substringAfterLast(final String str, final String separator) {
        if (isEmpty(str)) {
            return str;
        }
        if (isEmpty(separator)) {
            return EMPTY;
        }
        final int pos = str.lastIndexOf(separator);
        if (pos == INDEX_NOT_FOUND || pos == str.length() - separator.length()) {
            return EMPTY;
        }
        return str.substring(pos + separator.length());
    }

    // Substring between
    //-----------------------------------------------------------------------

    /**
     * <p>Gets the String that is nested in between two instances of the
     * same String.</p>
     *
     * <p>A {@code null} input String returns {@code null}.
     * A {@code null} tag returns {@code null}.</p>
     *
     * <pre>
     * StringUtils.substringBetween(null, *) = null
     * StringUtils.substringBetween("", "") = ""
     * StringUtils.substringBetween("", "tag") = null
     * StringUtils.substringBetween("tagabctag", null) = null
     * StringUtils.substringBetween("tagabctag", "") = ""
     * StringUtils.substringBetween("tagabctag", "tag") = "abc"
     * </pre>
     *
     * @param str the String containing the substring, may be null
     * @param tag the String before and after the substring, may be null
     * @return the substring, {@code null} if no match
     * @since 2.0
     */
    public static String substringBetween(final String str, final String tag) {
        return substringBetween(str, tag, tag);
    }

    /**
     * <p>Gets the String that is nested in between two Strings.
     * Only the first match is returned.</p>
     *
     * <p>A {@code null} input String returns {@code null}.
     * A {@code null} open/close returns {@code null} (no match).
     * An empty ("") open and close returns an empty string.</p>
     *
     * <pre>
     * StringUtils.substringBetween("wx[b]yz", "[", "]") = "b"
     * StringUtils.substringBetween(null, *, *) = null
     * StringUtils.substringBetween(*, null, *) = null
     * StringUtils.substringBetween(*, *, null) = null
     * StringUtils.substringBetween("", "", "") = ""
     * StringUtils.substringBetween("", "", "]") = null
     * StringUtils.substringBetween("", "[", "]") = null
     * StringUtils.substringBetween("yabcz", "", "") = ""
     * StringUtils.substringBetween("yabcz", "y", "z") = "abc"
     * StringUtils.substringBetween("yabczyabcz", "y", "z") = "abc"
     * </pre>
     *
     * @param str the String containing the substring, may be null
     * @param open the String before the substring, may be null
     * @param close the String after the substring, may be null
     * @return the substring, {@code null} if no match
     * @since 2.0
     */
    public static String substringBetween(final String str, final String open, final String close) {
        if (str == null || open == null || close == null) {
            return null;
        }
        final int start = str.indexOf(open);
        if (start != INDEX_NOT_FOUND) {
            final int end = str.indexOf(close, start + open.length());
            if (end != INDEX_NOT_FOUND) {
                return str.substring(start + open.length(), end);
            }
        }
        return null;
    }

    /**
     * <p>Searches a String for substrings delimited by a start and end tag,
     * returning all matching substrings in an array.</p>
     *
     * <p>A {@code null} input String returns {@code null}.
     * A {@code null} open/close returns {@code null} (no match).
     * An empty ("") open/close returns {@code null} (no match).</p>
     *
     * <pre>
     * StringUtils.substringsBetween("[a][b][c]", "[", "]") = ["a","b","c"]
     * StringUtils.substringsBetween(null, *, *) = null
     * StringUtils.substringsBetween(*, null, *) = null
     * StringUtils.substringsBetween(*, *, null) = null
     * StringUtils.substringsBetween("", "[", "]") = []
     * </pre>
     *
     * @param str the String containing the substrings, null returns null, empty returns empty
     * @param open the String identifying the start of the substring, empty returns null
     * @param close the String identifying the end of the substring, empty returns null
     * @return a String Array of substrings, or {@code null} if no match
     * @since 2.3
     */
    public static String[] substringsBetween(final String str, final String open, final String close) {
        if (str == null || isEmpty(open) || isEmpty(close)) {
            return null;
        }
        final int strLen = str.length();
        if (strLen == 0) {
            return ArrayUtils.EMPTY_STRING_ARRAY;
        }
        final int closeLen = close.length();
        final int openLen = open.length();
        final List<String> list = new ArrayList<>();
        int pos = 0;
        while (pos < strLen - closeLen) {
            int start = str.indexOf(open, pos);
            if (start < 0) {
                break;
            }
            start += openLen;
            final int end = str.indexOf(close, start);
            if (end < 0) {
                break;
            }
            list.add(str.substring(start, end));
            pos = end + closeLen;
        }
        if (list.isEmpty()) {
            return null;
        }
        return list.toArray(new String[list.size()]);
    }

    // Left/Right/Mid
    //-----------------------------------------------------------------------

    /**
     * <p>Gets the leftmost {@code len} characters of a String.</p>
     *
     * <p>If {@code len} characters are not available, or the
     * String is {@code null}, the String will be returned without
     * an exception. An empty String is returned if len is negative.</p>
     *
     * <pre>
     * StringUtils.left(null, *) = null
     * StringUtils.left(*, -ve) = ""
     * StringUtils.left("", *) = ""
     * StringUtils.left("abc", 0) = ""
     * StringUtils.left("abc", 2) = "ab"
     * StringUtils.left("abc", 4) = "abc"
     * </pre>
     *
     * @param str the String to get the leftmost characters from, may be null
     * @param len the length of the required String
     * @return the leftmost characters, {@code null} if null String input
     */
    public static String left(final String str, final int len) {
        if (str == null) {
            return null;
        }
        if (len < 0) {
            return EMPTY;
        }
        if (str.length() <= len) {
            return str;
        }
        return str.substring(0, len);
    }

    /**
     * <p>Gets the rightmost {@code len} characters of a String.</p>
     *
     * <p>If {@code len} characters are not available, or the String
     * is {@code null}, the String will be returned without an
     * an exception. An empty String is returned if len is negative.</p>
     *
     * <pre>
     * StringUtils.right(null, *) = null
     * StringUtils.right(*, -ve) = ""
     * StringUtils.right("", *) = ""
     * StringUtils.right("abc", 0) = ""
     * StringUtils.right("abc", 2) = "bc"
     * StringUtils.right("abc", 4) = "abc"
     * </pre>
     *
     * @param str the String to get the rightmost characters from, may be null
     * @param len the length of the required String
     * @return the rightmost characters, {@code null} if null String input
     */
    public static String right(final String str, final int len) {
        if (str == null) {
            return null;
        }
        if (len < 0) {
            return EMPTY;
        }
        if (str.length() <= len) {
            return str;
        }
        return str.substring(str.length() - len);
    }

    /**
     * <p>Gets {@code len} characters from the middle of a String.</p>
     *
     * <p>If {@code len} characters are not available, the remainder
     * of the String will be returned without an exception. If the
     * String is {@code null}, {@code null} will be returned.
     * An empty String is returned if len is negative or exceeds the
     * length of {@code str}.</p>
     *
     * <pre>
     * StringUtils.mid(null, *, *) = null
     * StringUtils.mid(*, *, -ve) = ""
     * StringUtils.mid("", 0, *) = ""
     * StringUtils.mid("abc", 0, 2) = "ab"
     * StringUtils.mid("abc", 0, 4) = "abc"
     * StringUtils.mid("abc", 2, 4) = "c"
     * StringUtils.mid("abc", 4, 2) = ""
     * StringUtils.mid("abc", -2, 2) = "ab"
     * </pre>
     *
     * @param str the String to get the characters from, may be null
     * @param pos the position to start from, negative treated as zero
     * @param len the length of the required String
     * @return the middle characters, {@code null} if null String input
     */
    public static String mid(final String str, int pos, final int len) {
        if (str == null) {
            return null;
        }
        if (len < 0 || pos > str.length()) {
            return EMPTY;
        }
        if (pos < 0) {
            pos = 0;
        }
        if (str.length() <= pos + len) {
            return str.substring(pos);
        }
        return str.substring(pos, pos + len);
    }

    /**
     * 将字符串重复拼接指定次数。
     *
     * @param string 待重复的字符串，不能为 {@code null}
     * @param times  重复次数，小于等于 0 时返回空字符串
     * @return 重复拼接后的字符串
     */
    public static String repeat(String string, int times) {
        StringBuilder buf = new StringBuilder(string.length() * times);
        for (int i = 0; i < times; i++) {
            buf.append(string);
        }
        return buf.toString();
    }

    /**
     * 将字符串重复拼接指定次数，相邻两份之间插入分隔符。
     *
     * @param string      待重复的字符串，不能为 {@code null}
     * @param times       重复次数，小于等于 1 时不追加分隔符
     * @param deliminator 分隔符，不能为 {@code null}
     * @return 以分隔符连接的重复字符串
     */
    public static String repeat(String string, int times, String deliminator) {
        StringBuilder buf = new StringBuilder((string.length() * times) + (deliminator.length() * (times - 1)))
                .append(string);
        for (int i = 1; i < times; i++) {
            buf.append(deliminator).append(string);
        }
        return buf.toString();
    }

    /**
     * 将字符重复指定次数生成字符串。
     *
     * @param character 待重复的字符
     * @param times     重复次数，为 0 时返回空字符串
     * @return 由该字符重复组成的字符串
     */
    public static String repeat(char character, int times) {
        char[] buffer = new char[times];
        Arrays.fill(buffer, character);
        return new String(buffer);
    }

    /**
     * Split a {@code String} at the first occurrence of the delimiter.
     * Does not include the delimiter in the result.
     *
     * @param toSplit the string to split (potentially {@code null} or empty)
     * @param delimiter to split the string up with (potentially {@code null} or empty)
     * @return a two element array with index 0 being before the delimiter, and
     * index 1 being after the delimiter (neither element includes the delimiter);
     * or {@code null} if the delimiter wasn't found in the given input {@code String}
     */

    public static String[] split(String toSplit, String delimiter) {
        if (!hasLength(toSplit) || !hasLength(delimiter)) {
            return null;
        }
        int offset = toSplit.indexOf(delimiter);
        if (offset < 0) {
            return null;
        }

        String beforeDelimiter = toSplit.substring(0, offset);
        String afterDelimiter = toSplit.substring(offset + delimiter.length());
        return new String[]{beforeDelimiter, afterDelimiter};
    }

    /**
     * Truncate the supplied {@link CharSequence}.
     * <p>If the length of the {@code CharSequence} is greater than the threshold,
     * this method returns a {@linkplain CharSequence#subSequence(int, int)
     * subsequence} of the {@code CharSequence} (up to the threshold) appended
     * with the suffix {@code " (truncated)..."}. Otherwise, this method returns
     * {@code charSequence.toString()}.
     *
     * @param charSequence the {@code CharSequence} to truncate
     * @param threshold the maximum length after which to truncate; must be a
     * positive number
     * @return a truncated string, or a string representation of the original
     * {@code CharSequence} if its length does not exceed the threshold
     * @since 5.3.27
     */
    public static String truncate(CharSequence charSequence, int threshold) {
        Assert.isTrue(threshold > 0,
                () -> "Truncation threshold must be a positive number: " + threshold);
        if (charSequence.length() > threshold) {
            return charSequence.subSequence(0, threshold) + TRUNCATION_SUFFIX;
        }
        return charSequence.toString();
    }

    /**
     * Trim leading and trailing whitespace from the given {@code String}.
     *
     * @param str the {@code String} to check
     * @return the trimmed {@code String}
     * @see Character#isWhitespace
     */
    public static String trimWhitespace(String str) {
        if (!hasLength(str)) {
            return str;
        }

        int beginIndex = 0;
        int endIndex = str.length() - 1;

        while (beginIndex <= endIndex && Character.isWhitespace(str.charAt(beginIndex))) {
            beginIndex++;
        }

        while (endIndex > beginIndex && Character.isWhitespace(str.charAt(endIndex))) {
            endIndex--;
        }

        return str.substring(beginIndex, endIndex + 1);
    }

    /**
     * Trim <em>all</em> whitespace from the given {@code CharSequence}:
     * leading, trailing, and in between characters.
     *
     * @param str the {@code CharSequence} to check
     * @return the trimmed {@code CharSequence}
     * @see #trimAllWhitespace(String)
     * @see Character#isWhitespace
     * @since 5.3.22
     */
    public static CharSequence trimAllWhitespace(CharSequence str) {
        if (!hasLength(str)) {
            return str;
        }

        int len = str.length();
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < len; i++) {
            char c = str.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb;
    }

    /**
     * Trim <em>all</em> whitespace from the given {@code String}:
     * leading, trailing, and in between characters.
     *
     * @param str the {@code String} to check
     * @return the trimmed {@code String}
     * @see #trimAllWhitespace(CharSequence)
     * @see Character#isWhitespace
     */
    public static String trimAllWhitespace(String str) {
        if (!hasLength(str)) {
            return str;
        }

        return trimAllWhitespace((CharSequence) str).toString();
    }

    /**
     * Trim all occurrences of the supplied trailing character from the given {@code String}.
     *
     * @param str the {@code String} to check
     * @param suffixCharacter the trailing character to be trimmed
     * @return the trimmed {@code String}
     */
    public static String trimSuffixCharacter(String str, char suffixCharacter) {
        if (!hasLength(str)) {
            return str;
        }

        int endIdx = str.length() - 1;
        while (endIdx >= 0 && suffixCharacter == str.charAt(endIdx)) {
            endIdx--;
        }
        return str.substring(0, endIdx + 1);
    }

    /**
     * Trim all occurrences of the supplied leading character from the given {@code String}.
     *
     * @param str the {@code String} to check
     * @param prefixCharacter the leading character to be trimmed
     * @return the trimmed {@code String}
     */
    public static String trimPrefixCharacter(String str, char prefixCharacter) {
        if (!hasLength(str)) {
            return str;
        }

        int beginIdx = 0;
        while (beginIdx < str.length() && prefixCharacter == str.charAt(beginIdx)) {
            beginIdx++;
        }
        return str.substring(beginIdx);
    }

    /**
     * 将给的字符的前后字符都去掉 {@code String}.
     *
     * @param str the {@code String} to check
     * @param character the character to be trimmed
     * @return the trimmed {@code String}
     * @see StringUtils#cleanValue(String, String, String)
     */
    public static String trimCharacter(String str, char character) {
        if (!hasLength(str)) {
            return str;
        }

        int beginIdx = 0;
        while (beginIdx < str.length() && character == str.charAt(beginIdx)) {
            beginIdx++;
        }
        str = str.substring(beginIdx);
        int endIdx = str.length() - 1;

        while (endIdx >= 0 && character == str.charAt(endIdx)) {
            endIdx--;
        }
        return str.substring(0, endIdx + 1);

    }

    /**
     * Test if the given {@code String} starts with the specified prefix,
     * ignoring upper/lower case.
     *
     * @param str the {@code String} to check
     * @param prefix the prefix to look for
     * @return {@code true} if the given {@code String} starts with the prefix ignoring case,
     * {@code false} otherwise (also when either argument is {@code null})
     * @see String#startsWith
     */
    public static boolean startsWithIgnoreCase(String str, String prefix) {
        return (str != null && prefix != null && str.length() >= prefix.length() &&
                str.regionMatches(true, 0, prefix, 0, prefix.length()));
    }

    /**
     * Test if the given {@code String} ends with the specified suffix,
     * ignoring upper/lower case.
     *
     * @param str the {@code String} to check
     * @param suffix the suffix to look for
     * @return {@code true} if the given {@code String} ends with the suffix ignoring case,
     * {@code false} otherwise (also when either argument is {@code null})
     * @see String#endsWith
     */
    public static boolean endsWithIgnoreCase(String str, String suffix) {
        return (str != null && suffix != null && str.length() >= suffix.length() &&
                str.regionMatches(true, str.length() - suffix.length(), suffix, 0, suffix.length()));
    }

    // -----------------------------------------------------------------------
    // JSON 工具方法（源自 json.internal.utils.StringUtils）
    // -----------------------------------------------------------------------

    /**
     * 拼接数组元素，使用指定分隔符。
     *
     * @param delimiter 分隔符，为 {@code null} 时使用英文逗号
     * @param arr       待拼接的元素，元素按 {@code String.valueOf} 输出
     * @return 拼接后的字符串；数组为 {@code null} 时返回 {@code null}
     */
    public static String join(String delimiter, Object... arr) {
        if (arr == null) {
            return null;
        }
        if (delimiter == null) {
            delimiter = ",";
        }
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Object val : arr) {
            if (!first) {
                builder.append(delimiter);
            } else {
                first = false;
            }
            builder.append(val);
        }
        return builder.toString();
    }

    /**
     * 下划线命名的数据库字段转驼峰（首字母小写）。
     *
     * @param columnName 源字段名，如 {@code user_name}
     * @return 首字母小写的驼峰字符串，如 {@code userName}；入参为 {@code null} 时返回 {@code null}
     */
    public static String getCamelCase(String columnName) {
        return getCamelCase(columnName, false);
    }

    /**
     * 下划线命名的数据库字段转驼峰。
     *
     * @param columnName 源字段名
     * @param upperCaseFirstChar 首字母是否大写
     * @return 驼峰命名字符串
     */
    public static String getCamelCase(String columnName, boolean upperCaseFirstChar) {
        if (columnName == null) {
            return null;
        }
        char[] chars = UnsafeHelper.getChars(columnName);
        StringBuilder builder = new StringBuilder();
        boolean upperCaseFlag = false;
        for (int i = 0, len = chars.length; i < len; ++i) {
            char ch = chars[i];
            if (ch == '_') {
                upperCaseFlag = true;
                continue;
            }
            char appendChar;
            boolean isLowerCase = ch >= 'a' && ch <= 'z';
            if (upperCaseFlag) {
                appendChar = isLowerCase ? (char) (ch - 32) : ch;
                upperCaseFlag = false;
            } else if (i == 0 && !upperCaseFirstChar && ch >= 'A' && ch <= 'Z') {
                appendChar = (char) (ch + 32);
            } else {
                appendChar = ch;
            }
            builder.append(appendChar);
        }
        if (upperCaseFirstChar && builder.length() > 0) {
            char first = builder.charAt(0);
            if (first >= 'a' && first <= 'z') {
                builder.setCharAt(0, (char) (first - 32));
            }
        }
        return builder.toString();
    }

    /**
     * 驼峰命名字符串转下划线（默认使用 "_" 分隔，首字符不加前缀）。
     *
     * @param camelCase 源驼峰字符串，如 {@code userName}
     * @return 下划线命名字符串，如 {@code user_name}；入参为 {@code null} 时返回 {@code null}
     */
    public static String camelCaseToSymbol(String camelCase) {
        return camelCaseToSymbol(camelCase, "_");
    }

    /**
     * 驼峰命名字符串转下划线，可指定分隔符（首字符不加前缀）。
     *
     * @param camelCase 源驼峰字符串
     * @param symbol    分隔符，如 {@code _} 或 {@code -}
     * @return 以指定分隔符分隔的小写字符串；入参为 {@code null} 时返回 {@code null}
     */
    public static String camelCaseToSymbol(String camelCase, String symbol) {
        return camelCaseToSymbol(camelCase, symbol, false);
    }

    /**
     * 驼峰命名字符串转下划线。
     *
     * @param camelCase 源驼峰字符串
     * @param symbol 分隔符
     * @param firstSymbol 首字符是否也添加分隔符
     * @return 下划线命名字符串
     */
    public static String camelCaseToSymbol(String camelCase, String symbol, boolean firstSymbol) {
        if (camelCase == null) {
            return null;
        }
        char[] chars = UnsafeHelper.getChars(camelCase);
        StringBuilder builder = new StringBuilder();
        for (int i = 0, len = chars.length; i < len; ++i) {
            char ch = chars[i];
            if (ch >= 'A' && ch <= 'Z') {
                if (firstSymbol || i != 0) {
                    builder.append(symbol);
                }
                builder.append((char) (ch + 32));
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    /**
     * 用正则匹配组替换占位符，支持 a.b.c 形式的上下文属性查找。
     *
     * @param source     源字符串
     * @param groupRegex 提取占位符名称的正则表达式，需包含一个匹配组
     * @param prefix     占位符前缀，与 {@code {名称}} 拼接后作为被替换的文本
     * @param context    取值上下文，可以是 Map 或普通对象
     * @return 替换后的字符串；上下文中取不到值的占位符保持原样
     */
    public static String regexGroupExprReplace(String source, String groupRegex, String prefix, Object context) {
        List<String> groups = RegexUtils.getMatcherGroups(source, groupRegex, false);
        Set<String> hashGroups = new HashSet<>(groups);
        String result = source;
        if (!hashGroups.isEmpty()) {
            for (String group : hashGroups) {
                Object value = ObjectUtils.get(context, group.trim());
                if (value != null) {
                    result = result.replace(prefix + "{" + group + "}", value.toString());
                }
            }
        }
        return result;
    }

    /**
     * 用参数替换消息中的固定占位符。
     * <p>例如：replacePlaceholder("{}, hello", "{}", "xx") → "xx, hello"
     *
     * @param message     待替换的消息文本
     * @param placeholder 占位符文本，如 {@code {}}
     * @param parameters  按出现顺序依次替换的参数；参数用尽后剩余占位符保持原样
     * @return 替换后的文本；占位符为空、参数为空或消息中不含占位符时原样返回 {@code message}
     */
    public static String replacePlaceholder(String message, String placeholder, Object... parameters) {
        if (isEmpty(placeholder) || parameters == null || parameters.length == 0) {
            return message;
        }
        int placeholderIndex = message.indexOf(placeholder);
        if (placeholderIndex == -1) {
            return message;
        }
        StringBuilder buffer = new StringBuilder();
        int fromIndex = 0;
        int placeholderLen = placeholder.length();
        for (int i = 0; placeholderIndex > -1; i++) {
            buffer.append(message, fromIndex, placeholderIndex);
            buffer.append(i < parameters.length ? parameters[i] : placeholder);
            fromIndex = placeholderIndex + placeholderLen;
            placeholderIndex = message.indexOf(placeholder, fromIndex);
        }
        buffer.append(message, fromIndex, message.length());
        return buffer.toString();
    }

    /**
     * 替换 ${var} 占位符（使用默认正则 {@code [$][{](.*?)[}]}）。
     *
     * @param message 待替换的消息文本
     * @param context 取值上下文，可以是 Map 或普通对象
     * @return 替换后的文本；取不到值的占位符会被替换为 {@code null} 字面量
     */
    public static String replaceGroupRegex(String message, Object context) {
        return replaceGroupRegex(message, "[$][{](.*?)[}]", context, false);
    }

    /**
     * 替换 ${var} 占位符（模板引擎专用，仅匹配字母、数字、下划线、点与 {@code $} 组成的变量名）。
     *
     * @param message     待替换的消息文本
     * @param context     取值上下文，可以是 Map 或普通对象
     * @param emptyIfNull 取不到值时是否替换为空字符串；为 {@code false} 时替换为 {@code null} 字面量
     * @return 替换后的文本
     */
    public static String replaceGroupRegex(String message, Object context, boolean emptyIfNull) {
        return replaceGroupRegex(message, "[$][{]([ ]*[0-9a-zA-Z_.$]+[ ]*)[}]", context, emptyIfNull);
    }

    /**
     * 用正则占位符替换，支持自定义正则表达式。
     *
     * @param message    待替换的消息文本
     * @param groupRegex 提取变量名的正则表达式，需包含一个匹配组
     * @param context    取值上下文，可以是 Map 或普通对象
     * @return 替换后的文本；取不到值的占位符会被替换为 {@code null} 字面量
     */
    public static String replaceGroupRegex(String message, String groupRegex, Object context) {
        return replaceGroupRegex(message, groupRegex, context, false);
    }

    /**
     * 用正则占位符替换，支持自定义正则表达式和空值处理。
     *
     * <p>占位符前带反斜杠时视为转义，保留原文不替换。
     *
     * @param message     待替换的消息文本
     * @param groupRegex  提取变量名的正则表达式，需包含一个匹配组
     * @param context     取值上下文，可以是 Map 或普通对象
     * @param emptyIfNull 取不到值时是否替换为空字符串；为 {@code false} 时替换为 {@code null} 字面量
     * @return 替换后的文本；正则为空、无匹配组或上下文为 {@code null} 时原样返回 {@code message}
     */
    public static String replaceGroupRegex(String message, String groupRegex, Object context, boolean emptyIfNull) {
        if (isEmpty(groupRegex) || context == null) {
            return message;
        }
        if (groupRegex.indexOf(')') <= groupRegex.indexOf('(') || groupRegex.indexOf('(') == -1) {
            return message;
        }
        StringBuilder buffer = new StringBuilder();
        Pattern pattern = RegexUtils.getPattern(groupRegex);
        Matcher matcher = pattern.matcher(message);
        int beginIndex = 0;
        while (matcher.find()) {
            int newBeginIndex = matcher.start(0);
            if (newBeginIndex > 0 && message.charAt(newBeginIndex - 1) == '\\') {
                buffer.append(message, beginIndex, newBeginIndex - 1);
                buffer.append(matcher.group(0));
            } else {
                String key = matcher.group(1).trim();
                buffer.append(message, beginIndex, newBeginIndex);
                Object value = ObjectUtils.get(context, key);
                if (!(value == null && emptyIfNull)) {
                    buffer.append(value);
                }
            }
            beginIndex = matcher.end(0);
        }
        buffer.append(message, beginIndex, message.length());
        return buffer.toString();
    }

    /**
     * 将异常转换为堆栈跟踪字符串。
     *
     * @param t 目标异常
     * @return 完整的堆栈跟踪文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String getThrowableContent(Throwable t) {
        if (t == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
            return sw.toString();
        }
    }

    /**
     * HTML 转义，处理 {@code & < > ( ) " '} 与空格。
     *
     * @param value 待转义的文本
     * @return 转义后的文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String escapeHtml(String value) {
        if (value == null) {
            return null;
        }
        value = value.replace("&", "&amp;");
        value = value.replaceAll("[<](.*?)[>]", "&lt;$1&gt;").replaceAll("[(](.*?)[)]", "&#40;$1&#41;");
        value = value.replaceAll("=([ ]*)\"", "=$1&quot;");
        value = value.replace("'", "&#39;").replace(" ", "&nbsp;");
        return value;
    }

    /**
     * HTML 反转义，是 {@link #escapeHtml(String)} 的逆操作。
     *
     * @param value 待反转义的文本
     * @return 还原后的文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String htmlUnescape(String value) {
        if (value == null) {
            return null;
        }
        value = value.replace("&#39;", "'").replace("&nbsp;", " ");
        value = value.replaceAll("=([ ]*)&quot;", "=$1\"");
        value = value.replaceAll("&lt;(.*?)&gt;", "<$1>").replaceAll("&#40;(.*?)&#41;", "($1)");
        value = value.replace("&amp;", "&");
        return value;
    }

    /**
     * 检查数组是否包含指定元素（忽略大小写）。
     *
     * <p>本方法判断的是<b>数组成员</b>而非子串，且比较时忽略大小写，
     * 因此与 {@link #contains(CharSequence, CharSequence)} 是两件不同的事。</p>
     *
     * @param arr     待查找的数组，可为 {@code null}
     * @param element 待查找的元素，可为 {@code null}
     * @return 存在忽略大小写相等的元素时返回 {@code true}，数组或元素为 {@code null} 时返回 {@code false}
     */
    public static boolean containsElementIgnoreCase(String[] arr, String element) {
        if (arr == null || element == null) {
            return false;
        }
        for (String str : arr) {
            if (element.equalsIgnoreCase(str)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 格式化路径，以 {@code /} 开头结尾并合并多余斜杠，反斜杠统一转为斜杠。
     *
     * @param paths 各级路径片段，{@code null} 与空串会被跳过
     * @return 规范化后的路径，如 {@code /a/b/}
     */
    public static String formatMappingPath(String... paths) {
        StringBuilder pathBuffer = new StringBuilder("/");
        for (String path : paths) {
            if (path == null || path.isEmpty()) {
                continue;
            }
            pathBuffer.append(path);
            if (!path.endsWith("/")) {
                pathBuffer.append("/");
            }
        }
        String path = pathBuffer.toString().trim();
        if (path.indexOf('\\') > -1) {
            path = path.replace('\\', '/');
        }
        return path.replaceAll("(/)+", "$1");
    }

    /**
     * 从类路径资源文件中读取字符串内容（按 UTF-8 解码）。
     *
     * @param resource 类路径下的资源路径，不以 {@code /} 开头时自动补齐
     * @return 资源的文本内容；入参为 {@code null} 或资源不存在时返回 {@code null}
     */
    public static String fromResource(String resource) {
        if (resource == null) {
            return null;
        }
        if (!resource.startsWith("/")) {
            resource = "/" + resource;
        }
        java.io.InputStream is = StringUtils.class.getResourceAsStream(resource);
        return IOUtils.is2String(is);
    }

    /**
     * 从输入流读取字符串（使用系统默认编码）。
     *
     * @param is 输入流
     * @return 读取到的文本；读取过程发生 IO 异常时返回 {@code null}
     */
    public static String fromStream(java.io.InputStream is) {
        return fromStream(is, java.nio.charset.Charset.defaultCharset());
    }

    /**
     * 从输入流读取字符串（使用指定编码名）。
     *
     * @param is          输入流
     * @param charsetName 编码名称，如 {@code UTF-8}；不受支持时抛出运行时异常
     * @return 读取到的文本；读取过程发生 IO 异常时返回 {@code null}
     */
    public static String fromStream(java.io.InputStream is, String charsetName) {
        return fromStream(is, java.nio.charset.Charset.forName(charsetName));
    }

    /**
     * 从输入流读取字符串（使用指定编码）。
     *
     * @param is      输入流
     * @param charset 指定的字符集
     * @return 读取到的文本；读取过程发生 IO 异常时返回 {@code null}
     */
    public static String fromStream(java.io.InputStream is, java.nio.charset.Charset charset) {
        try {
            byte[] bytes = IOUtils.readBytes(is);
            return new String(bytes, charset);
        } catch (java.io.IOException e) {
            return null;
        }
    }

}
