package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlStatement;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * 从 {@code icell/common-model} 测试类收获的 SQL 语料。
 * 默认 MYSQL；含双引号表/列引用的走 ANSI；Oracle/达梦相关走 ORACLE。
 * 已知非 SQL / 截断 / 数字开头裸标识符等已从资源剔除，见 {@link CommonModelSqlKnownGapsTest}。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
@RunWith(Parameterized.class)
public class CommonModelSqlCorpusTest {
    private final String dialect;
    private final String sql;

    /**
     * @param dialect 方言名
     * @param sql SQL
     */
    public CommonModelSqlCorpusTest(String dialect, String sql) {
        this.dialect = dialect;
        this.sql = sql;
    }

    /**
     * @return 语料用例
     * @throws Exception 读资源失败
     */
    @Parameterized.Parameters(name = "{0} :: {1}")
    public static Collection<Object[]> data() throws Exception {
        List<Object[]> rows = new ArrayList<Object[]>();
        InputStream in = CommonModelSqlCorpusTest.class.getResourceAsStream("/common-model-sql-corpus.txt");
        assertNotNull("missing classpath resource common-model-sql-corpus.txt", in);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                int sep = line.indexOf(" | ");
                if (sep < 0) {
                    continue;
                }
                String dialect = line.substring(0, sep).trim();
                String sql = line.substring(sep + 3)
                        .replace("\\n", "\n")
                        .replace("\\\\", "\\");
                rows.add(new Object[] {dialect, sql});
            }
        } finally {
            reader.close();
        }
        return rows;
    }

    /**
     * 语料中的每条 SQL 在对应方言下可解析。
     */
    @Test
    public void parses() {
        String s = sql.trim();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        SqlStatement stmt = SQL.parse(s, SqlDialect.fromName(dialect));
        assertNotNull(sql, stmt);
    }
}
