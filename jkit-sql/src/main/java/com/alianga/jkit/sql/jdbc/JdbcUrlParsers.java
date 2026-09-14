package com.alianga.jkit.sql.jdbc;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDBC URL 各协议解析器。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
final class JdbcUrlParsers {
    private static final Map<String, Parser> PARSERS = new HashMap<String, Parser>(16);

    static {
        Parser generic = new GenericParser();
        PARSERS.put("mysql", generic);
        PARSERS.put("mariadb", generic);
        PARSERS.put("tidb", generic);
        PARSERS.put("postgresql", generic);
        PARSERS.put("pgsql", generic);
        PARSERS.put("gaussdb", generic);
        PARSERS.put("opengauss", generic);
        PARSERS.put("kingbase", generic);
        PARSERS.put("kingbase8", generic);
        PARSERS.put("highgo", generic);
        PARSERS.put("dm", generic);
        PARSERS.put("gbase", generic);
        PARSERS.put("gbase8a", generic);
        PARSERS.put("clickhouse", generic);
        PARSERS.put("hive", generic);
        PARSERS.put("hive2", generic);
        PARSERS.put("h2", new H2Parser());
        PARSERS.put("hsqldb", new HsqldbParser());
        PARSERS.put("derby", new DerbyParser());
        PARSERS.put("oracle", new OracleParser());
        PARSERS.put("sqlserver", new SqlServerParser());
        PARSERS.put("sqlite", new SqliteParser());
    }

    private JdbcUrlParsers() {
    }

    static JdbcUrlInfo parse(String url) {
        return parserFor(url).parse(url);
    }

    private static Parser parserFor(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:sqlserver:") || u.startsWith("jdbc:microsoft:sqlserver:")) {
            return PARSERS.get("sqlserver");
        }
        if (u.startsWith("jdbc:oracle:") || u.startsWith("jdbc:alibaba:oracle:")) {
            return PARSERS.get("oracle");
        }
        String prefix = url.substring("jdbc:".length());
        int colon = prefix.indexOf(':');
        String base = colon < 0 ? prefix : prefix.substring(0, colon);
        Parser p = PARSERS.get(base.toLowerCase(Locale.ROOT));
        return p != null ? p : new GenericParser();
    }

    interface Parser {
        JdbcUrlInfo parse(String url);
    }

    static void parseAmpParams(String paramsStr, JdbcUrlInfo info) {
        parseDelimitedParams(paramsStr, '&', info);
    }

    static void parseSemicolonParams(String paramsStr, JdbcUrlInfo info) {
        parseDelimitedParams(paramsStr, ';', info);
    }

    static Integer parsePort(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void parseDelimitedParams(String paramsStr, char delim, JdbcUrlInfo info) {
        if (paramsStr == null || paramsStr.isEmpty()) {
            return;
        }
        int start = 0;
        while (start < paramsStr.length()) {
            int end = paramsStr.indexOf(delim, start);
            if (end < 0) {
                end = paramsStr.length();
            }
            String pair = paramsStr.substring(start, end).trim();
            if (!pair.isEmpty()) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    info.addParameter(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                } else {
                    info.addParameter(pair, "");
                }
            }
            start = end + 1;
        }
    }

    /**
     * {@code jdbc:type://host:port,host2:port2/db?k=v}，也认路径上的 {@code ;k=v}。
     */
    static final class GenericParser implements Parser {
        private static final Pattern PROTOCOL = Pattern.compile("^jdbc:([^:]+)(?::([^:]+))?://",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern BODY = Pattern.compile("^([^/?]+)(?:/([^?]*))?(?:\\?(.*))?$");

        /**
         * {@inheritDoc}
         */
        @Override
        public JdbcUrlInfo parse(String url) {
            JdbcUrlInfo info = new JdbcUrlInfo(url);
            Matcher proto = PROTOCOL.matcher(url);
            if (!proto.find()) {
                throw new IllegalArgumentException("Invalid JDBC URL protocol: " + url);
            }
            String main = proto.group(1).toLowerCase(Locale.ROOT);
            String sub = proto.group(2);
            info.setDbType(main);
            if (sub != null && !sub.isEmpty()) {
                info.addParameter("subProtocol", sub.toLowerCase(Locale.ROOT));
            }
            Matcher body = BODY.matcher(url.substring(proto.end()));
            if (!body.find()) {
                throw new IllegalArgumentException("Invalid JDBC URL body: " + url);
            }
            parseHostList(info, body.group(1));
            String path = body.group(2);
            String query = body.group(3);
            if (path != null && !path.isEmpty()) {
                int semi = path.indexOf(';');
                if (semi >= 0) {
                    parseSemicolonParams(path.substring(semi + 1), info);
                    path = path.substring(0, semi);
                }
                if (!path.isEmpty()) {
                    info.setDatabaseName(trimSlash(path));
                }
            }
            parseAmpParams(query, info);
            applyDatabaseNameAlias(info);
            return info;
        }

        private static void parseHostList(JdbcUrlInfo info, String hostsStr) {
            if (hostsStr == null || hostsStr.isEmpty()) {
                return;
            }
            String[] parts = hostsStr.split(",");
            for (int i = 0; i < parts.length; i++) {
                String node = parts[i].trim();
                if (node.isEmpty()) {
                    continue;
                }
                int colon = node.lastIndexOf(':');
                if (colon > 0 && colon < node.length() - 1
                        && isDigits(node.substring(colon + 1).trim())) {
                    info.addNode(node.substring(0, colon).trim(),
                            parsePort(node.substring(colon + 1)));
                } else {
                    info.addNode(node, null);
                }
            }
        }
    }

    /**
     * Oracle thin：SID、Service Name、RAC DESCRIPTION。
     */
    static final class OracleParser implements Parser {
        private static final Pattern RAC = Pattern.compile(
                "^jdbc:oracle:thin:@\\(DESCRIPTION=(.*)\\)(?:\\?(.*))?$",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern ADDRESS = Pattern.compile(
                "\\(ADDRESS=\\s*\\(\\s*PROTOCOL\\s*=\\s*TCP\\s*\\)\\s*\\(\\s*HOST\\s*=\\s*([^)]+)\\s*\\)"
                        + "\\s*\\(\\s*PORT\\s*=\\s*(\\d+)\\s*\\)\\s*\\)",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern SERVICE = Pattern.compile(
                "\\(CONNECT_DATA\\s*=\\s*\\([^)]*SERVICE_NAME\\s*=\\s*([^)]+)\\s*\\)",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern SID = Pattern.compile(
                "^jdbc:oracle:thin:@([^/:]+):(\\d+):([^:?/]+)(?:\\?(.*))?$",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern SVC = Pattern.compile(
                "^jdbc:oracle:thin:@//([^/:]+):(\\d+)/([^:?/]+)(?:\\?(.*))?$",
                Pattern.CASE_INSENSITIVE);

        /**
         * {@inheritDoc}
         */
        @Override
        public JdbcUrlInfo parse(String url) {
            JdbcUrlInfo info = new JdbcUrlInfo(url);
            info.setDbType("oracle");
            Matcher rac = RAC.matcher(url);
            if (rac.find()) {
                Matcher addr = ADDRESS.matcher(rac.group(1));
                boolean has = false;
                while (addr.find()) {
                    info.addNode(addr.group(1).trim(), parsePort(addr.group(2)));
                    has = true;
                }
                Matcher svc = SERVICE.matcher(rac.group(1));
                if (svc.find()) {
                    info.setDatabaseName(svc.group(1).trim());
                }
                parseAmpParams(rac.group(2), info);
                if (!has) {
                    throw new IllegalArgumentException("Oracle RAC URL has no ADDRESS: " + url);
                }
                return info;
            }
            Matcher m = SVC.matcher(url);
            if (m.find()) {
                info.addNode(m.group(1), parsePort(m.group(2)));
                info.setDatabaseName(m.group(3));
                parseAmpParams(m.group(4), info);
                return info;
            }
            m = SID.matcher(url);
            if (m.find()) {
                info.addNode(m.group(1), parsePort(m.group(2)));
                info.setDatabaseName(m.group(3));
                parseAmpParams(m.group(4), info);
                return info;
            }
            throw new IllegalArgumentException("Unsupported Oracle JDBC URL: " + url);
        }
    }

    /**
     * {@code jdbc:sqlserver://host\\instance:1433;databaseName=x}
     */
    static final class SqlServerParser implements Parser {
        private static final Pattern P = Pattern.compile(
                "^jdbc:(?:microsoft:)?sqlserver://([^;?]+)(?:;([^?]*))?(?:\\?(.*))?$",
                Pattern.CASE_INSENSITIVE);

        /**
         * {@inheritDoc}
         */
        @Override
        public JdbcUrlInfo parse(String url) {
            JdbcUrlInfo info = new JdbcUrlInfo(url);
            info.setDbType("sqlserver");
            Matcher m = P.matcher(url);
            if (!m.find()) {
                throw new IllegalArgumentException("Invalid SQL Server JDBC URL: " + url);
            }
            String[] nodes = m.group(1).split(",");
            for (int i = 0; i < nodes.length; i++) {
                String node = nodes[i].trim();
                if (node.isEmpty()) {
                    continue;
                }
                int colon = node.lastIndexOf(':');
                if (colon > 0 && isDigits(node.substring(colon + 1))) {
                    info.addNode(node.substring(0, colon), parsePort(node.substring(colon + 1)));
                } else {
                    info.addNode(node, null);
                }
            }
            parseSemicolonParams(m.group(2), info);
            parseAmpParams(m.group(3), info);
            applyDatabaseNameAlias(info);
            return info;
        }
    }

    /**
     * H2 mem / file / tcp / ssl / zip。
     */
    static final class H2Parser implements Parser {
        private static final Pattern TCP = Pattern.compile(
                "^jdbc:h2:(tcp|ssl)://([^/:?]+)(?::(\\d+))?/([^;?]+)(?:;?(.*))?$",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern ZIP = Pattern.compile(
                "^jdbc:h2:zip:([^;!?]+)!/([^;?]+)(?:;?(.*))?$",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern MEM = Pattern.compile(
                "^jdbc:h2:mem:([^;?]*)(?:;?(.*))?$",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern FILE = Pattern.compile(
                "^jdbc:h2:(?:file:)?([^;?]+)(?:;?(.*))?$",
                Pattern.CASE_INSENSITIVE);

        /**
         * {@inheritDoc}
         */
        @Override
        public JdbcUrlInfo parse(String url) {
            JdbcUrlInfo info = new JdbcUrlInfo(url);
            info.setDbType("h2");
            Matcher m = TCP.matcher(url);
            if (m.find()) {
                info.addNode(m.group(2), parsePort(m.group(3)));
                info.setDatabaseName(m.group(4));
                info.addParameter("connectionMode", m.group(1).toLowerCase(Locale.ROOT));
                parseSemicolonParams(m.group(5), info);
                return info;
            }
            m = ZIP.matcher(url);
            if (m.find()) {
                info.addNode(m.group(1), null);
                info.setDatabaseName(m.group(2));
                info.addParameter("connectionMode", "zip");
                parseSemicolonParams(m.group(3), info);
                return info;
            }
            m = MEM.matcher(url);
            if (m.find()) {
                String name = m.group(1).trim();
                info.setDatabaseName(name);
                info.addParameter("connectionMode", "mem");
                parseSemicolonParams(m.group(2), info);
                return info;
            }
            m = FILE.matcher(url);
            if (m.find()) {
                String file = m.group(1).trim();
                info.addNode(file, null);
                info.setDatabaseName(file);
                info.addParameter("connectionMode", "file");
                parseSemicolonParams(m.group(2), info);
                return info;
            }
            throw new IllegalArgumentException("Unsupported H2 JDBC URL: " + url);
        }
    }

    /**
     * Derby 嵌入 / 网络。
     */
    static final class DerbyParser implements Parser {
        private static final Pattern NET_MEM = Pattern.compile(
                "^jdbc:derby://([^/:?]+)(?::(\\d+))?/memory:([^;?]+)(?:;?(.*))?$",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern NET = Pattern.compile(
                "^jdbc:derby://([^/:?]+)(?::(\\d+))?/([^;?]+)(?:;?(.*))?$",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern EMBED = Pattern.compile(
                "^jdbc:derby:(?:([a-z]+):)?([^;?]+)(?:;?(.*))?$",
                Pattern.CASE_INSENSITIVE);

        /**
         * {@inheritDoc}
         */
        @Override
        public JdbcUrlInfo parse(String url) {
            JdbcUrlInfo info = new JdbcUrlInfo(url);
            info.setDbType("derby");
            Matcher m = NET_MEM.matcher(url);
            if (m.find()) {
                info.addNode(m.group(1), parsePort(m.group(2)));
                info.setDatabaseName("memory:" + m.group(3));
                info.addParameter("connectionMode", "network-memory");
                parseSemicolonParams(m.group(4), info);
                return info;
            }
            m = NET.matcher(url);
            if (m.find()) {
                info.addNode(m.group(1), parsePort(m.group(2)));
                info.setDatabaseName(m.group(3));
                info.addParameter("connectionMode", "network");
                parseSemicolonParams(m.group(4), info);
                return info;
            }
            m = EMBED.matcher(url);
            if (m.find()) {
                String sub = m.group(1);
                String db = m.group(2);
                if (sub != null) {
                    info.setDatabaseName(sub + ':' + db);
                    info.addParameter("connectionMode", "embedded-" + sub.toLowerCase(Locale.ROOT));
                } else {
                    info.setDatabaseName(db);
                    info.addParameter("connectionMode", "embedded");
                }
                parseSemicolonParams(m.group(3), info);
                return info;
            }
            throw new IllegalArgumentException("Unsupported Derby JDBC URL: " + url);
        }
    }

    /**
     * HSQLDB mem / file / res / hsql(s) / http(s)。
     */
    static final class HsqldbParser implements Parser {
        private static final Pattern SERVER = Pattern.compile(
                "^jdbc:hsqldb:(hsql|hsqls|http|https)://([^/:?]+)(?::(\\d+))?(/[^;?]+)(?:;?(.*))?$",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern MEM = Pattern.compile(
                "^jdbc:hsqldb:mem:([^;?]*)(?:;?(.*))?$", Pattern.CASE_INSENSITIVE);
        private static final Pattern FILE = Pattern.compile(
                "^jdbc:hsqldb:file:([^;?]+)(?:;?(.*))?$", Pattern.CASE_INSENSITIVE);
        private static final Pattern RES = Pattern.compile(
                "^jdbc:hsqldb:res:([^;?]+)(?:;?(.*))?$", Pattern.CASE_INSENSITIVE);

        /**
         * {@inheritDoc}
         */
        @Override
        public JdbcUrlInfo parse(String url) {
            JdbcUrlInfo info = new JdbcUrlInfo(url);
            info.setDbType("hsqldb");
            Matcher m = SERVER.matcher(url);
            if (m.find()) {
                info.addNode(m.group(2), parsePort(m.group(3)));
                info.setDatabaseName(m.group(4));
                info.addParameter("connectionMode", m.group(1).toLowerCase(Locale.ROOT));
                parseSemicolonParams(m.group(5), info);
                return info;
            }
            m = MEM.matcher(url);
            if (m.find()) {
                info.setDatabaseName(m.group(1).trim());
                info.addParameter("connectionMode", "mem");
                parseSemicolonParams(m.group(2), info);
                return info;
            }
            m = FILE.matcher(url);
            if (m.find()) {
                info.setDatabaseName(m.group(1).trim());
                info.addParameter("connectionMode", "file");
                parseSemicolonParams(m.group(2), info);
                return info;
            }
            m = RES.matcher(url);
            if (m.find()) {
                info.setDatabaseName(m.group(1).trim());
                info.addParameter("connectionMode", "res");
                parseSemicolonParams(m.group(2), info);
                return info;
            }
            throw new IllegalArgumentException("Unsupported HSQLDB JDBC URL: " + url);
        }
    }

    /**
     * {@code jdbc:sqlite:file.db} / {@code jdbc:sqlite://path}。
     */
    static final class SqliteParser implements Parser {
        /**
         * {@inheritDoc}
         */
        @Override
        public JdbcUrlInfo parse(String url) {
            JdbcUrlInfo info = new JdbcUrlInfo(url);
            info.setDbType("sqlite");
            String rest = url.substring("jdbc:sqlite:".length());
            int q = rest.indexOf('?');
            String path = q < 0 ? rest : rest.substring(0, q);
            if (path.startsWith("//")) {
                path = path.substring(2);
            }
            info.setDatabaseName(path);
            if (q >= 0) {
                parseAmpParams(rest.substring(q + 1), info);
            }
            return info;
        }
    }

    static void applyDatabaseNameAlias(JdbcUrlInfo info) {
        if (info.getDatabaseName() != null && !info.getDatabaseName().isEmpty()) {
            return;
        }
        String db = info.parameterIgnoreCase("databaseName");
        if (db == null) {
            db = info.parameterIgnoreCase("database");
        }
        if (db == null) {
            db = info.parameterIgnoreCase("dbname");
        }
        if (db != null) {
            info.setDatabaseName(db);
        }
    }

    static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static String trimSlash(String path) {
        if (path.endsWith("/") && path.length() > 1) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
