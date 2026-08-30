package com.alianga.jkit.json;

import com.alianga.jkit.StringUtils;

/**
 * JSON Schema 单条校验规则，支持正则表达式、条件表达式或自定义验证器三种校验方式。
 */
public final class JSONSchemaRule {
    // 构建内置正则校验器（如果同时配置正则校验器和表达式校验器优先使用正则校验器）
    private String regular;
    // 构建内置的表达式校验器
    private String expression;
    // 验证器(支持通过setter设置自定义验证器)
    private JSONSchemaRuleValidator validator;

    // 错误信息
    private String message;

    /**
     * 获取校验器，若尚未设置则按「正则优先于表达式」的顺序惰性构建并缓存。
     *
     * @return 当前的校验器；未设置自定义校验器且正则与表达式均为空时返回 {@code null}
     */
    public JSONSchemaRuleValidator validator() {
        if (validator == null) {
            // 正则校验器优先
            if (!StringUtils.isBlank(regular)) {
                return validator = new JSONSchemaRuleValidator.RegularImpl(regular);
            }
            if (!StringUtils.isBlank(expression)) {
                return validator = new JSONSchemaRuleValidator.ExpressionImpl(expression);
            }
        }
        return validator;
    }

    /**
     * 设置自定义校验器，设置后将不再根据正则或表达式构建内置校验器。
     *
     * @param validator 自定义校验器
     */
    public void setValidator(JSONSchemaRuleValidator validator) {
        this.validator = validator;
    }

    /**
     * 获取校验失败时的提示信息。
     *
     * @return 当前的错误提示信息，未设置时为 {@code null}
     */
    public String getMessage() {
        return message;
    }

    /**
     * 设置校验失败时的提示信息。
     *
     * @param message 错误提示信息
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * 获取用于构建内置表达式校验器的表达式。
     *
     * @return 当前的校验表达式，未设置时为 {@code null}
     */
    public String getExpression() {
        return expression;
    }

    /**
     * 设置用于构建内置表达式校验器的表达式。
     *
     * @param expression 校验表达式
     */
    public void setExpression(String expression) {
        this.expression = expression;
    }

    /**
     * 获取用于构建内置正则校验器的正则表达式。
     *
     * @return 当前的校验正则表达式，未设置时为 {@code null}
     */
    public String getRegular() {
        return regular;
    }

    /**
     * 设置用于构建内置正则校验器的正则表达式。
     *
     * @param regular 校验正则表达式
     */
    public void setRegular(String regular) {
        this.regular = regular;
    }

    /**
     * 返回自身引用。
     *
     * @return 当前对象，便于链式调用
     */
    public JSONSchemaRule self() {
        return this;
    }
}
