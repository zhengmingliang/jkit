package com.alianga.jkit.json;

import com.alianga.jkit.StringUtils;
import com.alianga.jkit.expression.Expression;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <p>
 * xpath语法支持（仅仅支持以下简单语法，部分语法进行了语义替换，xpath复杂的语法不考虑实现,可以自定义实现JSONNodePathCollector）
 * </p>
 *
 * <h2>路径分隔语法(和xpath一致)</h2>
 * <ul>
 * <li>"//" : 从当前节点开始查找所有满足条件的节点，并递归所有子孙节点；</li>
 * <li>"/" : 仅从当前节点开始查找所有满足条件的节点（不递归）；</li>
 * </ul>
 *
 * <h2>路径和属性匹配语法(xpath改进方便识别解析)</h2>
 * <ul>
 * <li> * : 任意匹配；</li>
 * <li> abc* : 仅仅支持对象节点查找，匹配前缀为abc；</li>
 * <li> *abc : 仅仅支持对象节点查找，匹配后缀为abc；</li>
 * <li> *abc* : 仅仅支持对象节点查找，匹配包含abc；</li>
 * <li> ^xxx : 仅仅支持对象节点查找，匹配正则表达式；</li>
 * <li> n+ : 仅仅支持数组节点查找，匹配索引大于等于n；</li>
 * <li> n- : 仅仅支持数组节点查找，匹配索引小于等于n；</li>
 * <li> n~m : 仅仅支持数组节点查找，匹配索引大于等于n且小于等于m；</li>
 * <li> [n,n1,n2] : 仅仅支持数组节点查找，匹配索引存在集合；</li>
 * <li> '*BC*[SDA' : 仅仅支持对象节点查找,且精确匹配*BC*[SDA；</li>
 * </ul>
 * <h2>路径后面统一支持过滤表达式使用中括号环绕，表达式语法参考Expression</h2>
 *
 * @time 2024/10/10 22:17
 * @see Expression
 */
public abstract class JSONNodePathCollector {
    /**
     * 当前层级要匹配的路径（对象属性名或数组下标描述）
     */
    protected Serializable path;
    /**
     * 是否递归查找子孙节点，对应路径语法中的 "//"
     */
    protected boolean recursive;
    JSONNodePathCollector next;
    JSONNodePathFilter filter;

    /**
     * 构造一个不限定路径的收集器。
     */
    public JSONNodePathCollector() {
        this(null);
    }

    /**
     * 构造一个指定匹配路径的收集器。
     *
     * @param path 当前层级要匹配的路径，可为 null 表示不限定
     */
    public JSONNodePathCollector(Serializable path) {
        this.path = path;
    }

    final JSONNodePathCollector self() {
        return this;
    }

    final boolean isSupportedExtract() {
        return !recursive && filter == null;
    }

    /**
     * Path syntax: '//' or '/'
     *
     * @param recursive '//' if true, '/' otherwise
     * @return 当前对象，便于链式调用
     */
    public final JSONNodePathCollector recursive(boolean recursive) {
        this.recursive = recursive;
        return this;
    }

    /**
     * 设置节点过滤器。
     *
     * @param filter 节点过滤器，为 null 表示不过滤
     * @return 当前对象，便于链式调用
     */
    public final JSONNodePathCollector filter(JSONNodePathFilter filter) {
        this.filter = filter;
        return this;
    }

    /**
     * 表达式过滤
     *
     * @param condition 过滤条件表达式
     * @return 任意路径匹配且带有该条件过滤器的新收集器
     */
    public static final JSONNodePathCollector filter(String condition) {
        return JSONNodePathCollector.any().filter(JSONNodePathFilter.condition(condition));
    }

    /**
     * 表达式过滤
     *
     * @param condition 过滤条件表达式
     * @return 当前对象，便于链式调用
     */
    public final JSONNodePathCollector condition(String condition) {
        return filter(JSONNodePathFilter.condition(condition));
    }

    /**
     * 表达式过滤
     *
     * @param expression 已编译的过滤表达式
     * @return 当前对象，便于链式调用
     */
    public final JSONNodePathCollector condition(Expression expression) {
        return filter(JSONNodePathFilter.expression(expression));
    }

    /**
     * 判断当前收集器是否为路径链上的最后一段。
     *
     * @return 后续没有其他路径片段时返回 {@code true}，否则返回 {@code false}
     */
    public final boolean leafPath() {
        return next == null;
    }

    /**
     * 判断是否匹配成功
     *
     * @param index  目标节点在父数组中的下标
     * @param size   父数组的元素总数
     * @param target 父节点为数组，且下标值为index的目标节点
     * @return 匹配成功时返回 {@code true}，否则返回 {@code false}
     */
    protected abstract boolean matched(int index, int size, JSONNode target);

    /**
     * 判断是否匹配成功
     *
     * @param field  目标节点在父对象中的属性名
     * @param target 父节点为对象，名称为${field}的目标节点
     * @return 匹配成功时返回 {@code true}，否则返回 {@code false}
     */
    protected abstract boolean matched(String field, JSONNode target);

    /**
     * 执行过滤操作
     *
     * @param target 待过滤的目标节点
     * @return 未设置过滤器或通过过滤时返回 {@code true}，否则返回 {@code false}
     */
    protected final boolean doFilter(JSONNode target) {
        if (filter == null) {
            return true;
        }
        return filter.doFilter(target);
    }

    final <T> void addIfRecord(JSONNode node, Collection<T> results, JSONNodeCollector<T> collector,
                               JSONNodePathCtx collectCtx) {
        if (node.collectCtx != collectCtx) {
            if (collector.filter(node)) {
                node.collectCtx = collectCtx;
                results.add(collector.map(node));
            }
        }
    }

    /**
     * 遍历父节点的子元素，把匹配当前路径且通过过滤的节点交给下一段路径或直接收集。
     *
     * @param parentNode 待遍历的父节点
     * @param results    收集结果的容器
     * @param collector  节点收集器，负责过滤与映射
     * @param collectCx  本次收集的上下文，用于避免同一节点重复收集
     * @param <T>        收集结果的元素类型
     */
    protected <T> void collect(JSONNode parentNode, Collection<T> results, JSONNodeCollector<T> collector,
                               final JSONNodePathCtx collectCx) {
        if (parentNode.leaf) {
            return;
        }
        parentNode.ensureCompleted(!recursive);
        final boolean leafPath = leafPath();
        if (parentNode.array) {
            for (int i = 0, size = parentNode.elementSize; i < size; ++i) {
                JSONNode value = parentNode.elementValues[i];
                if (matched(i, size, value) && doFilter(value)) {
                    if (leafPath) {
                        addIfRecord(value, results, collector, collectCx);
                    } else {
                        next.collect(value, results, collector, collectCx);
                    }
                }
                if (recursive) {
                    collect(value, results, collector, collectCx);
                }
            }
        } else {
            Map<Serializable, JSONNode> fieldValues = parentNode.fieldValues;
            Set<Map.Entry<Serializable, JSONNode>> entrySet = fieldValues.entrySet();
            for (Map.Entry<Serializable, JSONNode> entry : entrySet) {
                String field = (String) entry.getKey();
                JSONNode value = entry.getValue();
                if (matched(field, value) && doFilter(value)) {
                    if (leafPath) {
                        addIfRecord(value, results, collector, collectCx);
                    } else {
                        next.collect(value, results, collector, collectCx);
                    }
                }
                if (recursive) {
                    collect(value, results, collector, collectCx);
                }
            }
        }
    }

    JSONNodePathCollector cloneCurrent() {
        return this;
    }

    public final JSONNodePathCollector clone() {
        JSONNodePathCollector pathCollector = cloneCurrent();
        pathCollector.recursive = recursive;
        pathCollector.filter = filter;
        return pathCollector;
    }

    final JSONNodePathCollector chainable(JSONNodePathCollector prev) {
        JSONNodePathCollector cloneFragment = clone();
        prev.next = cloneFragment;
        return prev;
    }

    public final String toString() {
        String pathStr = toPathString();
        String result = recursive ? "//" + pathStr : "/" + pathStr;
        if (filter != null) {
            result += filter.toString();
        }
        return result;
    }

    /**
     * 返回当前路径片段的字符串表示，供 toString 拼接使用。
     *
     * @return 路径片段字符串，基类默认返回空字符串
     */
    protected String toPathString() {
        return "";
    }

    /**
     * 判断对象属性名是否匹配当前路径片段。
     *
     * @param key 对象节点的属性名
     * @return 基类默认返回 -1 表示不匹配；子类实现中 1 表示匹配且为最后一个匹配项，0 表示匹配
     */
    protected int matchedObjectField(String key) {
        return -1;
    }

    /**
     * 判断匹配数组下标时是否需要预先知道数组长度。
     *
     * @return 需要数组长度参与匹配时返回 {@code true}，否则返回 {@code false}
     */
    protected boolean preparedSize() {
        return false;
    }

    /**
     * 返回匹配结果
     *
     * @param index 目标节点在父数组中的下标
     * @param size  父数组的元素总数
     * @return 如果没有匹配到返回-1，如果匹配到了并且能确定是最后一个匹配到的则返回1，否则返回0
     */
    protected int matchedArrayIndex(int index, int size) {
        return -1;
    }

    /**
     * 判断当前收集器是否可走直接提取（无需遍历）的快速路径。
     *
     * @return 支持直接提取时返回 {@code true}，否则返回 {@code false}
     */
    protected boolean isExtract() {
        return false;
    }

    abstract static class PathInternalImpl extends JSONNodePathCollector {
        public PathInternalImpl(Serializable path) {
            super(path);
        }

        public PathInternalImpl() {
        }

        @Override
        protected final boolean matched(int index, int size, JSONNode target) {
            return false;
        }

        @Override
        protected final boolean matched(String field, JSONNode target) {
            return false;
        }
    }

    static final class AnyPathImpl extends PathInternalImpl {
        @Override
        protected int matchedObjectField(String key) {
            return 0;
        }

        @Override
        protected int matchedArrayIndex(int index, int size) {
            return 0;
        }

        @Override
        protected <T> void collect(JSONNode parentNode, Collection<T> results, JSONNodeCollector<T> collector,
                                   JSONNodePathCtx collectCx) {
            if (parentNode.leaf) {
                return;
            }
            parentNode.ensureCompleted(!recursive);
            final boolean leafPath = leafPath();
            if (parentNode.array) {
                for (int i = 0, size = parentNode.elementSize; i < size; ++i) {
                    JSONNode value = parentNode.elementValues[i];
                    if (doFilter(value)) {
                        if (leafPath) {
                            addIfRecord(value, results, collector, collectCx);
                            if (recursive) {
                                collect(value, results, collector, collectCx);
                            }
                        } else {
                            next.collect(value, results, collector, collectCx);
                        }
                    } else {
                        if (recursive) {
                            collect(value, results, collector, collectCx);
                        }
                    }
                }
            } else {
                Map<Serializable, JSONNode> fieldValues = parentNode.fieldValues;
                for (JSONNode value : fieldValues.values()) {
                    if (doFilter(value)) {
                        if (leafPath) {
                            addIfRecord(value, results, collector, collectCx);
                            if (recursive) {
                                collect(value, results, collector, collectCx);
                            }
                        } else {
                            next.collect(value, results, collector, collectCx);
                        }
                    } else {
                        if (recursive) {
                            collect(value, results, collector, collectCx);
                        }
                    }
                }
            }
        }

        @Override
        protected String toPathString() {
            return "*";
        }

        @Override
        JSONNodePathCollector cloneCurrent() {
            return new AnyPathImpl();
        }
    }

    static final class ExactImpl extends PathInternalImpl {
        final boolean preparedSize;
        final byte[] pathBytes;

        public ExactImpl(Serializable path) {
            super(path);
            this.preparedSize = path instanceof Integer && (Integer) path < 0;
            this.pathBytes = JSONMemoryHandle.getStringUTF8Bytes(path.toString());
        }

        @Override
        protected int matchedObjectField(String key) {
            return path.toString().equals(key) ? 1 : -1;
        }

        @Override
        protected boolean isExtract() {
            return true;
        }

        @Override
        protected int matchedArrayIndex(int index, int size) {
            if (path instanceof Integer) {
                int val = (Integer) path;
                if (val < 0) {
                    val += size;
                }
                return val == index ? 1 : -1;
            }
            return -1;
        }

        @Override
        protected boolean preparedSize() {
            return preparedSize;
        }

        protected <T> void collect(JSONNode parentNode, Collection<T> results, JSONNodeCollector<T> collector,
                                   JSONNodePathCtx collectCx) {
            if (parentNode.leaf) {
                return;
            }
            final boolean leafPath = leafPath();
            if (recursive) {
                parentNode.ensureCompleted(false, false);
                if (parentNode.array) {
                    int size = parentNode.elementSize;
                    int index = path instanceof Integer ? (Integer) path : size;
                    if (index < 0) {
                        index += size;
                    }
                    for (int i = 0; i < size; ++i) {
                        JSONNode value = parentNode.elementValues[i];
                        if (i == index && doFilter(value)) {
                            if (leafPath) {
                                addIfRecord(value, results, collector, collectCx);
                            } else {
                                next.collect(value, results, collector, collectCx);
                            }
                            if (!collectCx.greedy) {
                                continue;
                            }
                        }
                        collect(value, results, collector, collectCx);
                    }
                } else {
                    Map<Serializable, JSONNode> fieldValues = parentNode.fieldValues;
                    Set<Map.Entry<Serializable, JSONNode>> entrySet = fieldValues.entrySet();
                    for (Map.Entry<Serializable, JSONNode> entry : entrySet) {
                        String field = (String) entry.getKey();
                        JSONNode value = entry.getValue();
                        if (field.equals(path.toString()) && doFilter(value)) {
                            if (leafPath) {
                                addIfRecord(value, results, collector, collectCx);
                            } else {
                                next.collect(value, results, collector, collectCx);
                            }
                            if (!collectCx.greedy) {
                                continue;
                            }
                        }
                        collect(value, results, collector, collectCx);
                    }
                }
            } else {
                JSONNode target;
                if (parentNode.array) {
                    if (!(path instanceof Integer)) {
                        return;
                    }
                    int index = (Integer) path;
                    if (index < 0) {
                        parentNode.ensureCompleted(true);
                        index += parentNode.elementSize;
                    }
                    target = parentNode.getElementAt(index);
                } else {
                    target = parentNode.getFieldNodeAt(path.toString());
                }
                if (target == null) {
                    return;
                }
                if (doFilter(target)) {
                    if (leafPath) {
                        addIfRecord(target, results, collector, collectCx);
                    } else {
                        next.collect(target, results, collector, collectCx);
                    }
                }
            }
        }

        @Override
        protected String toPathString() {
            return String.valueOf(path);
        }

        @Override
        JSONNodePathCollector cloneCurrent() {
            return new ExactImpl(path);
        }
    }

    abstract static class ObjectNodeImpl extends PathInternalImpl {
        public ObjectNodeImpl(Serializable path) {
            super(path);
        }

        protected abstract boolean matched(String field, String path);

        protected <T> void collect(JSONNode parentNode, Collection<T> results, JSONNodeCollector<T> collector,
                                   JSONNodePathCtx collectCx) {
            if (parentNode.leaf) {
                return;
            }
            String str = path.toString().trim();
            final boolean leafPath = leafPath();
            if (parentNode.isObject()) {
                parentNode.ensureCompleted(!recursive);
                Map<Serializable, JSONNode> fieldValues = parentNode.fieldValues;
                Set<Map.Entry<Serializable, JSONNode>> entrySet = fieldValues.entrySet();
                for (Map.Entry<Serializable, JSONNode> entry : entrySet) {
                    String field = entry.getKey().toString();
                    JSONNode value = entry.getValue();
                    if (matched(field, str) && doFilter(value)) {
                        if (leafPath) {
                            addIfRecord(value, results, collector, collectCx);
                        } else {
                            next.collect(value, results, collector, collectCx);
                        }
                        if (!collectCx.greedy) {
                            continue;
                        }
                    }
                    if (recursive) {
                        collect(value, results, collector, collectCx);
                    }
                }
            } else {
                if (recursive) {
                    parentNode.ensureCompleted(false);
                    for (int size = parentNode.elementSize, i = 0; i < size; ++i) {
                        JSONNode value = parentNode.elementValues[i];
                        collect(value, results, collector, collectCx);
                    }
                }
            }
        }
    }

    abstract static class ArrayNodeImpl extends PathInternalImpl {
        public abstract void parse(JSONNode parentNode);

        public int from(int size) {
            return 0;
        }

        public int to(int size) {
            return size;
        }

        protected abstract boolean matched(int index, int size);

        protected <T> void collect(JSONNode parentNode, Collection<T> results, JSONNodeCollector<T> collector,
                                   JSONNodePathCtx collectCx) {
            if (parentNode.leaf) {
                return;
            }
            final boolean leafPath = leafPath();
            if (parentNode.array) {
                if (recursive) {
                    parentNode.ensureCompleted(false);
                    for (int i = 0, size = parentNode.elementSize; i < size; ++i) {
                        JSONNode value = parentNode.elementValues[i];
                        if (matched(i, size) && doFilter(value)) {
                            if (leafPath) {
                                addIfRecord(value, results, collector, collectCx);
                            } else {
                                next.collect(value, results, collector, collectCx);
                            }
                            if (!collectCx.greedy) {
                                continue;
                            }
                        }
                        collect(value, results, collector, collectCx);
                    }
                } else {
                    parse(parentNode);
                    for (int size = parentNode.elementSize, i = from(size), to = to(size); i < to; ++i) {
                        JSONNode value = parentNode.elementValues[i];
                        if (matched(i, size) && doFilter(value)) {
                            if (leafPath) {
                                addIfRecord(value, results, collector, collectCx);
                            } else {
                                next.collect(value, results, collector, collectCx);
                            }
                        }
                    }
                }
            } else {
                if (recursive) {
                    parentNode.ensureCompleted(false);
                    Map<Serializable, JSONNode> fieldValues = parentNode.fieldValues;
                    for (JSONNode value : fieldValues.values()) {
                        collect(value, results, collector, collectCx);
                    }
                }
            }
        }
    }

    static final class PrefixImpl extends ObjectNodeImpl {
        public PrefixImpl(Serializable path) {
            super(path);
        }

        @Override
        protected boolean matched(String field, String path) {
            return field.startsWith(path);
        }

        @Override
        protected int matchedObjectField(String key) {
            return key.startsWith((String) path) ? 0 : -1;
        }

        @Override
        JSONNodePathCollector cloneCurrent() {
            return new PrefixImpl(path);
        }

        @Override
        protected String toPathString() {
            return path + "*";
        }
    }

    static final class SuffixImpl extends ObjectNodeImpl {
        public SuffixImpl(Serializable path) {
            super(path);
        }

        @Override
        protected boolean matched(String field, String path) {
            return field.endsWith(path);
        }

        @Override
        protected int matchedObjectField(String key) {
            return key.endsWith((String) path) ? 0 : -1;
        }

        @Override
        JSONNodePathCollector cloneCurrent() {
            return new SuffixImpl(path);
        }

        @Override
        protected String toPathString() {
            return "*" + path;
        }
    }

    static final class ContainsImpl extends ObjectNodeImpl {
        public ContainsImpl(Serializable path) {
            super(path);
        }

        @Override
        protected boolean matched(String field, String path) {
            return field.contains(path);
        }

        @Override
        protected int matchedObjectField(String key) {
            return key.contains((String) path) ? 0 : -1;
        }

        @Override
        JSONNodePathCollector cloneCurrent() {
            return new ContainsImpl(path);
        }

        @Override
        protected String toPathString() {
            return "*" + path + "*";
        }
    }

    // 正则
    static final class RegularImpl extends ObjectNodeImpl {
        final Pattern pattern;

        public RegularImpl(String path) {
            this(Pattern.compile(path), path);
        }

        RegularImpl(Pattern pattern, Serializable path) {
            super(path);
            this.pattern = pattern;
        }

        @Override
        protected boolean matched(String field, String path) {
            try {
                return pattern.matcher(field).matches();
            } catch (Throwable throwable) {
                return false;
            }
        }

        @Override
        protected int matchedObjectField(String key) {
            try {
                return pattern.matcher(key).matches() ? 0 : -1;
            } catch (Throwable throwable) {
                return -1;
            }
        }

        @Override
        JSONNodePathCollector cloneCurrent() {
            return new RegularImpl(pattern, path);
        }

        @Override
        protected String toPathString() {
            return '(' + String.valueOf(path) + ')';
        }
    }

    static final class GEImpl extends ArrayNodeImpl {
        final int minIndexValue;
        final boolean preparedSize;

        public GEImpl(int minIndexValue) {
            this.minIndexValue = minIndexValue;
            this.preparedSize = minIndexValue < 0;
        }

        @Override
        public int from(int size) {
            return minIndexValue < 0 ? Math.max(minIndexValue + size, 0) : minIndexValue;
        }

        @Override
        protected boolean matched(int index, int size) {
            int targetIndex = minIndexValue < 0 ? minIndexValue + size : minIndexValue;
            return index >= targetIndex;
        }

        @Override
        protected int matchedArrayIndex(int index, int size) {
            return matched(index, size) ? 0 : -1;
        }

        @Override
        protected boolean preparedSize() {
            return preparedSize;
        }

        @Override
        public void parse(JSONNode parentNode) {
            parentNode.parseElementTo(-1);
        }

        @Override
        JSONNodePathCollector cloneCurrent() {
            return new GEImpl(minIndexValue);
        }

        @Override
        protected String toPathString() {
            return String.valueOf(minIndexValue) + '+';
        }
    }

    static final class LEImpl extends ArrayNodeImpl {
        final int maxIndexValue;
        final boolean preparedSize;

        public LEImpl(int maxIndexValue) {
            this.maxIndexValue = maxIndexValue;
            this.preparedSize = maxIndexValue < 0;
        }

        @Override
        public int to(int size) {
            return maxIndexValue < 0 ? maxIndexValue + size + 1 : Math.min(maxIndexValue + 1, size);
        }

        @Override
        protected boolean preparedSize() {
            return preparedSize;
        }

        @Override
        protected boolean matched(int index, int size) {
            int targetIndex = maxIndexValue;
            if (targetIndex < 0) {
                targetIndex += size;
            }
            return index <= targetIndex;
        }

        @Override
        protected int matchedArrayIndex(int index, int size) {
            int mv = maxIndexValue > 0 ? maxIndexValue : maxIndexValue + size;
            if (index == mv) {
                return 1;
            }
            return index < maxIndexValue ? 0 : -1;
        }

        @Override
        public void parse(JSONNode parentNode) {
            parentNode.parseElementTo(maxIndexValue);
        }

        @Override
        JSONNodePathCollector cloneCurrent() {
            return new LEImpl(maxIndexValue);
        }

        @Override
        protected String toPathString() {
            return String.valueOf(maxIndexValue) + '-';
        }
    }

    // 范围(连续)
    static final class RangeImpl extends ArrayNodeImpl {
        final int from;
        final int to;
        final boolean preparedSize;

        public RangeImpl(int from, int to) {
            this.from = from;
            this.to = to;
            this.preparedSize = from < 0 || to < 0;
        }

        @Override
        public int from(int size) {
            if (from < 0) {
                return Math.max(from + size, 0);
            } else {
                return from;
            }
        }

        @Override
        public int to(int size) {
            if (to < 0) {
                return to + size + 1;
            } else {
                return Math.min(to + 1, size);
            }
        }

        @Override
        protected boolean matched(int index, int size) {
            int startIndex = from < 0 ? from + size : from;
            int endIndex = to < 0 ? to + size : to;
            return index >= startIndex && index <= endIndex;
        }

        @Override
        protected int matchedArrayIndex(int index, int size) {
            int startIndex = from < 0 ? from + size : from;
            int endIndex = to < 0 ? to + size : to;
            boolean rec = index >= startIndex && index <= endIndex;
            if (rec) {
                return index == endIndex ? 1 : 0;
            } else {
                return -1;
            }
        }

        @Override
        protected boolean preparedSize() {
            return preparedSize;
        }

        @Override
        public void parse(JSONNode parentNode) {
            parentNode.parseElementTo(to < 0 ? -1 : to);
        }

        @Override
        JSONNodePathCollector cloneCurrent() {
            return new RangeImpl(from, to);
        }

        @Override
        protected String toPathString() {
            return from + "~" + to;
        }
    }

    // 离散数组下标例如[1,5,7]
    static final class DiscreteIndexImpl extends PathInternalImpl {
        final Integer[] indexs;
        final boolean preparedSize;
        final int maxPositiveIndex;

        public DiscreteIndexImpl(Integer[] indexs) {
            this.indexs = indexs;
            boolean preparedSize = false;
            int maxIndex = 0;
            for (Integer index : indexs) {
                if (index < 0) {
                    preparedSize = true;
                } else {
                    maxIndex = Math.max(maxIndex, index);
                }
            }
            this.preparedSize = preparedSize;
            this.maxPositiveIndex = maxIndex;
        }

        @Override
        protected <T> void collect(JSONNode parentNode, Collection<T> results, JSONNodeCollector<T> collector,
                                   JSONNodePathCtx collectCx) {
            if (parentNode.leaf) {
                return;
            }
            final boolean leafPath = leafPath();
            if (recursive) {
                parentNode.ensureCompleted(false);
                if (parentNode.array) {
                    for (int size = parentNode.elementSize, i = 0; i < size; ++i) {
                        JSONNode value = parentNode.elementValues[i];
                        if (matched(i, size) && doFilter(value)) {
                            if (leafPath) {
                                addIfRecord(value, results, collector, collectCx);
                            } else {
                                next.collect(value, results, collector, collectCx);
                            }
                            if (!collectCx.greedy) {
                                continue;
                            }
                        }
                        collect(value, results, collector, collectCx);
                    }
                } else {
                    Map<Serializable, JSONNode> fieldValues = parentNode.fieldValues;
                    for (JSONNode value : fieldValues.values()) {
                        collect(value, results, collector, collectCx);
                    }
                }
            } else {
                for (Integer index : indexs) {
                    JSONNode value = parentNode.getElementAt(index);
                    if (value != null) {
                        if (leafPath) {
                            addIfRecord(value, results, collector, collectCx);
                        } else {
                            next.collect(value, results, collector, collectCx);
                        }
                    }
                }
            }
        }

        protected boolean matched(int i, int size) {
            for (Integer index : indexs) {
                if (index == i || (index + size) == i) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected int matchedArrayIndex(int index, int size) {
            if (!preparedSize && index == maxPositiveIndex) {
                return 1;
            }
            return matched(index, size) ? 0 : -1;
        }

        @Override
        protected boolean preparedSize() {
            return preparedSize;
        }

        @Override
        JSONNodePathCollector cloneCurrent() {
            return new DiscreteIndexImpl(indexs);
        }

        @Override
        protected String toPathString() {
            return '[' + StringUtils.join(",", (Object[]) indexs) + ']';
        }
    }

    /**
     * 创建任意匹配（对应语法 *）的路径收集器。
     *
     * @return 匹配当前层级任意对象属性或数组元素的收集器
     */
    public static final JSONNodePathCollector any() {
        return new AnyPathImpl();
    }

    /**
     * 创建精确匹配的路径收集器。
     *
     * @param path 要精确匹配的对象属性名或数组下标
     * @return 仅匹配与 path 完全相等的节点的收集器
     */
    public static final JSONNodePathCollector exact(Serializable path) {
        return new ExactImpl(path);
    }

    /**
     * 创建前缀匹配（对应语法 abc*）的路径收集器。
     *
     * @param path 属性名前缀
     * @return 匹配属性名以 path 开头的节点的收集器
     */
    public static final JSONNodePathCollector prefix(Serializable path) {
        return new PrefixImpl(path);
    }

    /**
     * 创建后缀匹配（对应语法 *abc）的路径收集器。
     *
     * @param path 属性名后缀
     * @return 匹配属性名以 path 结尾的节点的收集器
     */
    public static final JSONNodePathCollector suffix(Serializable path) {
        return new SuffixImpl(path);
    }

    /**
     * 创建包含匹配（对应语法 *abc*）的路径收集器。
     *
     * @param path 属性名需要包含的片段
     * @return 匹配属性名包含 path 的节点的收集器
     */
    public static final JSONNodePathCollector contains(Serializable path) {
        return new ContainsImpl(path);
    }

    /**
     * 创建正则匹配（对应语法 ^xxx）的路径收集器。
     *
     * @param path 用于匹配属性名的正则表达式
     * @return 匹配属性名满足该正则的节点的收集器
     */
    public static final JSONNodePathCollector regular(String path) {
        return new RegularImpl(path);
    }

    /**
     * 创建数组下标下限匹配（对应语法 n+）的路径收集器。
     *
     * @param index 下标下限
     * @return 匹配数组下标大于等于 index 的元素的收集器
     */
    public static final JSONNodePathCollector ge(int index) {
        return new GEImpl(index);
    }

    /**
     * 创建数组下标上限匹配（对应语法 n-）的路径收集器。
     *
     * @param index 下标上限
     * @return 匹配数组下标小于等于 index 的元素的收集器
     */
    public static final JSONNodePathCollector le(int index) {
        return new LEImpl(index);
    }

    /**
     * 创建数组下标区间匹配（对应语法 n~m）的路径收集器。
     *
     * @param from 起始下标（含）
     * @param to   结束下标（含）
     * @return 匹配下标位于 [from, to] 区间内的元素的收集器
     */
    public static final JSONNodePathCollector range(int from, int to) {
        return new RangeImpl(from, to);
    }

    /**
     * 创建离散数组下标匹配（对应语法 [n,n1,n2]）的路径收集器。
     *
     * @param indexs 允许匹配的下标集合
     * @return 匹配下标存在于给定集合中的元素的收集器
     */
    public static final JSONNodePathCollector indexs(Integer... indexs) {
        return new DiscreteIndexImpl(indexs);
    }
}
