package com.alianga.jkit.sql.corpus;

import com.alianga.jkit.sql.SqlDialect;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 复杂业务 SQL 语料加载器。从 {@code /sqls/complex-sql/*_complex_300.sql}
 * 按 {@code -- [NNN]} 切条，四方言编号 001–300 对齐。
 *
 * <p>Oracle 语料是 12c+（{@code FETCH FIRST} / 列清单递归 CTE），对应
 * {@link SqlDialect#ORACLE12}，不要用经典 {@link SqlDialect#ORACLE}。</p>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class ComplexSqlCorpus {
    /** classpath 目录，以 {@code /} 开头。 */
    public static final String RESOURCE_DIR = "/sqls/complex-sql/";

    private static final Pattern MARKER = Pattern.compile(
            "^-- \\[(\\d+)\\]\\s*(.+?)\\s*$", Pattern.MULTILINE);

    private ComplexSqlCorpus() {
    }

    /**
     * 语料来源文件。
     */
    public enum Source {
        /** MySQL 8.0+。 */
        MYSQL("mysql_complex_300.sql", SqlDialect.MYSQL),
        /** PostgreSQL 12+。 */
        POSTGRES("postgresql_complex_300.sql", SqlDialect.POSTGRES),
        /** Oracle 12c+（{@code ORACLE12}）。 */
        ORACLE("oracle_complex_300.sql", SqlDialect.ORACLE12),
        /** SQL Server 2017+。 */
        SQLSERVER("sqlserver_complex_300.sql", SqlDialect.SQLSERVER);

        private final String fileName;
        private final SqlDialect dialect;

        Source(String fileName, SqlDialect dialect) {
            this.fileName = fileName;
            this.dialect = dialect;
        }

        /**
         * @return 资源文件名
         */
        public String fileName() {
            return fileName;
        }

        /**
         * @return 解析 / 转换用方言
         */
        public SqlDialect dialect() {
            return dialect;
        }

        /**
         * @return classpath 路径
         */
        public String resourcePath() {
            return RESOURCE_DIR + fileName;
        }
    }

    /**
     * 一条已切分的复杂 SQL。
     */
    public static final class Case {
        private final Source source;
        private final String id;
        private final String technique;
        private final String domain;
        private final String title;
        private final String sql;

        /**
         * @param source 来源方言文件
         * @param id 三位编号
         * @param technique 技法标签
         * @param domain 业务域
         * @param title 标题
         * @param sql 已剥注释 / {@code GO} / 末尾分号的 SQL
         */
        public Case(Source source, String id, String technique, String domain,
                    String title, String sql) {
            this.source = source;
            this.id = id;
            this.technique = technique;
            this.domain = domain;
            this.title = title;
            this.sql = sql;
        }

        /**
         * @return 来源
         */
        public Source source() {
            return source;
        }

        /**
         * @return 三位编号，如 {@code 001}
         */
        public String id() {
            return id;
        }

        /**
         * @return 技法
         */
        public String technique() {
            return technique;
        }

        /**
         * @return 业务域
         */
        public String domain() {
            return domain;
        }

        /**
         * @return 标题
         */
        public String title() {
            return title;
        }

        /**
         * @return SQL 正文
         */
        public String sql() {
            return sql;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            return source.name().toLowerCase(Locale.ROOT) + "-" + id + " " + title;
        }
    }

    /**
     * 加载一个方言文件的 300 条。
     *
     * @param source 来源
     * @return 按编号排序的列表
     */
    public static List<Case> load(Source source) {
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        String text = readResource(source.resourcePath());
        List<Case> cases = split(source, text);
        if (cases.size() != 300) {
            throw new IllegalStateException(source + " expected 300 statements, got " + cases.size());
        }
        return cases;
    }

    /**
     * 加载全部四方言，共 1200 条。
     *
     * @return 按 MYSQL / POSTGRES / ORACLE / SQLSERVER、再按编号
     */
    public static List<Case> loadAll() {
        List<Case> all = new ArrayList<Case>(1200);
        Source[] sources = Source.values();
        for (int i = 0; i < sources.length; i++) {
            all.addAll(load(sources[i]));
        }
        return all;
    }

    /**
     * 按编号对齐四方言（同一 {@code id} 的四条）。
     *
     * @return 编号 → 方言 → Case
     */
    public static Map<String, Map<Source, Case>> loadAligned() {
        Map<String, Map<Source, Case>> out = new LinkedHashMap<String, Map<Source, Case>>(300);
        Source[] sources = Source.values();
        for (int i = 0; i < sources.length; i++) {
            List<Case> cases = load(sources[i]);
            for (int j = 0; j < cases.size(); j++) {
                Case item = cases.get(j);
                Map<Source, Case> row = out.get(item.id());
                if (row == null) {
                    row = new LinkedHashMap<Source, Case>(4);
                    out.put(item.id(), row);
                }
                row.put(item.source(), item);
            }
        }
        return out;
    }

    static List<Case> split(Source source, String text) {
        Matcher matcher = MARKER.matcher(text);
        List<int[]> spans = new ArrayList<int[]>(300);
        List<String> ids = new ArrayList<String>(300);
        List<String> headers = new ArrayList<String>(300);
        while (matcher.find()) {
            spans.add(new int[] {matcher.start(), matcher.end()});
            ids.add(padId(matcher.group(1)));
            headers.add(matcher.group(2).trim());
        }
        List<Case> cases = new ArrayList<Case>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            int bodyStart = spans.get(i)[1];
            int bodyEnd = i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length();
            String body = text.substring(bodyStart, bodyEnd);
            String sql = stripBody(body);
            String[] meta = splitHeader(headers.get(i));
            cases.add(new Case(source, ids.get(i), meta[0], meta[1], meta[2], sql));
        }
        return cases;
    }

    static String stripBody(String body) {
        String[] rawLines = body.split("\n", -1);
        List<String> lines = new ArrayList<String>(rawLines.length);
        boolean started = false;
        for (int i = 0; i < rawLines.length; i++) {
            String line = rtrim(rawLines[i]);
            if (!started) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue;
                }
                started = true;
            }
            lines.add(line);
        }
        while (!lines.isEmpty()) {
            String last = lines.get(lines.size() - 1).trim();
            if (last.isEmpty() || isGo(last) || last.startsWith("--")) {
                lines.remove(lines.size() - 1);
                continue;
            }
            break;
        }
        String sql = joinLines(lines).trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }

    static String padId(String raw) {
        int n = Integer.parseInt(raw);
        if (n < 10) {
            return "00" + n;
        }
        if (n < 100) {
            return "0" + n;
        }
        return String.valueOf(n);
    }

    static String[] splitHeader(String header) {
        String[] parts = header.split("\\|", 3);
        String technique = parts.length > 0 ? parts[0].trim() : "";
        String domain = parts.length > 1 ? parts[1].trim() : "";
        String title = parts.length > 2 ? parts[2].trim() : header;
        return new String[] {technique, domain, title};
    }

    static boolean isGo(String trimmed) {
        return "GO".equalsIgnoreCase(trimmed);
    }

    static String rtrim(String line) {
        int end = line.length();
        while (end > 0) {
            char c = line.charAt(end - 1);
            if (c != ' ' && c != '\t' && c != '\r') {
                break;
            }
            end--;
        }
        return end == line.length() ? line : line.substring(0, end);
    }

    static String joinLines(List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    static String readResource(String path) {
        InputStream in = ComplexSqlCorpus.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("missing classpath resource " + path);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("read " + path, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    /**
     * @return 不可变的全部来源列表
     */
    public static List<Source> sources() {
        List<Source> list = new ArrayList<Source>(4);
        Collections.addAll(list, Source.values());
        return list;
    }
}
