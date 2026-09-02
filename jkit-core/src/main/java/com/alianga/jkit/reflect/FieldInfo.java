package com.alianga.jkit.reflect;

/**
 * 字段反射信息，聚合同一字段名对应的 setter 与 getter 访问器。
 */
public class FieldInfo {
    private String name;
    private SetterInfo setterInfo;
    private GetterInfo getterInfo;
    private int index;

    /**
     * 获取字段名称。
     *
     * @return 当前的字段名称，未设置时为 {@code null}
     */
    public String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    /**
     * 获取该字段的 setter 访问器信息。
     *
     * @return 当前的 setter 信息，该字段没有可写入口时为 {@code null}
     */
    public SetterInfo getSetterInfo() {
        return setterInfo;
    }

    void setSetterInfo(SetterInfo setterInfo) {
        this.setterInfo = setterInfo;
    }

    /**
     * 获取该字段的 getter 访问器信息。
     *
     * @return 当前的 getter 信息，该字段没有可读入口时为 {@code null}
     */
    public GetterInfo getGetterInfo() {
        return getterInfo;
    }

    void setGetterInfo(GetterInfo getterInfo) {
        this.getterInfo = getterInfo;
    }

    /**
     * 获取字段在所属类字段列表中的序号。
     *
     * @return 当前的字段序号，从 0 开始
     */
    public int getIndex() {
        return index;
    }

    void setIndex(int index) {
        this.index = index;
    }
}
