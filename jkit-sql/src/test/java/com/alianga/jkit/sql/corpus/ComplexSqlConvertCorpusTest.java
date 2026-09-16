package com.alianga.jkit.sql.corpus;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlParseException;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import com.alianga.jkit.sql.corpus.ComplexSqlCorpus.Case;
import com.alianga.jkit.sql.corpus.ComplexSqlCorpus.Source;
import com.alianga.jkit.sql.schema.convert.ConversionResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * L3：跨方言转换后再 parse，并检查源方言特征残留。
 *
 * <p>全矩阵：四方言两两组合共 12 个方向对（4×3×300 = 3600 次转换），
 * 覆盖 POSTGRES 作为源的方向。代表题 001 / 022 / 211 额外打印转换摘要。</p>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class ComplexSqlConvertCorpusTest {

    /**
     * 主矩阵转换后再解析，通过率默认 ≥ 90%。
     */
    @Test
    public void convertMainMatrixThenParse() {
        Map<String, Map<Source, Case>> aligned = ComplexSqlCorpus.loadAligned();
        Pair[] pairs = new Pair[] {
                new Pair(Source.MYSQL, Source.ORACLE),
                new Pair(Source.MYSQL, Source.SQLSERVER),
                new Pair(Source.MYSQL, Source.POSTGRES),
                new Pair(Source.POSTGRES, Source.MYSQL),
                new Pair(Source.POSTGRES, Source.ORACLE),
                new Pair(Source.POSTGRES, Source.SQLSERVER),
                new Pair(Source.ORACLE, Source.MYSQL),
                new Pair(Source.ORACLE, Source.POSTGRES),
                new Pair(Source.ORACLE, Source.SQLSERVER),
                new Pair(Source.SQLSERVER, Source.MYSQL),
                new Pair(Source.SQLSERVER, Source.POSTGRES),
                new Pair(Source.SQLSERVER, Source.ORACLE)
        };
        int total = 0;
        int parsePass = 0;
        int leftoverHits = 0;
        List<String> failClasses = new ArrayList<String>();
        StringBuilder fails = new StringBuilder();
        fails.append("src\tdst\tid\tkind\terrorClass\tmessage\n");
        StringBuilder leftovers = new StringBuilder();
        leftovers.append("src\tdst\tid\tfeature\tsnippet\n");
        StringBuilder reps = new StringBuilder();
        for (int p = 0; p < pairs.length; p++) {
            Pair pair = pairs[p];
            for (Map.Entry<String, Map<Source, Case>> e : aligned.entrySet()) {
                Case src = e.getValue().get(pair.from);
                if (src == null) {
                    continue;
                }
                total++;
                ConversionResult converted;
                try {
                    converted = SQL.convert(src.sql(), pair.from.dialect(), pair.to.dialect(),
                            null);
                } catch (RuntimeException ex) {
                    record(fails, failClasses, pair, src.id(), "CONVERT", "CONVERT_RUNTIME",
                            ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    continue;
                }
                try {
                    SqlStatement stmt = SQL.parse(converted.sql(), pair.to.dialect());
                    if (stmt == null || stmt.type() != SqlStatementType.SELECT) {
                        record(fails, failClasses, pair, src.id(), "PARSE", "PARSE_NOT_SELECT",
                                stmt == null ? "null" : String.valueOf(stmt.type()));
                    } else {
                        parsePass++;
                    }
                } catch (SqlParseException ex) {
                    record(fails, failClasses, pair, src.id(), "PARSE",
                            ComplexSqlReports.parseErrorClass(ex.getMessage()), ex.getMessage());
                }
                String leftover = leftoverFeature(converted.sql(), pair.from.dialect(),
                        pair.to.dialect());
                if (leftover != null) {
                    leftoverHits++;
                    leftovers.append(pair.from.name()).append('\t')
                            .append(pair.to.name()).append('\t')
                            .append(src.id()).append('\t')
                            .append(leftover).append('\t')
                            .append(ComplexSqlReports.tsv(ComplexSqlReports.trunc(converted.sql(), 240)))
                            .append('\n');
                }
                if (isRep(src.id()) && pair.from == Source.MYSQL) {
                    reps.append(pair.from.name()).append("->").append(pair.to.name())
                            .append(' ').append(src.id())
                            .append(" leftover=").append(leftover == null ? "-" : leftover)
                            .append('\n')
                            .append(ComplexSqlReports.trunc(converted.sql(), 500))
                            .append("\n\n");
                }
            }
        }
        double rate = total == 0 ? 0.0 : 100.0 * parsePass / total;
        StringBuilder summary = new StringBuilder();
        summary.append("L3 convert total=").append(total)
                .append(" parsePass=").append(parsePass)
                .append(" parseFail=").append(total - parsePass)
                .append(" leftoverHits=").append(leftoverHits)
                .append(" rate=").append(String.format(Locale.ROOT, "%.2f", rate))
                .append("%\n");
        Map<String, Integer> counts = ComplexSqlReports.countClasses(failClasses);
        summary.append("errorClass counts:\n");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            summary.append("  ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        ComplexSqlReports.write("l3-fails.tsv", fails.toString());
        ComplexSqlReports.write("l3-leftovers.tsv", leftovers.toString());
        ComplexSqlReports.write("l3-reps.txt", reps.toString());
        ComplexSqlReports.write("l3-summary.txt", summary.toString());
        System.out.print(summary);
        double minPass = minPassPercent();
        assertTrue("L3 convert-parse rate " + rate + "% < " + minPass + "%; see l3-fails.tsv",
                rate + 1e-9 >= minPass);
    }

    private static boolean isRep(String id) {
        return "001".equals(id) || "022".equals(id) || "211".equals(id);
    }

    private static void record(StringBuilder fails, List<String> failClasses, Pair pair, String id,
                               String kind, String errorClass, String message) {
        failClasses.add(errorClass);
        fails.append(pair.from.name()).append('\t')
                .append(pair.to.name()).append('\t')
                .append(id).append('\t')
                .append(kind).append('\t')
                .append(ComplexSqlReports.tsv(errorClass)).append('\t')
                .append(ComplexSqlReports.tsv(ComplexSqlReports.trunc(message, 300)))
                .append('\n');
    }

    /**
     * 粗粒度源方言残留。只报明确不该出现在目标里的标记。
     *
     * @param sql 转换结果
     * @param source 源方言
     * @param target 目标方言
     * @return 残留特征名，没有则 null
     */
    static String leftoverFeature(String sql, SqlDialect source, SqlDialect target) {
        if (sql == null) {
            return "EMPTY";
        }
        String u = sql.toUpperCase(Locale.ROOT);
        if (target == SqlDialect.ORACLE12 || target == SqlDialect.ORACLE) {
            if (hasKeyword(u, "LIMIT")) {
                return "LIMIT";
            }
            if (hasKeyword(u, "GROUP_CONCAT")) {
                return "GROUP_CONCAT";
            }
            if (hasKeyword(u, "DATE_FORMAT")) {
                return "DATE_FORMAT";
            }
            if (hasKeyword(u, "DATE_SUB") || hasKeyword(u, "DATE_ADD")) {
                return "DATE_ADD_SUB";
            }
            if (source == SqlDialect.MYSQL && hasKeyword(u, "RECURSIVE")
                    && u.contains("WITH RECURSIVE ")) {
                return "WITH_RECURSIVE";
            }
        }
        if (target == SqlDialect.SQLSERVER) {
            if (hasKeyword(u, "LIMIT")) {
                return "LIMIT";
            }
            if (hasKeyword(u, "GROUP_CONCAT")) {
                return "GROUP_CONCAT";
            }
            if (hasKeyword(u, "DATE_FORMAT")) {
                return "DATE_FORMAT";
            }
            if (hasKeyword(u, "DATE_SUB") || hasKeyword(u, "DATE_ADD")) {
                return "DATE_ADD_SUB";
            }
            if (hasKeyword(u, "LEAST") || hasKeyword(u, "GREATEST")) {
                return "LEAST_GREATEST";
            }
        }
        if (target == SqlDialect.POSTGRES) {
            if (hasKeyword(u, "GROUP_CONCAT")) {
                return "GROUP_CONCAT";
            }
            if (hasKeyword(u, "DATE_FORMAT")) {
                return "DATE_FORMAT";
            }
        }
        if (target == SqlDialect.MYSQL) {
            if (u.contains("FETCH FIRST") || u.contains("FETCH NEXT")) {
                return "FETCH";
            }
            if (hasKeyword(u, "STRING_AGG")) {
                return "STRING_AGG";
            }
            if (hasKeyword(u, "LISTAGG")) {
                return "LISTAGG";
            }
            if (hasKeyword(u, "GETDATE")) {
                return "GETDATE";
            }
            if (hasKeyword(u, "DATEADD")) {
                return "DATEADD";
            }
        }
        return null;
    }

    static boolean hasKeyword(String upperSql, String token) {
        int from = 0;
        while (from <= upperSql.length() - token.length()) {
            int idx = upperSql.indexOf(token, from);
            if (idx < 0) {
                return false;
            }
            boolean leftOk = idx == 0 || !isIdentChar(upperSql.charAt(idx - 1));
            int end = idx + token.length();
            boolean rightOk = end >= upperSql.length() || !isIdentChar(upperSql.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = idx + token.length();
        }
        return false;
    }

    static boolean isIdentChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static double minPassPercent() {
        // 4×3 全矩阵已 3600/3600 全过，门禁收紧到 100%。
        String raw = System.getProperty("complex.sql.minPass", "100");
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 90.0;
        }
    }

    private static final class Pair {
        private final Source from;
        private final Source to;

        private Pair(Source from, Source to) {
            this.from = from;
            this.to = to;
        }
    }
}
