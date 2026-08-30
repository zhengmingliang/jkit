package com.alianga.jkit.json;

import com.alianga.jkit.expression.Expression;

import java.util.regex.Pattern;

/**
 * 自定义校验规则的执行器抽象，由具体实现决定如何判定一个 JSON 节点是否满足规则。
 */
public abstract class JSONSchemaRuleValidator {
    /**
     * 校验节点是否满足指定规则。
     *
     * @param rule 规则定义
     * @param value 待校验的 JSON 节点
     * @return 校验通过返回 {@code true}，否则返回 {@code false}
     */
    public abstract boolean validate(JSONSchemaRule rule, JSONNode value);

    static class RegularImpl extends JSONSchemaRuleValidator {
        final Pattern pattern;

        public RegularImpl(String regular) {
            pattern = Pattern.compile(regular);
        }

        @Override
        public boolean validate(JSONSchemaRule rule, JSONNode value) {
            if (value.type == JSONNode.STRING) {
                return pattern.matcher(value.value.toString()).matches();
            }
            return false;
        }
    }

    static class ExpressionImpl extends JSONSchemaRuleValidator {
        final JSONNodePathFilter pathFilter;

        public ExpressionImpl(String elStr) {
            pathFilter = JSONNodePathFilter.expression(Expression.parse(elStr));
        }

        @Override
        public boolean validate(JSONSchemaRule rule, JSONNode value) {
            return pathFilter.doFilter(value);
        }
    }
}
