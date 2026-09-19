package com.alianga.jkit.sql.corpus;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlParseException;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import com.alianga.jkit.sql.corpus.ComplexSqlCorpus.Case;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * L2：同方言 {@code parse → toSqlString → parse}。结构保真是硬门槛
 * （类型 / 表名 / CTE 个数 / 分页有无）；文本归一化保真只出报告。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class ComplexSqlRoundTripTest {

    /**
     * 1200 条回写后再解析必须成功，结构不漂移。
     */
    @Test
    public void roundTripKeepsStructure() {
        List<Case> all = ComplexSqlCorpus.loadAll();
        int parse2Fail = 0;
        int structFail = 0;
        int textMismatch = 0;
        int parenLoss = 0;
        List<String> failClasses = new ArrayList<String>();
        StringBuilder fails = new StringBuilder();
        fails.append("dialect\tid\tkind\terrorClass\tmessage\n");
        for (int i = 0; i < all.size(); i++) {
            Case item = all.get(i);
            SqlStatement stmt;
            try {
                stmt = SQL.parse(item.sql(), item.source().dialect());
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
                formatted = SQL.toSqlString(stmt, item.source().dialect());
            } catch (RuntimeException e) {
                parse2Fail++;
                record(fails, failClasses, item, "FORMAT", "FORMAT_RUNTIME",
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                continue;
            }
            SqlStatement stmt2;
            try {
                stmt2 = SQL.parse(formatted, item.source().dialect());
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
            // 有效括号只能多不能少：少一对就可能把 (a - b) / c 变成 a - b / c。
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
        int total = all.size();
        StringBuilder summary = new StringBuilder();
        summary.append("L2 roundtrip total=").append(total)
                .append(" parse2Fail=").append(parse2Fail)
                .append(" structFail=").append(structFail)
                .append(" parenLoss=").append(parenLoss)
                .append(" textMismatch=").append(textMismatch)
                .append(" textMatchRate=")
                .append(String.format(Locale.ROOT, "%.2f",
                        100.0 * (total - textMismatch) / total))
                .append("%\n");
        Map<String, Integer> counts = ComplexSqlReports.countClasses(failClasses);
        summary.append("errorClass counts:\n");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            summary.append("  ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        ComplexSqlReports.write("l2-fails.tsv", fails.toString());
        ComplexSqlReports.write("l2-summary.txt", summary.toString());
        System.out.print(summary);
        assertTrue("L2 re-parse failures " + parse2Fail + " (must be 0)", parse2Fail == 0);
        assertTrue("L2 structure drift " + structFail + " (must be 0)", structFail == 0);
        assertTrue("L2 paren loss " + parenLoss + " (must be 0); see l2-fails.tsv", parenLoss == 0);
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
        boolean la = hasLimit(a);
        boolean lb = hasLimit(b);
        if (la != lb) {
            return "limit " + la + " -> " + lb;
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
}
