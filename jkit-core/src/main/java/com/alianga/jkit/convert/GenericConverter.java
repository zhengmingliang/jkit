/*
 * Created by 郑明亮 on 2021/4/23 15:05.
 */

//

package com.alianga.jkit.convert;

import java.util.List;
import java.util.function.Function;

/**
 *
 * @author 郑明亮
 * @version 1.0
 * @date 2021/4/4 15:05
 * @email mpro@vip.qq.com
 * @since 1.2.2
 */
public interface GenericConverter<I, O> extends Function<I, O> {
    /**
     * 转换单个对象，入参为 {@code null} 时不执行转换。
     *
     * @param input 待转换的输入对象，可为 {@code null}
     * @return 转换后的输出对象；入参为 {@code null} 时返回 {@code null}
     */
    default O convert(final I input) {
        O output = null;
        if (input != null) {
            output = this.apply(input);
        }
        return output;
    }

    /**
     * 按顺序逐个转换列表中的元素，入参为 {@code null} 时不执行转换。
     *
     * @param input 待转换的输入列表，可为 {@code null}
     * @return 与入参等长、元素逐个转换后的新列表；入参为 {@code null} 时返回 {@code null}
     */
    default List<O> convert(final List<I> input) {
        List<O> output = null;
        if (input != null) {
            output = new java.util.ArrayList<>(input.size());
            for (I data : input) {
                output.add(apply(data));
            }
        }
        return output;
    }

}
