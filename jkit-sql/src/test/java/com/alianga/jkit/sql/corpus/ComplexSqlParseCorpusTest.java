package com.alianga.jkit.sql.corpus;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlParseException;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import com.alianga.jkit.sql.corpus.ComplexSqlCorpus.Case;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * L1：1200 条复杂业务 SQL 在对应方言下可解析为 SELECT。
 *
 * <p>单条失败不中断，汇总后断言通过率（默认 ≥ 95%，可用
 * {@code -Dcomplex.sql.minPass=100} 收紧）。报告写到
 * {@code target/complex-sql-reports/l1-fails.tsv}。</p>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class ComplexSqlParseCorpusTest {

    /**
     * 全量 parse，按方言打印通过率，失败聚类写入报告。
     */
    @Test
    public void parsesAllDialects() {
        List<Case> all = ComplexSqlCorpus.loadAll();
        int pass = 0;
        List<String> failClasses = new ArrayList<String>();
        StringBuilder fails = new StringBuilder();
        fails.append("dialect\tid\terrorClass\tmessage\tsql\n");
        StringBuilder summary = new StringBuilder();
        ComplexSqlCorpus.Source[] sources = ComplexSqlCorpus.Source.values();
        int[] srcTotal = new int[sources.length];
        int[] srcPass = new int[sources.length];
        for (int i = 0; i < all.size(); i++) {
            Case item = all.get(i);
            int srcIdx = item.source().ordinal();
            srcTotal[srcIdx]++;
            try {
                SqlStatement stmt = SQL.parse(item.sql(), item.source().dialect());
                if (stmt == null || stmt.type() != SqlStatementType.SELECT) {
                    String msg = stmt == null ? "null statement"
                            : "type=" + stmt.type();
                    recordFail(fails, failClasses, item, "PARSE_NOT_SELECT", msg);
                } else {
                    pass++;
                    srcPass[srcIdx]++;
                }
            } catch (SqlParseException e) {
                recordFail(fails, failClasses, item, ComplexSqlReports.parseErrorClass(e.getMessage()),
                        e.getMessage());
            } catch (RuntimeException e) {
                recordFail(fails, failClasses, item, "PARSE_RUNTIME",
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        double rate = all.isEmpty() ? 0.0 : 100.0 * pass / all.size();
        summary.append("L1 parse total=").append(all.size())
                .append(" pass=").append(pass)
                .append(" fail=").append(all.size() - pass)
                .append(" rate=").append(String.format(Locale.ROOT, "%.2f", rate))
                .append("%\n");
        for (int i = 0; i < sources.length; i++) {
            double r = srcTotal[i] == 0 ? 0.0 : 100.0 * srcPass[i] / srcTotal[i];
            summary.append("  ").append(sources[i].name())
                    .append(" pass=").append(srcPass[i]).append('/').append(srcTotal[i])
                    .append(" rate=").append(String.format(Locale.ROOT, "%.2f", r))
                    .append("%\n");
        }
        summary.append("errorClass counts:\n");
        Map<String, Integer> counts = ComplexSqlReports.countClasses(failClasses);
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            summary.append("  ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        ComplexSqlReports.write("l1-fails.tsv", fails.toString());
        ComplexSqlReports.write("l1-summary.txt", summary.toString());
        System.out.print(summary);
        double minPass = minPassPercent();
        assertTrue("L1 parse rate " + rate + "% < " + minPass + "%; see target/complex-sql-reports/l1-fails.tsv",
                rate + 1e-9 >= minPass);
    }

    private static void recordFail(StringBuilder fails, List<String> failClasses, Case item,
                                   String errorClass, String message) {
        failClasses.add(errorClass);
        fails.append(item.source().name()).append('\t')
                .append(item.id()).append('\t')
                .append(ComplexSqlReports.tsv(errorClass)).append('\t')
                .append(ComplexSqlReports.tsv(ComplexSqlReports.trunc(message, 240))).append('\t')
                .append(ComplexSqlReports.tsv(ComplexSqlReports.trunc(item.sql(), 400)))
                .append('\n');
    }

    private static double minPassPercent() {
        String raw = System.getProperty("complex.sql.minPass", "95");
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 95.0;
        }
    }
}
