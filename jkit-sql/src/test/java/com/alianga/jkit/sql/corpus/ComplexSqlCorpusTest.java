package com.alianga.jkit.sql.corpus;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.corpus.ComplexSqlCorpus.Case;
import com.alianga.jkit.sql.corpus.ComplexSqlCorpus.Source;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 复杂 SQL 语料切条：四文件各 300 条、编号对齐、代表题能抽出正文。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class ComplexSqlCorpusTest {

    /**
     * 四方言各 300 条，编号 001–300 无重复。
     */
    @Test
    public void eachDialectHas300AlignedIds() {
        Source[] sources = Source.values();
        for (int i = 0; i < sources.length; i++) {
            List<Case> cases = ComplexSqlCorpus.load(sources[i]);
            assertEquals(sources[i].name(), 300, cases.size());
            Set<String> ids = new HashSet<String>(300);
            for (int j = 0; j < cases.size(); j++) {
                Case item = cases.get(j);
                assertEquals(padId(j + 1), item.id());
                assertTrue(item.id(), ids.add(item.id()));
                assertFalse(item.id() + " empty sql", item.sql().isEmpty());
                assertFalse(item.id() + " still has GO", containsBareGo(item.sql()));
                assertFalse(item.id() + " leading comment", item.sql().trim().startsWith("--"));
                assertFalse(item.id() + " trailing semicolon", item.sql().trim().endsWith(";"));
                assertTrue(item.technique(), item.technique().length() > 0);
                assertTrue(item.domain(), item.domain().length() > 0);
                assertTrue(item.title(), item.title().length() > 0);
            }
            assertEquals(sources[i].name(), "001", cases.get(0).id());
            assertEquals(sources[i].name(), "300", cases.get(299).id());
        }
    }

    /**
     * {@link SqlDialect#ORACLE12} 而不是经典 11g {@link SqlDialect#ORACLE}。
     */
    @Test
    public void oracleSourceUsesOracle12() {
        assertEquals(SqlDialect.ORACLE12, Source.ORACLE.dialect());
        assertEquals(SqlDialect.MYSQL, Source.MYSQL.dialect());
        assertEquals(SqlDialect.POSTGRES, Source.POSTGRES.dialect());
        assertEquals(SqlDialect.SQLSERVER, Source.SQLSERVER.dialect());
    }

    /**
     * 同一编号四方言对齐，001 / 022 / 211 能抽出 CTE 正文。
     */
    @Test
    public void alignedIdsAndRepresentativeBodies() {
        Map<String, Map<Source, Case>> aligned = ComplexSqlCorpus.loadAligned();
        assertEquals(300, aligned.size());
        String[] ids = {"001", "022", "211"};
        for (int i = 0; i < ids.length; i++) {
            Map<Source, Case> row = aligned.get(ids[i]);
            assertEquals(ids[i], 4, row.size());
            Source[] sources = Source.values();
            for (int j = 0; j < sources.length; j++) {
                Case item = row.get(sources[j]);
                String sql = item.sql().toUpperCase();
                assertTrue(item + " should start with WITH", sql.startsWith("WITH"));
                assertTrue(item + " too short: " + item.sql().length(), item.sql().length() > 200);
            }
        }
        Case mysql001 = aligned.get("001").get(Source.MYSQL);
        assertTrue(mysql001.sql(), mysql001.sql().toUpperCase().contains("LIMIT"));
        Case oracle001 = aligned.get("001").get(Source.ORACLE);
        assertTrue(oracle001.sql(), oracle001.sql().toUpperCase().contains("FETCH"));
        Case sqlserver001 = aligned.get("001").get(Source.SQLSERVER);
        assertTrue(sqlserver001.sql(), sqlserver001.sql().toUpperCase().contains("OFFSET"));
        Case mysql022 = aligned.get("022").get(Source.MYSQL);
        assertTrue(mysql022.sql(), mysql022.sql().toUpperCase().contains("RECURSIVE"));
        Case oracle022 = aligned.get("022").get(Source.ORACLE);
        assertTrue(oracle022.sql(), oracle022.sql().toUpperCase().contains("DATE_SEQ"));
        assertEquals("电商", mysql001.domain());
        assertTrue(mysql022.technique(), mysql022.technique().contains("递归"));
    }

    /**
     * {@link ComplexSqlCorpus#loadAll()} 共 1200 条。
     */
    @Test
    public void loadAllIs1200() {
        assertEquals(1200, ComplexSqlCorpus.loadAll().size());
    }

    private static String padId(int n) {
        if (n < 10) {
            return "00" + n;
        }
        if (n < 100) {
            return "0" + n;
        }
        return String.valueOf(n);
    }

    private static boolean containsBareGo(String sql) {
        String[] lines = sql.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if ("GO".equalsIgnoreCase(lines[i].trim())) {
                return true;
            }
        }
        return false;
    }
}
