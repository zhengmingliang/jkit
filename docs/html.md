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
