package com.alianga.jkit.mock;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 惰性重复数组。
 *
 * <p>模板里 {@code "data|100000":[{...}]} 这类大数组，如果一次性展开成 {@code List}，
 * 会把十几万个对象同时压在堆上——10 万条 × 50 字段就是几百万个对象，实测峰值接近 2 GB。
 * 本类不预先展开，只在迭代时逐条向 {@link MockJs} 取值，配合
 * {@link MockDataFormatter} 的流式写出，可以做到「边生成边输出」，内存占用与条数无关。</p>
 *
 * <p>迭代顺序与 {@link MockJs#gen(Object, String)} 原本的展开顺序一致：
 * 第 {@code n} 个元素取自模板的第 {@code n % template.size()} 项。</p>
 *
 * <p>元素是按模板随机生成的，因此每次迭代都会得到不同的数据，本类不保证可重复迭代出相同结果。</p>
 */
public final class MockRepeat implements Iterable<Object> {
    private final MockJs engine;
    private final List<Object> template;
    private final int count;

    /**
     * 构造惰性数组。
     *
     * @param engine   生成引擎（复用同一实例才能保持 {@code |+1} 自增的连续性）
     * @param template 模板数组（非空，至少一项）
     * @param count    重复次数
     */
    MockRepeat(MockJs engine, List<Object> template, int count) {
        this.engine = engine;
        this.template = template;
        this.count = count;
    }

    /**
     * 元素总数（重复次数 × 模板项数），无需真正生成即可得知。
     *
     * @return 元素总数
     */
    public int size() {
        return count * (template == null ? 0 : template.size());
    }

    /**
     * 是否为空。
     *
     * @return 元素总数为 0 时返回 true
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * 返回逐条生成的迭代器，生成过程不缓存已产出的元素。
     *
     * @return 惰性迭代器
     */
    @Override
    public Iterator<Object> iterator() {
        return new Iterator<Object>() {
            private int produced;

            @Override
            public boolean hasNext() {
                return produced < size();
            }

            @Override
            public Object next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                int at = produced % template.size();
                produced++;
                return engine.gen(template.get(at), String.valueOf(at));
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
