package com.alianga.jkit.csv;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link CSVUtils#readStream(File, java.nio.charset.Charset, CSVValuesHandler)} 与
 * {@link CSVWriter} 的测试。
 *
 * <p>解析器改成按块扫描 {@code char[]} 之后，字段、引号、{@code ""} 转义、{@code \r\n}
 * 都可能被切在两个块之间，这里用每次只吐 N 个字符的 {@link ChunkedReader} 覆盖所有切分位置。</p>
 */
public class CSVStreamTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void streamResultEqualsFullRead() throws Exception {
        File csv = tmp.newFile("stream.csv");
        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(Arrays.asList("Tom", "a,b\"c", ""));
        rows.add(Arrays.asList("Lucy", "line1\nline2", "x"));
        CSVUtils.write(csv, rows, "name", "note", "tag");

        final List<List<String>> streamed = new ArrayList<List<String>>();
        long count = CSVUtils.readStream(csv, new CSVValuesHandler() {
            @Override
            public boolean handle(List<String> row, long rowIndex) {
                assertEquals(streamed.size(), (int) rowIndex);
                streamed.add(row);
                return true;
            }
        });
        assertEquals(3, count);
        assertEquals(CSVUtils.read(csv), streamed);
    }

    @Test
    public void handlerCanStopEarly() throws Exception {
        File csv = tmp.newFile("stop.csv");
        List<List<String>> rows = new ArrayList<List<String>>();
        for (int i = 0; i < 100; i++) {
            rows.add(Arrays.asList("row" + i, String.valueOf(i)));
        }
        CSVUtils.write(csv, rows, "name", "value");

        final List<List<String>> streamed = new ArrayList<List<String>>();
        long count = CSVUtils.readStream(csv, new CSVValuesHandler() {
            @Override
            public boolean handle(List<String> row, long rowIndex) {
                streamed.add(row);
                return rowIndex < 2;
            }
        });
        assertEquals(3, count);
        assertEquals(3, streamed.size());
        assertEquals("row1", streamed.get(2).get(0));
    }

    @Test
    public void missingFileIsNotAnError() throws Exception {
        long count = CSVUtils.readStream(new File(tmp.getRoot(), "not-exist.csv"), new CSVValuesHandler() {
            @Override
            public boolean handle(List<String> row, long rowIndex) {
                throw new IllegalStateException("should not be called");
            }
        });
        assertEquals(0, count);
    }

    @Test
    public void chunkBoundaryDoesNotChangeParsing() throws Exception {
        String content = "a,b,c\n"
                + "1,,3\n"
                + ",2,\n"
                + "\"q,1\",\"he said \"\"hi\"\"\",\"line1\nline2\"\r\n"
                + "\"\",\"\"\"\",x\r\n"
                + "\r\n"
                + "last,row,\"tail\"";
        List<List<String>> expected = parse(new StringReader(content));
        assertEquals(6, expected.size());
        assertEquals(Arrays.asList("1", "", "3"), expected.get(1));
        assertEquals(Arrays.asList("", "2", ""), expected.get(2));
        assertEquals(Arrays.asList("q,1", "he said \"hi\"", "line1\nline2"), expected.get(3));
        assertEquals(Arrays.asList("", "\"", "x"), expected.get(4));
        assertEquals(Arrays.asList("last", "row", "tail"), expected.get(5));

        // 每次只吐 N 个字符，把每一种切分位置都走一遍
        for (int chunk = 1; chunk <= 17; chunk++) {
            assertEquals("chunk=" + chunk, expected, parse(new ChunkedReader(content, chunk)));
        }
    }

    @Test
    public void fieldLongerThanOneChunk() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 50000; i++) {
            big.append('x');
        }
        String content = "a,b\n" + big + ",\"" + big + "\"\n";
        List<List<String>> rows = parse(new ChunkedReader(content, 4096));
        assertEquals(2, rows.size());
        assertEquals(big.toString(), rows.get(1).get(0));
        assertEquals(big.toString(), rows.get(1).get(1));
    }

    @Test
    public void writerStreamsRowsAndReadsBack() throws Exception {
        File csv = tmp.newFile("writer.csv");
        CSVWriter writer = CSVUtils.writer(csv, "name", "note");
        try {
            writer.writeRow(Arrays.asList("Tom", ""));
            writer.writeRow(Arrays.asList("Lucy", "a,b\"c\nd"));
            writer.writeRow("Jack", null);
        } finally {
            writer.close();
        }
        assertEquals(4, writer.getRowCount());

        List<List<String>> back = CSVUtils.read(csv);
        assertEquals(4, back.size());
        assertEquals(Arrays.asList("name", "note"), back.get(0));
        assertEquals(Arrays.asList("Tom", ""), back.get(1));
        assertEquals(Arrays.asList("Lucy", "a,b\"c\nd"), back.get(2));
        assertEquals(Arrays.asList("Jack", ""), back.get(3));
    }

    @Test
    public void writerCreatesMissingParentDirectory() throws Exception {
        File csv = new File(tmp.getRoot(), "sub/dir/out.csv");
        CSVWriter writer = CSVUtils.writer(csv, StandardCharsets.UTF_8);
        try {
            writer.writeRow(Arrays.asList("1", "2"));
        } finally {
            writer.close();
        }
        assertTrue(csv.exists());
        assertEquals(Arrays.asList("1", "2"), CSVUtils.read(csv).get(0));
    }

    private List<List<String>> parse(Reader reader) throws IOException {
        final List<List<String>> rows = new ArrayList<List<String>>();
        try {
            CSVUtils.readStream(reader, new CSVValuesHandler() {
                @Override
                public boolean handle(List<String> row, long rowIndex) {
                    rows.add(row);
                    return true;
                }
            });
        } finally {
            reader.close();
        }
        return rows;
    }

    /**
     * 每次 read 最多返回 chunk 个字符的 Reader，用来制造块边界
     */
    private static final class ChunkedReader extends Reader {
        private final String content;
        private final int chunk;
        private int pos;

        ChunkedReader(String content, int chunk) {
            this.content = content;
            this.chunk = chunk;
        }

        @Override
        public int read(char[] buf, int off, int len) {
            if (pos == content.length()) {
                return -1;
            }
            int count = Math.min(Math.min(len, chunk), content.length() - pos);
            content.getChars(pos, pos + count, buf, off);
            pos += count;
            return count;
        }

        @Override
        public void close() {
            // 无需释放资源
        }
    }
}
