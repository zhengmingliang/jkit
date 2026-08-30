package com.alianga.jkit.yaml;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;

/**
 * YAML 非空行的中间表示。
 *
 * <p>保存缩进、键值、类型标记及写回 YAML 所需的行信息。</p>
 */
class YamlLine extends YamlGeneral {
    /**
     * 行号
     */
    protected int lineNum;

    /**
     * 当前节点缩进字符位（空格数）,只支持空格' '(32)
     * <p>
     * 缩进信息，缩进紧跟正文（key）
     */
    protected int indent;

    /**
     * 行内容
     */
    protected char[] content;

    /**
     * key
     */
    protected String key;

    /**
     * value
     */
    protected String value;

    /** 值类型编号：0 未指定，1 字符串，2 浮点数，3 整数，4 布尔值，5 二进制，6 时间戳，7 集合，8 有序映射，9 序列，10 映射。 */
    protected int valueType;

    /** 按 YAML 类型标签转换后的缓存值。 */
    protected Object typeOfValue;

    /** 节点层级。 */
    protected int level;

    /**
     * 是否叶子节点
     * <p>分隔符后面存在非#开头的内容时为叶子节点，否则为对象</p>
     */
    protected boolean leaf;

    /**
     * 是否文本块
     */
    protected boolean textBlock;

    /**
     * 文本块类型
     */
    public int blockType;

    /**
     * 是否数组token '-'
     */
    protected boolean arrayToken;

    /**
     * 锚点key
     */
    protected String anchorKey;

    /**
     * 引用key
     */
    public String referenceKey;

    /**
     * 数组索引
     */
    protected int arrayIndex = -1;

    /**
     * 按 {@code valueType} 写出 YAML 类型标签（如 {@code !!int}、{@code !!bool}），字符串类型不写标签。
     *
     * @param writer 目标输出流
     * @throws IOException 写出失败时抛出
     */
    protected void writeTypeToken(Writer writer) throws IOException {
        if (valueType > 0) {
            switch (valueType) {
                case 1:
//                    writer.write("!!str ");
                    break;
                case 2:
                    writer.write("!!float ");
                    break;
                case 3:
                    writer.write("!!int ");
                    break;
                case 4:
                    writer.write("!!bool ");
                    break;
                case 5:
                    writer.write("!!binary ");
                    break;
                case 6:
                    writer.write("!!timestamp ");
                    break;
                case 7:
                    writer.write("!!set ");
                    break;
                case 8:
                    writer.write("!!omap ");
                    break;
                case 9:
                    writer.write("!!seq ");
                    break;
                default:
                    writer.write("!!map ");
            }
        }
    }

    /**
     * 写出文本块起始标记 {@code |}，并按 {@code blockType} 追加保留（{@code +}）或裁剪（{@code -}）修饰符。
     *
     * @param writer 目标输出流
     * @throws IOException 写出失败时抛出
     */
    protected void writeBlockToken(Writer writer) throws IOException {
        writer.write("|");
        if (blockType == 1) {
            writer.write("+");
        } else if (blockType == 2) {
            writer.write("-");
        }
    }

    /**
     * 当前行属于数组元素（数组索引大于 {@code -1}）时写出数组标记 {@code "- "}。
     *
     * @param writer 目标输出流
     * @throws IOException 写出失败时抛出
     */
    protected void writeArrayToken(Writer writer) throws IOException {
        if (arrayIndex > -1) {
//            writeIndent(writer, indent - 2);
            writer.write("- ");
        }
    }

    /**
     * 写出换行符，Windows 平台使用 {@code \r\n}，其他平台使用 {@code \n}。
     *
     * @param writer 目标输出流
     * @throws IOException 写出失败时抛出
     */
    protected void writeNewLine(Writer writer) throws IOException {
        writer.write(IS_WINDOW_OS ? "\r\n" : "\n");
    }

    /**
     * 按当前行的缩进量写出空格缩进。
     *
     * @param writer 目标输出流
     * @throws IOException 写出失败时抛出
     */
    protected void writeIndent(Writer writer) throws IOException {
        writeIndent(writer, indent);
    }

    /**
     * 写出指定数量的空格缩进。
     *
     * @param writer 目标输出流
     * @param indent 缩进的空格个数
     * @throws IOException 写出失败时抛出
     */
    protected void writeIndent(Writer writer, int indent) throws IOException {
        char[] indents = new char[indent];
        Arrays.fill(indents, ' ');
        writer.write(indents);
    }
}
