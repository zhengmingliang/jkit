package com.alianga.jkit.csv;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

/**
 * {@link CSVWriter#writeValue(Appendable, String)} 的回归测试。
 *
 * <p>修复前 {@code appendable.append(value, begin, j - begin)} 把 {@link Appendable} 第三个参数
 * （结束下标）当成了长度，字段里出现第二个及以后的分隔字符时会丢字符；
 * 另外含换行的字段没有加引号，写出后会裂成多行。</p>
 */
public class CSVWriteValueTest {

    @Test
    public void plainValueIsNotQuoted() throws IOException {
        assertEquals("plain", writeValue("plain"));
        assertEquals("", writeValue(""));
    }

    @Test
    public void multipleCommasAreNotDropped() throws IOException {
        assertEquals("\"a,b,c\"", writeValue("a,b,c"));
        assertEquals("\"a,b\"", writeValue("a,b"));
        assertEquals("\"1,2,3,4\"", writeValue("1,2,3,4"));
    }

    @Test
    public void quotesAreDoubled() throws IOException {
        assertEquals("\"he said \"\"hi\"\"\"", writeValue("he said \"hi\""));
        assertEquals("\"\"\"\"", writeValue("\""));
    }

    @Test
    public void newLineForcesQuoting() throws IOException {
        assertEquals("\"a\nb\"", writeValue("a\nb"));
        assertEquals("\"a\r\nb\"", writeValue("a\r\nb"));
    }

    private String writeValue(String value) throws IOException {
        StringBuilder builder = new StringBuilder();
        CSVWriter.writeValue(builder, value);
        return builder.toString();
    }
}
