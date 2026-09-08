package com.alianga.jkit.sql;

/**
 * 关键字开地址哈希表。查找时对源 {@code char[]} 做大小写折叠，不分配字符串。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
final class SqlKeywords {
    private static final int MASK = 1023;
    private static final String[] KEYS = new String[MASK + 1];
    private static final SqlTokenType[] VALS = new SqlTokenType[MASK + 1];

    static {
        put("SELECT", SqlTokenType.SELECT);
        put("INSERT", SqlTokenType.INSERT);
        put("UPDATE", SqlTokenType.UPDATE);
        put("DELETE", SqlTokenType.DELETE);
        put("MERGE", SqlTokenType.MERGE);
        put("REPLACE", SqlTokenType.REPLACE);
        put("CREATE", SqlTokenType.CREATE);
        put("DROP", SqlTokenType.DROP);
        put("ALTER", SqlTokenType.ALTER);
        put("TRUNCATE", SqlTokenType.TRUNCATE);
        put("RENAME", SqlTokenType.RENAME);
        put("EXPLAIN", SqlTokenType.EXPLAIN);
        put("DESCRIBE", SqlTokenType.DESCRIBE);
        put("DESC", SqlTokenType.DESC);
        put("SHOW", SqlTokenType.SHOW);
        put("USE", SqlTokenType.USE);
        put("SET", SqlTokenType.SET);
        put("CALL", SqlTokenType.CALL);
        put("WITH", SqlTokenType.WITH);
        put("RECURSIVE", SqlTokenType.RECURSIVE);
        put("FROM", SqlTokenType.FROM);
        put("WHERE", SqlTokenType.WHERE);
        put("GROUP", SqlTokenType.GROUP);
        put("HAVING", SqlTokenType.HAVING);
        put("ORDER", SqlTokenType.ORDER);
        put("BY", SqlTokenType.BY);
        put("LIMIT", SqlTokenType.LIMIT);
        put("OFFSET", SqlTokenType.OFFSET);
        put("FETCH", SqlTokenType.FETCH);
        put("FIRST", SqlTokenType.FIRST);
        put("NEXT", SqlTokenType.NEXT);
        put("ONLY", SqlTokenType.ONLY);
        put("ROW", SqlTokenType.ROW);
        put("ROWS", SqlTokenType.ROWS);
        put("PERCENT", SqlTokenType.PERCENT);
        put("TIES", SqlTokenType.TIES);
        put("TOP", SqlTokenType.TOP);
        put("JOIN", SqlTokenType.JOIN);
        put("INNER", SqlTokenType.INNER);
        put("LEFT", SqlTokenType.LEFT);
        put("RIGHT", SqlTokenType.RIGHT);
        put("FULL", SqlTokenType.FULL);
        put("CROSS", SqlTokenType.CROSS);
        put("OUTER", SqlTokenType.OUTER);
        put("NATURAL", SqlTokenType.NATURAL);
        put("STRAIGHT_JOIN", SqlTokenType.STRAIGHT_JOIN);
        put("ON", SqlTokenType.ON);
        put("USING", SqlTokenType.USING);
        put("UNION", SqlTokenType.UNION);
        put("ALL", SqlTokenType.ALL);
        put("DISTINCT", SqlTokenType.DISTINCT);
        put("INTERSECT", SqlTokenType.INTERSECT);
        put("EXCEPT", SqlTokenType.EXCEPT);
        put("MINUS", SqlTokenType.MINUS);
        put("AS", SqlTokenType.AS);
        put("INTO", SqlTokenType.INTO);
        put("VALUES", SqlTokenType.VALUES);
        put("VALUE", SqlTokenType.VALUE);
        put("AND", SqlTokenType.AND);
        put("OR", SqlTokenType.OR);
        put("NOT", SqlTokenType.NOT);
        put("XOR", SqlTokenType.XOR);
        put("IN", SqlTokenType.IN);
        put("IS", SqlTokenType.IS);
        put("NULL", SqlTokenType.NULL);
        put("LIKE", SqlTokenType.LIKE);
        put("ILIKE", SqlTokenType.ILIKE);
        put("RLIKE", SqlTokenType.RLIKE);
        put("REGEXP", SqlTokenType.REGEXP);
        put("BETWEEN", SqlTokenType.BETWEEN);
        put("EXISTS", SqlTokenType.EXISTS);
        put("ANY", SqlTokenType.ANY);
        put("SOME", SqlTokenType.SOME);
        put("CASE", SqlTokenType.CASE);
        put("WHEN", SqlTokenType.WHEN);
        put("THEN", SqlTokenType.THEN);
        put("ELSE", SqlTokenType.ELSE);
        put("END", SqlTokenType.END);
        put("CAST", SqlTokenType.CAST);
        put("CONVERT", SqlTokenType.CONVERT);
        put("ASC", SqlTokenType.ASC);
        put("TABLE", SqlTokenType.TABLE);
        put("DATABASE", SqlTokenType.DATABASE);
        put("SCHEMA", SqlTokenType.SCHEMA);
        put("INDEX", SqlTokenType.INDEX);
        put("VIEW", SqlTokenType.VIEW);
        put("SEQUENCE", SqlTokenType.SEQUENCE);
        put("PRIMARY", SqlTokenType.PRIMARY);
        put("KEY", SqlTokenType.KEY);
        put("FOREIGN", SqlTokenType.FOREIGN);
        put("REFERENCES", SqlTokenType.REFERENCES);
        put("CONSTRAINT", SqlTokenType.CONSTRAINT);
        put("UNIQUE", SqlTokenType.UNIQUE);
        put("CHECK", SqlTokenType.CHECK);
        put("DEFAULT", SqlTokenType.DEFAULT);
        put("IF", SqlTokenType.IF);
        put("TEMPORARY", SqlTokenType.TEMPORARY);
        put("TEMP", SqlTokenType.TEMP);
        put("FOR", SqlTokenType.FOR);
        put("NOWAIT", SqlTokenType.NOWAIT);
        put("SKIP", SqlTokenType.SKIP);
        put("LOCKED", SqlTokenType.LOCKED);
        put("SHARE", SqlTokenType.SHARE);
        put("MODE", SqlTokenType.MODE);
        put("OF", SqlTokenType.OF);
        put("RETURNING", SqlTokenType.RETURNING);
        put("OUTPUT", SqlTokenType.OUTPUT);
        put("DUPLICATE", SqlTokenType.DUPLICATE);
        put("CONFLICT", SqlTokenType.CONFLICT);
        put("DO", SqlTokenType.DO);
        put("NOTHING", SqlTokenType.NOTHING);
        put("PARTITION", SqlTokenType.PARTITION);
        put("WINDOW", SqlTokenType.WINDOW);
        put("OVER", SqlTokenType.OVER);
        put("ROWNUM", SqlTokenType.ROWNUM);
        put("DUAL", SqlTokenType.DUAL);
        put("INTERVAL", SqlTokenType.INTERVAL);
        put("TRUE", SqlTokenType.TRUE);
        put("FALSE", SqlTokenType.FALSE);
        put("UNKNOWN", SqlTokenType.UNKNOWN);
        put("CHARACTER", SqlTokenType.CHARACTER);
        put("CHAR", SqlTokenType.CHAR);
        put("VARCHAR", SqlTokenType.VARCHAR);
        put("DECIMAL", SqlTokenType.DECIMAL);
        put("NUMERIC", SqlTokenType.NUMERIC);
        put("FLOAT", SqlTokenType.FLOAT);
        put("DOUBLE", SqlTokenType.DOUBLE);
        put("REAL", SqlTokenType.REAL);
        put("PRECISION", SqlTokenType.PRECISION);
        put("BOOLEAN", SqlTokenType.BOOLEAN);
        put("DATE", SqlTokenType.DATE);
        put("TIME", SqlTokenType.TIME);
        put("TIMESTAMP", SqlTokenType.TIMESTAMP);
        put("DATETIME", SqlTokenType.DATETIME);
        put("UNSIGNED", SqlTokenType.UNSIGNED);
        put("ZEROFILL", SqlTokenType.ZEROFILL);
        put("AUTO_INCREMENT", SqlTokenType.AUTO_INCREMENT);
        put("COMMENT", SqlTokenType.COMMENT);
        put("ENGINE", SqlTokenType.ENGINE);
        put("CHARSET", SqlTokenType.CHARSET);
        put("COLLATE", SqlTokenType.COLLATE);
        put("CASCADE", SqlTokenType.CASCADE);
        put("RESTRICT", SqlTokenType.RESTRICT);
        put("MATERIALIZED", SqlTokenType.MATERIALIZED);
        put("LATERAL", SqlTokenType.LATERAL);
        put("APPLY", SqlTokenType.APPLY);
        put("ARRAY", SqlTokenType.ARRAY);
        put("CURRENT", SqlTokenType.CURRENT);
        put("FOLLOWING", SqlTokenType.FOLLOWING);
        put("PRECEDING", SqlTokenType.PRECEDING);
        put("UNBOUNDED", SqlTokenType.UNBOUNDED);
        put("FORCE", SqlTokenType.FORCE);
        put("IGNORE", SqlTokenType.IGNORE);
        put("LOCK", SqlTokenType.LOCK);
        put("UNLOCK", SqlTokenType.UNLOCK);
        put("TABLES", SqlTokenType.TABLES);
        put("OPTIMIZE", SqlTokenType.OPTIMIZE);
        put("REPAIR", SqlTokenType.REPAIR);
        put("GRANT", SqlTokenType.GRANT);
        put("REVOKE", SqlTokenType.REVOKE);
        put("TO", SqlTokenType.TO);
        put("USER", SqlTokenType.USER);
        put("SESSION", SqlTokenType.SESSION);
        put("GLOBAL", SqlTokenType.GLOBAL);
        put("LOCAL", SqlTokenType.LOCAL);
        put("NAMES", SqlTokenType.NAMES);
        put("ZONE", SqlTokenType.ZONE);
        put("WITHOUT", SqlTokenType.WITHOUT);
        put("VARYING", SqlTokenType.VARYING);
        put("BINARY", SqlTokenType.BINARY);
        put("BOTH", SqlTokenType.BOTH);
        put("LEADING", SqlTokenType.LEADING);
        put("TRAILING", SqlTokenType.TRAILING);
        put("EXTRACT", SqlTokenType.EXTRACT);
        put("POSITION", SqlTokenType.POSITION);
        put("SUBSTRING", SqlTokenType.SUBSTRING);
        put("TRIM", SqlTokenType.TRIM);
        put("ROLLUP", SqlTokenType.ROLLUP);
        put("CUBE", SqlTokenType.CUBE);
        put("GROUPING", SqlTokenType.GROUPING);
        put("SETS", SqlTokenType.SETS);
        put("ESCAPE", SqlTokenType.ESCAPE);
        put("SIMILAR", SqlTokenType.SIMILAR);
        put("DIV", SqlTokenType.DIV);
        put("MOD", SqlTokenType.MOD);
        put("CONNECT", SqlTokenType.CONNECT);
        put("START", SqlTokenType.START);
        put("SIBLINGS", SqlTokenType.SIBLINGS);
        put("PIVOT", SqlTokenType.PIVOT);
        put("UNPIVOT", SqlTokenType.UNPIVOT);
        put("MATCH", SqlTokenType.MATCH);
        put("AGAINST", SqlTokenType.AGAINST);
        put("MATCHED", SqlTokenType.MATCHED);
        put("SOURCE", SqlTokenType.SOURCE);
        put("TARGET", SqlTokenType.TARGET);
        put("ANALYZE", SqlTokenType.ANALYZE);
        put("FORMAT", SqlTokenType.FORMAT);
        put("COLUMNS", SqlTokenType.COLUMNS);
        put("DATABASES", SqlTokenType.DATABASES);
        put("STATUS", SqlTokenType.STATUS);
        put("PROCESSLIST", SqlTokenType.PROCESSLIST);
        put("VARIABLES", SqlTokenType.VARIABLES);
        put("DISTINCTROW", SqlTokenType.DISTINCTROW);
        put("HIGH_PRIORITY", SqlTokenType.HIGH_PRIORITY);
        put("SQL_CALC_FOUND_ROWS", SqlTokenType.SQL_CALC_FOUND_ROWS);
        put("LOW_PRIORITY", SqlTokenType.LOW_PRIORITY);
        put("DELAYED", SqlTokenType.DELAYED);
        put("QUICK", SqlTokenType.QUICK);
        put("PROCEDURE", SqlTokenType.PROCEDURE);
        put("FUNCTION", SqlTokenType.FUNCTION);
        put("TRIGGER", SqlTokenType.TRIGGER);
        put("EVENT", SqlTokenType.EVENT);
        put("BEGIN", SqlTokenType.BEGIN);
        put("DECLARE", SqlTokenType.DECLARE);
        put("VACUUM", SqlTokenType.VACUUM);
        put("COPY", SqlTokenType.COPY);
        put("HANDLER", SqlTokenType.HANDLER);
        put("PREPARE", SqlTokenType.PREPARE);
        put("EXECUTE", SqlTokenType.EXECUTE);
        put("DEALLOCATE", SqlTokenType.DEALLOCATE);
        put("GO", SqlTokenType.GO);
    }

    private SqlKeywords() {
    }

    private static void put(String key, SqlTokenType type) {
        int i = hash(key) & MASK;
        while (KEYS[i] != null) {
            i = (i + 1) & MASK;
        }
        KEYS[i] = key;
        VALS[i] = type;
    }

    private static int hash(String key) {
        int h = 0;
        for (int i = 0; i < key.length(); i++) {
            h = 31 * h + key.charAt(i);
        }
        return h;
    }

    /**
     * 在源缓冲上查找关键字，未命中返回 {@link SqlTokenType#IDENT}。
     *
     * @param src 源字符
     * @param off 起始
     * @param len 长度
     * @return 关键字或 IDENT
     */
    static SqlTokenType lookup(char[] src, int off, int len) {
        int h = 0;
        for (int i = 0; i < len; i++) {
            char c = src[off + i];
            if (c >= 'a' && c <= 'z') {
                c = (char) (c - 32);
            }
            h = 31 * h + c;
        }
        int i = h & MASK;
        while (true) {
            String k = KEYS[i];
            if (k == null) {
                return SqlTokenType.IDENT;
            }
            if (k.length() == len && equalsIgnoreCase(k, src, off, len)) {
                return VALS[i];
            }
            i = (i + 1) & MASK;
        }
    }

    private static boolean equalsIgnoreCase(String k, char[] src, int off, int len) {
        for (int i = 0; i < len; i++) {
            char c = src[off + i];
            if (c >= 'a' && c <= 'z') {
                c = (char) (c - 32);
            }
            if (c != k.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
