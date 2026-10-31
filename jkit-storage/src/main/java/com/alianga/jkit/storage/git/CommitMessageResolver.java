package com.alianga.jkit.storage.git;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 提交信息模板渲染：把连接级模板（如 {@code upload: {filename} @ {time}}）填成实际文案。
 *
 * <p>支持的占位符：{@code {filename}}（文件名）、{@code {path}}（仓库内路径）、
 * {@code {time}}（当前时间）、{@code {op}}（操作：上传 / 删除 / 新建目录）、
 * {@code {branch}}（分支）。未知占位符原样保留——用户写错时不该静默吞掉内容。</p>
 *
 * <p>纯逻辑、不依赖 JavaFX，可在单测里直接断言。</p>
 *
 * @author 郑明亮
 */
public final class CommitMessageResolver {
    /** 时间占位符的输出格式。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CommitMessageResolver() {
    }

    /**
     * 一次写操作的上下文。
     *
     * @author 郑明亮
     */
    public static final class Request {
        private final String operation;
        private final String path;
        private final String branch;

        /**
         * @param operation 操作名（上传 / 删除 / 新建目录）
         * @param path      仓库内路径
         * @param branch    分支
         */
        public Request(String operation, String path, String branch) {
            this.operation = operation;
            this.path = path;
            this.branch = branch;
        }

        /** @return 操作名 */
        public String operation() {
            return operation;
        }

        /** @return 仓库内路径 */
        public String path() {
            return path;
        }

        /** @return 分支 */
        public String branch() {
            return branch;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Request)) {
                return false;
            }
            Request that = (Request) other;
            return java.util.Objects.equals(operation, that.operation)
                    && java.util.Objects.equals(path, that.path)
                    && java.util.Objects.equals(branch, that.branch);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(operation, path, branch);
        }

        @Override
        public String toString() {
            return "Request[" + operation + " " + path + " @" + branch + "]";
        }
    }

    /**
     * 按模板渲染提交信息。
     *
     * @param template 模板
     * @param request  操作上下文
     * @param now      当前时间（便于单测固定）
     * @return 渲染后的提交信息（非空白）
     */
    public static String render(String template, Request request, LocalDateTime now) {
        String effective = template == null || template.trim().isEmpty()
                ? GitSourceConfig.DEFAULT_TEMPLATE : template;
        String filename = filenameOf(request.path());
        String time = (now == null ? LocalDateTime.now() : now).format(TIME_FORMAT);
        StringBuilder out = new StringBuilder(effective.length() + 32);
        for (int i = 0; i < effective.length(); i++) {
            char ch = effective.charAt(i);
            if (ch != '{') {
                out.append(ch);
                continue;
            }
            int close = effective.indexOf('}', i);
            if (close < 0) {
                out.append(ch);
                continue;
            }
            String token = effective.substring(i + 1, close).trim().toLowerCase(Locale.ROOT);
            out.append(replaceToken(token, effective, i, close, request, filename, time));
            i = close;
        }
        String result = out.toString().trim();
        // 模板全是占位符且路径为空时可能渲染成空串：兜一句，Git 不接受空提交信息
        return result.isEmpty() ? (request.operation() == null ? "update" : request.operation()) : result;
    }

    private static String replaceToken(String token, String template, int open, int close,
                                       Request request, String filename, String time) {
        if ("filename".equals(token)) {
            return filename;
        }
        if ("path".equals(token)) {
            return request.path() == null ? "" : request.path();
        }
        if ("time".equals(token)) {
            return time;
        }
        if ("op".equals(token) || "operation".equals(token)) {
            return request.operation() == null ? "" : request.operation();
        }
        if ("branch".equals(token)) {
            return request.branch() == null ? "" : request.branch();
        }
        // 未知占位符原样保留：用户写错时看得见，好过被静默替换成空
        return template.substring(open, close + 1);
    }

    private static String filenameOf(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int at = path.lastIndexOf('/');
        return at < 0 ? path : path.substring(at + 1);
    }
}
