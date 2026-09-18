package com.alianga.jkit.notify;

/**
 * 代码块语法高亮（零依赖，自研轻量词法扫描）。
 *
 * <p>只做“够用”的着色：注释、字符串、数字、关键字、标签名 / 属性名。不追求完整语法树，
 * 因此不需要引入 highlight.js 之类的前端库，也不生成 class 依赖外部 CSS——直接输出
 * {@code <span style="color:...">}，配合内联模式可在邮件客户端与微信里正常显示。
 *
 * <p>支持 java、javascript/ts、go、python、sql、shell、yaml、properties、json、xml/html；
 * 未知语言或空语言退化为纯转义（与不加高亮时输出一致）。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
final class CodeHighlighter {
    private static final String[] NO_WORDS = new String[0];

    private static final String[] JAVA_WORDS = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "record", "return", "sealed", "short", "static", "strictfp", "super", "switch",
            "synchronized", "this", "throw", "throws", "transient", "try", "var", "void",
            "volatile", "while", "true", "false", "null"
    };

    private static final String[] JS_WORDS = {
            "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "export", "extends", "false", "finally", "for",
            "from", "function", "get", "if", "import", "in", "instanceof", "let", "new", "null",
            "of", "return", "set", "static", "super", "switch", "this", "throw", "true", "try",
            "typeof", "undefined", "var", "void", "while", "yield"
    };

    private static final String[] GO_WORDS = {
            "bool", "break", "byte", "case", "chan", "const", "continue", "default", "defer",
            "else", "error", "fallthrough", "false", "float64", "for", "func", "go", "goto", "if",
            "import", "int", "interface", "map", "nil", "package", "range", "return", "rune",
            "select", "string", "struct", "switch", "true", "type", "var"
    };

    private static final String[] PY_WORDS = {
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
            "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import",
            "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return",
            "self", "True", "try", "while", "with", "yield"
    };

    private static final String[] SQL_WORDS = {
            "add", "all", "alter", "and", "as", "asc", "avg", "between", "bigint", "boolean",
            "by", "case", "cast", "coalesce", "count", "create", "date", "decimal", "default",
            "delete", "desc", "distinct", "drop", "else", "end", "exists", "false", "foreign",
            "from", "full", "group", "having", "in", "index", "inner", "insert", "int", "into",
            "is", "join", "key", "left", "like", "limit", "max", "min", "not", "null", "offset",
            "on", "or", "order", "outer", "primary", "references", "right", "select", "set",
            "sum", "table", "text", "then", "timestamp", "true", "union", "unique", "update",
            "values", "varchar", "when", "where", "with"
    };

    private static final String[] SHELL_WORDS = {
            "apt", "awk", "case", "cd", "docker", "do", "done", "echo", "elif", "else", "esac",
            "exit", "export", "fi", "for", "function", "grep", "if", "in", "java", "kubectl",
            "local", "mvn", "npm", "return", "sed", "set", "source", "sudo", "then", "unset",
            "while", "yum"
    };

    private static final String[] YAML_WORDS = {"true", "false", "null", "yes", "no", "on", "off"};

    private static final String[] JSON_WORDS = {"true", "false", "null"};

    private static final Language JAVA = new Language(new String[]{"//"}, true, "\"'",
            JAVA_WORDS, false);
    private static final Language JS = new Language(new String[]{"//"}, true, "\"'`",
            JS_WORDS, false);
    private static final Language GO = new Language(new String[]{"//"}, true, "\"'",
            GO_WORDS, false);
    private static final Language PYTHON = new Language(new String[]{"#"}, false, "\"'",
            PY_WORDS, false);
    private static final Language SQL = new Language(new String[]{"--"}, true, "'",
            SQL_WORDS, true);
    private static final Language SHELL = new Language(new String[]{"#"}, false, "\"'",
            SHELL_WORDS, false);
    private static final Language YAML = new Language(new String[]{"#"}, false, "\"'",
            YAML_WORDS, true);
    private static final Language JSON = new Language(NO_WORDS, false, "\"",
            JSON_WORDS, true);
    private static final Language PROPERTIES = new Language(new String[]{"#", "!"}, false, "\"'",
            NO_WORDS, false);
    private static final Language MARKUP = Language.markup();

    private CodeHighlighter() {
    }

    /**
     * 高亮代码块，返回已转义的 HTML（高亮 span 与转义同时进行，不会二次转义）。
     *
     * @param code 原始代码，未转义；{@code null} 返回空串
     * @param lang 语言标识（围栏后的首个单词），可为 {@code null}
     * @param style 主题，提供代码配色
     * @return 可嵌入 {@code <pre><code>} 的 HTML
     */
    static String render(String code, String lang, MarkdownStyle style) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        Language language = languageOf(lang);
        if (language == null) {
            return NotifyUtils.escapeHtml(code);
        }
        return new Scanner(code, language, style).scan();
    }

    /**
     * 判断语言是否被支持（不支持时调用方可跳过着色）。
     *
     * @param lang 语言标识
     * @return 支持返回 {@code true}
     */
    static boolean supports(String lang) {
        return languageOf(lang) != null;
    }

    private static Language languageOf(String lang) {
        if (lang == null) {
            return null;
        }
        String key = lang.trim().toLowerCase(java.util.Locale.ROOT);
        int space = key.indexOf(' ');
        if (space > 0) {
            key = key.substring(0, space);
        }
        if (key.isEmpty()) {
            return null;
        }
        if ("java".equals(key) || "kotlin".equals(key) || "kt".equals(key)) {
            return JAVA;
        }
        if ("js".equals(key) || "javascript".equals(key) || "jsx".equals(key) || "ts".equals(key)
                || "typescript".equals(key) || "tsx".equals(key) || "json5".equals(key)) {
            return JS;
        }
        if ("go".equals(key) || "golang".equals(key)) {
            return GO;
        }
        if ("py".equals(key) || "python".equals(key)) {
            return PYTHON;
        }
        if ("sql".equals(key) || "mysql".equals(key) || "pgsql".equals(key)
                || "postgresql".equals(key) || "oracle".equals(key) || "sqlite".equals(key)
                || "gbase".equals(key)) {
            return SQL;
        }
        if ("sh".equals(key) || "bash".equals(key) || "zsh".equals(key) || "shell".equals(key)
                || "console".equals(key)) {
            return SHELL;
        }
        if ("yml".equals(key) || "yaml".equals(key)) {
            return YAML;
        }
        if ("json".equals(key) || "jsonc".equals(key)) {
            return JSON;
        }
        if ("properties".equals(key) || "ini".equals(key) || "conf".equals(key)
                || "env".equals(key)) {
            return PROPERTIES;
        }
        if ("xml".equals(key) || "html".equals(key) || "htm".equals(key) || "svg".equals(key)
                || "vue".equals(key) || "markup".equals(key) || "pom".equals(key)) {
            return MARKUP;
        }
        return null;
    }

    /**
     * 语言配置：行注释前缀、是否支持块注释、字符串引号、关键字表、关键字是否忽略大小写。
     */
    private static final class Language {
        private final String[] lineComments;
        private final boolean blockComment;
        private final String quotes;
        private final String[] keywords;
        private final boolean ignoreCase;
        private final boolean markup;

        Language(String[] lineComments, boolean blockComment, String quotes, String[] keywords,
                 boolean ignoreCase) {
            this.lineComments = lineComments;
            this.blockComment = blockComment;
            this.quotes = quotes;
            this.keywords = keywords;
            this.ignoreCase = ignoreCase;
            this.markup = false;
        }

        private Language() {
            this.lineComments = NO_WORDS;
            this.blockComment = false;
            this.quotes = "";
            this.keywords = NO_WORDS;
            this.ignoreCase = false;
            this.markup = true;
        }

        static Language markup() {
            return new Language();
        }

        boolean isKeyword(String word) {
            for (int i = 0; i < keywords.length; i++) {
                if (ignoreCase ? keywords[i].equalsIgnoreCase(word) : keywords[i].equals(word)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 单次扫描的游标状态。
     */
    private static final class Scanner {
        private final String code;
        private final Language lang;
        private final MarkdownStyle style;
        private final StringBuilder out;
        private final StringBuilder plain = new StringBuilder();

        Scanner(String code, Language lang, MarkdownStyle style) {
            this.code = code;
            this.lang = lang;
            this.style = style;
            this.out = new StringBuilder(code.length() + 64);
        }

        String scan() {
            int i = 0;
            int n = code.length();
            while (i < n) {
                if (lang.markup) {
                    i = markupStep(i, n);
                    continue;
                }
                int comment = commentLength(i, n);
                if (comment > 0) {
                    emit(code.substring(i, i + comment), style.codeComment());
                    i += comment;
                    continue;
                }
                char c = code.charAt(i);
                if (lang.quotes.indexOf(c) >= 0) {
                    int length = stringLength(i, n, c);
                    emit(code.substring(i, i + length), style.codeString());
                    i += length;
                    continue;
                }
                if (isDigit(c) && (i == 0 || !isIdentChar(code.charAt(i - 1)))) {
                    int length = numberLength(i, n);
                    emit(code.substring(i, i + length), style.codeNumber());
                    i += length;
                    continue;
                }
                if (isIdentStart(c)) {
                    int length = identLength(i, n);
                    String word = code.substring(i, i + length);
                    if (lang.isKeyword(word)) {
                        emit(word, style.codeKeyword());
                    } else {
                        plain.append(word);
                    }
                    i += length;
                    continue;
                }
                plain.append(c);
                i++;
            }
            flushPlain();
            return out.toString();
        }

        private int markupStep(int i, int n) {
            char c = code.charAt(i);
            if (c == '<' && code.startsWith("<!--", i)) {
                int end = code.indexOf("-->", i + 4);
                int stop = end < 0 ? n : end + 3;
                emit(code.substring(i, stop), style.codeComment());
                return stop;
            }
            if (c == '<' && i + 1 < n && (isLetter(code.charAt(i + 1)) || code.charAt(i + 1) == '/'
                    || code.charAt(i + 1) == '!')) {
                int end = code.indexOf('>', i);
                if (end > i) {
                    flushPlain();
                    out.append("&lt;");
                    emitTagBody(code.substring(i + 1, end));
                    out.append("&gt;");
                    return end + 1;
                }
            }
            plain.append(c);
            return i + 1;
        }

        /**
         * 着色标签内部：首个标识符是标签名，其余是属性名，引号内是属性值。
         */
        private void emitTagBody(String body) {
            int i = 0;
            boolean tagNameDone = false;
            while (i < body.length()) {
                char c = body.charAt(i);
                if (c == '"' || c == '\'') {
                    int end = body.indexOf(c, i + 1);
                    int stop = end < 0 ? body.length() : end + 1;
                    emit(body.substring(i, stop), style.codeString());
                    i = stop;
                    continue;
                }
                if (isIdentStart(c)) {
                    int end = i;
                    while (end < body.length() && isIdentChar(body.charAt(end))) {
                        end++;
                    }
                    String word = body.substring(i, end);
                    if (tagNameDone) {
                        emit(word, style.codeNumber());
                    } else {
                        emit(word, style.codeKeyword());
                        tagNameDone = true;
                    }
                    i = end;
                    continue;
                }
                out.append(NotifyUtils.escapeHtml(String.valueOf(c)));
                i++;
            }
        }

        private int commentLength(int i, int n) {
            for (int k = 0; k < lang.lineComments.length; k++) {
                String marker = lang.lineComments[k];
                if (code.startsWith(marker, i)) {
                    int end = code.indexOf('\n', i);
                    return end < 0 ? n - i : end - i;
                }
            }
            if (lang.blockComment && code.startsWith("/*", i)) {
                int end = code.indexOf("*/", i + 2);
                return end < 0 ? n - i : end + 2 - i;
            }
            return 0;
        }

        private int stringLength(int i, int n, char quote) {
            int j = i + 1;
            while (j < n) {
                char c = code.charAt(j);
                if (c == '\\') {
                    j += 2;
                    continue;
                }
                if (c == quote) {
                    return j + 1 - i;
                }
                if (c == '\n') {
                    return j - i;
                }
                j++;
            }
            return n - i;
        }

        private int numberLength(int i, int n) {
            int j = i;
            while (j < n) {
                char c = code.charAt(j);
                boolean numeric = isDigit(c) || c == '.' || c == '_' || c == 'x' || c == 'X'
                        || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                        || c == 'e' || c == 'E' || c == 'L' || c == 'l' || c == 'f' || c == 'F'
                        || (j > i && (c == '+' || c == '-') && isExp(code.charAt(j - 1)));
                if (!numeric) {
                    break;
                }
                j++;
            }
            return j - i;
        }

        private int identLength(int i, int n) {
            int j = i;
            while (j < n && isIdentChar(code.charAt(j))) {
                j++;
            }
            return j - i;
        }

        private void emit(String text, String color) {
            flushPlain();
            if (color == null || color.isEmpty()) {
                out.append(NotifyUtils.escapeHtml(text));
                return;
            }
            out.append("<span style=\"color:").append(color).append("\">")
                    .append(NotifyUtils.escapeHtml(text)).append("</span>");
        }

        private void flushPlain() {
            if (plain.length() > 0) {
                out.append(NotifyUtils.escapeHtml(plain.toString()));
                plain.setLength(0);
            }
        }
    }

    private static boolean isExp(char c) {
        return c == 'e' || c == 'E';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isIdentStart(char c) {
        return isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentChar(char c) {
        return isIdentStart(c) || isDigit(c);
    }
}
