package com.alianga.jkit.html;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 CSS 选择器引擎，支持标签、{@code #id}、{@code .class}、属性选择器、
 * 后代 / 子代 / 相邻兄弟 / 通用兄弟组合符、分组，以及 {@code :not}、
 * {@code :first-child}、{@code :nth-child}、{@code :contains} 等常用伪类子集。
 *
 * <p>无法解析的选择器抛出 {@link SelectorException}，不做静默降级。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class Selector {
    /** 已解析选择器的缓存上限，超出后淘汰最久未用者。 */
    private static final int CACHE_MAX = 256;

    /** 解析结果不可变且线程安全，按访问序缓存，避免重复解析同一选择器。 */
    private static final Map<String, List<Complex>> CACHE =
            new LinkedHashMap<String, List<Complex>>(64, 0.75f, true);

    private Selector() {
    }

    /**
     * 解析选择器并缓存结果；解析失败时抛出 {@link SelectorException} 且不写入缓存。
     *
     * @param query CSS 选择器
     * @return 解析后的复合选择器列表
     */
    private static List<Complex> parseCached(String query) {
        if (query == null) {
            throw new SelectorException("选择器为 null");
        }
        synchronized (CACHE) {
            List<Complex> hit = CACHE.get(query);
            if (hit != null) {
                return hit;
            }
        }
        List<Complex> parsed = new Parser(query).parse();
        synchronized (CACHE) {
            if (CACHE.size() >= CACHE_MAX) {
                Iterator<String> it = CACHE.keySet().iterator();
                it.next();
                it.remove();
            }
            CACHE.put(query, parsed);
        }
        return parsed;
    }

    /**
     * 在 {@code root} 子树中按 CSS 选择器查询所有匹配元素（文档顺序、去重）。
     *
     * @param query CSS 选择器
     * @param root 查询根元素
     * @return 匹配元素集合
     */
    public static Elements select(String query, Element root) {
        List<Complex> complexes = parseCached(query);
        Elements out = new Elements();
        boolean dedup = complexes.size() > 1;
        // 分组去重用 HashSet 标记，避免对结果集线性 contains 退化成 O(n²)
        Set<Element> seen = dedup ? new HashSet<Element>() : null;
        for (int i = 0; i < complexes.size(); i++) {
            Complex c = complexes.get(i);
            int last = c.steps.size() - 1;
            collect(c, last, root, out, seen);
        }
        return out;
    }

    /**
     * 深度优先收集匹配元素，显式栈迭代实现，深嵌套文档不会栈溢出。
     * 直接走子节点表，不构建中间列表、不分配迭代器。
     *
     * @param c 复合选择器
     * @param last 最末一步下标
     * @param root 查询根元素
     * @param out 结果容器
     * @param seen 分组去重标记集，非分组查询传 {@code null}
     */
    private static void collect(Complex c, int last, Element root, Elements out, Set<Element> seen) {
        ArrayDeque<Element> stack = new ArrayDeque<Element>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Element el = stack.pop();
            List<Element> cands = candidates(c, last, el);
            if (cands != null) {
                // 索引路径：候选已按文档顺序排好，逐个验证完整条件即可；
                // 只有 Document 根会走此路径，候选已覆盖整棵树，无需再下钻
                for (int i = 0; i < cands.size(); i++) {
                    Element cand = cands.get(i);
                    if (matchFrom(c, last, cand) && (seen == null || seen.add(cand))) {
                        out.add(cand);
                    }
                }
                continue;
            }
            if (matchFrom(c, last, el) && (seen == null || seen.add(el))) {
                out.add(el);
            }
            // 逆序压栈，弹出顺序与文档顺序（深度优先前序）一致
            for (int k = el.childCount() - 1; k >= 0; k--) {
                Node n = el.childAt(k);
                if (n instanceof Element) {
                    stack.push((Element) n);
                }
            }
        }
    }

    /**
     * 取索引候选集；用不上索引时返回 {@code null}（调用方退化为全树遍历）。
     *
     * <p>只有查询根是 {@link Document} 时才走索引：候选集是以整份文档为范围建的，
     * 若根是某个子树，还得逐个判断候选是否落在子树内，子树小的时候反而比直接遍历更慢。
     *
     * @param c 复合选择器
     * @param last 最末一步下标
     * @param el 查询根元素
     * @return 候选元素列表（按文档顺序），或 {@code null}
     */
    private static List<Element> candidates(Complex c, int last, Element el) {
        if (!(el instanceof Document)) {
            return null;
        }
        String key = c.steps.get(last).indexKey();
        if (key == null) {
            return null;
        }
        List<Element> cands = ((Document) el).index().get(key);
        // 索引里没有这个键，说明文档里根本没有带该 tag / id / class 的元素，结果必为空
        return cands == null ? Collections.<Element>emptyList() : cands;
    }

    /**
     * 返回第一个匹配元素，无匹配时 {@code null}。
     *
     * @param query CSS 选择器
     * @param root 查询根元素
     * @return 首个匹配元素
     */
    public static Element selectFirst(String query, Element root) {
        List<Complex> complexes = parseCached(query);
        for (int i = 0; i < complexes.size(); i++) {
            Complex c = complexes.get(i);
            int last = c.steps.size() - 1;
            Element hit = findFirst(c, last, root);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Element findFirst(Complex c, int last, Element root) {
        ArrayDeque<Element> stack = new ArrayDeque<Element>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Element el = stack.pop();
            List<Element> cands = candidates(c, last, el);
            if (cands != null) {
                // 候选按文档顺序排列，第一个匹配的就是全树遍历会先遇到的那个；
                // 只有 Document 根会走此路径，候选已覆盖整棵树
                for (int i = 0; i < cands.size(); i++) {
                    if (matchFrom(c, last, cands.get(i))) {
                        return cands.get(i);
                    }
                }
                return null;
            }
            if (matchFrom(c, last, el)) {
                return el;
            }
            for (int k = el.childCount() - 1; k >= 0; k--) {
                Node n = el.childAt(k);
                if (n instanceof Element) {
                    stack.push((Element) n);
                }
            }
        }
        return null;
    }

    private static boolean matchFrom(Complex c, int idx, Element el) {
        if (!c.steps.get(idx).matches(el)) {
            return false;
        }
        if (idx == 0) {
            return true;
        }
        Combinator comb = c.combs.get(idx - 1);
        if (comb == Combinator.CHILD) {
            Element parent = el.parentElement();
            return parent != null && matchFrom(c, idx - 1, parent);
        }
        if (comb == Combinator.NEXT_SIBLING) {
            Element prev = el.previousElementSibling();
            return prev != null && matchFrom(c, idx - 1, prev);
        }
        if (comb == Combinator.SUBSEQUENT_SIBLING) {
            Element prev = el.previousElementSibling();
            while (prev != null) {
                if (matchFrom(c, idx - 1, prev)) {
                    return true;
                }
                prev = prev.previousElementSibling();
            }
            return false;
        }
        Element parent = el.parentElement();
        while (parent != null) {
            if (matchFrom(c, idx - 1, parent)) {
                return true;
            }
            parent = parent.parentElement();
        }
        return false;
    }

    private enum Combinator {
        DESCENDANT, CHILD, NEXT_SIBLING, SUBSEQUENT_SIBLING
    }

    private interface Simple {
        boolean matches(Element e);
    }

    private static final class Compound {
        final List<Simple> simples = new ArrayList<Simple>();

        /** 按下标遍历，避免每个被测试元素都分配一次 Iterator。空复合不匹配任何元素（解析已禁止，纯防御）。 */
        boolean matches(Element e) {
            for (int i = 0; i < simples.size(); i++) {
                if (!simples.get(i).matches(e)) {
                    return false;
                }
            }
            return !simples.isEmpty();
        }

        /**
         * 取可用于索引查找的键；没有可索引特征时返回 {@code null}。
         *
         * <p>键只需保证「候选集是结果的超集」——后续仍会用 {@link #matches} 校验完整条件，
         * 所以 {@code div.foo} 取 {@code .foo} 或 {@code div} 都对，取更小的一个更快。
         * 优先级按通常的选择性排：id &gt; class &gt; 标签名。属性选择器与伪类不作为键
         * （属性值的取值空间无界，建索引不划算）。
         *
         * @return 索引键，或 {@code null}
         */
        String indexKey() {
            String cls = null;
            String tag = null;
            for (int i = 0; i < simples.size(); i++) {
                Simple s = simples.get(i);
                if (s instanceof IdSimple) {
                    return Document.ID_PREFIX + ((IdSimple) s).id;
                }
                if (cls == null && s instanceof ClassSimple) {
                    cls = ((ClassSimple) s).cls;
                } else if (tag == null && s instanceof TagSimple) {
                    tag = ((TagSimple) s).tag;
                }
            }
            if (cls != null) {
                return Document.CLASS_PREFIX + cls;
            }
            if (tag != null && !"*".equals(tag)) {
                return Document.TAG_PREFIX + tag;
            }
            return null;
        }
    }

    private static final class Complex {
        final List<Compound> steps = new ArrayList<Compound>();
        final List<Combinator> combs = new ArrayList<Combinator>();
    }

    private static final class TagSimple implements Simple {
        private final String tag;

        TagSimple(String tag) {
            // DOM 标签名解析时已统一小写，选择器侧也归一，匹配退化为 equals；
            // 用 Locale.ROOT 避免土耳其语等环境下 I→ı 导致失配
            this.tag = "*".equals(tag) ? tag : tag.toLowerCase(Locale.ROOT);
        }

        public boolean matches(Element e) {
            return "*".equals(tag) || e.tagName().equals(tag);
        }
    }

    private static final class IdSimple implements Simple {
        private final String id;

        IdSimple(String id) {
            this.id = id;
        }

        public boolean matches(Element e) {
            return e.id().equals(id);
        }
    }

    private static final class ClassSimple implements Simple {
        private final String cls;

        ClassSimple(String cls) {
            this.cls = cls;
        }

        public boolean matches(Element e) {
            return e.hasClass(cls);
        }
    }

    private static final class AttrSimple implements Simple {
        private final String name;
        private final String op;
        private final String value;

        AttrSimple(String name, String op, String value) {
            this.name = name;
            this.op = op;
            this.value = value;
        }

        public boolean matches(Element e) {
            if (op.isEmpty()) {
                return e.hasAttr(name);
            }
            if ("!=".equals(op)) {
                return !e.attr(name).equals(value);
            }
            String av = e.attrOrNull(name);
            if (av == null) {
                return false;
            }
            if ("=".equals(op)) {
                return av.equals(value);
            }
            if ("~=".equals(op)) {
                return hasToken(av, value);
            }
            if ("|=".equals(op)) {
                return av.equals(value) || av.startsWith(value + "-");
            }
            if ("^=".equals(op)) {
                return av.startsWith(value);
            }
            if ("$=".equals(op)) {
                return av.endsWith(value);
            }
            if ("*=".equals(op)) {
                return av.contains(value);
            }
            return false;
        }

        /** {@code ~=} 的空白分词匹配，手写扫描替代正则 split。 */
        private static boolean hasToken(String av, String tok) {
            int i = 0;
            int n = av.length();
            int len = tok.length();
            while (i < n) {
                while (i < n && isSep(av.charAt(i))) {
                    i++;
                }
                int s = i;
                while (i < n && !isSep(av.charAt(i))) {
                    i++;
                }
                if (i - s == len && av.regionMatches(s, tok, 0, len)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isSep(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
        }
    }

    /** 支持的无参数伪类，解析期校验，不依赖文档内容是否命中。 */
    private static final Set<String> SUPPORTED_PSEUDOS = new HashSet<String>(Arrays.asList(
            "first-child", "last-child", "only-child", "root", "empty",
            "first-of-type", "last-of-type", "only-of-type"));

    private static final class PseudoSimple implements Simple {
        private final String name;
        /** 仅 {@code :not} 使用：参数选择器列表，命中任一即排除。 */
        private final List<Complex> nots;
        private final int[] nth;
        private final String arg;

        PseudoSimple(String name, List<Complex> nots, int[] nth, String arg) {
            this.name = name;
            this.nots = nots;
            this.nth = nth;
            this.arg = arg;
        }

        public boolean matches(Element e) {
            switch (name) {
                case "first-child":
                    return indexInParent(e) == 1;
                case "last-child":
                    return indexInParent(e) == elementChildCount(e.parentElement());
                case "only-child":
                    return elementChildCount(e.parentElement()) == 1 && indexInParent(e) == 1;
                case "root":
                    return e.parent() == null;
                case "empty":
                    return isEmptyOfContent(e);
                case "not":
                    for (int k = 0; k < nots.size(); k++) {
                        Complex nc = nots.get(k);
                        if (matchFrom(nc, nc.steps.size() - 1, e)) {
                            return false;
                        }
                    }
                    return true;
                case "nth-child":
                    return nthMatches(e, nth[0], nth[1]);
                case "nth-last-child":
                    return reverseNthMatches(e, nth[0], nth[1]);
                case "nth-of-type":
                    return typeMatches(e, nth[0], nth[1]);
                case "nth-last-of-type":
                    return nth(nth[0], nth[1], sameTypeCount(e) - typeIndexOf(e) + 1);
                case "first-of-type":
                    return typeIndexOf(e) == 1;
                case "last-of-type":
                    return typeIndexOf(e) == sameTypeCount(e);
                case "only-of-type":
                    return sameTypeCount(e) == 1;
                case "contains":
                    return e.text().contains(arg);
                case "containsown":
                    return e.ownText().contains(arg);
                case "matches":
                    return Pattern.compile(arg).matcher(e.text()).matches();
                case "matchesown":
                    return Pattern.compile(arg).matcher(e.ownText()).matches();
                default:
                    throw new SelectorException("unsupported pseudo: " + name);
            }
        }

        private boolean nthMatches(Element e, int a, int b) {
            return nth(a, b, indexInParent(e));
        }

        private boolean typeMatches(Element e, int a, int b) {
            return nth(a, b, typeIndexOf(e));
        }

        private boolean reverseNthMatches(Element e, int a, int b) {
            Element p = e.parentElement();
            if (p == null) {
                return false;
            }
            return nth(a, b, elementChildCount(p) - indexInParent(e) + 1);
        }

        /** 与 Jsoup 一致：空白文本节点与注释不算“有内容”。 */
        private boolean isEmptyOfContent(Element e) {
            int size = e.childCount();
            for (int k = 0; k < size; k++) {
                Node n = e.childAt(k);
                if (n instanceof Comment) {
                    continue;
                }
                if (n instanceof TextNode && ((TextNode) n).text().trim().isEmpty()) {
                    continue;
                }
                return false;
            }
            return true;
        }

        private boolean nth(int a, int b, int idx) {
            if (idx <= 0) {
                return false;
            }
            if (a == 0) {
                return idx == b;
            }
            int diff = idx - b;
            return diff % a == 0 && diff / a >= 0;
        }

        /** 在同类型兄弟中的序号，从 1 开始。 */
        private int typeIndexOf(Element e) {
            Element p = e.parentElement();
            if (p == null) {
                return 1;
            }
            int idx = 0;
            int size = p.childCount();
            for (int k = 0; k < size; k++) {
                Node n = p.childAt(k);
                if (n instanceof Element && ((Element) n).tagName().equals(e.tagName())) {
                    idx++;
                    if (n == e) {
                        return idx;
                    }
                }
            }
            return idx;
        }

        private int sameTypeCount(Element e) {
            Element p = e.parentElement();
            if (p == null) {
                return 1;
            }
            int count = 0;
            int size = p.childCount();
            for (int k = 0; k < size; k++) {
                Node n = p.childAt(k);
                if (n instanceof Element && ((Element) n).tagName().equals(e.tagName())) {
                    count++;
                }
            }
            return count;
        }
    }

    /** 元素在同级元素中的序号，从 1 开始；无父元素或不在子节点中返回 0。 */
    private static int indexInParent(Element e) {
        Element p = e.parentElement();
        if (p == null) {
            return 0;
        }
        int idx = 0;
        int size = p.childCount();
        for (int k = 0; k < size; k++) {
            Node n = p.childAt(k);
            if (!(n instanceof Element)) {
                continue;
            }
            idx++;
            if (n == e) {
                return idx;
            }
        }
        return 0;
    }

    /** 直接子元素个数，父元素为 null 时返回 0。 */
    private static int elementChildCount(Element p) {
        if (p == null) {
            return 0;
        }
        int count = 0;
        int size = p.childCount();
        for (int k = 0; k < size; k++) {
            if (p.childAt(k) instanceof Element) {
                count++;
            }
        }
        return count;
    }

    private static final class Parser {
        private final String q;
        private final int n;
        private int i;

        Parser(String q) {
            this.q = q;
            this.n = q.length();
            this.i = 0;
        }

        List<Complex> parse() {
            List<Complex> complexes = new ArrayList<Complex>();
            Complex cur = new Complex();
            Compound comp = new Compound();
            while (i < n) {
                boolean ws = false;
                while (i < n && isWs(q.charAt(i))) {
                    i++;
                    ws = true;
                }
                if (i >= n) {
                    break;
                }
                char c = q.charAt(i);
                if (c == ',') {
                    if (comp.simples.isEmpty()) {
                        throw new SelectorException("分组选择器缺少操作数：" + q);
                    }
                    cur.steps.add(comp);
                    comp = new Compound();
                    complexes.add(cur);
                    cur = new Complex();
                    i++;
                    continue;
                }
                if (c == '>') {
                    flush(cur, comp, Combinator.CHILD);
                    comp = new Compound();
                    i++;
                    continue;
                }
                if (c == '+') {
                    flush(cur, comp, Combinator.NEXT_SIBLING);
                    comp = new Compound();
                    i++;
                    continue;
                }
                if (c == '~') {
                    flush(cur, comp, Combinator.SUBSEQUENT_SIBLING);
                    comp = new Compound();
                    i++;
                    continue;
                }
                if (ws && !comp.simples.isEmpty()) {
                    flush(cur, comp, Combinator.DESCENDANT);
                    comp = new Compound();
                }
                comp.simples.add(readSimple());
            }
            if (!comp.simples.isEmpty()) {
                cur.steps.add(comp);
            } else if (!cur.steps.isEmpty()) {
                // 末尾悬空组合符（如 "p >"），右侧缺少操作数
                throw new SelectorException("组合符缺少操作数：" + q);
            }
            if (cur.steps.isEmpty()) {
                throw new SelectorException("空选择器：" + q);
            }
            complexes.add(cur);
            return complexes;
        }

        private void flush(Complex cur, Compound comp, Combinator comb) {
            if (comp.simples.isEmpty()) {
                throw new SelectorException("组合符缺少操作数：" + q);
            }
            cur.steps.add(comp);
            cur.combs.add(comb);
        }

        private Simple readSimple() {
            char c = q.charAt(i);
            if (c == '#') {
                i++;
                return new IdSimple(readIdent());
            }
            if (c == '.') {
                i++;
                return new ClassSimple(readIdent());
            }
            if (c == '[') {
                return readAttr();
            }
            if (c == ':') {
                return readPseudo();
            }
            if (c == '*') {
                i++;
                return new TagSimple("*");
            }
            int ks = i;
            while (i < n && isIdentPart(q.charAt(i))) {
                i++;
            }
            if (i == ks) {
                throw new SelectorException("无法解析的选择器，位置 " + ks + " 附近：" + q);
            }
            return new TagSimple(q.substring(ks, i));
        }

        private String readIdent() {
            int ks = i;
            while (i < n && isIdentPart(q.charAt(i))) {
                i++;
            }
            return q.substring(ks, i);
        }

        private Simple readAttr() {
            i++;
            skipWs();
            int ks = i;
            while (i < n && !isWs(q.charAt(i)) && q.charAt(i) != '='
                    && q.charAt(i) != ']' && q.charAt(i) != '>'
                    && q.charAt(i) != '^' && q.charAt(i) != '$'
                    && q.charAt(i) != '*' && q.charAt(i) != '~'
                    && q.charAt(i) != '|' && q.charAt(i) != '!') {
                i++;
            }
            if (i == ks) {
                throw new SelectorException("属性选择器缺少属性名：" + q);
            }
            String name = q.substring(ks, i).toLowerCase(Locale.ROOT);
            skipWs();
            if (i >= n) {
                throw new SelectorException("属性选择器未闭合：" + q);
            }
            if (q.charAt(i) == ']') {
                i++;
                return new AttrSimple(name, "", "");
            }
            char o1 = q.charAt(i);
            String op;
            if (o1 == '=') {
                op = "=";
                i++;
            } else if (o1 == '!' || o1 == '^' || o1 == '$' || o1 == '*'
                    || o1 == '~' || o1 == '|') {
                if (i + 1 >= n || q.charAt(i + 1) != '=') {
                    throw new SelectorException("属性选择器操作符后缺少 '='：" + q);
                }
                op = "" + o1 + '=';
                i += 2;
            } else {
                throw new SelectorException("无法解析的属性选择器，位置 " + i + " 附近：" + q);
            }
            skipWs();
            if (i >= n) {
                throw new SelectorException("属性选择器缺少属性值：" + q);
            }
            String value;
            if (q.charAt(i) == '"' || q.charAt(i) == '\'') {
                char qc = q.charAt(i);
                i++;
                int vs = i;
                while (i < n && q.charAt(i) != qc) {
                    i++;
                }
                if (i >= n) {
                    throw new SelectorException("属性值引号未闭合：" + q);
                }
                value = q.substring(vs, i);
                i++;
            } else {
                int vs = i;
                while (i < n && !isWs(q.charAt(i)) && q.charAt(i) != ']' && q.charAt(i) != '>') {
                    i++;
                }
                value = q.substring(vs, i);
            }
            skipWs();
            if (i >= n || q.charAt(i) != ']') {
                throw new SelectorException("属性选择器未闭合：" + q);
            }
            i++;
            return new AttrSimple(name, op, value);
        }

        private Simple readPseudo() {
            i++;
            if (i < n && q.charAt(i) == ':') {
                i++;
            }
            String name = readIdent().toLowerCase(Locale.ROOT);
            String arg = null;
            if (i < n && q.charAt(i) == '(') {
                int depth = 1;
                int start = i + 1;
                i++;
                while (i < n && depth > 0) {
                    char ch = q.charAt(i);
                    if (ch == '(') {
                        depth++;
                    } else if (ch == ')') {
                        depth--;
                    }
                    i++;
                }
                if (depth > 0) {
                    throw new SelectorException("伪类参数括号未闭合：" + q);
                }
                arg = q.substring(start, i - 1).trim();
            }
            return makePseudo(name, arg);
        }

        private Simple makePseudo(String name, String arg) {
            if (name.isEmpty()) {
                throw new SelectorException("伪类名缺失：" + q);
            }
            if ("not".equals(name)) {
                if (arg == null || arg.isEmpty()) {
                    throw new SelectorException(":not() 缺少参数：" + q);
                }
                // 支持选择器列表（如 :not(a,b)）与含组合器的复杂选择器（如 :not(div p)），
                // 解析器已保证非空，不会出现空复合
                List<Complex> cs = new Parser(arg).parse();
                return new PseudoSimple(name, cs, null, arg);
            }
            if ("nth-child".equals(name) || "nth-of-type".equals(name)
                    || "nth-last-child".equals(name) || "nth-last-of-type".equals(name)) {
                requireArg(name, arg);
                return new PseudoSimple(name, null, parseNth(arg), arg);
            }
            if ("contains".equals(name) || "containsown".equals(name)
                    || "matches".equals(name) || "matchesown".equals(name)) {
                requireArg(name, arg);
                return new PseudoSimple(name, null, null, arg);
            }
            if (!SUPPORTED_PSEUDOS.contains(name)) {
                // 解析期拒绝，避免「索引无候选时静默返回空结果」绕过运行期校验
                throw new SelectorException("不支持的伪类 :" + name + "（选择器：" + q + "）");
            }
            return new PseudoSimple(name, null, null, arg);
        }

        private void requireArg(String name, String arg) {
            if (arg == null || arg.isEmpty()) {
                throw new SelectorException(":" + name + "() 缺少参数：" + q);
            }
        }

        private int[] parseNth(String arg) {
            String s = arg.replace(" ", "");
            if ("odd".equals(s)) {
                return new int[]{2, 1};
            }
            if ("even".equals(s)) {
                return new int[]{2, 0};
            }
            Matcher m = Pattern.compile("^([+-]?\\d*)n([+-]\\d+)?$").matcher(s);
            if (m.matches()) {
                String aStr = m.group(1);
                int a;
                if (aStr.isEmpty() || "+".equals(aStr)) {
                    a = 1;
                } else if ("-".equals(aStr)) {
                    a = -1;
                } else {
                    a = Integer.parseInt(aStr);
                }
                String bStr = m.group(2);
                int b = bStr == null ? 0 : Integer.parseInt(bStr);
                return new int[]{a, b};
            }
            try {
                return new int[]{0, Integer.parseInt(s)};
            } catch (NumberFormatException e) {
                throw new SelectorException("nth 伪类参数无法解析：" + arg);
            }
        }

        private void skipWs() {
            while (i < n && isWs(q.charAt(i))) {
                i++;
            }
        }

        private boolean isWs(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
        }

        private boolean isIdentPart(char c) {
            return Character.isLetterOrDigit(c) || c == '-' || c == '_';
        }
    }
}
