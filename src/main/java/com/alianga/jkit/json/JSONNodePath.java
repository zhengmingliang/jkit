package com.alianga.jkit.json;

import com.alianga.jkit.expression.ExprParser;
import com.alianga.jkit.expression.Expression;
import com.alianga.jkit.json.exceptions.JSONException;
import com.alianga.jkit.math.NumberUtils;
import com.alianga.jkit.reflect.UnsafeHelper;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JSON xpath
 *
 * <ul>
 * <li>"//" : 从当前节点开始查找所有满足条件的节点，并遍历所有子孙节点；</li>
 * <li>"/" : 仅从当前节点开始查找所有满足条件的节点（不包括子孙节点）；</li>
 * </ul>
 *
 * @time 2024/10/9 19:28
 */
public final class JSONNodePath {
    static final JSONNodePath ALL = JSONNodePath.collectors(JSONNodePathCollector.any().recursive(true));
    private int depth;
    JSONNodePathCollector head;
    JSONNodePathCollector tail;
    boolean supportedExtract;
    // 默认贪婪模式（只在path递归查找下生效）
    boolean greedy = true;

    private JSONNodePath() {
    }

    JSONNodePath(JSONNodePathCollector head, JSONNodePathCollector tail, int depth) {
        this.head = head;
        this.tail = tail;
        this.depth = depth;
    }

    // 是否为单节点模式
    JSONNodePath supportedExtract(boolean supportedExtract) {
        this.supportedExtract = supportedExtract;
        return this;
    }

    /**
     * 返回自身，便于在链式构建过程中书写。
     *
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath self() {
        return this;
    }

    /**
     * 设置贪婪模式
     *
     * @param greedy 是否开启贪婪模式，仅在路径递归查找（{@code //}）下生效；关闭后可减少无效遍历
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath greedy(boolean greedy) {
        this.greedy = greedy;
        return this;
    }

    /**
     * 从指定节点开始按路径收集匹配的节点。
     *
     * @param parentNode 起始（父）节点
     * @return 匹配到的节点列表，没有匹配时返回空列表
     */
    public List<JSONNode> collect(JSONNode parentNode) {
        List<JSONNode> results = new ArrayList<JSONNode>();
        head.collect(parentNode, results, JSONNodeCollector.DEFAULT, newCollectCtx());
        return results;
    }

    /**
     * 从指定节点开始按路径收集匹配的节点，并用指定收集器把节点转换为目标类型。
     *
     * @param parentNode 起始（父）节点
     * @param collector  节点转换收集器
     * @param <T>        收集结果的元素类型
     * @return 转换后的结果列表，没有匹配时返回空列表
     */
    public <T> List<T> collect(JSONNode parentNode, JSONNodeCollector<T> collector) {
        List<T> results = new ArrayList<T>();
        head.collect(parentNode, results, collector, newCollectCtx());
        return results;
    }

    /**
     * 从指定节点开始按路径收集匹配的节点，结果追加到给定集合中。
     *
     * @param parentNode 起始（父）节点
     * @param results    存放匹配节点的集合
     */
    public void collect(JSONNode parentNode, Collection<JSONNode> results) {
        head.collect(parentNode, results, JSONNodeCollector.DEFAULT, newCollectCtx());
    }

    /**
     * 从指定节点开始按路径收集匹配的节点，经收集器转换后追加到给定集合中。
     *
     * @param parentNode 起始（父）节点
     * @param results    存放转换结果的集合
     * @param collector  节点转换收集器，不能为 {@code null}
     * @param <T>        收集结果的元素类型
     */
    public <T> void collect(JSONNode parentNode, Collection<T> results, JSONNodeCollector<T> collector) {
        collector.getClass();
        head.collect(parentNode, results, collector, newCollectCtx());
    }

    JSONNodePathCtx newCollectCtx() {
        return new JSONNodePathCtx(greedy);
    }

    /**
     * 创建一个空的路径模型，之后可通过 {@code exact/any/prefix} 等方法逐段追加。
     *
     * @return 新创建的空路径模型
     */
    public static JSONNodePath create() {
        return new JSONNodePath();
    }

    /**
     * 解析xpath字符串（通过字符串构建path模型功能有限）
     * <ul>
     * <li> 以斜杠为分界，如果路径中存在斜杠内容请使用单引号字符串，单引号需要紧跟斜杠，例如 /name/'/'/P;
     * <li> 支持双斜杠代表递归查找;
     * <li> 以单引号开头代表字符串明确匹配对象节点的属性;
     * <li> 以[开头代表明确数组下标集合（离散下标），例如/users/[1,3,7,9];
     * <li> 其他开头智能判断(支持n, n+,n-, n~m等);
     * <li> 路径片段结束支持紧跟中括号代表属性过滤操作，例如/persion[name = 'li'];
     * <li> 过滤表达式中的字符串内容以单引号标记；
     * <li> 不支持path内容中存在单引号，请使用编程方式构建；
     * <li> 不支持设置禁用贪婪模式，请使用编程方式构建(禁用后避免很多无效搜索遍历能提升很大性能)；
     * </ul>
     *
     * @param xpath xpath 字符串，为 {@code null} 或空白时返回 {@code null}
     * @return 解析得到的路径模型；语法错误时抛出 {@link JSONException}
     */
    public static JSONNodePath parse(String xpath) {
        int len;
        if (xpath == null || (len = (xpath = xpath.trim()).length()) == 0) {
            return null;
        }
        int offset = 0;
        char ch;
        JSONNodePath result = JSONNodePath.create();
        char[] chars = UnsafeHelper.getChars(xpath);
        try {
            int dn = 0;
            char beginChar = chars[0];
            if (beginChar != '/') {
                ++dn;
            }
            for (; offset < len; ++offset, dn = 1) {
                while ((ch = chars[offset]) == '/') {
                    ++dn;
                    if (++offset == len) {
                        // finish
                        return result;
                    }
                }
                boolean recursive = dn > 1;
                int begin = offset;
                JSONNodePathCollector pathCollector;
                do {
                    if (ch == '\'') {
                        while (chars[++offset] != '\'') {
                            // skip
                        }
                        String path = new String(chars, begin + 1, offset - begin - 1);
                        pathCollector = JSONNodePathCollector.exact(path).recursive(recursive);
                        result.next(pathCollector);
                        if (++offset == len) {
                            return result;
                        }
                        // ch is / or [
                        ch = chars[offset];
                        break;
                    }
                    if (ch == '[') {
                        try {
                            JSONParseContext parseContext = new JSONNodeContext();
                            parseContext.toIndex = len;
                            Integer[] values =
                                    JSONTypeDeserializer.INTEGER_ARRAY.deserialize(chars, offset, parseContext);
                            if (values.length == 1) {
                                pathCollector = JSONNodePathCollector.exact(values[0]);
                            } else {
                                if (values.length == 0) {
                                    String errorMsg = JSONGeneral.createErrorContextText(chars, offset);
                                    throw new JSONException(
                                            "Syntax error, at pos " + offset + ", context text by '" + errorMsg +
                                                    "', empty array");
                                }
                                pathCollector = JSONNodePathCollector.indexs(values);
                            }
                            result.next(pathCollector.recursive(recursive));
                            offset = parseContext.endIndex + 1;
                            if (offset == len) {
                                return result;
                            }
                            ch = chars[offset];
                            if (ch == '[' || ch == '/') {
                                break;
                            }
                        } catch (Throwable throwable) {
                        }
                        String errorMsg = JSONGeneral.createErrorContextText(chars, offset);
                        throw new JSONException("Syntax error, at pos " + offset + ", context text by '" + errorMsg +
                                "', error index array");
                    }

                    // any or
                    begin = offset++;
                    beginChar = ch;
                    boolean isNegative = ch == '-';
                    if (isNegative || NumberUtils.isDigit(ch)) {
                        int val = 0;
                        if (!isNegative) {
                            val = ch - 48;
                        }
                        while (offset < len && NumberUtils.isDigit(ch = chars[offset])) {
                            ++offset;
                            val = val * 10 + ch - 48;
                        }
                        int digitCnt = offset - begin;
                        // max index: 999999
                        if (digitCnt < 7) {
                            boolean up;
                            boolean down = false;
                            boolean range = false;
                            if ((up = ch == '+') || (down = ch == '-') || (range = ch == '~')) {
                                ++offset;
                            }
                            if (range) {
                                final int from = isNegative ? -val : val;
                                int to = 0;
                                isNegative = chars[offset] ==
                                        '-'; // Don't worry about whether the array is out of bounds or not
                                if (isNegative) {
                                    ++offset;
                                }
                                while (offset < len && NumberUtils.isDigit(ch = chars[offset])) {
                                    ++offset;
                                    to = to * 10 + ch - 48;
                                }
                                pathCollector = JSONNodePathCollector.range(from, isNegative ? -to : to);
                                result.next(pathCollector.recursive(recursive));
                                if (offset == len) {
                                    return result;
                                }
                                if (ch == '[' || ch == '/') {
                                    break;
                                }
                                String errorMsg = JSONGeneral.createErrorContextText(chars, offset);
                                throw new JSONException(
                                        "Syntax error, at pos " + offset + ", context text by '" + errorMsg +
                                                "', error range ");
                            }
                            boolean isEnd = offset == len;
                            if (isEnd || (ch = chars[offset]) == '[' || ch == '/') {
                                if (up) {
                                    pathCollector = JSONNodePathCollector.ge(isNegative ? -val : val);
                                } else if (down) {
                                    pathCollector = JSONNodePathCollector.le(isNegative ? -val : val);
                                } else {
                                    pathCollector = JSONNodePathCollector.exact(isNegative ? -val : val);
                                }
                                result.next(pathCollector.recursive(recursive));
                                if (isEnd) {
                                    return result;
                                }
                                break;
                            }
                        }
                        // -> string collector
                    }
                    while (offset < len && (ch = chars[offset]) != '[' && ch != '/') {
                        ++offset;
                    }
                    int pathLen = offset - begin;
                    char endChar = chars[offset - 1];
                    if (beginChar == '*') {
                        if (pathLen == 1) {
                            pathCollector = JSONNodePathCollector.any();
                        } else {
                            if (endChar == '*') {
                                pathCollector =
                                        JSONNodePathCollector.contains(new String(chars, begin + 1, pathLen - 2));
                            } else {
                                pathCollector = JSONNodePathCollector.suffix(new String(chars, begin + 1, pathLen - 1));
                            }
                        }
                    } else if (beginChar == '^') {
                        // 正则
                        pathCollector = JSONNodePathCollector.regular(new String(chars, begin + 1, pathLen - 1));
                    } else {
                        if (endChar == '*') {
                            pathCollector = JSONNodePathCollector.prefix(new String(chars, begin, pathLen - 1));
                        } else {
                            pathCollector =
                                    JSONNodePathCollector.exact(JSONNodeContext.getString(chars, begin, pathLen));
                        }
                    }
                    result.next(pathCollector.recursive(recursive));
                    if (offset == len) {
                        return result;
                    }

                } while (false);
                if (ch == '[') {
                    // Expression parsing util the end token ']'
                    try {
                        ExprParser parser = Expression.find(xpath, ++offset);
                        offset = parser.findIndex();
                        pathCollector.condition(parser);
                        result.supportedExtract(false);
                        if (offset == len || (ch = xpath.charAt(offset++)) != ']') {
                            String errorMsg = JSONGeneral.createErrorContextText(chars, offset);
                            throw new JSONException(
                                    "Syntax error, at pos " + offset + ", context text by '" + errorMsg +
                                            "', unexpected '" + ch + "', expected end ']'  error expression, ");
                        }
                        if (offset == len) {
                            return result;
                        }
                        ch = chars[offset];
                    } catch (RuntimeException e) {
                        if (e instanceof JSONException) {
                            throw e;
                        }
                        String errorMsg = JSONGeneral.createErrorContextText(chars, offset);
                        throw new JSONException("Syntax error, at pos " + offset + ", context text by '" + errorMsg +
                                "', error expression", e);
                    }
                }
                if (ch != '/') {
                    throw new JSONException(
                            "Syntax error, at pos " + offset + ", unexpected '" + ch + "', expected '/'.");
                }
            }
        } catch (Throwable e) {
            throw e instanceof JSONException ? (JSONException) e :
                    new JSONException("not supported path '" + xpath + "'");
        }
        return result;
    }

    /**
     * 按给定的路径片段收集器依次串成一条路径链。
     *
     * @param collectors 路径片段收集器，按顺序构成路径的各个层级
     * @return 新创建的路径模型；所有片段都支持单节点提取时该路径同样支持提取
     */
    public static JSONNodePath collectors(JSONNodePathCollector... collectors) {
        JSONNodePathCollector prev = null;
        JSONNodePathCollector head = null;
        JSONNodePathCollector tail = null;
        boolean supportedExtract = true;
        for (JSONNodePathCollector pathCollector : collectors) {
            if (!pathCollector.isSupportedExtract()) {
                supportedExtract = false;
            }
            if (prev == null) {
                head = tail = prev = pathCollector.clone();
            } else {
                pathCollector.chainable(prev);
                tail = prev = prev.next;
            }
        }
        return new JSONNodePath(head, tail, collectors.length).supportedExtract(supportedExtract);
    }

    /**
     * 构建精确路径链(单节点)
     *
     * @param paths 各层级的精确路径，字符串对应对象属性名，整数对应数组下标
     * @return 新创建的精确路径模型
     */
    public static JSONNodePath paths(Serializable... paths) {
        JSONNodePathCollector prev = null;
        JSONNodePathCollector head = null;
        JSONNodePathCollector tail = null;
        for (Serializable path : paths) {
            JSONNodePathCollector pathFragment = JSONNodePathCollector.exact(path);
            if (prev == null) {
                head = tail = prev = pathFragment;
            } else {
                prev.next = pathFragment;
                tail = prev = pathFragment;
            }
        }
        return new JSONNodePath(head, tail, paths.length).supportedExtract(true);
    }

    /**
     * 在当前路径末尾依次追加精确匹配的路径片段。
     *
     * @param paths 各层级的精确路径，字符串对应对象属性名，整数对应数组下标
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath nextPaths(Serializable... paths) {
        JSONNodePath nextNodePath = paths(paths);
        if (head == null) {
            head = nextNodePath.head;
            tail = nextNodePath.tail;
        } else {
            tail.next = nextNodePath.head;
            tail = nextNodePath.tail;
        }
        depth += paths.length;
        return this;
    }

    /**
     * 追加任意匹配片段（非递归）: {@code /*}
     *
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath any() {
        return any(false);
    }

    /**
     * 追加任意匹配片段: {@code //*} 或 {@code /*}
     *
     * @param recursive {@code true} 表示递归匹配子孙节点（{@code //*}），{@code false} 仅匹配当前层级
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath any(boolean recursive) {
        return next(JSONNodePathCollector.any().recursive(recursive));
    }

    /**
     * 追加精确匹配片段（非递归）: {@code /${path}}
     *
     * @param path 精确路径，字符串对应对象属性名，整数对应数组下标
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath exact(Serializable path) {
        return exact(path, false);
    }

    /**
     * 精确匹配: //${path} or /${path}
     *
     * @param path      精确路径，字符串对应对象属性名，整数对应数组下标
     * @param recursive true //${path}; false /${path}
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath exact(Serializable path, boolean recursive) {
        return next(JSONNodePathCollector.exact(path).recursive(recursive));
    }

    /**
     * 追加前缀匹配片段（非递归）: {@code /${path}*}
     *
     * @param path 属性名前缀
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath prefix(String path) {
        return prefix(path, false);
    }

    /**
     * 匹配前缀: //${path}* or /${path}*
     *
     * @param path      属性名前缀
     * @param recursive true -> //${path}*; false -> /${path}*
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath prefix(String path, boolean recursive) {
        return next(JSONNodePathCollector.prefix(path).recursive(recursive));
    }

    /**
     * 追加后缀匹配片段（非递归）: {@code /*${path}}
     *
     * @param path 属性名后缀
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath suffix(String path) {
        return suffix(path, false);
    }

    /**
     * 匹配后缀: //*${path} or /*${path}
     *
     * @param path      属性名后缀
     * @param recursive true -> //*${path}; false -> /*${path}
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath suffix(String path, boolean recursive) {
        return next(JSONNodePathCollector.suffix(path).recursive(recursive));
    }

    /**
     * 追加模糊（包含）匹配片段（非递归）: {@code /*${path}*}
     *
     * @param path 属性名中需要包含的内容
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath contains(String path) {
        return contains(path, false);
    }

    /**
     * 模糊匹配: //*${path}* or /*${path}*
     *
     * @param path      属性名中需要包含的内容
     * @param recursive true -> //*${path}*; false -> /*${path}*
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath contains(String path, boolean recursive) {
        return next(JSONNodePathCollector.contains(path).recursive(recursive));
    }

    /**
     * 追加正则匹配片段（非递归）。
     *
     * @param path 用于匹配属性名的正则表达式
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath regular(String path) {
        return regular(path, false);
    }

    /**
     * 正则表达式匹配路径
     *
     * @param str       用于匹配属性名的正则表达式
     * @param recursive {@code true} 表示递归匹配子孙节点，{@code false} 仅匹配当前层级
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath regular(String str, boolean recursive) {
        return next(JSONNodePathCollector.regular(str).recursive(recursive));
    }

    /**
     * 追加数组下标大于或等于某个值的收集片段（非递归）。
     *
     * @param index 起始下标，支持负数（倒数）
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath ge(int index) {
        return ge(index, false);
    }

    /**
     * 当目标节点为数组元素时支持收集大于或等于某个下标的集合
     *
     * @param index     下标支持负数（倒数）
     * @param recursive {@code true} 表示递归匹配子孙节点，{@code false} 仅匹配当前层级
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath ge(int index, boolean recursive) {
        return next(JSONNodePathCollector.ge(index).recursive(recursive));
    }

    /**
     * 追加数组下标小于或等于某个值的收集片段（非递归）。
     *
     * @param index 结束下标，支持负数（倒数）
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath le(int index) {
        return le(index, false);
    }

    /**
     * 当目标节点为数组元素时支持收集小于或等于某个下标的集合
     *
     * @param index     下标支持负数（倒数）
     * @param recursive {@code true} 表示递归匹配子孙节点，{@code false} 仅匹配当前层级
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath le(int index, boolean recursive) {
        return next(JSONNodePathCollector.le(index).recursive(recursive));
    }

    /**
     * 追加数组下标范围收集片段（非递归，from 和 to 都包含）。
     *
     * @param from 起始下标，支持负数（倒数）
     * @param to   结束下标，支持负数（倒数）
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath range(int from, int to) {
        return range(from, to, false);
    }

    /**
     * 当目标节点为数组元素时支持范围收集(from和to都是包含)
     *
     * @param from      下标支持负数（倒数）
     * @param to        下标支持负数（倒数）
     * @param recursive {@code true} 表示递归匹配子孙节点，{@code false} 仅匹配当前层级
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath range(int from, int to, boolean recursive) {
        return next(JSONNodePathCollector.range(from, to).recursive(recursive));
    }

    /**
     * 追加离散数组下标收集片段（非递归）。
     *
     * @param indexs 需要收集的离散下标列表，支持负数（倒数）
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath indexs(Integer... indexs) {
        return indexs(false, indexs);
    }

    /**
     * 当目标节点为数组元素时支持收集离散的下标列表
     *
     * @param recursive {@code true} 表示递归匹配子孙节点，{@code false} 仅匹配当前层级
     * @param indexs    需要收集的离散下标列表，支持负数（倒数）
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath indexs(boolean recursive, Integer... indexs) {
        return next(JSONNodePathCollector.indexs(indexs).recursive(recursive));
    }

    /**
     * 在当前路径末尾追加一个路径片段收集器。
     *
     * @param next 待追加的路径片段收集器
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath next(JSONNodePathCollector next) {
        final boolean supportedExtract = next.isSupportedExtract();
        if (head == null) {
            head = tail = next.self();
            this.supportedExtract = supportedExtract;
        } else {
            tail = tail.next = next.self();
            if (!supportedExtract) {
                this.supportedExtract = false;
            }
        }
        ++depth;
        return this;
    }

    /**
     * 清空已构建的所有路径片段，恢复为空路径。
     *
     * @return 当前对象，便于链式调用
     */
    public JSONNodePath resetPaths() {
        head = tail = null;
        supportedExtract = false;
        depth = 0;
        return this;
    }

    public String toString() {
        StringBuilder builder = new StringBuilder();
        JSONNodePathCollector value = head;
        while (value != null) {
            builder.append(value);
            value = value.next;
        }
        return builder.toString();
    }
}
