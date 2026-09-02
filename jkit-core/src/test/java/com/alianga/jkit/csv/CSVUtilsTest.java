package com.alianga.jkit.csv;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CSVUtilsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testWriteReadRoundTrip() throws Exception {
        File csv = tmp.newFile("sample.csv");
        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(Arrays.asList("Tom", "18"));
        rows.add(Arrays.asList("Lucy", "20"));

        CSVUtils.write(csv, rows, "name", "age");

        List<List<String>> back = CSVUtils.read(csv);
        assertEquals(3, back.size()); // 表头 + 2 行
        assertEquals("name", back.get(0).get(0));
        assertEquals("Tom", back.get(1).get(0));
        assertEquals("20", back.get(2).get(1));
    }

    @Test
    public void testQuotedFieldWithDelimiterAndQuote() throws Exception {
        File csv = tmp.newFile("quoted.csv");
        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(Arrays.asList("Tom", "a,b\"c"));
        CSVUtils.write(csv, rows, "name", "note");

        List<List<String>> back = CSVUtils.read(csv);
        assertEquals("a,b\"c", back.get(1).get(1));
    }

    @Test
    public void testMultilineField() throws Exception {
        File csv = tmp.newFile("multiline.csv");
        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(Arrays.asList("Lucy", "line1\nline2"));
        CSVUtils.write(csv, rows, "name", "note");

        List<List<String>> back = CSVUtils.read(csv);
        assertEquals("line1\nline2", back.get(1).get(1));
    }

    @Test
    public void testReadWithHeader() throws Exception {
        File csv = tmp.newFile("header.csv");
        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(Arrays.asList("Tom", "18"));
        CSVUtils.write(csv, rows, "name", "age");

        List<Map<String, String>> maps = CSVUtils.readWithHeader(csv);
        assertEquals(1, maps.size());
        assertEquals("Tom", maps.get(0).get("name"));
        assertEquals("18", maps.get(0).get("age"));
    }

    @Test
    public void testEmptyLinesIgnored() throws Exception {
        File csv = tmp.newFile("empty.csv");
        // 手写含空行与 \r\n 的 CSV
        java.nio.file.Files.write(csv.toPath(),
                "a,b\r\n1,2\r\n\r\n3,4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<List<String>> back = CSVUtils.read(csv);
        assertEquals(3, back.size());
        assertTrue(back.get(2).get(0).equals("3"));
    }

    @Test
    public void testMissingFileReturnsEmpty() throws Exception {
        List<List<String>> rows = CSVUtils.read(new File(tmp.getRoot(), "not-exist.csv"));
        assertEquals(0, rows.size());
    }
}
