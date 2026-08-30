package com.alianga.jkit.json;

import com.alianga.jkit.reflect.GenericParameterizedType;
import com.alianga.jkit.reflect.ReflectConsts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/***
 * Response parsing process through callback (subscription) mode
 * Hook mode, non asynchronous call
 */
public abstract class JSONReaderHook {
    private boolean abored;
    /**
     * 已命中过滤条件并被收集下来的解析结果
     */
    protected List<Object> results = new ArrayList<Object>();

    private String filterRegular;

    /**
     * 创建不带路径过滤条件的读取回调
     */
    public JSONReaderHook() {
    }

    /**
     * 按路径片段设置过滤用的正则表达式
     *
     * @param pathRegularSegments 路径片段，会被拼装成逐级可选匹配的正则表达式
     */
    public void setFilterRegular(String... pathRegularSegments) {
        this.filterRegular = buildRegular(pathRegularSegments);
    }

    /**
     * 清除已设置的路径过滤正则表达式
     *
     * @param pathRegularSegments 路径片段，当前实现忽略该参数，一律清空过滤条件
     */
    public void clearFilterRegular(String... pathRegularSegments) {
        this.filterRegular = null;
    }

    static final String buildRegular(String... pathRegularSegments) {
        StringBuilder regularBuilder = new StringBuilder();
        int i = 0;
        for (String pathRegularSegment : pathRegularSegments) {
            if (i++ > 0) {
                regularBuilder.append("(");
            }
            regularBuilder.append("\\/");
            regularBuilder.append(pathRegularSegment);
        }
        while (--i > 0) {
            regularBuilder.append(")?");
        }
        return regularBuilder.toString();
    }

    /**
     * 指定路径上的值需要反序列化成的泛型类型，子类可重写
     *
     * @param path JSON Absolute Path
     * @return 该路径对应的目标泛型类型，默认实现返回 {@code null} 表示不做类型转换
     */
    protected GenericParameterizedType<?> getParameterizedType(String path) {
        return null;
    }

    /**
     * 是否跳过
     *
     * @param path JSON Absolute Path
     * @param type 1. Object type; 2 Collection type; 3 String type; 4 Number type; 5 Boolean type; 6 Null
     * @return true: continue; false: stop
     */
    protected boolean filter(String path, int type) {
        if (filterRegular == null) {
            return true;
        }
        return "".equals(path) || path.matches(filterRegular);
    }

    /**
     * The method will no longer be used in future versions. Please use createdMap and createdCollection instead
     *
     * @param path JSON Absolute Path
     * @param type 1. Object type; 2 Collection type
     * @return the container instance created for the path, or null to let the parser create one
     * @throws Exception if the implementation fails to create the instance
     * @see #createdMap(String)
     * @see #createdCollection(String)
     */
    @Deprecated
    protected Object created(String path, int type) throws Exception {
        return null;
    }

    /**
     * 创建指定路径上对象({})所使用的 Map 实例，子类可重写
     *
     * @param path JSON Absolute Path
     * @return 该路径使用的 Map 实例，默认实现返回 {@code null}，表示由解析器决定
     */
    protected Map createdMap(String path) {
        return null;
    }

    /**
     * 创建指定路径上数组所使用的集合实例，子类可重写
     *
     * @param path JSON Absolute Path
     * @return 该路径使用的集合实例，默认实现返回 {@code null}，表示由解析器决定
     */
    protected Collection<?> createdCollection(String path) {
        return null;
    }

    /**
     * Assign property settings to the caller.
     * If you want to interrupt and exit the read, you can call abort()
     *
     * @param key the key if object({}), otherwise null
     * @param value map/collection/string/number
     * @param host object or collection
     * @param elementIndex the index if collection, otherwise -1
     * @param path JSON Absolute Path
     * @param type 1 Object; 2 Collection; 3 String; 4 Number; 5 Boolean; 6 Null;
     * @throws Exception if the implementation fails to handle the parsed value
     */
    protected abstract void parseValue(String key, Object value, Object host, int elementIndex, String path, int type)
            throws Exception;

    /**
     * 清空已收集的解析结果并复位终止标记，便于该回调对象被复用
     */
    public void reset() {
        if (results != null) {
            results.clear();
        }
        this.abored = false;
    }

    /**
     * Parse completed callback
     *
     * @param result 解析结果
     */
    protected void onCompleted(Object result) {
    }

    /**
     * terminate read operation
     */
    protected final void abort() {
        this.abored = true;
    }

    /**
     * 读取是否已被终止
     *
     * @return 已调用过 {@link #abort()} 时返回 {@code true}，否则返回 {@code false}
     */
    protected final boolean isAbored() {
        return abored;
    }

    /**
     * path解析完成触发调用(仅限对象或者数组);
     *
     * <p> 如果返回true则会终止读取；子类可重写实现自定义终止的时机;
     *
     * @param value 解析结果
     * @param path JSON Absolute Path
     * @param type 1 对象； 2 数组；
     * @return abort if true;
     */
    protected boolean isAboredOnParsed(Object value, String path, int type) {
        return false;
    }

    /**
     * 构建JSONReaderCallback
     *
     * @param regular 正则表达式
     * @return 按正则匹配路径来收集结果的 JSONReaderHook 实例
     */
    public static final JSONReaderHook regularPath(String regular) {
        return new JSONReaderHookRegular(regular);
    }

    /**
     * 构建JSONReaderCallback
     *
     * @param regular 正则表达式
     * @param onlyLeaf 是否只收集叶子节点的值，为 {@code true} 时不再构建中间的对象和数组容器
     * @return 按正则匹配路径来收集结果的 JSONReaderHook 实例
     */
    public static final JSONReaderHook regularPath(String regular, boolean onlyLeaf) {
        return new JSONReaderHookRegular(regular, onlyLeaf);
    }

    /**
     * 构建JSONReaderCallback
     *
     * @param exactPath 路径
     * @return 按路径精确匹配来收集结果的 JSONReaderHook 实例
     */
    public static final JSONReaderHook exactPath(String exactPath) {
        return new JSONReaderHookExact(exactPath);
    }

    /**
     * 构建JSONReaderCallback
     *
     * @param exactPath 路径
     * @param actualType 目标类型
     * @return 按路径精确匹配并将结果转换为 {@code actualType} 的 JSONReaderHook 实例
     */
    public static final JSONReaderHook exactPathAs(String exactPath, Class<?> actualType) {
        return exactPathAs(exactPath, GenericParameterizedType.actualType(actualType));
    }

    /**
     * 构建JSONReaderCallback
     *
     * @param exactPath 路径
     * @param parameterizedType 目标类型
     * @return 按目标类型的类别选择的 JSONReaderHook 实例：任意类型时不做转换，
     *         数组/集合/Map/对象使用复合类型实现，其余使用叶子节点实现
     */
    public static final JSONReaderHook exactPathAs(String exactPath, GenericParameterizedType<?> parameterizedType) {
        ReflectConsts.ClassCategory classCategory = parameterizedType.getActualClassCategory();
        switch (classCategory) {
            case ANY:
                return new JSONReaderHookExact(exactPath);
            case ArrayCategory:
            case CollectionCategory:
            case MapCategory:
            case ObjectCategory:
                return new JSONReaderHookExactComplex(exactPath, parameterizedType);
            default:
                return new JSONReaderHookExactLeaf(exactPath, parameterizedType);
        }
    }

    /**
     * 获取收集到的全部解析结果
     *
     * @return 结果列表，未命中任何路径时为空列表
     */
    public final List<Object> getResults() {
        return results;
    }

    /**
     * 获取收集到的第一个解析结果
     *
     * @param <E> 结果元素类型，由调用方指定
     * @return 首个解析结果，没有任何结果时返回 {@code null}
     */
    public final <E> E first() {
        return results.isEmpty() ? null : (E) results.get(0);
    }
}
