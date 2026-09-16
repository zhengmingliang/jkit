package com.alianga.jkit.sql.corpus;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlParseException;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import com.alianga.jkit.sql.corpus.ComplexSqlCorpus.Case;
import com.alianga.jkit.sql.corpus.ComplexSqlCorpus.Source;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 方言切片 L2 往返的公共实现：{@code parse → toSqlString → parse}。
 *
 * <p>全量 L2 在 {@link ComplexSqlRoundTripTest}；切片类只声明方言与编号区间，
 * 各写各的报告，方便并行时互不覆盖。断言与全量一致：再解析 0 失败、
 * 结构 0 漂移、括号 0 丢失。</p>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public abstract class AbstractComplexSqlSliceL2Test {

    /**
     * @return 切片所属方言
     */
    protected abstract Source source();

    /**
     * @return 起始编号（含）
     */
    protected abstract int fromId();

    /**
     * @return 结束编号（含）
     */
    protected abstract int toId();

    /**
     * 本切片回写后再解析必须成功，结构不漂移、括号不减少。
     */
    @Test
    public void sliceRoundTrip() {
        List<Case> slice = loadSlice();
        assertEquals(150, slice.size());

        int parse2Fail = 0;
        int structFail = 0;
        int parenLoss = 0;
        int textMismatch = 0;
        List<String> failClasses = new ArrayList<String>();
        StringBuilder fails = new StringBuilder();
        fails.append("dialect\tid\tkind\terrorClass\tmessage\n");

        for (int i = 0; i < slice.size(); i++) {
            Case item = slice.get(i);
            SqlStatement stmt;
            try {
                stmt = SQL.parse(item.sql(), source().dialect());
            } catch (SqlParseException e) {
                parse2Fail++;
                record(fails, failClasses, item, "PARSE1",
                        ComplexSqlReports.parseErrorClass(e.getMessage()), e.getMessage());
                continue;
            } catch (RuntimeException e) {
                parse2Fail++;
                record(fails, failClasses, item, "PARSE1", "PARSE_RUNTIME",
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            }
            String formatted;
            try {
                formatted = SQL.toSqlString(stmt, source().dialect());
            } catch (RuntimeException e) {
                parse2Fail++;
                record(fails, failClasses, item, "FORMAT", "FORMAT_RUNTIME",
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            }
            SqlStatement stmt2;
            try {
                stmt2 = SQL.parse(formatted, source().dialect());
            } catch (SqlParseException e) {
                parse2Fail++;
                record(fails, failClasses, item, "PARSE2",
                        ComplexSqlReports.parseErrorClass(e.getMessage()), e.getMessage());
                continue;
            } catch (RuntimeException e) {
                parse2Fail++;
                record(fails, failClasses, item, "PARSE2", "PARSE_RUNTIME",
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            }
            String drift = structureDrift(stmt, stmt2);
            if (drift != null) {
                structFail++;
                record(fails, failClasses, item, "STRUCT", "STRUCT_DRIFT", drift);
            }
            int before = ComplexSqlReports.significantParens(item.sql());
            int after = ComplexSqlReports.significantParens(formatted);
            if (after < before) {
                parenLoss++;
                record(fails, failClasses, item, "PAREN", "PAREN_LOSS",
                        "paren count " + before + " -> " + after);
            }
            if (!normalize(item.sql()).equals(normalize(formatted))) {
                textMismatch++;
            }
        }

        String prefix = reportPrefix();
        StringBuilder summary = new StringBuilder();
        summary.append("L2 ").append(source().name()).append(" slice ")
                .append(pad(fromId())).append('-').append(pad(toId()))
                .append(" total=").append(slice.size())
                .append(" parse2Fail=").append(parse2Fail)
                .append(" structFail=").append(structFail)
                .append(" parenLoss=").append(parenLoss)
                .append(" textMismatch=").append(textMismatch)
                .append(" textMatchRate=")
                .append(String.format(Locale.ROOT, "%.2f",
                        100.0 * (slice.size() - textMismatch) / slice.size()))
                .append("%\n");
        Map<String, Integer> counts = ComplexSqlReports.countClasses(failClasses);
        summary.append("errorClass counts:\n");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            summary.append("  ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        ComplexSqlReports.write(prefix + "-fails.tsv", fails.toString());
        ComplexSqlReports.write(prefix + "-summary.txt", summary.toString());
        System.out.print(summary);
        assertTrue(source() + " L2 re-parse failures " + parse2Fail, parse2Fail == 0);
        assertTrue(source() + " L2 structure drift " + structFail, structFail == 0);
        assertTrue(source() + " L2 paren loss " + parenLoss, parenLoss == 0);
    }

    private List<Case> loadSlice() {
        List<Case> all = ComplexSqlCorpus.load(source());
        List<Case> out = new ArrayList<Case>(toId() - fromId() + 1);
        for (int i = 0; i < all.size(); i++) {
            Case item = all.get(i);
            int id = Integer.parseInt(item.id());
            if (id >= fromId() && id <= toId()) {
                out.add(item);
            }
        }
        return out;
    }

    private String reportPrefix() {
        return source().name().toLowerCase(Locale.ROOT) + "-l2-" + pad(fromId()) + "-" + pad(toId());
    }

    private static void record(StringBuilder fails, List<String> failClasses, Case item,
                               String kind, String errorClass, String message) {
        failClasses.add(errorClass);
        fails.append(item.source().name()).append('\t')
                .append(item.id()).append('\t')
                .append(kind).append('\t')
                .append(ComplexSqlReports.tsv(errorClass)).append('\t')
                .append(ComplexSqlReports.tsv(ComplexSqlReports.trunc(message, 300)))
                .append('\n');
    }

    private static String structureDrift(SqlStatement a, SqlStatement b) {
        if (a.type() != b.type() || a.type() != SqlStatementType.SELECT) {
            return "type " + a.type() + " -> " + b.type();
        }
        if (a.isReadOnly() != b.isReadOnly()) {
            return "readOnly " + a.isReadOnly() + " -> " + b.isReadOnly();
        }
        if (a.withItems().size() != b.withItems().size()) {
            return "cteCount " + a.withItems().size() + " -> " + b.withItems().size();
        }
        if (a.withRecursive() != b.withRecursive()) {
            return "withRecursive " + a.withRecursive() + " -> " + b.withRecursive();
        }
        List<String> ta = normalizeTables(SQL.tables(a));
        List<String> tb = normalizeTables(SQL.tables(b));
        if (!ta.equals(tb)) {
            return "tables " + ta + " -> " + tb;
        }
        if (hasLimit(a) != hasLimit(b)) {
            return "limit " + hasLimit(a) + " -> " + hasLimit(b);
        }
        String deep = ComplexSqlReports.deepStructureDrift(a, b);
        if (deep != null) {
            return "deep " + deep;
        }
        return null;
    }

    private static boolean hasLimit(SqlStatement stmt) {
        if (!(stmt instanceof SqlSelect)) {
            return false;
        }
        SqlSelect select = (SqlSelect) stmt;
        if (select.limit() != null || select.top() != null) {
            return true;
        }
        if (select.union() != null) {
            return hasLimit(select.union());
        }
        return false;
    }

    private static List<String> normalizeTables(List<String> tables) {
        List<String> out = new ArrayList<String>(tables.size());
        for (int i = 0; i < tables.size(); i++) {
            out.add(tables.get(i).toLowerCase(Locale.ROOT));
        }
        Collections.sort(out);
        return out;
    }

    private static String normalize(String s) {
        String r = s.replaceAll("--[^\\n]*", " ").replaceAll("/\\*[\\s\\S]*?\\*/", " ");
        r = r.replaceAll("(?i)\\s+AS\\s+", " ").replaceAll("(?i)\\bAS\\b", " ");
        r = r.replaceAll("(?i)\\bINNER\\b", " ").replaceAll("(?i)\\bOUTER\\b", " ");
        r = r.replaceAll("(?i)\\bASC\\b", " ");
        r = r.replaceAll("\\s+", "");
        return r.toUpperCase(Locale.ROOT);
    }

    private static String pad(int n) {
        if (n < 10) {
            return "00" + n;
        }
        if (n < 100) {
            return "0" + n;
        }
        return String.valueOf(n);
    }
}
