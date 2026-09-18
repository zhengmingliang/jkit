# HTML 解析（轻量）

`com.alianga.jkit.html` 提供一个零依赖的轻量 HTML 解析器与 CSS 选择器引擎，用于替代此前移除的 Jsoup。它面向**网页内容抽取**场景：解析脏 HTML → CSS 选择器定位 → 抽取文本/属性。

模块以 Jsoup 1.18.1 为对照做了差分验证，在 93 组「HTML + 选择器」用例上 86 组结果完全一致，剩余差异集中在 Jsoup 专有扩展与 HTML5 收养算法（见 [第 7 节](#7-与-jsoup-的差异)）。

## 1. 快速开始

```java
Document doc = Html.parse("<html><head><title>标题</title></head>"
        + "<body><div id=\"main\" class=\"box\"><p>hello</p></div></body></html>");

doc.title();                       // "标题"
doc.select("div#main p").text();   // "hello"
doc.select(".box").attr("id");     // "main"
```

`Html.parse` 返回 `Document`，它是解析树的根，本身也是 `Element`，因此 `select` / `text` 等 API 与元素一致。即使输入是片段，也会补出 `html` / `head` / `body` 骨架：

```java
Html.parse("<p>hi</p>").outerHtml();
// <html><head></head><body><p>hi</p></body></html>
```

## 2. DOM 模型

| 类型 | 说明 |
|------|------|
| `Node` | 节点基类，提供 `parent()` / `nodeText()` / `outerHtml()` |
| `Element` | 元素：标签名、属性、子节点，外加选择、抽取 API |
| `TextNode` | 标签之间的文本节点，序列化时做实体转义 |
| `DataNode` | `script` / `style` 的原始内容节点，序列化时不转义 |
| `Comment` | `<!-- ... -->` 注释节点 |
| `Document` | 文档根（标签名 `#document`），额外提供 `title()` / `head()` / `body()` |
| `Elements` | `List<Element>` 的便捷子集（`text()` / `attr()` / `html()` / `eachText()` / `first()` / `select()`） |

## 3. 元素 API

```java
Element div = doc.selectFirst("div");

div.tagName();                  // "div"
div.attr("id");                 // "main"，不存在返回空串（不返回 null）
div.hasAttr("id");              // true
div.attributes();               // 属性映射的拷贝
div.id();                       // "main"
div.className();                // "box"
div.classNames();               // ["box"]
div.hasClass("box");            // true
div.text();                     // 归一化后的纯文本（script/style 内容不计入）
div.ownText();                  // 仅直接文本子节点，不含后代元素
div.nodeText();                 // 未归一化的原始文本（含 script/style 内容）
div.innerHtml();                // 子节点序列化
div.outerHtml();                // 含自身标签的序列化
div.children();                 // 直接子元素（仅 Element）
div.child(0);                   // 第 1 个子元素
div.childNodeSize();            // 直接子节点数（含文本与注释）
div.parentElement();            // 父元素，文档根返回 null
div.nextElementSibling();       // 下一个兄弟元素
div.previousElementSibling();   // 上一个兄弟元素
div.elementSiblingIndex();      // 同级下标，从 0 开始
div.val();                      // textarea 取文本，其余取 value 属性
div.select(css);                // 在子树中查询
div.selectFirst(css);           // 首个匹配，无则 null
```

约定：**属性名、标签名按 HTML 语义大小写不敏感**（解析时统一小写，匹配时忽略大小写）；`id` / `class` / 属性值则区分大小写。

`text()` 的文本归一化与 Jsoup 一致：块级元素之间补一个空格、连续空白折叠为单个空格、去掉首尾空白（含 `&nbsp;`）；`pre` / `textarea` 保留原始空白。

## 4. CSS 选择器

支持的选择器子集：

| 类别 | 示例 |
|------|------|
| 标签 / 通配 | `div`、`*`、`div.foo` |
| id / class | `#main`、`.box`、`.a.b`（同时含两类） |
| 属性 | `[href]`、`[href=x]`、`[href!=x]`、`[href^=https]`、`[href$=pdf]`、`[href*=img]`、`[title~=a]`、`[lang\|=en]` |
| 后代 / 子代 | `div p`、`ul > li`、`a b > c` |
| 相邻兄弟 / 通用兄弟 | `h1 + p`、`h1 ~ p` |
| 分组 | `p.a, p.b`（逗号取并集） |
| 结构性伪类 | `:first-child`、`:last-child`、`:only-child`、`:root`、`:empty`、`:not(...)`、`:nth-child(an+b)`、`:nth-last-child(an+b)` |
| 同类型伪类 | `:first-of-type`、`:last-of-type`、`:only-of-type`、`:nth-of-type(an+b)`、`:nth-last-of-type(an+b)` |
| 文本伪类 | `:contains(text)`、`:containsOwn(text)`、`:matches(regex)`、`:matchesOwn(regex)` |

`an+b` 支持 `odd` / `even` / `3` / `2n+1` / `n+1` / `-n+3` 等写法，伪类名大小写不敏感。

```java
doc.select("div p");                 // 所有后代 p
doc.select("body > div");            // 直接子元素
doc.select("h1 + p");                // h1 紧邻的下一个 p
doc.select("a[href^=https]");        // 属性前缀
doc.select("li:first-child");        // 首个 li
doc.select("li:not(.done)");         // 排除含 done 的 li
doc.select("li:nth-child(2n+1)");    // 奇数项
doc.select("p:contains(详情)");       // 文本包含
doc.selectFirst("title");            // 首个匹配，无则 null
```

`Elements` 上的 `select(css)` 会在集合每个元素的子树中查询并合并结果；`Elements.text()` 以空格拼接各元素文本。

## 5. 实体转义

解析阶段会对文本与属性值做实体解码；反之提供转义能力。

```java
Html.escape("a<b>&");        // "a&lt;b&gt;&amp;"
Html.unescape("a&amp;b");    // "a&b"
Html.unescape("&#65;");      // "A"
Html.unescape("&#x41;");     // "A"
Html.unescape("&copy;");     // "©"
Html.unescape("&mdash;");    // "—"
```

命名实体内置约 200 个高频实体（`&copy;` `&reg;` `&trade;` `&mdash;` `&hellip;` `&ldquo;` 等），数字实体支持十进制 `&#65;` 与十六进制 `&#x41;`，无长度限制。未收录的命名实体原样保留——如需全量 HTML5 实体表，请改用 `&#nnn;` 写法或自行预解码。

## 6. 容错处理

解析器按 HTML5 的常见规则做容错：

- **隐式骨架**：任何输入都会补出 `html` / `head` / `body`。`head` 专用标签（`title` / `meta` / `link` / `base` / `style`）与 body 之前的 `script` 归入 `head`，其余进入 `body`；
- **省略结束标签自动闭合**：`<p>一<p>二` 得到两个并列 `p`；`<ul><li>a<li>b</ul>` 得到两个并列 `li`；同样覆盖 `dt`/`dd`、`td`/`th`/`tr`、`option`、`rt`/`rp`、`thead`/`tbody`/`tfoot`。块级标签还会关闭被行内元素埋住的 `p`（`<p>a<b>c<div>d` 中 `b` 与 `p` 一起闭合）；
- **表格隐式 tbody**：`<table><tr><td>x</td></tr></table>` 自动补 `<tbody>`，与浏览器一致；
- 空元素（`br` / `img` / `input` / `meta` 等）不进栈，序列化时不输出结束标签；
- 自闭合 `<tag/>` 与形如 `<div/` 的残缺标签都能安全终止，不会进入死循环；
- `<script>` / `<style>` 内容按原始文本处理，不再解析其中的标签；`<textarea>` 同样按原文处理但会做实体解码。

## 7. 与 Jsoup 的差异

以下差异是刻意保留的取舍，迁移时按清单核对即可：

| 差异 | 说明 |
|------|------|
| `:has()` | 不支持，会抛 `SelectorException` |
| `:eq()` / `:lt()` / `:gt()` / `:first` / `:last` | Jsoup 位置型扩展，不支持 |
| `:header` | 不支持（可用 `h1, h2, h3, h4, h5, h6` 替代） |
| `:not()` 内嵌组合符 | 不支持，如 `div:not(a b)` |
| 活动格式化元素重排 | 不实现 HTML5 收养算法。`<div><p>a<b>c<div>d</div></div>` 中 Jsoup 会把 `<b>` 重建到后续块内，本模块只做闭合；文本抽取结果一致，结构查询会不同 |
| 文档根标签名 | 本模块为 `#document`，Jsoup 为 `#root`，仅影响 `*` 这类全量查询的展示 |
| `doctype` | 解析时跳过，不保留在 DOM 中 |
| 输出格式 | `outerHtml()` 为紧凑输出，不做缩进美化 |
| 命名空间与 XML 模式 | 不支持，只按 HTML 解析 |

## 8. 异常

| 异常 | 场景 |
|------|------|
| `SelectorException` | 选择器中出现不支持的伪类、组合符缺少操作数，或选择器串本身无法解析 |

非法选择器**一定抛异常，绝不静默降级或死循环**——输入 `h1 @ p`、`p ~ + p`、`p:not()` 都会立即得到明确的错误信息。

解析本身不抛异常——所有不规范输入都会被尽量适配为 DOM，不会因 HTML 脏数据中断流程。

## 9. 性能

同机与 Jsoup 1.18.1 实测对比（预热 3 轮、计时 7 轮取中位数，`-Xms1g -Xmx2g`，JDK 11；Jsoup 仅用于基准测试，未进入项目依赖）。测试页面为拼接的文章列表页：小页面 2 条约 1.6 KB、中页面 60 条约 30 KB、大页面 600 条约 300 KB。倍数为 `Jsoup 耗时 / jkit 耗时`，大于 1 表示 jkit 更快。

计时用**成对交替**：每个场景按 A→B→B→A 各测两轮、各取较小值。原因是固定「先 jkit 后 jsoup」会让先跑的一方吃亏——JIT 编译、分支预测、缓存预热都压在前几轮，实测大页面 `.post` 在固定顺序下只有 0.60x~0.90x，交替后稳定在 1.3x。不做这个处理，报告里就会出现并不存在的「jkit 更慢」。

| 场景 | jkit | Jsoup 1.18.1 | jkit 快 |
|------|------|--------------|---------|
| 小页面解析 | 0.011 ms | 0.021 ms | 1.94x |
| 中页面解析 | 0.128 ms | 0.261 ms | 2.03x |
| 大页面解析 | 1.204 ms | 2.539 ms | 2.11x |
| 大页面解析 + `text()` | 1.638 ms | 2.750 ms | 1.68x |
| 中页面 `#main` | <0.001 ms | 0.012 ms | 80x |
| 中页面 `.post` | 0.001 ms | 0.014 ms | 10.4x |
| 中页面 `article.post h2` | 0.002 ms | 0.020 ms | 8.3x |
| 中页面 `a[href^=/p/]` | 0.002 ms | 0.016 ms | 9.4x |
| 中页面 `li:first-child` | 0.003 ms | 0.015 ms | 4.6x |
| 大页面 `#main` | <0.001 ms | 0.146 ms | 749x |
| 大页面 `.post` | 0.015 ms | 0.182 ms | 12.4x |
| 大页面 `article.post h2` | 0.023 ms | 0.202 ms | 8.6x |
| 大页面 `a[href^=/p/]` | 0.016 ms | 0.173 ms | 10.7x |
| 150 份大页面 DOM 常驻堆（只解析） | 202.6 MB | 179.7 MB | 0.89x |
| 150 份大页面 DOM 常驻堆（解析 + 一次查询） | 209.1 MB | 181.0 MB | 0.87x |
| 端到端（解析 + 3 次查询 + 取文本） | 3.397 ms | 6.428 ms | 1.89x |

### 真实站点验证

合成页面容易失真，所以另取一个真实站点（Halo 1.4.5 博客首页，70 KB，含 24 个 `script`、30 个 `img`、10 篇 `article`）做端到端对照——抽最近 10 篇文章的链接、标题、懒加载图 `data-src`、占位图 `src`、发布日期，以及文章详情页的 `pre code` 代码块。

| 场景 | jkit | Jsoup 1.18.1 | jkit 快 |
|------|------|--------------|---------|
| 首页解析（70 KB） | 0.200 ms | 0.347 ms | 1.74x |
| 端到端：解析 + 抽 10 篇全部字段 | 0.268 ms | 0.462 ms | 1.73x |
| `article.post-list-thumb` | <0.001 ms | 0.015 ms | 33.9x |
| `a.post-title h3` | <0.001 ms | 0.016 ms | 37.1x |
| `.post-thumb img` | 0.003 ms | 0.020 ms | 5.9x |
| `.post-date span.i18n` | 0.006 ms | 0.026 ms | 4.5x |

正确性上 **10 篇文章 5 个字段逐条与 Jsoup 完全一致**；3 篇详情页的 `pre code` 命中数（11 / 13 / 8）、`class`、以及 `text()` 与原文 `nodeText()` 也都与 Jsoup 一致。

结论：

- 解析快 **1.7x–2.1x**，端到端快 **1.7x–1.9x**，选择器在 id / class / 标签索引加持下快 **4.5x–37x**；
- **常驻内存高 13%**（202.6 MB vs 179.7 MB），建索引后高 16%（209.1 MB vs 181.0 MB）。索引本身约占一份 DOM 的 3.5%，换来的是上面那个数量级差距；只解析不查询时完全不建，所以纯解析场景的内存和之前一模一样；
- 索引能这么省心，前提是 **DOM 解析完成后不可变**——没有 setter、没有增删子节点的入口，索引建一次就再也不会失效。这是本模块与 Jsoup 的一个设计差异（Jsoup 的 DOM 可改），别把它当成限制，它是简化的来源。

### 索引的使用范围

索引只对**查询根是 `Document`** 时生效，即 `doc.select(...)` / `doc.selectFirst(...)`。查询根是某个子元素时（如 `article.selectFirst("a.post-title")`）仍走深度优先遍历——候选集是以整份文档为范围建的，若根是子树，还得逐个判断候选是否落在子树内，子树小的时候反而更慢。所以抽取类代码里，能提到文档级做的查询尽量提到文档级。

键的取法是「能缩小候选范围就用，缩不小就退化」：优先 id，其次 class，最后标签名；`div.foo` 取 `.foo`、`a[href^=/p/]` 取 `a`（标签名也算索引键，所以带属性的查询一样受益），只有纯属性/伪类查询（如 `[data-x]`、`:empty`）才退回全树遍历。候选仍然是超集，最终条件照样逐个校验，结果与不建索引时逐一相同——`indexAndTraversalReturnTheSameElements` 用例就是把同一批查询分别在文档级（走索引）与元素级（走遍历）跑一遍，断言元素序列逐个 `assertSame`。

### 复现方式

压测代码不在 jkit 仓库内——Jsoup 不能进 jkit 依赖，所以放在独立项目 **tools-test**：

- `com.alianga.test.html.HtmlParseBenchTest`：合成页面吞吐、与 Jsoup 的差分比对（119 组用例）、script 密集页退化回归、常驻内存；
- `com.alianga.test.html.HtmlRealSiteBenchTest`：真实站点 alianga.com 的抽取正确性与端到端耗时。

```bash
mvn test -Dtest='HtmlParseBenchTest,HtmlRealSiteBenchTest'
```

报告写到 `target/html-parse-bench.md` 与 `target/html-real-site-bench.md`，定稿副本归档在 `reports/`。断言只卡数量级、不卡具体数字，避免换台机器就误报；真实站点用例需要网络，取不到就跳过，不会让构建变红。

优化手段集中在五处：属性表由 `LinkedHashMap` 改为并行 `String[]` 并懒创建、标签名与属性名走 `Names` 驻留表复用；解析期用下标缓存替代 O(栈深) 的栈内查找、属性写入复用缓冲区；`script`/`style`/`textarea` 的结束标签查找去掉整串 `toLowerCase()`，改为 `regionMatches` 忽略大小写逐字符扫描；选择器遍历去掉中间列表与迭代器、属性匹配合并为一次扫描、解析结果按访问序缓存（上限 256 条）；最后是 `Document` 上的惰性 id / class / 标签索引，把「每次查询全树遍历」变成「取候选 + 校验」。

前四处加起来，选择器在合成页面上只是 1.3x–2.2x；加上索引这一步，中页面才到 4.6x–80x、大页面 8.6x–749x。索引之所以敢这么用，是因为本模块的 DOM 没有 setter、没有 `appendChild`，解析完就定型了，索引不需要任何失效逻辑。

第三项最关键：此前每个 `script` 结束标签查找都会复制并扫描一遍全文，复杂度是 O(标签数 × 文档长度)。合成页面只有 1 个 `script` 影响不明显，真实页面有 24 个 `script` 时，解析耗时从 0.184 ms 涨到 **3.929 ms（比 Jsoup 慢 14 倍）**——这个缺陷只有拿真实页面测才暴露得出来。
