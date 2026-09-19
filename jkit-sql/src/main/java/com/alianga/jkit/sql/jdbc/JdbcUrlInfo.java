package com.alianga.jkit.sql.jdbc;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC URL 解析结果：协议、节点、库名、schema、参数。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class JdbcUrlInfo {
    private final String rawUrl;
    private String dbType;
    private String host;
    private Integer port;
    private final List<NodeInfo> nodes;
    private String databaseName;
    private String schema;
    private final Map<String, String> parameters;

    /**
     * @param rawUrl 原始 URL
     */
    public JdbcUrlInfo(String rawUrl) {
        this.rawUrl = rawUrl;
        this.nodes = new ArrayList<NodeInfo>(2);
        this.parameters = new LinkedHashMap<String, String>(4);
    }

    /**
     * @return 原始 URL
     */
    public String getRawUrl() {
        return rawUrl;
    }

    /**
     * @return 协议类型，如 {@code mysql}、{@code postgresql}
     */
    public String getDbType() {
        return dbType;
    }

    /**
     * 第一个节点的主机；无节点时为 null。
     *
     * @return 主机
     */
    public String getHost() {
        if (host == null && !nodes.isEmpty()) {
            return nodes.get(0).getHost();
        }
        return host;
    }

    /**
     * 第一个节点的端口；无节点或未写端口时为 null。
     *
     * @return 端口
     */
    public Integer getPort() {
        if (port == null && !nodes.isEmpty()) {
            return nodes.get(0).getPort();
        }
        return port;
    }

    /**
     * @return 全部节点（只读）
     */
    public List<NodeInfo> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    /**
     * @return 库名 / SID / Service Name，可空
     */
    public String getDatabaseName() {
        return databaseName;
    }

    /**
     * @return schema，可空
     */
    public String getSchema() {
        return schema;
    }

    /**
     * @return 查询参数（只读，插入顺序）
     */
    public Map<String, String> getParameters() {
        return Collections.unmodifiableMap(parameters);
    }

    /**
     * @return {@code k=v&amp;k2=v2} 形式的参数串，无参数时为空串
     */
    public String getUrlParameters() {
        if (parameters.isEmpty()) {
            if (rawUrl != null) {
                int q = rawUrl.indexOf('?');
                return q >= 0 && q < rawUrl.length() - 1 ? rawUrl.substring(q + 1) : "";
            }
            return "";
        }
        StringBuilder sb = new StringBuilder(parameters.size() * 16);
        boolean first = true;
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(encode(e.getValue()));
        }
        return sb.toString();
    }

    void setDbType(String dbType) {
        this.dbType = dbType;
    }

    void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    void setSchema(String schema) {
        this.schema = schema;
    }

    void addNode(String host, Integer port) {
        nodes.add(new NodeInfo(host, port));
        if (nodes.size() == 1) {
            this.host = host;
            this.port = port;
        }
    }

    void addParameter(String key, String value) {
        if (key == null || key.isEmpty()) {
            return;
        }
        parameters.put(key, value == null ? "" : value);
    }

    String parameterIgnoreCase(String key) {
        if (key == null) {
            return null;
        }
        String exact = parameters.get(key);
        if (exact != null && !exact.isEmpty()) {
            return exact;
        }
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            if (key.equalsIgnoreCase(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String encode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    /**
     * 主机 + 可选端口。
     *
     * @author 郑明亮
     * @since 2.0.2
     */
    public static final class NodeInfo {
        private final String host;
        private final Integer port;

        /**
         * @param host 主机或路径
         * @param port 端口，可空
         */
        public NodeInfo(String host, Integer port) {
            this.host = host;
            this.port = port;
        }

        /**
         * @return 主机
         */
        public String getHost() {
            return host;
        }

        /**
         * @return 端口，可空
         */
        public Integer getPort() {
            return port;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            return port == null ? String.valueOf(host) : host + ':' + port;
        }
    }
}
