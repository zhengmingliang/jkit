package com.alianga.jkit.csv;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * {@link CSV#read(String)} 与 {@link CSV#read(java.io.InputStream, Charset)} 的回归测试。
 *
 * <p>修复前解析器有三类缺陷：空字段（{@code 1,} / {@code ,2} / {@code 1,,3}）会抛越界或把后续字段并在一起；
 * {@code read(String)} 补行尾换行的 {@code Arrays.copyOf} 没有真正扩容，内容不以换行结尾（含空串）就抛越界；
 * 流式重载按行解析，读不回引号内的换行。</p>
 */
public class CSVReadTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Test
    public void emptyValueAtRowEnd() {
        CSVTable table = CSV.read("a,b\n1,\n");
        assertEquals(1, table.size());
        assertEquals(Arrays.asList("1", ""), values(table.getRow(0)));
    }

    @Test
    public void emptyValueAtRowStart() {
        CSVTable table = CSV.read("a,b\n,2\n");
        assertEquals(1, table.size());
        assertEquals(Arrays.asList("", "2"), values(table.getRow(0)));
    }

    @Test
    public void emptyValueInMiddle() {
        CSVTable table = CSV.read("a,b,c\n1,,3\n");
        assertEquals(1, table.size());
        assertEquals(Arrays.asList("1", "", "3"), values(table.getRow(0)));
    }

    @Test
    public void allValuesEmpty() {
        CSVTable table = CSV.read("a,b,c\n,,\n");
        assertEquals(1, table.size());
        assertEquals(Arrays.asList("", "", ""), values(table.getRow(0)));
    }

    @Test
    public void emptyValueReadableByColumnName() {
        CSVTable table = CSV.read("name,age\nTom,\n");
        assertEquals("Tom", table.getRow(0).get("name"));
        assertEquals("", table.getRow(0).get("age"));
    }

    @Test
    public void contentWithoutTrailingLineBreak() {
        CSVTable table = CSV.read("name,age\nTom,18");
        assertEquals(1, table.size());
        assertEquals(Arrays.asList("name", "age"), values(table.getColumns()));
        assertEquals(Arrays.asList("Tom", "18"), values(table.getRow(0)));
    }

    @Test
    public void singleRowWithoutLineBreak() {
        CSVTable table = CSV.read("name,age");
        assertEquals(0, table.size());
        assertEquals(Arrays.asList("name", "age"), values(table.getColumns()));
    }

    @Test
    public void emptyContentReturnsEmptyTable() {
        CSVTable table = CSV.read("");
        assertEquals(0, table.size());
        assertEquals(0, table.getColumns().values.size());
    }

    @Test
    public void blankContentReturnsEmptyTable() {
        assertEquals(0, CSV.read("\n").size());
        assertEquals(0, CSV.read("  \r\n \n").getColumns().values.size());
    }

    @Test
    public void blankLinesAreSkipped() {
        CSVTable table = CSV.read("a,b\n\n1,2\n   \n3,4\n\n");
        assertEquals(2, table.size());
        assertEquals(Arrays.asList("1", "2"), values(table.getRow(0)));
        assertEquals(Arrays.asList("3", "4"), values(table.getRow(1)));
    }

    @Test
    public void blankLinesAreSkippedWithCarriageReturn() {
        CSVTable table = CSV.read("a,b\r\r\n\r1,2\r\n \r\n3,4");
        assertEquals(2, table.size());
        assertEquals(Arrays.asList("1", "2"), values(table.getRow(0)));
        assertEquals(Arrays.asList("3", "4"), values(table.getRow(1)));
    }

    @Test
    public void trailingSpacesAfterLastRowDoNotAddRow() {
        CSVTable table = CSV.read("a,b\n1,2\n   ");
        assertEquals(1, table.size());
    }

    @Test
    public void quotedValueKeepsSeparatorAndLineBreak() {
        CSVTable table = CSV.read("a,b\n\"x,y\",\"line1\nline2\"\n");
        assertEquals(1, table.size());
        assertEquals(Arrays.asList("x,y", "line1\nline2"), values(table.getRow(0)));
    }

    @Test
    public void quotedValueUnescapesDoubledQuote() {
        CSVTable table = CSV.read("a\n\"he said \"\"hi\"\"\"\n");
        assertEquals(Arrays.asList("he said \"hi\""), values(table.getRow(0)));
    }

    @Test
    public void quotedEmptyValue() {
        CSVTable table = CSV.read("a,b\n\"\",\"x\"\n");
        assertEquals(Arrays.asList("", "x"), values(table.getRow(0)));
    }

    @Test
    public void spacesAroundValuesAreTrimmed() {
        CSVTable table = CSV.read("a,b\n  1  ,  \"2\"  \n");
        assertEquals(Arrays.asList("1", "2"), values(table.getRow(0)));
    }

    @Test
    public void lineBreakVariants() {
        assertEquals(Arrays.asList("1", "2"), values(CSV.read("a,b\r\n1,2\r\n").getRow(0)));
        assertEquals(Arrays.asList("1", "2"), values(CSV.read("a,b\r1,2\r").getRow(0)));
        assertEquals(Arrays.asList("1", ""), values(CSV.read("a,b\r\n1,\r\n").getRow(0)));
    }

    @Test
    public void missingClosingQuoteFails() {
        try {
            CSV.read("a,b\n\"x,1\n");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertEquals(true, e.getMessage().contains("missing closing"));
        }
    }

    @Test
    public void textAfterClosingQuoteFails() {
        try {
            CSV.read("a,b\n\"x\"y,1\n");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertEquals(true, e.getMessage().contains("expected ','"));
        }
    }

    @Test
    public void streamReadSupportsEmptyValueAndQuotedLineBreak() {
        String content = "name,note\nTom,\nLucy,\"line1\nline2\"";
        CSVTable table = CSV.read(new ByteArrayInputStream(content.getBytes(UTF8)), UTF8);
        assertEquals(2, table.size());
        assertEquals("", table.getRow(0).get("note"));
        assertEquals("line1\nline2", table.getRow(1).get("note"));
    }

    @Test
    public void bytesReadSupportsEmptyValue() {
        CSVTable table = CSV.read("a,b\n1,\n".getBytes(UTF8), "UTF-8");
        assertEquals(Arrays.asList("1", ""), values(table.getRow(0)));
    }

    @Test
    public void writeThenReadRoundTrip() {
        CSVTable table = CSVTable.create(new String[]{"name", "note"});
        table.addRow(Arrays.asList("Tom", ""));
        table.addRow(Arrays.asList("Lucy", "a,b\"c\nd"));
        CSVTable read = CSV.read(table.toCSVString());
        assertEquals(2, read.size());
        assertEquals(Arrays.asList("Tom", ""), values(read.getRow(0)));
        assertEquals(Arrays.asList("Lucy", "a,b\"c\nd"), values(read.getRow(1)));
    }

    private List<String> values(CSVRow row) {
        return row.values;
    }
}
