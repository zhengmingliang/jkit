package com.alianga.jkit.json;

import com.alianga.jkit.json.annotations.JsonProperty;

import java.io.Serializable;
import java.util.*;
import java.util.regex.Pattern;

/**
 * JSONSchema简化版实现
 * <p>
 * 参考文档: <br>
 * <a href="https://json-schema.org/understanding-json-schema/reference/type">https://json-schema.org/understanding-json-schema/reference/type</a>
 *
 * <br>
 * <p>
 * JSON 字段定义/校验, 绑定JSONNode实现.
 *
 */
public final class JSONSchema extends JSONSchemaBase implements Serializable {
    /** 适配JSONSchema官网定义: schema 唯一标识（id） */
    private String id;
    /** 适配JSONSchema官网定义: 所遵循的 JSONSchema 规范版本地址（$schema） */
    private String $schema;
    /** 适配JSONSchema官网定义: 指向 definitions 中已定义 schema 的引用路径（$ref） */
    private String $ref;
    /** 适配JSONSchema官网定义: 标题 */
    private String title;
    /** 适配JSONSchema官网定义: 描述 */
    private String description;
    /** 适配JSONSchema官网定义: 类型，取值为单个 {@link JSONType} 或 {@link JSONType} 数组 */
    @JsonProperty(possibleTypes = {JSONType.class, JSONType[].class})
    private final Serializable type;

    /** 适配JSONSchema官网定义: 枚举可选值列表（enum） */
    @JsonProperty(name = "enum")
    private String[] enums;

    /**
     * 数据格式: url | email | date | time | datetime | regex | ip | ipv4 | ipv6 | hostname | uri | uri-reference
     * <p>当前仅仅支持: url | email | date
     */
    private String format;
    /** 适配JSONSchema官网定义: 可复用的 schema 定义集合（definitions） */
    private Map<Serializable, JSONSchema> definitions;
    /** definitions 的内部索引，key 为 {@code #/definitions/xxx} 形式的引用路径，供 $ref 解析使用 */
    @JsonProperty(deserialize = false, serialize = false)
    Map<Serializable, JSONSchema> __definitions;
    /** 适配JSONSchema官网定义: 属性映射 */
    private Map<Serializable, JSONSchema> properties;
    /** 适配JSONSchema官网定义: 必填字段编码集合 */
    private Set<String> required;

    /** 数组（集合）元素 schema，长度固定的场景下可以给每个元素指定不同的Schema，取值为单个 schema 或 schema 数组 */
    @JsonProperty(possibleTypes = {JSONSchema.class, JSONSchema[].class})
    private Object items;

    /** 适配JSONSchema官网定义: 是否允许附加属性取值boolean或者JSONSchema类型（对象） */
    @JsonProperty(possibleTypes = {Boolean.class, JSONSchema.class})
    private Object additionalProperties;
    /** when the type is number, define max number */
    private Number maximum;
    /** 数值上界是否为开区间（不包含 maximum 本身） */
    private Boolean exclusiveMaximum;
    /** when the type is number, define min number */
    private Number minimum;
    /** 数值下界是否为开区间（不包含 minimum 本身） */
    private Boolean exclusiveMinimum;
    /** when the type is string, define max string length */
    private Integer maxLength;
    /** when the type is string, define min string length */
    private Integer minLength;
    /** when the type is string, define pattern */
    private String pattern;
    /** pattern 编译后的缓存对象，由 {@link #patternObject()} 惰性构建 */
    @JsonProperty(deserialize = false, serialize = false)
    private Pattern patternObject;

    /** when the type is array, define min array size */
    private Integer minItems;
    /** when the type is array, define max array size */
    private Integer maxItems;
    /** 数组唯一性 */
    private Boolean uniqueItems;

    /** 适配JSONSchema官网定义: 必须同时满足的 schema 列表（allOf），取值为单个 schema 或 schema 数组 */
    @JsonProperty(possibleTypes = {JSONSchema.class, JSONSchema[].class})
    private Object allOf;

    /** 适配JSONSchema官网定义: 至少满足其一的 schema 列表（anyOf），取值为单个 schema 或 schema 数组 */
    @JsonProperty(possibleTypes = {JSONSchema.class, JSONSchema[].class})
    private Object anyOf;

    /** 适配JSONSchema官网定义: 有且仅满足其一的 schema 列表（oneOf），取值为单个 schema 或 schema 数组 */
    @JsonProperty(possibleTypes = {JSONSchema[].class, JSONSchema.class})
    private Object oneOf;

    /** 适配JSONSchema官网定义: 字段缺省值（default） */
    @JsonProperty(name = "default")
    private Serializable defaultValue;

    /** 适配JSONSchema官网定义: 字段间的依赖约束（dependencies） */
    private Map<String, Object> dependencies;

    /** when the type is array, define child elementSchema */
    private JSONSchema elementSchema;

    /**
     * 是否禁止出现fields中不存在的字段
     */
    private Boolean disableExtra;

    /**
     * 当前是否必填(和required冲突改名)
     */
    private Boolean must;

    /**
     * 校验规则列表
     */
    private List<JSONSchemaRule> rules;

    /**
     * 根据字符串构建JSONSchema
     *
     * @param schemaJson JSONSchema字符串
     * @return 解析出的 JSONSchema，其 root 已指向自身
     */
    public static JSONSchema of(String schemaJson) {
        JSONSchema schema = JSON.parseObject(schemaJson, JSONSchema.class, PERFECT_READ_OPTIONS);
        schema.setRoot(schema);
        return schema;
    }

    /**
     * 构建指定 JSON 类型的 schema。
     *
     * @param type 该 schema 约束的 JSON 类型
     */
    public JSONSchema(JSONType type) {
        this.type = type;
    }

    /**
     * 获取所遵循的 JSONSchema 规范版本地址。
     *
     * @return 当前的 $schema 值，未设置时为 {@code null}
     */
    public String get$schema() {
        return $schema;
    }

    /**
     * 设置所遵循的 JSONSchema 规范版本地址。
     *
     * @param $schema JSONSchema 规范版本地址
     */
    public void set$schema(String $schema) {
        this.$schema = $schema;
    }

    /**
     * 获取 schema 唯一标识。
     *
     * @return 当前的 id，未设置时为 {@code null}
     */
    public String getId() {
        return id;
    }

    /**
     * 设置 schema 唯一标识。
     *
     * @param id schema 唯一标识
     */
    public void setId(String id) {
        this.id = id;
    }

    void setRoot(JSONSchema root) {
        this.root = root;
        if (definitions != null) {
            __definitions = new HashMap<Serializable, JSONSchema>();
            Set<Map.Entry<Serializable, JSONSchema>> entrySet = definitions.entrySet();
            for (Map.Entry<Serializable, JSONSchema> entry : entrySet) {
                String key = entry.getKey().toString();
                JSONSchema value = entry.getValue();
                if (value != null) {
                    value.setRoot(root);
                }
                __definitions.put("#/definitions/" + key, value);
            }
        }
        if (properties != null) {
            Set<Map.Entry<Serializable, JSONSchema>> entrySet = properties.entrySet();
            for (Map.Entry<Serializable, JSONSchema> entry : entrySet) {
                JSONSchema value = entry.getValue();
                if (value != null) {
                    value.setRoot(root);
                }
            }
        }
        if (elementSchema != null) {
            elementSchema.setRoot(root);
        }
        setRoot(root, items);
        setRoot(root, additionalProperties);
        setRoot(root, anyOf);
        setRoot(root, allOf);
        setRoot(root, oneOf);
    }

    void setRoot(JSONSchema root, Object target) {
        if (target instanceof JSONSchema) {
            JSONSchema value = (JSONSchema) target;
            value.setRoot(root);
        } else if (target instanceof JSONSchema[]) {
            JSONSchema[] values = (JSONSchema[]) target;
            for (JSONSchema value : values) {
                value.setRoot(root);
            }
        }
    }

    /**
     * 获取指向 definitions 中已定义 schema 的引用路径。
     *
     * @return 当前的 $ref 值，未设置时为 {@code null}
     */
    public String get$ref() {
        return $ref;
    }

    /**
     * 设置指向 definitions 中已定义 schema 的引用路径。
     *
     * @param $ref 引用路径，形如 {@code #/definitions/xxx}
     */
    public void set$ref(String $ref) {
        this.$ref = $ref;
    }

    /**
     * 获取标题。
     *
     * @return 当前的 title，未设置时为 {@code null}
     */
    public String getTitle() {
        return title;
    }

    /**
     * 获取描述。
     *
     * @return 当前的 description，未设置时为 {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * 设置标题。
     *
     * @param title 标题
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * 设置描述。
     *
     * @param description 描述
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 获取该 schema 约束的 JSON 类型。
     *
     * @return 单个 {@link JSONType} 或 {@link JSONType} 数组，未设置时为 {@code null}
     */
    public Object getType() {
        return type;
    }

    /**
     * 获取枚举可选值列表。
     *
     * @return 当前的枚举值数组，未设置时为 {@code null}
     */
    public String[] getEnums() {
        return enums;
    }

    /**
     * 设置枚举可选值列表。
     *
     * @param enums 允许的取值数组
     */
    public void setEnums(String[] enums) {
        this.enums = enums;
    }

    /**
     * 获取数据格式。
     *
     * @return 当前的 format 值，未设置时为 {@code null}
     */
    public String getFormat() {
        return format;
    }

    /**
     * 设置数据格式。
     *
     * @param format 数据格式，当前仅支持 url、email、date
     */
    public void setFormat(String format) {
        this.format = format;
    }

    /**
     * 获取可复用的 schema 定义集合。
     *
     * @return 定义名到子 schema 的映射，未设置时为 {@code null}
     */
    public Map<Serializable, JSONSchema> getDefinitions() {
        return definitions;
    }

    /**
     * 设置可复用的 schema 定义集合。
     *
     * @param definitions 定义名到子 schema 的映射
     */
    public void setDefinitions(Map<Serializable, JSONSchema> definitions) {
        this.definitions = definitions;
    }

    /**
     * 获取属性映射。
     *
     * @return 属性名到子 schema 的映射，未设置时为 {@code null}
     */
    public Map<Serializable, JSONSchema> getProperties() {
        return properties;
    }

    /**
     * 设置属性映射。
     *
     * @param properties 属性名到子 schema 的映射
     */
    public void setProperties(Map<Serializable, JSONSchema> properties) {
        this.properties = properties;
    }

    /**
     * 判断是否禁止出现 properties 中未定义的额外字段。
     *
     * @return 已显式设置为 true 时返回 {@code true}，未设置或为 false 时返回 {@code false}
     */
    public Boolean getDisableExtra() {
        return disableExtra != null && disableExtra;
    }

    /**
     * 设置是否禁止出现 properties 中未定义的额外字段。
     *
     * @param disableExtra 为 true 时禁止额外字段
     */
    public void setDisableExtra(boolean disableExtra) {
        this.disableExtra = disableExtra;
    }

    /**
     * 判断当前 schema 对应的字段是否必填。
     *
     * @return 已显式设置为 true 时返回 {@code true}，未设置或为 false 时返回 {@code false}
     */
    public Boolean getMust() {
        return must != null && must;
    }

    /**
     * 设置当前 schema 对应的字段是否必填。
     *
     * @param must 为 true 时表示必填
     */
    public void setMust(Boolean must) {
        this.must = must;
    }

    /**
     * 获取必填字段编码集合。
     *
     * @return 必填字段名集合，未设置时为 {@code null}
     */
    public Set<String> getRequired() {
        return required;
    }

    /**
     * 设置必填字段编码集合。
     *
     * @param required 必填字段名集合
     */
    public void setRequired(Set<String> required) {
        this.required = required;
    }

    /**
     * 获取自定义校验规则列表。
     *
     * @return 校验规则列表，未添加过规则时为 {@code null}
     */
    public List<JSONSchemaRule> getRules() {
        return rules;
    }

    /**
     * 设置自定义校验规则列表。
     *
     * @param rules 校验规则列表
     */
    public void setRules(List<JSONSchemaRule> rules) {
        this.rules = rules;
    }

    /**
     * 判断是否配置了数组元素的 schema。
     *
     * @return elementSchema 或 items 任一不为空时返回 {@code true}，否则返回 {@code false}
     */
    public final boolean hasElementSchema() {
        return elementSchema != null || items != null;
    }

    /**
     * 获取数组中指定下标元素对应的 schema。
     *
     * @param index 数组元素下标
     * @return items 未设置时返回 elementSchema；items 为单个 schema 时返回该 schema；
     *     items 为 schema 数组时返回对应下标的元素，下标越界返回 {@code null}
     */
    public JSONSchema getElementSchemaAt(int index) {
        if (items == null) {
            return elementSchema;
        }
        if (items instanceof JSONSchema) {
            return (JSONSchema) items;
        }
        JSONSchema[] schemaItems = (JSONSchema[]) items;
        return index < schemaItems.length ? schemaItems[index] : null;
    }

    /**
     * 获取数组元素统一使用的 schema。
     *
     * @return 当前的 elementSchema，未设置时为 {@code null}
     */
    public JSONSchema getElementSchema() {
        return elementSchema;
    }

    /**
     * 设置数组元素统一使用的 schema。
     *
     * @param elementSchema 数组元素 schema
     */
    public void setElementSchema(JSONSchema elementSchema) {
        this.elementSchema = elementSchema;
    }

    /**
     * 获取数组元素 schema 定义。
     *
     * @return 单个 JSONSchema 或 JSONSchema 数组，未设置时为 {@code null}
     */
    public Object getItems() {
        return items;
    }

    /**
     * 设置数组元素 schema 定义。
     *
     * @param items 单个 JSONSchema 或 JSONSchema 数组
     */
    public void setItems(Object items) {
        this.items = items;
    }

    /**
     * 获取附加属性约束。
     *
     * @return {@link Boolean} 或 JSONSchema 对象，未设置时为 {@code null}
     */
    public Object getAdditionalProperties() {
        return additionalProperties;
    }

    /**
     * 设置附加属性约束。
     *
     * @param additionalProperties {@link Boolean} 表示是否允许附加属性，JSONSchema 表示附加属性需满足的约束
     */
    public void setAdditionalProperties(Object additionalProperties) {
        this.additionalProperties = additionalProperties;
    }

    /**
     * 获取数组最小长度限制。
     *
     * @return 当前的 minItems，未设置时为 {@code null}
     */
    public Integer getMinItems() {
        return minItems;
    }

    /**
     * 设置数组最小长度限制。
     *
     * @param minItems 数组允许的最小元素个数
     */
    public void setMinItems(Integer minItems) {
        this.minItems = minItems;
    }

    /**
     * 获取数组最大长度限制。
     *
     * @return 当前的 maxItems，未设置时为 {@code null}
     */
    public Integer getMaxItems() {
        return maxItems;
    }

    /**
     * 设置数组最大长度限制。
     *
     * @param maxItems 数组允许的最大元素个数
     */
    public void setMaxItems(Integer maxItems) {
        this.maxItems = maxItems;
    }

    /**
     * 获取数值上界。
     *
     * @return 当前的 maximum，未设置时为 {@code null}
     */
    public Number getMaximum() {
        return maximum;
    }

    /**
     * 设置数值上界。
     *
     * @param maximum 数值允许的最大值
     */
    public void setMaximum(Number maximum) {
        this.maximum = maximum;
    }

    /**
     * 获取数值下界。
     *
     * @return 当前的 minimum，未设置时为 {@code null}
     */
    public Number getMinimum() {
        return minimum;
    }

    /**
     * 设置数值下界。
     *
     * @param minimum 数值允许的最小值
     */
    public void setMinimum(Number minimum) {
        this.minimum = minimum;
    }

    /**
     * 判断数值下界是否为开区间。
     *
     * @return 已显式设置为 {@link Boolean#TRUE} 时返回 {@code true}，否则返回 {@code false}
     */
    public Boolean getExclusiveMinimum() {
        return exclusiveMinimum == Boolean.TRUE;
    }

    /**
     * 设置数值下界是否为开区间。
     *
     * @param exclusiveMinimum 为 true 时校验值必须严格大于 minimum
     */
    public void setExclusiveMinimum(Boolean exclusiveMinimum) {
        this.exclusiveMinimum = exclusiveMinimum;
    }

    /**
     * 判断数值上界是否为开区间。
     *
     * @return 已显式设置为 {@link Boolean#TRUE} 时返回 {@code true}，否则返回 {@code false}
     */
    public Boolean getExclusiveMaximum() {
        return exclusiveMaximum == Boolean.TRUE;
    }

    /**
     * 设置数值上界是否为开区间。
     *
     * @param exclusiveMaximum 为 true 时校验值必须严格小于 maximum
     */
    public void setExclusiveMaximum(Boolean exclusiveMaximum) {
        this.exclusiveMaximum = exclusiveMaximum;
    }

    /**
     * 获取数组元素唯一性要求。
     *
     * @return 当前的 uniqueItems，未设置时为 {@code null}
     */
    public Boolean getUniqueItems() {
        return uniqueItems;
    }

    /**
     * 设置数组元素唯一性要求。
     *
     * @param uniqueItems 为 true 时要求数组元素互不相同
     */
    public void setUniqueItems(Boolean uniqueItems) {
        this.uniqueItems = uniqueItems;
    }

    /**
     * 获取 oneOf 约束。
     *
     * @return 单个 JSONSchema 或 JSONSchema 数组，未设置时为 {@code null}
     */
    public Object getOneOf() {
        return oneOf;
    }

    /**
     * 设置 oneOf 约束，即有且仅能满足其中一个 schema。
     *
     * @param oneOf 单个 JSONSchema 或 JSONSchema 数组
     */
    public void setOneOf(Object oneOf) {
        this.oneOf = oneOf;
    }

    /**
     * 获取 anyOf 约束。
     *
     * @return 单个 JSONSchema 或 JSONSchema 数组，未设置时为 {@code null}
     */
    public Object getAnyOf() {
        return anyOf;
    }

    /**
     * 设置 anyOf 约束，即至少需要满足其中一个 schema。
     *
     * @param anyOf 单个 JSONSchema 或 JSONSchema 数组
     */
    public void setAnyOf(Object anyOf) {
        this.anyOf = anyOf;
    }

    /**
     * 获取 allOf 约束。
     *
     * @return 单个 JSONSchema 或 JSONSchema 数组，未设置时为 {@code null}
     */
    public Object getAllOf() {
        return allOf;
    }

    /**
     * 设置 allOf 约束，即需要同时满足全部 schema。
     *
     * @param allOf 单个 JSONSchema 或 JSONSchema 数组
     */
    public void setAllOf(Object allOf) {
        this.allOf = allOf;
    }

    /**
     * 获取字符串最小长度限制。
     *
     * @return 当前的 minLength，未设置时为 {@code null}
     */
    public Integer getMinLength() {
        return minLength;
    }

    /**
     * 设置字符串最小长度限制。
     *
     * @param minLength 字符串允许的最小长度
     */
    public void setMinLength(Integer minLength) {
        this.minLength = minLength;
    }

    /**
     * 获取字符串最大长度限制。
     *
     * @return 当前的 maxLength，未设置时为 {@code null}
     */
    public Integer getMaxLength() {
        return maxLength;
    }

    /**
     * 设置字符串最大长度限制。
     *
     * @param maxLength 字符串允许的最大长度
     */
    public void setMaxLength(Integer maxLength) {
        this.maxLength = maxLength;
    }

    /**
     * 获取字符串需要匹配的正则表达式。
     *
     * @return 当前的 pattern 字符串，未设置时为 {@code null}
     */
    public String getPattern() {
        return pattern;
    }

    /**
     * 获取 pattern 编译后的正则对象，首次调用时编译并缓存。
     *
     * @return 编译后的 {@link Pattern}，未设置 pattern 时返回 {@code null}
     */
    public Pattern patternObject() {
        if (pattern == null) {
            return null;
        }
        if (patternObject != null) {
            return patternObject;
        }
        return patternObject = Pattern.compile(pattern);
    }

    /**
     * 设置字符串需要匹配的正则表达式。
     *
     * @param pattern 正则表达式字符串
     */
    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    /**
     * 获取字段缺省值。
     *
     * @return 当前的 default 值，未设置时为 {@code null}
     */
    public Serializable getDefaultValue() {
        return defaultValue;
    }

    /**
     * 设置字段缺省值。
     *
     * @param defaultValue 字段缺省值
     */
    public void setDefaultValue(Serializable defaultValue) {
        this.defaultValue = defaultValue;
    }

    /**
     * 获取字段间的依赖约束。
     *
     * @return 依赖约束映射，未设置时为 {@code null}
     */
    public Map<String, Object> getDependencies() {
        return dependencies;
    }

    /**
     * 设置字段间的依赖约束。
     *
     * @param dependencies 依赖约束映射
     */
    public void setDependencies(Map<String, Object> dependencies) {
        this.dependencies = dependencies;
    }

    /**
     * 追加一条自定义校验规则，规则列表为空时会先创建。
     *
     * @param rule 待添加的校验规则，实际加入的是其 {@code self()} 返回的实例
     */
    public void addRule(JSONSchemaRule rule) {
        if (rules == null) {
            rules = new ArrayList<JSONSchemaRule>();
        }
        rules.add(rule.self());
    }

    /**
     * 清空已添加的自定义校验规则。
     */
    public void clearRules() {
        if (rules != null) {
            rules.clear();
        }
    }

    /**
     * 为对象类型 schema 添加一个属性定义，properties 为空时会先创建。
     *
     * @param property 属性名
     * @param schema 该属性对应的 schema
     * @throws UnsupportedOperationException 当前 schema 的类型不是 {@link JSONType#OBJECT} 时抛出
     */
    public void addProperty(String property, JSONSchema schema) {
        if (type != JSONType.OBJECT) {
            throw new UnsupportedOperationException("schema type not supported");
        }
        if (properties == null) {
            properties = new HashMap<Serializable, JSONSchema>();
        }
        properties.put(property, schema);
    }

    /**
     * 移除对象类型 schema 中的一个属性定义。
     *
     * @param property 待移除的属性名
     * @throws UnsupportedOperationException 当前 schema 的类型不是 {@link JSONType#OBJECT} 时抛出
     */
    public void removeProperty(String property) {
        if (type != JSONType.OBJECT) {
            throw new UnsupportedOperationException("schema type not supported");
        }
        if (properties != null) {
            properties.remove(property);
        }
    }

    /**
     * schema校验
     *
     * @param json json字符串
     * @return 校验结果；json 解析或校验过程抛出异常时返回携带异常信息的失败结果
     */
    public JSONSchemaResult validate(String json) {
        try {
            return JSONNode.parse(json).validateSchema(this);
        } catch (Throwable throwable) {
            return JSONSchemaResult.fail(throwable.getMessage());
        }
    }

    /**
     * schema校验
     *
     * @param json json字符串
     * @return 校验通过返回 {@code true}，校验失败或解析过程抛出异常返回 {@code false}
     */
    public boolean validateSuccess(String json) {
        try {
            return JSONNode.parse(json).validateSchema(this, true).isSuccess();
        } catch (Throwable throwable) {
            return false;
        }
    }

    /**
     * schema校验
     *
     * @param node 节点
     * @return 校验结果；校验过程抛出异常时返回携带异常信息的失败结果
     */
    public JSONSchemaResult validate(JSONNode node) {
        try {
            return node.validateSchema(this);
        } catch (Throwable throwable) {
            return JSONSchemaResult.fail(throwable.getMessage());
        }
    }

    /**
     * schema校验
     *
     * @param node 节点
     * @return 校验通过返回 {@code true}，校验失败或校验过程抛出异常返回 {@code false}
     */
    public boolean validateSuccess(JSONNode node) {
        try {
            return node.validateSchema(this, true).isSuccess();
        } catch (Throwable throwable) {
            return false;
        }
    }

    /**
     * 判断是否配置了 anyOf 约束。
     *
     * @return anyOf 不为空时返回 {@code true}，否则返回 {@code false}
     */
    public boolean ifAnyOf() {
        return anyOf != null;
    }

    /**
     * 判断是否配置了 allOf 约束。
     *
     * @return allOf 不为空时返回 {@code true}，否则返回 {@code false}
     */
    public boolean ifAllOf() {
        return allOf != null;
    }

    /**
     * 判断是否配置了 oneOf 约束。
     *
     * @return oneOf 不为空时返回 {@code true}，否则返回 {@code false}
     */
    public boolean ifOneOf() {
        return oneOf != null;
    }

    /**
     * 判断当前 schema 是否为引用（$ref）类型。
     *
     * @return $ref 不为空时返回 {@code true}，否则返回 {@code false}
     */
    public boolean ifRef() {
        return $ref != null;
    }

    /**
     * 解析 $ref 指向的实际 schema。
     *
     * @return $ref 在根 schema 的 definitions 索引中命中的 schema；
     *     未设置 $ref、根 schema 没有 definitions 或引用路径不存在时返回 {@code null}
     */
    public JSONSchema refSchema() {
        if ($ref == null/*|| !$ref.startsWith("#/definitions/")*/) {
            return null;
        }
        Map<Serializable, JSONSchema> __root_definitions = root.__definitions;
        if (__root_definitions == null) {
            return null;
        }
        return __root_definitions.get($ref);
    }

    /**
     * 判断是否通过 enum 限定了取值范围。
     *
     * @return enums 不为空且长度大于 0 时返回 {@code true}，否则返回 {@code false}
     */
    public boolean ifTypeEnums() {
        return enums != null && enums.length > 0;
    }
}
