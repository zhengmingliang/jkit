package com.alianga.jkit.template;

import com.alianga.jkit.StringUtils;
import com.alianga.jkit.beans.ObjectUtils;

import java.util.Map;

/**
 * 模板类的抽象基类，编译生成的模板实现继承该类，通过内部缓冲区拼装渲染结果
 */
public abstract class TemplateClass {
    private final StringBuilder buffer;

    /**
     * 构造模板对象并初始化输出缓冲区
     */
    public TemplateClass() {
        buffer = new StringBuilder();
    }

    /***
     * 输出普通文本
     * @param text 待输出的文本内容
     * @return 当前对象，便于链式调用
     */
    protected final TemplateClass print(Object text) {
        buffer.append(text);
        return this;
    }

    /***
     * 输出换行符
     * @return 当前对象，便于链式调用
     */
    protected final TemplateClass println() {
        buffer.append("\r\n");
        return this;
    }

    /***
     * 输出普通文本并换行
     * @param text 待输出的文本内容
     * @return 当前对象，便于链式调用
     */
    protected final TemplateClass println(Object text) {
        buffer.append(text).append("\r\n");
        return this;
    }

    /**
     * 占位符替换
     *
     * @param text 含占位符的文本内容
     * @param context 占位符取值的上下文
     * @return 当前对象，便于链式调用
     */
    protected final TemplateClass println(String text, Map<String, Object> context) {
        return println(StringUtils.replaceGroupRegex(text, context, true));
    }

    /**
     * 获取上下文中指定key对应的可迭代对象
     *
     * @param context 参数上下文
     * @param key 上下文中的键，支持层级取值
     * @return 该key对应值的可迭代视图，用于模板中的循环渲染
     */
    protected final Iterable<Object> getContextIterable(Map<String, Object> context, String key) {
        return ObjectUtils.getIterable(context, key);
    }

    /**
     * 获取上下文内容
     *
     * @param target 实体对象或者map
     * @param key 属性名或键名，支持层级取值
     * @return 该key对应的值，不存在时返回 {@code null}
     */
    protected final Object getContextValue(Object target, String key) {
        return ObjectUtils.get(target, key);
    }

    /**
     * 获取上下文内容（提供缺省取值）
     *
     * @param target 实体对象或者map
     * @param key 属性名或键名，支持层级取值
     * @param defaultValue 默认值
     * @return 该key对应的值，取值为 {@code null} 时返回 {@code defaultValue}
     */
    protected final Object getContextValue(Object target, String key, Object defaultValue) {
        Object value = ObjectUtils.get(target, key);
        if (value == null) {
            return defaultValue;
        }
        return value;
    }

    /**
     * 清空缓冲区并渲染模板
     *
     * @param context 渲染使用的参数上下文
     * @return 渲染后的完整文本
     */
    protected synchronized String render(Map<String, Object> context) {
        buffer.setLength(0);
        renderTemplate(context);
        return getTemplate();
    }

    /**
     * 由子类实现的模板渲染逻辑，将渲染内容通过 print/println 写入缓冲区
     *
     * @param context 渲染使用的参数上下文
     */
    protected abstract void renderTemplate(Map<String, Object> context);

    private String getTemplate() {
        return buffer.toString();
    }
}
