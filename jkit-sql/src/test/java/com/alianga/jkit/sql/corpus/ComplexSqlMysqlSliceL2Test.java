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
 * 开发助手2 切片：MySQL 001–150 的 L2 往返（parse → toSqlString → parse）。
 * 全量 L2 已在 {@link ComplexSqlRoundTripTest}；本类只跑本切片并写独立报告，
 * 方便并行时互不覆盖。
 *
 * @author 开发助手2
 */
public class ComplexSqlMysqlSliceL2Test {
    private static final int FROM_ID = 1;
    private static final int TO_ID = 150;

    @Test
    public void mysql001To150RoundTrip() {
        List<Case> slice = loadSlice();
        assertEquals(150, slice.size());

        int parse2Fail = 0;
        int structFail = 0;
        int textMismatch = 0;
        List<String> failClasses = new ArrayList<String>();
        StringBuilder fails = new StringBuilder();
        fails.append("dialect\tid\tkind\terrorClass\tmessage\n");

        for (int i = 0; i < slice.size(); i++) {
            Case item = slice.get(i);
            SqlStatement stmt;
            try {
                stmt = SQL.parse(item.sql(), Source.MYSQL.dialect());
            } catch (SqlParseException e) {
                parse2Fail++;
                record(fails, failClasses, item, "PARSE1",
                        ComplexSqlReports.parseErrorClass(e.getMessage()), e.getMessage());
                continue;
            }
            String formatted;
            try {
                formatted = SQL.toSqlString(stmt, Source.MYSQL.dialect());
            } catch (RuntimeException e) {
                parse2Fail++;
                record(fails, failClasses, item, "FORMAT", "FORMAT_RUNTIME",
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            }
            SqlStatement stmt2;
            try {
                stmt2 = SQL.parse(formatted, Source.MYSQL.dialect());
            } catch (SqlParseException e) {
                parse2Fail++;
                record(fails, failClasses, item, "PARSE2",
                        ComplexSqlReports.parseErrorClass(e.getMessage()), e.getMessage());
                continue;
            }
            String drift = structureDrift(stmt, stmt2);
            if (drift != null) {
                structFail++;
                record(fails, failClasses, item, "STRUCT", "STRUCT_DRIFT", drift);
            }
            if (!normalize(item.sql()).equals(normalize(formatted))) {
                textMismatch++;
            }
        }

        StringBuilder summary = new StringBuilder();
        summary.append("L2 MySQL slice ").append(pad(FROM_ID)).append('-').append(pad(TO_ID))
                .append(" total=").append(slice.size())
                .append(" parse2Fail=").append(parse2Fail)
                .append(" structFail=").append(structFail)
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
        ComplexSqlReports.write("mysql-l2-001-150-fails.tsv", fails.toString());
        ComplexSqlReports.write("mysql-l2-001-150-summary.txt", summary.toString());
        System.out.print(summary);
        assertTrue("MySQL L2 re-parse failures " + parse2Fail, parse2Fail == 0);
        assertTrue("MySQL L2 structure drift " + structFail, structFail == 0);
    }

    private static List<Case> loadSlice() {
        List<Case> all = ComplexSqlCorpus.load(Source.MYSQL);
        List<Case> out = new ArrayList<Case>(TO_ID - FROM_ID + 1);
        for (int i = 0; i < all.size(); i++) {
            Case item = all.get(i);
            int id = Integer.parseInt(item.id());
            if (id >= FROM_ID && id <= TO_ID) {
                out.add(item);
            }
        }
        return out;
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
