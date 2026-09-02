package com.alianga.jkit.csv;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * 增量式 CSV 解析器：喂进一块 {@code char[]} 就吐出其中已完整的行，跨块的字段、引号与
 * {@code ""} 转义由解析器自己的状态保存，因此内存占用只和「单行大小」有关，与内容总长无关。
 * <p>
 * 包内两套读入口（{@link CSVUtils} 的 {@code List<List<String>>} 门面与 {@link CSV} 的
 * {@link CSVTable} 门面）共用这一个解析器，靠两个开关表达各自的历史语义：
 * <ul>
 * <li>{@code trim}：字段两侧的空格是否去掉。开启时前导空格跳过、尾随空格截掉，只含空格的行
 * 视为空行；关闭时空格是字段内容的一部分，只有长度为 0 的行才算空行。</li>
 * <li>{@code strict}：引号格式非法时是否抛异常。开启时缺少收尾引号、收尾引号后跟着非空格内容
 * 都抛 {@link UnsupportedOperationException}，且只有出现在字段开头的引号才是引号字段；
 * 关闭时任意位置的引号都进入引号模式，收尾引号之后的内容拼在字段后面，末尾未闭合也照样收下。</li>
 * </ul>
 * 解析器实例是有状态的，不能复用、也不能跨线程共享。
 */
final class CSVParser {
    /** 按块读取时的缓冲大小（字符数） */
    static final int BUFFER_SIZE = 1 << 15;

    private static final char[] EMPTY = new char[0];

    private final boolean trim;
    private final boolean strict;
    private final CSVValuesHandler handler;

    /** 当前字段跨块或含转义时，前几段内容暂存在这里 */
    private final StringBuilder carry = new StringBuilder();
    private List<String> row = new ArrayList<String>();
    private boolean useCarry;
    private boolean inQuotes;
    /** 引号正好落在块末尾，要等下一个字符才能判定是转义还是收尾 */
    private boolean quoteSeen;
    /** 当前字段是引号字段 */
    private boolean quotedField;
    /** 已读到收尾引号，后面只允许空格与分隔符（仅 strict） */
    private boolean afterQuote;
    /** 当前字段已有内容 */
    private boolean fieldStarted;
    /** 当前行已有内容 */
    private boolean recordStarted;
    private boolean skipLf;
    private boolean stopped;
    private long rowIndex;
    /** 已消费的字符数，用于错误信息里的偏移 */
    private long position;
    private long quoteStart;

    CSVParser(boolean trim, boolean strict, CSVValuesHandler handler) {
        this.trim = trim;
        this.strict = strict;
        this.handler = handler;
    }

    /**
     * 按块读完整个字符流并解析，不关闭该流
     *
     * @param reader 源字符流
     * @return 回调的行数
     * @throws IOException 读取失败时抛出
     */
    long parse(Reader reader) throws IOException {
        char[] buf = new char[BUFFER_SIZE];
        int count;
        while (!stopped && (count = reader.read(buf)) != -1) {
            feed(buf, 0, count);
        }
        return finish();
    }

    /**
     * 解析已在内存里的字符数组，不做任何拷贝
     *
     * @param chars 待解析内容
     * @param count 有效长度
     * @return 回调的行数
     */
    long parse(char[] chars, int count) {
        feed(chars, 0, count);
        return finish();
    }

    private void feed(char[] buf, int off, int count) {
        int end = off + count;
        int mark = off;
        long base = position - off;
        for (int i = off; i < end && !stopped; i++) {
            char ch = buf[i];
            if (quoteSeen) {
                quoteSeen = false;
                if (ch == '"') {
                    // 跨块的 "" 转义
                    carry.append('"');
                    mark = i + 1;
                    continue;
                }
                inQuotes = false;
                afterQuote = strict;
            }
            if (skipLf) {
                skipLf = false;
                if (ch == '\n') {
                    mark = i + 1;
                    continue;
                }
            }
            if (inQuotes) {
                if (ch != '"') {
                    continue;
                }
                carry.append(buf, mark, i - mark);
                useCarry = true;
                mark = i + 1;
                if (i + 1 == end) {
                    quoteSeen = true;
                } else if (buf[i + 1] == '"') {
                    carry.append('"');
                    mark = ++i + 1;
                } else {
                    inQuotes = false;
                    afterQuote = strict;
                }
            } else if (ch == ',') {
                addField(buf, mark, i);
                recordStarted = true;
                mark = i + 1;
            } else if (ch == '\n' || ch == '\r') {
                endRow(buf, mark, i);
                skipLf = ch == '\r';
                mark = i + 1;
            } else if (ch == '"' && !(strict && fieldStarted)) {
                if (mark < i) {
                    carry.append(buf, mark, i - mark);
                    useCarry = true;
                }
                inQuotes = true;
                quotedField = true;
                fieldStarted = true;
                recordStarted = true;
                quoteStart = base + i;
                mark = i + 1;
            } else if (ch == ' ') {
                if (afterQuote || (trim && !fieldStarted)) {
                    // 引号后的空格、字段前导空格都不算内容
                    mark = i + 1;
                } else if (!trim) {
                    recordStarted = true;
                }
            } else {
                if (afterQuote) {
                    throw new UnsupportedOperationException("ERROR CSV offset " + (base + i)
                            + " expected ',' or line break after closing '\"'");
                }
                fieldStarted = true;
                recordStarted = true;
            }
        }
        if (!stopped && mark < end) {
            // 本块剩下的内容属于未结束的字段，转入 carry 等下一块
            carry.append(buf, mark, end - mark);
            useCarry = true;
        }
        position = base + end;
    }

    private long finish() {
        if (stopped) {
            return rowIndex;
        }
        if (quoteSeen) {
            quoteSeen = false;
            inQuotes = false;
        }
        if (inQuotes && strict) {
            throw new UnsupportedOperationException("ERROR CSV missing closing '\"' from offset " + quoteStart);
        }
        if (recordStarted || !row.isEmpty() || carry.length() > 0) {
            // 末行没有行尾
            addField(EMPTY, 0, 0);
            emitRow();
        }
        return rowIndex;
    }

    private void addField(char[] buf, int mark, int end) {
        boolean trimTail = trim && !quotedField;
        int stop = end;
        if (trimTail) {
            while (stop > mark && buf[stop - 1] == ' ') {
                --stop;
            }
        }
        String value;
        if (useCarry) {
            carry.append(buf, mark, stop - mark);
            if (trimTail) {
                int length = carry.length();
                while (length > 0 && carry.charAt(length - 1) == ' ') {
                    --length;
                }
                carry.setLength(length);
            }
            value = carry.toString();
            carry.setLength(0);
            useCarry = false;
        } else {
            value = new String(buf, mark, stop - mark);
        }
        row.add(value);
        fieldStarted = false;
        quotedField = false;
        afterQuote = false;
    }

    private void endRow(char[] buf, int mark, int end) {
        if (recordStarted) {
            addField(buf, mark, end);
            emitRow();
        } else {
            // 空行：丢掉可能残留的空格
            carry.setLength(0);
            useCarry = false;
            fieldStarted = false;
            quotedField = false;
            afterQuote = false;
        }
        recordStarted = false;
    }

    private void emitRow() {
        List<String> values = row;
        row = new ArrayList<String>();
        ++rowIndex;
        if (!handler.handle(values, rowIndex - 1)) {
            stopped = true;
        }
    }
}
