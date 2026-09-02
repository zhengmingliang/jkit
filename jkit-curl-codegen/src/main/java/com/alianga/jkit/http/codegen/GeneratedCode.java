package com.alianga.jkit.http.codegen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 代码生成结果。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class GeneratedCode {
    private final String filename;
    private final String language;
    private final String source;
    private final List<String> dependencies;
    private final List<String> notes;

    /**
     * @param filename 建议文件名
     * @param language 语言
     * @param source 源码
     * @param dependencies 依赖说明
     * @param notes 备注
     */
    public GeneratedCode(String filename, String language, String source,
                         List<String> dependencies, List<String> notes) {
        this.filename = filename;
        this.language = language;
        this.source = source;
        this.dependencies = dependencies == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(dependencies));
        this.notes = notes == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(notes));
    }

    /**
     * @return 建议文件名
     */
    public String filename() {
        return filename;
    }

    /**
     * @return 语言
     */
    public String language() {
        return language;
    }

    /**
     * @return 源码
     */
    public String source() {
        return source;
    }

    /**
     * @return 依赖
     */
    public List<String> dependencies() {
        return dependencies;
    }

    /**
     * @return 备注
     */
    public List<String> notes() {
        return notes;
    }
}
