# HTML Parsing (Lightweight)

`com.alianga.jkit.html` provides a zero-dependency, lightweight HTML parser plus a CSS selector engine, replacing the previously removed Jsoup. It targets **web content extraction**: parse dirty HTML → locate nodes with CSS selectors → extract text/attributes.

The module was differentially validated against Jsoup 1.18.1: on 93 "HTML + selector" cases, 86 produce identical results. The remaining differences are Jsoup-only extensions and the HTML5 adoption agency algorithm (see [§7](#7-differences-from-jsoup)).

## 1. Quick Start

```java
Document doc = Html.parse("<html><head><title>Title</title></head>"
        + "<body><div id=\"main\" class=\"box\"><p>hello</p></div></body></html>");

doc.title();                       // "Title"
doc.select("div#main p").text();   // "hello"
doc.select(".box").attr("id");     // "main"
```

`Html.parse` returns a `Document`, the root of the tree, itself an `Element` — so `select` / `text` behave exactly as on elements. Even a fragment gets a full `html` / `head` / `body` skeleton:

```java
Html.parse("<p>hi</p>").outerHtml();
// <html><head></head><body><p>hi</p></body></html>
```

## 2. DOM Model

| Type | Description |
|------|-------------|
| `Node` | Node base class: `parent()` / `nodeText()` / `outerHtml()` |
| `Element` | Element: tag name, attributes, child nodes, plus selection/extraction API |
| `TextNode` | Text between tags; escaped when serialized |
| `DataNode` | Raw content of `script` / `style`; never escaped |
| `Comment` | `<!-- ... -->` comment node |
| `Document` | Document root (tag name `#document`); adds `title()` / `head()` / `body()` |
| `Elements` | Convenience `List<Element>` (`text()` / `attr()` / `html()` / `eachText()` / `first()` / `select()`) |

## 3. Element API

```java
Element div = doc.selectFirst("div");

div.tagName();                  // "div"
div.attr("id");                 // "main"; missing attributes return "" (never null)
div.hasAttr("id");              // true
div.attributes();               // copy of the attribute map
div.id();                       // "main"
div.className();                // "box"
div.classNames();               // ["box"]
div.hasClass("box");            // true
div.text();                     // normalised text of all descendants (script/style excluded)
div.ownText();                  // direct text children only, no descendants
div.nodeText();                 // raw, un-normalised text (includes script/style)
div.innerHtml();                // serialized child nodes
div.outerHtml();                // serialized including this element's tags
div.children();                 // direct child elements (Element only)
div.child(0);                   // first child element
div.childNodeSize();            // direct child nodes, including text and comments
div.parentElement();            // parent element; null at the document root
div.nextElementSibling();       // next sibling element
div.previousElementSibling();   // previous sibling element
div.elementSiblingIndex();      // index among siblings, from 0
div.val();                      // text for textarea, value attribute otherwise
div.select(css);                // query within this subtree
div.selectFirst(css);           // first match, else null
```

Convention: **tag names and attribute names are case-insensitive** (normalized to lower case on parse, matched ignoring case), while `id` / `class` / attribute values are case-sensitive.

`text()` normalisation matches Jsoup: a single space is inserted between block-level elements, runs of whitespace collapse to one space, and leading/trailing whitespace (including `&nbsp;`) is trimmed. `pre` / `textarea` keep their original whitespace.

## 4. CSS Selector

Supported subset:

| Category | Examples |
|----------|----------|
| Type / universal | `div`, `*`, `div.foo` |
| id / class | `#main`, `.box`, `.a.b` (both classes) |
| Attribute | `[href]`, `[href=x]`, `[href!=x]`, `[href^=https]`, `[href$=pdf]`, `[href*=img]`, `[title~=a]`, `[lang\|=en]` |
| Descendant / child | `div p`, `ul > li`, `a b > c` |
| Adjacent / general sibling | `h1 + p`, `h1 ~ p` |
| Grouping | `p.a, p.b` (comma = union) |
| Structural pseudo-class | `:first-child`, `:last-child`, `:only-child`, `:root`, `:empty`, `:not(...)`, `:nth-child(an+b)`, `:nth-last-child(an+b)` |
| Of-type pseudo-class | `:first-of-type`, `:last-of-type`, `:only-of-type`, `:nth-of-type(an+b)`, `:nth-last-of-type(an+b)` |
| Text pseudo-class | `:contains(text)`, `:containsOwn(text)`, `:matches(regex)`, `:matchesOwn(regex)` |

The `an+b` form accepts `odd` / `even` / `3` / `2n+1` / `n+1` / `-n+3`, and pseudo-class names are case-insensitive.

```java
doc.select("div p");                 // all descendant p
doc.select("body > div");            // direct children
doc.select("h1 + p");                // p immediately after an h1
doc.select("a[href^=https]");        // attribute prefix
doc.select("li:first-child");        // first li
doc.select("li:not(.done)");         // exclude li with class done
doc.select("li:nth-child(2n+1)");    // odd items
doc.select("p:contains(details)");   // text contains
doc.selectFirst("title");            // first match, else null
```

`Elements.select(css)` queries the subtree of every element in the set and merges the results; `Elements.text()` joins element texts with a space.

## 5. Entity Escaping

Parsing decodes entities in text and attribute values; escaping is available in reverse.

```java
Html.escape("a<b>&");        // "a&lt;b&gt;&amp;"
Html.unescape("a&amp;b");    // "a&b"
Html.unescape("&#65;");      // "A"
Html.unescape("&#x41;");     // "A"
Html.unescape("&copy;");     // "©"
Html.unescape("&mdash;");    // "—"
```

About 200 frequently used named entities are built in (`&copy;` `&reg;` `&trade;` `&mdash;` `&hellip;` `&ldquo;`, …); numeric entities support decimal `&#65;` and hex `&#x41;` without length limits. Unlisted named entities are preserved verbatim — use the `&#nnn;` form if you need the full HTML5 table.

## 6. Error Tolerance

The parser follows the common HTML5 recovery rules:

- **Implicit skeleton**: every input gets `html` / `head` / `body`. Head-only tags (`title` / `meta` / `link` / `base` / `style`) and any `script` appearing before the body go into `head`; everything else goes into `body`;
- **Optional end tags auto-close**: `<p>one<p>two` yields two sibling `p` elements; `<ul><li>a<li>b</ul>` yields two sibling `li` elements. Same for `dt`/`dd`, `td`/`th`/`tr`, `option`, `rt`/`rp`, and `thead`/`tbody`/`tfoot`. A block-level start tag also closes a `p` buried under inline elements (`<p>a<b>c<div>d` closes both `b` and `p`);
- **Implicit `tbody`**: `<table><tr><td>x</td></tr></table>` gets a `<tbody>`, like browsers do;
- Void elements (`br` / `img` / `input` / `meta`, …) are never pushed on the stack and serialize without a closing tag;
- Self-closing `<tag/>` and truncated forms like `<div/` terminate safely instead of looping;
- `<script>` / `<style>` content is treated as raw text (no tags parsed inside); `<textarea>` likewise, but with entity decoding.

## 7. Differences from Jsoup

These are deliberate trade-offs — check the list when migrating:

| Difference | Note |
|------------|------|
| `:has()` | Not supported; throws `SelectorException` |
| `:eq()` / `:lt()` / `:gt()` / `:first` / `:last` | Jsoup positional extensions; not supported |
| `:header` | Not supported (use `h1, h2, h3, h4, h5, h6`) |
| Combinators inside `:not()` | Not supported, e.g. `div:not(a b)` |
| Active formatting elements | The HTML5 adoption agency algorithm is not implemented. For `<div><p>a<b>c<div>d</div></div>` Jsoup re-creates `<b>` inside later blocks; this module only closes it. Extracted text is identical, structural queries differ |
| Document root tag name | `#document` here vs `#root` in Jsoup; only affects all-element queries such as `*` |
| `doctype` | Skipped during parsing, not kept in the DOM |
| Output formatting | `outerHtml()` is compact; no pretty printing |
| Namespaces / XML mode | Not supported; HTML only |

## 8. Exceptions

| Exception | When |
|-----------|------|
| `SelectorException` | An unsupported pseudo-class, a combinator missing its operand, or a selector string that cannot be parsed |

An illegal selector **always throws — it never degrades silently or loops**. `h1 @ p`, `p ~ + p` and `p:not()` each fail immediately with a clear message.

Parsing itself throws nothing — all malformed input is adapted into a DOM rather than aborting on dirty HTML.

## 9. Performance

Measured on the same machine against Jsoup 1.18.1 (3 warmup rounds, 7 timed rounds, median taken; `-Xms1g -Xmx2g`, JDK 8). Jsoup is used for benchmarking only and is **not** a project dependency. The corpus is a generated article list page: small = 2 items (~1.6 KB), medium = 60 items (~30 KB), large = 600 items (~300 KB). The ratio is `Jsoup time / jkit time`, so values above 1 mean jkit is faster.

| Scenario | jkit | Jsoup 1.18.1 | jkit faster |
|----------|------|--------------|-------------|
| Small page parse | 0.008 ms | 0.017 ms | 2.08x |
| Medium page parse | 0.144 ms | 0.199 ms | 1.38x |
| Large page parse | 1.060 ms | 1.925 ms | 1.82x |
| Large page parse + `text()` | 1.506 ms | 2.148 ms | 1.43x |
| Medium `#main` | 0.006 ms | 0.010 ms | 1.60x |
| Medium `.post` | 0.008 ms | 0.013 ms | 1.57x |
| Medium `article.post h2` | 0.007 ms | 0.016 ms | 2.20x |
| Medium `a[href^=/p/]` | 0.008 ms | 0.014 ms | 1.81x |
| Large `#main` | 0.099 ms | 0.098 ms | 0.99x |
| Large `a[href^=/p/]` | 0.115 ms | 0.126 ms | 1.10x |
| Retained heap, 200 large DOMs | 262.4 MB | 241.5 MB | 0.92x |
| End-to-end (parse + 3 queries + text) | 1.894 ms | 2.525 ms | 1.33x |

### Real-site validation

Synthetic pages distort results, so a real site was measured too — a Halo 1.4.5 blog index (70 KB, 24 `script` tags, 30 `img` tags, 10 `article` blocks), extracting link, title, lazy-load `data-src`, placeholder `src` and publish date for the 10 most recent posts, plus `pre code` blocks from three article detail pages.

| Scenario | jkit | Jsoup 1.18.1 | jkit faster |
|----------|------|--------------|-------------|
| Index page parse (70 KB) | 0.179 ms | 0.295 ms | 1.65x |
| Index parse + `text()` | 0.226 ms | 0.330 ms | 1.46x |
| `article.post-list-thumb` | 0.006 ms | 0.014 ms | 2.48x |
| `a.post-title h3` | 0.006 ms | 0.013 ms | 2.27x |
| `.post-thumb img` | 0.008 ms | 0.016 ms | 1.95x |
| End-to-end: parse + extract all fields for 10 posts | 0.310 ms | 0.381 ms | 1.23x |

On correctness, **all 5 fields of all 10 posts are identical to Jsoup**, and across three detail pages the `pre code` hit counts (11 / 13 / 8), the `class` attribute, and both `text()` and raw `nodeText()` match Jsoup exactly.

Verdict:

- Parsing, selectors and end-to-end throughput are **faster than Jsoup on both synthetic pages and the real site** (1.2x–2.5x).
- **Retained heap is still about 9% higher.** `Element` stores attributes in parallel arrays and children in an `ArrayList` — simpler and dependency-free, but each DOM is slightly fatter than Jsoup's.
- Full-tree selectors on large pages (e.g. `#main`) are on par with Jsoup (0.99x), because every query does a complete depth-first traversal with no id / class index.

The optimizations cluster in four places: attributes moved from `LinkedHashMap` to lazily allocated parallel `String[]`, with tag and attribute names interned through `Names`; the parser replaces O(depth) stack searches with cached indices and writes attributes into a reusable buffer; end-tag lookup for `script`/`style`/`textarea` no longer lower-cases the whole document, scanning character by character with `regionMatches` instead; selector traversal drops intermediate lists and iterators, collapses attribute matching into a single scan, and caches parsed selectors by access order (cap 256).

That third item matters most. Every end-tag lookup used to copy and scan the entire document, making the cost O(tags × document length). The synthetic page has a single `script`, so the effect was invisible; with 24 `script` tags on a real page, parse time jumped to **3.929 ms — 14x slower than Jsoup**. Only a real page exposes that.
