# HTML 解析（轻量）

`com.alianga.jkit.html` 提供一个零依赖的轻量 HTML 解析器与 CSS 选择器引擎，用于替代此前移除的 Jsoup。它面向**常见网页内容抽取**场景，覆盖 Jsoup 日常使用的 API 子集，不做完整的 HTML5 规范树构建（不处理 `<table>`/`<form>` 等元素的隐式插入、不实现 `bogus comment` 全量规则）。

## 1. 快速开始

```java
Document doc = Html.parse("<html><head><title>标题</title></head>"
        + "<body><div id=\"main\" class=\"box\"><p>hello</p></div></body></html>");

doc.title();                       // "标题"
doc.select("div#main p").text();   // "hello"
doc.select(".box").attr("id");     // "main"
```

`Html.parse` 返回 `Document`，它是解析树的根，本身也是 `Element`，因此 `select` / `text` 等 API 与元素一致。

## 2. DOM 模型

| 类型 | 说明 |
|------|------|
| `Node` | 节点基类，提供 `parent()` / `nodeText()` / `outerHtml()` |
| `Element` | 元素：标签名、属性、子节点，外加选择、抽取 API |
| `TextNode` | 标签之间的文本节点 |
| `Comment` | `<!-- ... -->` 注释节点 |
| `Document` | 文档根，额外提供 `title()` / `head()` / `body()` |
| `Elements` | `List<Element>` 的便捷子集（`text()` / `attr()` / `html()` / `eachText()` / `first()` / `select()`） |

## 3. 元素 API

```java
Element div = doc.selectFirst("div");

div.tagName();          // "div"
div.attr("id");         // "main"，不存在返回空串（不返回 null）
div.hasAttr("id");      // true
div.attributes();       // 属性映射的拷贝
div.id();               // "main"
div.className();        // "box"
div.classNames();       // ["box"]
div.hasClass("box");    // true
div.text();             // 递归拼接所有后代文本
div.innerHtml();        // 子节点序列化
div.outerHtml();        // 含自身标签的序列化
div.children();         // 直接子元素（仅 Element）
div.child(0);           // 第 1 个子元素
div.parentElement();    // 父元素，文档根返回 null
div.val();              // textarea 取文本，其余取 value 属性
```

约定：**属性名、标签名按 HTML 语义大小写不敏感**（解析时统一小写，匹配时忽略大小写）；`id` / `class` / 属性值则区分大小写。

## 4. CSS 选择器

支持的选择器子集：

| 类别 | 示例 |
|------|------|
| 标签 / 通配 | `div`、`*`、`div.foo` |
| id / class | `#main`、`.box`、`.a.b`（同时含两类） |
| 属性 | `[href]`、`[href=x]`、`[href^=https]`、`[href$=pdf]`、`[href*=img]`、`[title~=a]`、`[lang\|=en]` |
| 后代 / 子代 | `div p`、`ul > li`、`a b > c` |
| 分组 | `p.a, p.b`（逗号取并集） |
| 伪类 | `:first-child`、`:last-child`、`:only-child`、`:root`、`:empty`、`:not(...)`、`:nth-child(an+b)` |

`:nth-child` 支持 `odd` / `even` / `3` / `2n+1` / `n+1` / `-n+3` 等写法。

```java
doc.select("div p");                 // 所有后代 p
doc.select("body > div");            // 直接子元素
doc.select("a[href^=https]");        // 属性前缀
doc.select("li:first-child");        // 首个 li
doc.select("li:not(.done)");         // 排除含 done 的 li
doc.select("li:nth-child(2n+1)");    // 奇数项
doc.selectFirst("title");            // 首个匹配，无则 null
```

`Elements` 上的 `select(css)` 会在集合每个元素的子树中查询并合并结果。

## 5. 实体转义

解析阶段会对文本与属性值做实体解码；反之提供转义能力。

```java
Html.escape("a<b>&");        // "a&lt;b&gt;&amp;"
Html.unescape("a&amp;b");    // "a&b"
Html.unescape("&#65;");      // "A"
Html.unescape("&#x41;");     // "A"
```

支持的命名实体：`&amp;` `&lt;` `&gt;` `&quot;` `&apos;` `&nbsp;`；数字实体支持十进制 `&#65;` 与十六进制 `&#x41;`。无法识别的 `&` 原样保留。

## 6. 容错与限制

解析器对不规范 HTML 做容错处理：

- 未闭合标签在遇到匹配的结束标签时会连同其上层隐式关闭（如 `<div><span>hi</div>` 中 `</div>` 会一并关闭 `span`）；
- 空元素（`br` / `img` / `input` / `meta` 等）不进栈，序列化时不输出结束标签；
- 自闭合 `<tag/>` 与形如 `<div/` 的残缺标签都能安全终止，不会进入死循环；
- `<script>` / `<style>` 内容按原始文本处理，不再解析其中的标签；`<textarea>` 同样按原文处理但会做实体解码。

已知限制（与 Jsoup 的差异）：

- 不做 HTML5 规范要求的元素隐式插入与重排，DOM 结构以源码嵌套为准；
- 不实现 CSS 伪类的全部语义（如 `:has()`、`:nth-of-type()`、`::before` 等）；
- 不支持 `:not()` 内嵌组合符，`div:not(a b)` 这类写法不合法。

## 7. 异常

| 异常 | 场景 |
|------|------|
| `SelectorException` | 选择器中出现不支持的伪类，或选择器串本身无法解析 |

解析本身不抛异常——所有不规范输入都会被尽量适配为 DOM，不会因 HTML 脏数据中断流程。
