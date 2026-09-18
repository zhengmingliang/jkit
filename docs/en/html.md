# HTML Parsing (Lightweight)

`com.alianga.jkit.html` provides a zero-dependency, lightweight HTML parser plus a CSS selector engine, replacing the previously removed Jsoup. It targets **common web content extraction**, covering the daily-use subset of the Jsoup API. It is *not* a full HTML5 spec tree builder (no implicit insertion for `<table>`/`<form>`, no complete `bogus comment` rules).

## 1. Quick Start

```java
Document doc = Html.parse("<html><head><title>Title</title></head>"
        + "<body><div id=\"main\" class=\"box\"><p>hello</p></div></body></html>");

doc.title();                       // "Title"
doc.select("div#main p").text();   // "hello"
doc.select(".box").attr("id");     // "main"
```

`Html.parse` returns a `Document`, the root of the tree, itself an `Element` — so `select` / `text` behave exactly as on elements.

## 2. DOM Model

| Type | Description |
|------|-------------|
| `Node` | Node base class: `parent()` / `nodeText()` / `outerHtml()` |
| `Element` | Element: tag name, attributes, child nodes, plus selection/extraction API |
| `TextNode` | Text between tags |
| `Comment` | `<!-- ... -->` comment node |
| `Document` | Document root; adds `title()` / `head()` / `body()` |
| `Elements` | Convenience `List<Element>` (`text()` / `attr()` / `html()` / `eachText()` / `first()` / `select()`) |

## 3. Element API

```java
Element div = doc.selectFirst("div");

div.tagName();          // "div"
div.attr("id");         // "main"; missing attributes return "" (never null)
div.hasAttr("id");      // true
div.attributes();       // copy of the attribute map
div.id();               // "main"
div.className();        // "box"
div.classNames();       // ["box"]
div.hasClass("box");    // true
div.text();             // recursive text of all descendants
div.innerHtml();        // serialized child nodes
div.outerHtml();        // serialized including this element's tags
div.children();         // direct child elements (Element only)
div.child(0);           // first child element
div.parentElement();    // parent element; null at the document root
div.val();              // text for textarea, value attribute otherwise
```

Convention: **tag names and attribute names are case-insensitive** (normalized to lower case on parse, matched ignoring case), while `id` / `class` / attribute values are case-sensitive.

## 4. CSS Selector

Supported subset:

| Category | Examples |
|----------|----------|
| Type / universal | `div`, `*`, `div.foo` |
| id / class | `#main`, `.box`, `.a.b` (both classes) |
| Attribute | `[href]`, `[href=x]`, `[href^=https]`, `[href$=pdf]`, `[href*=img]`, `[title~=a]`, `[lang\|=en]` |
| Descendant / child | `div p`, `ul > li`, `a b > c` |
| Grouping | `p.a, p.b` (comma = union) |
| Pseudo-class | `:first-child`, `:last-child`, `:only-child`, `:root`, `:empty`, `:not(...)`, `:nth-child(an+b)` |

`:nth-child` accepts `odd` / `even` / `3` / `2n+1` / `n+1` / `-n+3`, etc.

```java
doc.select("div p");                 // all descendant p
doc.select("body > div");            // direct children
doc.select("a[href^=https]");        // attribute prefix
doc.select("li:first-child");        // first li
doc.select("li:not(.done)");         // exclude li with class done
doc.select("li:nth-child(2n+1)");    // odd items
doc.selectFirst("title");            // first match, else null
```

`Elements.select(css)` queries the subtree of every element in the set and merges the results.

## 5. Entity Escaping

Parsing decodes entities in text and attribute values; escaping is available in reverse.

```java
Html.escape("a<b>&");        // "a&lt;b&gt;&amp;"
Html.unescape("a&amp;b");    // "a&b"
Html.unescape("&#65;");      // "A"
Html.unescape("&#x41;");     // "A"
```

Named entities: `&amp;` `&lt;` `&gt;` `&quot;` `&apos;` `&nbsp;`. Numeric entities support decimal `&#65;` and hex `&#x41;`. An unrecognised `&` is preserved verbatim.

## 6. Tolerance and Limits

The parser tolerates malformed HTML:

- An unclosed tag is implicitly closed together with its ancestors when the matching end tag is seen (`<div><span>hi</div>` closes `span` too);
- Void elements (`br` / `img` / `input` / `meta`, …) are never pushed on the stack and serialize without a closing tag;
- Self-closing `<tag/>` and truncated forms like `<div/` terminate safely instead of looping;
- `<script>` / `<style>` content is treated as raw text (no tags parsed inside); `<textarea>` likewise, but with entity decoding.

Known limits (vs Jsoup):

- No HTML5 implicit element insertion/reordering; the DOM mirrors source nesting;
- Not the full pseudo-class semantics (no `:has()`, `:nth-of-type()`, `::before`, …);
- `:not()` does not accept combinators — `div:not(a b)` is invalid.

## 7. Exceptions

| Exception | When |
|-----------|------|
| `SelectorException` | An unsupported pseudo-class, or a selector string that cannot be parsed |

Parsing itself throws nothing — all malformed input is adapted into a DOM rather than aborting on dirty HTML.
