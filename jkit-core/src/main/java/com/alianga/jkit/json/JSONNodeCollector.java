package com.alianga.jkit.json;

/**
 * @time 2024/10/13 7:53
 */
public abstract class JSONNodeCollector<T> {
    /**
     * 返回自身，用于在链式调用中固定泛型类型
     *
     * @return 当前收集器对象
     */
    public final JSONNodeCollector<T> self() {
        return this;
    }

    /**
     * 将节点映射为目标类型的结果
     *
     * @param node 待映射的JSON节点
     * @return 映射后的结果对象
     */
    public abstract T map(JSONNode node);

    /**
     * 创建按指定类型取值的收集器
     *
     * @param targetClass 节点值的目标类型，不允许为 {@code null}
     * @param <T> 节点值的目标类型
     * @return 新建的收集器，其 map 方法返回节点转换为 {@code targetClass} 后的值
     */
    public static final <T> JSONNodeCollector<T> of(final Class<T> targetClass) {
        targetClass.getClass();
        return new JSONNodeCollector<T>() {
            @Override
            public T map(JSONNode node) {
                return node.getValue(targetClass);
            }
        };
    }

    static class LeafWrapImpl<T> extends JSONNodeCollector<T> {
        final JSONNodeCollector<T> collector;

        LeafWrapImpl(JSONNodeCollector<T> collector) {
            this.collector = collector;
        }

        @Override
        public T map(JSONNode node) {
            return collector.map(node);
        }

        public boolean filter(JSONNode node) {
            if (!node.leaf) {
                return false;
            }
            return collector.filter(node);
        }
    }

    /**
     * 是否只收集叶子节点（对象和数组类型忽略）
     *
     * @return 已是只收集叶子节点的收集器时返回自身，否则返回包装当前收集器的新实例
     */
    public JSONNodeCollector onlyCollectLeaf() {
        return this instanceof LeafWrapImpl ? this : new LeafWrapImpl(this);
    }

    /**
     * 判断节点是否需要被收集
     *
     * @param node 待判断的JSON节点
     * @return 默认实现始终返回 {@code true}，即收集全部节点
     */
    public boolean filter(JSONNode node) {
        return true;
    }

    /**
     * 默认收集器，直接收集 {@link JSONNode} 节点本身
     */
    public static final JSONNodeCollector<JSONNode> DEFAULT = new JSONNodeCollector<JSONNode>() {
        @Override
        public JSONNode map(JSONNode node) {
            return node;
        }
    };

    /**
     * 任意类型收集器，收集节点自动推断类型后的值
     */
    public static final JSONNodeCollector<Object> ANY = new JSONNodeCollector<Object>() {
        @Override
        public Object map(JSONNode node) {
            return node.any();
        }
    };
}
