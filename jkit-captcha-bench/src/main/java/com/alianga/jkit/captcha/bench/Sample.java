package com.alianga.jkit.captcha.bench;

import java.io.File;

/**
 * 数据集里的一张验证码：图片文件、期望答案、类别与风格标签。
 */
public final class Sample {
    /** 验证码类别。 */
    public enum Category {
        /** 纯英文，大小写混合。 */
        LETTERS,
        /** 纯数字。 */
        DIGITS,
        /** 英文数字混合。 */
        MIXED,
        /** 算术运算。 */
        ARITHMETIC
    }

    private final File file;
    private final String expected;
    private final Category category;
    private final String styleTag;
    private final Integer value;

    /**
     * @param file     图片文件
     * @param expected 期望文本；算术题为不含 {@code =?} 的式子
     * @param category 类别
     * @param styleTag 风格标签
     * @param value    算术题结果；非算术题为 {@code null}
     */
    public Sample(File file, String expected, Category category, String styleTag, Integer value) {
        this.file = file;
        this.expected = expected;
        this.category = category;
        this.styleTag = styleTag;
        this.value = value;
    }

    /**
     * @return 图片文件
     */
    public File getFile() {
        return file;
    }

    /**
     * @return 期望文本
     */
    public String getExpected() {
        return expected;
    }

    /**
     * @return 类别
     */
    public Category getCategory() {
        return category;
    }

    /**
     * @return 风格标签
     */
    public String getStyleTag() {
        return styleTag;
    }

    /**
     * @return 算术题结果，非算术题返回 {@code null}
     */
    public Integer getValue() {
        return value;
    }

    /**
     * 写进清单文件的一行：路径、期望、类别、风格、结果。
     *
     * @return 一行文本
     */
    public String manifestLine() {
        return relativePath() + '\t' + expected + '\t' + category + '\t' + styleTag + '\t'
                + (value == null ? "" : String.valueOf(value.intValue()));
    }

    /** 清单里存相对路径，图片整体挪到别的目录也能照原样读回来。 */
    private String relativePath() {
        File parent = file.getParentFile();
        String name = file.getName();
        return parent == null ? name : parent.getName() + "/" + name;
    }

    /**
     * 从清单文件的一行还原样本。
     *
     * @param directory 数据集目录
     * @param line      清单行
     * @return 样本，行格式不对时返回 {@code null}
     */
    public static Sample fromManifest(File directory, String line) {
        // -1 保留末尾空字段：非算术题的 value 列是空串，默认 split 会把它连同列一起丢掉。
        String[] parts = line.split("\t", -1);
        if (parts.length < 5) {
            return null;
        }
        Integer value = parts[4].isEmpty() ? null : Integer.valueOf(parts[4]);
        return new Sample(new File(directory, parts[0]), parts[1], Category.valueOf(parts[2]), parts[3],
                value);
    }
}
