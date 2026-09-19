# HTML Parsing (Lightweight)

`com.alianga.jkit.html` provides a zero-dependency, lightweight HTML parser plus a CSS selector engine, replacing the previously removed Jsoup. It targets **web content extraction**: parse dirty HTML → locate nodes with CSS selectors → extract text/attributes.

The module was differentially validated against Jsoup 1.18.1: on 119 "HTML + selector" cases, 112 produce identical results. The remaining differences are Jsoup-only extensions and the HTML5 adoption agency algorithm (see [§7](#7-differences-from-jsoup)).

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
doc.select("div :not(a, p)");        // :not accepts a selector list
doc.select("div b:not(a b)");        // :not accepts a complex selector with combinators
doc.select("li:nth-child(2n+1)");    // odd items
doc.select("p:contains(details)");   // text contains
doc.selectFirst("title");            // first match, else null
```

`Elements.select(css)` queries the subtree of every element in the set and merges the results **with deduplication** (an element hit via nested subtrees appears once); `Elements.text()` joins element texts with a space.

## 5. Entity Escaping

Parsing decodes entities in text and attribute values; escaping is available in reverse.

```java
Html.escape("a<b>&");        // "a&lt;b&gt;&amp;"
Html.unescape("a&amp;b");    // "a&b"
Html.unescape("&#65;");      // "A"
Html.unescape("&#x41;");     // "A"
Html.unescape("&copy;");     // "©"
Html.unescape("&mdash;");    // "—"
Html.unescape("&#128512;");  // "😀" (code points beyond the BMP become surrogate pairs)
```

About 200 frequently used named entities are built in (`&copy;` `&reg;` `&trade;` `&mdash;` `&hellip;` `&ldquo;`, …); numeric entities support decimal `&#65;` and hex `&#x41;` without length limits. Unlisted named entities are preserved verbatim — use the `&#nnn;` form if you need the full HTML5 table.

## 6. Error Tolerance

The parser follows the common HTML5 recovery rules:

- **Implicit skeleton**: every input gets `html` / `head` / `body`. Head-only tags (`title` / `meta` / `link` / `base` / `style`) and any `script` appearing before the body go into `head`; everything else goes into `body`. Attributes on the `<html lang="en">` tag are preserved;
- **Optional end tags auto-close**: `<p>one<p>two` yields two sibling `p` elements; `<ul><li>a<li>b</ul>` yields two sibling `li` elements. Same for `dt`/`dd`, `td`/`th`/`tr`, `option`, `rt`/`rp`, and `thead`/`tbody`/`tfoot`. A block-level start tag also closes a `p` buried under inline elements (`<p>a<b>c<div>d` closes both `b` and `p`);
- **Implicit `tbody`**: `<table><tr><td>x</td></tr></table>` gets a `<tbody>`, like browsers do;
- Void elements (`br` / `img` / `input` / `meta`, …) are never pushed on the stack and serialize without a closing tag;
- Self-closing `<tag/>` and truncated forms like `<div/` terminate safely instead of looping;
- `<script>` / `<style>` content is treated as raw text (no tags parsed inside); `<textarea>` likewise, but with entity decoding;
- `<!-->` and `<!--->` are complete empty comments per HTML5 (abrupt closing) and no longer swallow the rest of the document; an unterminated comment extends to the end of input, per spec.

## 7. Differences from Jsoup

These are deliberate trade-offs — check the list when migrating:

| Difference | Note |
|------------|------|
| `:has()` | Not supported; throws `SelectorException` |
| `:eq()` / `:lt()` / `:gt()` / `:first` / `:last` | Jsoup positional extensions; not supported |
| `:header` | Not supported (use `h1, h2, h3, h4, h5, h6`) |
| Active formatting elements | The HTML5 adoption agency algorithm is not implemented. For `<div><p>a<b>c<div>d</div></div>` Jsoup re-creates `<b>` inside later blocks; this module only closes it. Extracted text is identical, structural queries differ |
| Document root tag name | `#document` here vs `#root` in Jsoup; only affects all-element queries such as `*` |
| `doctype` | Skipped during parsing, not kept in the DOM |
| Output formatting | `outerHtml()` is compact; no pretty printing |
| Namespaces / XML mode | Not supported; HTML only |

## 8. Exceptions

| Exception | When |
|-----------|------|
| `SelectorException` | An unsupported pseudo-class, a combinator missing its operand, or a selector string that cannot be parsed |

An illegal selector **always throws — it never degrades silently or loops**. Malformed input is rejected at parse time: an empty or blank selector, a group missing an operand (`,p` / `p,,p`), a combinator missing its right operand (`p >`), an unterminated attribute selector or one missing `=` (`img[src`, `[src^png]`), an unterminated pseudo-class argument (`p:nth-child(2`), and unsupported pseudo-classes (`table:hover` throws even when the document contains no `table`) all fail immediately with a clear message.

Parsing itself throws nothing — all malformed input is adapted into a DOM rather than aborting on dirty HTML.

## 9. Performance

Measured on the same machine against Jsoup 1.18.1 (3 warmup rounds, 7 timed rounds, median taken; max heap 2 GB, JDK 17). Jsoup is used for benchmarking only and is **not** a project dependency. The corpus is a generated article list page: small = 2 items (~1.6 KB), medium = 60 items (~30 KB), large = 600 items (~300 KB). The ratio is `Jsoup time / jkit time`, so values above 1 mean jkit is faster.

Timing is **pairwise and order-balanced**: every scenario is measured A→B→B→A and the lower of the two medians is kept. Running jkit first and Jsoup second every time penalizes whichever runs first — JIT compilation, branch prediction and cache warmup all land in the opening rounds. With a fixed order, `.post` on the large page reported 0.60x–0.90x; with alternating order it settles at ~1.3x. Without this, the report invents a slowdown that does not exist.

| Scenario | jkit | Jsoup 1.18.1 | jkit faster |
|----------|------|--------------|-------------|
| Small page parse | 0.010 ms | 0.023 ms | 2.27x |
| Medium page parse | 0.142 ms | 0.197 ms | 1.39x |
| Large page parse | 1.294 ms | 2.275 ms | 1.76x |
| Large page parse + `text()` | 1.654 ms | 2.022 ms | 1.22x |
| Medium `#main` | <0.001 ms | 0.013 ms | 68x |
| Medium `.post` | 0.001 ms | 0.019 ms | 14.8x |
| Medium `article.post h2` | 0.002 ms | 0.024 ms | 9.8x |
| Medium `a[href^=/p/]` | 0.002 ms | 0.018 ms | 8.8x |
| Medium `li:first-child` | 0.004 ms | 0.020 ms | 4.9x |
| Large `#main` | <0.001 ms | 0.164 ms | 1344x |
| Large `.post` | 0.015 ms | 0.185 ms | 12.7x |
| Large `article.post h2` | 0.024 ms | 0.212 ms | 8.7x |
| Large `a[href^=/p/]` | 0.016 ms | 0.160 ms | 10.2x |
| Retained heap, 150 large DOMs (parse only) | **153.3 MB** | 180.2 MB | **1.18x** |
| Retained heap, 150 large DOMs (parse + one query) | **159.1 MB** | 180.9 MB | **1.14x** |
| End-to-end (parse + 3 queries + text) | 2.847 ms | 3.405 ms | 1.20x |

### Real-site validation

Synthetic pages distort results, so a real site was measured too — a Halo 1.4.5 blog index (70 KB, 24 `script` tags, 30 `img` tags, 10 `article` blocks), extracting link, title, lazy-load `data-src`, placeholder `src` and publish date for the 10 most recent posts, plus `pre code` blocks from three article detail pages.

| Scenario | jkit | Jsoup 1.18.1 | jkit faster |
|----------|------|--------------|-------------|
| Index page parse (70 KB) | 0.306 ms | 0.456 ms | 1.49x |
| End-to-end: parse + extract all fields for 10 posts | 0.360 ms | 0.465 ms | 1.29x |
| `article.post-list-thumb` | <0.001 ms | 0.013 ms | 26.6x |
| `a.post-title h3` | 0.001 ms | 0.019 ms | 31.4x |
| `.post-thumb img` | 0.005 ms | 0.022 ms | 4.9x |
| `.post-date span.i18n` | 0.008 ms | 0.028 ms | 3.5x |

On correctness, **all 5 fields of all 10 posts are identical to Jsoup**, and across three detail pages the `pre code` hit counts (11 / 13 / 8), the `class` attribute, and both `text()` and raw `nodeText()` match Jsoup exactly.

Verdict:

- Parsing is **1.4x–2.3x faster**, end-to-end **1.2x–1.3x faster**, and selectors — now backed by the id / class / tag index — are **3.5x–1344x faster**.
- **Retained heap is 15% lower** (153.3 MB vs 180.2 MB), 12% lower once the index is built (159.1 MB vs 180.9 MB). The index itself costs roughly 4% of one DOM, which buys the order-of-magnitude gap above. Retained memory used to be 13% *higher*; the entire gap was strings — class values and templated list text repeat heavily on real pages, and Jsoup 1.18 ships a string cache while this module used to allocate a fresh instance per node. A parse-time string-interning cache closed it and then some.
- The index is only this cheap because **the DOM is immutable once parsed** — no setters, no way to add or remove children — so it is built once and can never go stale. That is a deliberate design difference from Jsoup (whose DOM is mutable); it is a simplification, not a limitation.

### Where the index applies

The index is only used when **the query root is the `Document`**, i.e. `doc.select(...)` / `doc.selectFirst(...)`. When the root is a child element (`article.selectFirst("a.post-title")`) the traversal still runs: candidates are collected document-wide, so a subtree root would have to test each candidate for containment, which is slower than walking a small subtree directly. In extraction code, prefer hoisting queries to document level where you can.

Key selection is "use whatever narrows the candidate set, fall back otherwise": id first, then class, then tag name. `div.foo` keys on `.foo`; `a[href^=/p/]` keys on `a` — tag names are index keys too, so attribute queries benefit as well. Only pure attribute / pseudo-class queries (`[data-x]`, `:empty`) fall back to a full traversal. Candidates remain a superset and every condition is still checked, so results are identical element for element — the `indexAndTraversalReturnTheSameElements` case runs the same queries at document level (indexed) and at element level (traversal) and asserts `assertSame` on every element.

### Reproducing the benchmark

The benchmark does not live in the jkit repo — Jsoup cannot enter jkit's dependencies — so it sits in the separate **tools-test** project:

- `com.alianga.test.html.HtmlParseBenchTest`: synthetic throughput, differential comparison against Jsoup (119 cases), the script-heavy regression guard, and retained heap.
- `com.alianga.test.html.HtmlRealSiteBenchTest`: extraction correctness and end-to-end timing on the real alianga.com site.

```bash
mvn test -Dtest='HtmlParseBenchTest,HtmlRealSiteBenchTest'
```

Reports are written to `target/html-parse-bench.md` and `target/html-real-site-bench.md`, with finalized copies archived under `reports/`. Assertions only guard against order-of-magnitude regressions rather than exact numbers, so they will not misfire on another machine; the real-site case needs network access and skips itself when the site is unreachable.

The optimizations cluster in six places: attributes moved from `LinkedHashMap` to lazily allocated parallel `String[]`, with tag and attribute names interned through `Names`; the parser replaces O(depth) stack searches with cached indices and writes attributes into a reusable buffer; end-tag lookup for `script`/`style`/`textarea` no longer lower-cases the whole document, scanning character by character with `regionMatches` instead; selector traversal drops intermediate lists and iterators, collapses attribute matching into a single scan, and caches parsed selectors by access order (cap 256); a lazy id / class / tag index on `Document` turns "traverse the whole tree on every query" into "fetch candidates, then verify"; and a parse-time string-interning cache merges repeated text and attribute values into single instances (after the first five items memory still trailed Jsoup slightly; the sixth one flips it).

The first five together only reach 1.3x–2.2x on selectors over the synthetic corpus and left retained heap 13% above Jsoup; the index lifts selectors to 4.6x–80x on medium pages and 8.6x–749x on large ones, and the string cache finally puts retained heap 15% below. The index is safe to use this way precisely because the DOM has no setters and no `appendChild` — it is fixed once parsed, so the index needs no invalidation logic at all.

That third item matters most. Every end-tag lookup used to copy and scan the entire document, making the cost O(tags × document length). The synthetic page has a single `script`, so the effect was invisible; with 24 `script` tags on a real page, parse time jumped to **3.929 ms — 14x slower than Jsoup**. Only a real page exposes that.
