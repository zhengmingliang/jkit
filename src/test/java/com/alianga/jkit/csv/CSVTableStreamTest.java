package com.alianga.jkit.csv;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code csv} 包流式读写的测试：{@link CSV#readStream(File, CSVRowHandler)} 与
 * {@link CSV#writer(File, String...)} / {@link CSVObjectWriter#writeObject(Object)}。
 *
 * <p>流式读的首行按 {@code csv} 包惯例当表头，回调里拿到的 {@link CSVRow} 已绑定表头，
 * 因此可以按列名取值、直接转实体。</p>
 */
public class CSVTableStreamTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void readStreamBindsHeaderAndSkipsIt() {
        final List<String> names = new ArrayList<String>();
        final List<Long> indexes = new ArrayList<Long>();
        long rows = CSV.readStream("name,age\nTom,18\nLucy,20\n", new CSVRowHandler() {
            @Override
            public boolean handle(CSVRow row, long rowIndex) {
                names.add(row.get("name") + ":" + row.get("age"));
                indexes.add(rowIndex);
                return true;
            }
        });
        assertEquals(2, rows);
        assertEquals(Arrays.asList("Tom:18", "Lucy:20"), names);
        assertEquals(Arrays.asList(0L, 1L), indexes);
    }

    @Test
    public void readStreamHandlesEmptyFieldsAndQuotes() {
        final List<String> notes = new ArrayList<String>();
        long rows = CSV.readStream("name,note\nTom,\nLucy,\"a,b\nc\"\n\"\",x", new CSVRowHandler() {
            @Override
            public boolean handle(CSVRow row, long rowIndex) {
                notes.add(row.get("name") + "|" + row.get("note"));
                return true;
            }
        });
        assertEquals(3, rows);
        assertEquals(Arrays.asList("Tom|", "Lucy|a,b\nc", "|x"), notes);
    }

    @Test
    public void readStreamOnHeaderOnlyOrEmptyContent() {
        CSVRowHandler failing = new CSVRowHandler() {
            @Override
            public boolean handle(CSVRow row, long rowIndex) {
                throw new IllegalStateException("should not be called");
            }
        };
        assertEquals(0, CSV.readStream("name,age\n", failing));
        assertEquals(0, CSV.readStream("", failing));
    }

    @Test
    public void readStreamCanStopEarly() {
        final List<String> seen = new ArrayList<String>();
        long rows = CSV.readStream("name\na\nb\nc\nd\n", new CSVRowHandler() {
            @Override
            public boolean handle(CSVRow row, long rowIndex) {
                seen.add(row.get(0));
                return rowIndex < 1;
            }
        });
        assertEquals(2, rows);
        assertEquals(Arrays.asList("a", "b"), seen);
    }

    @Test
    public void readStreamConvertsRowToBean() throws Exception {
        File csv = tmp.newFile("beans.csv");
        CSVObjectWriter writer = CSV.writer(csv, StandardCharsets.UTF_8);
        try {
            writer.writeObject(newUser("Tom", 18));
            writer.writeObject(newUser("Lucy", 20));
        } finally {
            writer.close();
        }

        final List<String> beans = new ArrayList<String>();
        long rows = CSV.readStream(csv, StandardCharsets.UTF_8, new CSVRowHandler() {
            @Override
            public boolean handle(CSVRow row, long rowIndex) {
                User user = row.toBean(User.class);
                beans.add(user.getName() + "/" + user.getAge());
                return true;
            }
        });
        assertEquals(2, rows);
        assertEquals(Arrays.asList("Tom/18", "Lucy/20"), beans);
    }

    @Test
    public void writerWritesGivenHeaderOnce() throws Exception {
        File csv = tmp.newFile("header.csv");
        CSVObjectWriter writer = CSV.writer(csv, StandardCharsets.UTF_8, "name", "age");
        try {
            writer.writeRow(Arrays.asList("Tom", "18"));
            writer.writeObject(newUser("Lucy", 20));
        } finally {
            writer.close();
        }
        CSVTable table = CSV.read(csv, "UTF-8");
        assertEquals(Arrays.asList("name", "age"), table.getColumns().values);
        assertEquals(2, table.size());
        assertEquals("Tom", table.getRow(0).get("name"));
        assertEquals("Lucy", table.getRow(1).get("name"));
        assertEquals(3, writer.getRowCount());
    }

    @Test
    public void writerWritesMapObject() throws Exception {
        File csv = tmp.newFile("map.csv");
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("name", "Tom");
        map.put("note", "a,b");
        CSVObjectWriter writer = CSV.writer(csv, StandardCharsets.UTF_8);
        try {
            writer.writeObject(map);
        } finally {
            writer.close();
        }
        CSVTable table = CSV.read(csv, "UTF-8");
        assertEquals(Arrays.asList("name", "note"), table.getColumns().values);
        assertEquals("a,b", table.getRow(0).get("note"));
    }

    @Test
    public void writerUsesFixedLineSeparatorAndCreatesParent() throws Exception {
        File csv = new File(tmp.getRoot(), "sub/dir/out.csv");
        CSVObjectWriter writer = CSV.writer(csv, StandardCharsets.UTF_8, "a");
        try {
            writer.writeRow(Arrays.asList("1"));
        } finally {
            writer.close();
        }
        assertTrue(csv.exists());
        byte[] bytes = java.nio.file.Files.readAllBytes(csv.toPath());
        assertEquals("a\n1\n", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    public void writeObjectToAndToCSVStringStillWork() throws Exception {
        List<User> users = Arrays.asList(newUser("Tom", 18), newUser("Lucy", 20));
        String text = CSV.toCSVString(users);
        CSVTable table = CSV.read(text);
        assertEquals(2, table.size());
        assertEquals("18", table.getRow(0).get("age"));
        assertEquals("Lucy", table.getRow(1).get("name"));

        File csv = tmp.newFile("objects.csv");
        CSV.writeObjectTo(users, csv);
        CSVTable back = CSV.read(csv);
        assertEquals(2, back.size());
        assertEquals("Tom", back.getRow(0).get("name"));
    }

    @Test
    public void streamAndFullReadAgree() throws Exception {
        File csv = tmp.newFile("agree.csv");
        CSVObjectWriter writer = CSV.writer(csv, StandardCharsets.UTF_8, "a", "b", "c");
        try {
            writer.writeRow(Arrays.asList("1", "", "x,y"));
            writer.writeRow(Arrays.asList("", "2", "he said \"hi\""));
            writer.writeRow(Arrays.asList("3", "line1\nline2", ""));
        } finally {
            writer.close();
        }
        CSVTable table = CSV.read(csv, "UTF-8");
        final List<List<String>> streamed = new ArrayList<List<String>>();
        CSV.readStream(csv, StandardCharsets.UTF_8, new CSVRowHandler() {
            @Override
            public boolean handle(CSVRow row, long rowIndex) {
                streamed.add(row.getValues());
                return true;
            }
        });
        assertEquals(3, streamed.size());
        for (int i = 0; i < streamed.size(); i++) {
            assertEquals(table.getRow(i).getValues(), streamed.get(i));
        }
    }

    @Test
    public void missingClosingQuoteStillFailsInStreamMode() {
        try {
            CSV.readStream("a,b\n\"x,1\n", new CSVRowHandler() {
                @Override
                public boolean handle(CSVRow row, long rowIndex) {
                    return true;
                }
            });
            org.junit.Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("missing closing"));
        }
    }

    private User newUser(String name, int age) {
        User user = new User();
        user.setName(name);
        user.setAge(age);
        return user;
    }

    /**
     * 测试用实体
     */
    public static class User {
        private String name;
        private int age;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }

}
