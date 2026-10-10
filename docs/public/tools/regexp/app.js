/*
 * 正则表达式速查工具（jkit 在线工具）
 *
 * 功能对标 FeHelper「正则表达式工具」：
 *   https://github.com/zxlie/FeHelper/blob/master/apps/regexp/ （MIT License,
 *   Copyright (c) 2019 zhaoxianlie）。在其基础上重写为零依赖的原生页面，补齐了
 *   捕获组展示、零宽匹配防死循环、中英文双语、深色模式与本地状态持久化。
 * 常用正则数据见 data.js；全部计算在本地浏览器完成，不上传任何内容。
 */
(function () {
  'use strict';

  var $ = function (id) {
    return document.getElementById(id);
  };

  var DATA = window.REGEX_DATA || { categories: [], entries: [] };

  var patternInput = $('patternInput');
  var flagsInput = $('flagsInput');
  var testInput = $('testInput');
  var presetSelect = $('presetSelect');
  var flagHint = $('flagHint');
  var errorMsg = $('errorMsg');
  var statusMsg = $('statusMsg');
  var highlightView = $('highlightView');
  var highlightCode = highlightView.querySelector('code');
  var matchList = $('matchList');
  var searchInput = $('searchInput');
  var searchCount = $('searchCount');
  var libraryContainer = $('libraryContainer');
  var emptySearch = $('emptySearch');
  var syntaxContainer = $('syntaxContainer');
  var modalMask = $('modalMask');
  var modalClose = $('modalClose');
  var modalTitle = $('modalTitle');
  var modalDesc = $('modalDesc');
  var modalCodes = $('modalCodes');
  var loadBtn = $('loadBtn');
  var langBtn = $('langBtn');
  var themeBtn = $('themeBtn');

  var FLAG_LIST = ['g', 'i', 'm', 's', 'u', 'y'];
  var MAX_MATCHES = 1000;
  var ROW_LIMIT = 100;

  /* ---------------- 国际化 ---------------- */
  var I18N = {
    zh: {
      title: '正则表达式速查 · jkit 在线工具',
      brandTool: '正则表达式速查',
      backHome: '返回文档',
      debuggerTitle: '正则表达式调试',
      presetPlaceholder: '自定义 / 选择常用正则',
      patternPh: '请输入正则表达式，如：^\\d{3}-\\d{8}$',
      testTextPh: '请输入要测试的文本',
      noMatch: '没有匹配，试着调整正则或标志',
      matchOne: '匹配 1 处',
      matchN: '匹配 {n} 处',
      matchCapped: '匹配 {n} 处（仅展示前 {n} 处）',
      emptyMatch: '（空匹配）',
      unnamedGroup: '第 {i} 组',
      libraryTitle: '常用正则速查库',
      searchPh: '搜索名称、说明或正则内容…',
      searchResult: '搜索到 {n} 条',
      emptySearch: '没有匹配的常用正则',
      syntaxTitle: '语法速查',
      copy: '复制',
      copied: '已复制 ✓',
      loadToDebugger: '↓ 填入调试器',
      footPrivacy: '🔒 所有匹配均在本地浏览器中完成，测试文本不会上传到任何服务器。',
      footCredit1: '功能与常用正则数据对标 FeHelper「正则表达式工具」',
      invalidRegex: '正则无效：{msg}',
      flagTips: {
        g: 'g：全局匹配，找出所有匹配项',
        i: 'i：忽略大小写',
        m: 'm：多行模式，^ 与 $ 作用于每一行',
        s: 's：允许 . 匹配换行符',
        u: 'u：启用 Unicode 支持',
        y: 'y：粘性匹配，从 lastIndex 精确锚定',
      },
      syntax: [
        {
          title: '字符与字符类',
          rows: [
            ['.', '任意字符（标志 s 下含换行）'],
            ['\\d  \\D', '数字 / 非数字'],
            ['\\w  \\W', '单词字符 [A-Za-z0-9_] / 非单词字符'],
            ['\\s  \\S', '空白 / 非空白'],
            ['[abc]  [^abc]', '字符集合 / 取反'],
            ['[a-z0-9]', '字符范围'],
            ['\\b  \\B', '单词边界 / 非边界'],
            ['^  $', '字符串（多行时为行）开头 / 结尾'],
          ],
        },
        {
          title: '量词',
          rows: [
            ['*', '0 次或多次'],
            ['+', '1 次或多次'],
            ['?', '0 次或 1 次'],
            ['{n}', '恰好 n 次'],
            ['{n,}', '至少 n 次'],
            ['{n,m}', 'n 到 m 次'],
            ['*?  +?  ??', '惰性匹配（默认贪婪）'],
          ],
        },
        {
          title: '分组与引用',
          rows: [
            ['(abc)', '捕获组'],
            ['(?:abc)', '非捕获组'],
            ['(?<name>abc)', '命名捕获组'],
            ['a|b', '或：匹配 a 或 b'],
            ['\\1  $1', '反向引用 / 替换中引用分组'],
            ['(?=x)  (?!x)', '正向 / 负向先行断言'],
            ['(?<=x)  (?<!x)', '正向 / 负向后行断言'],
          ],
        },
        {
          title: '标志位',
          rows: [
            ['g', '全局匹配'],
            ['i', '忽略大小写'],
            ['m', '多行模式'],
            ['s', '. 匹配换行符'],
            ['u', 'Unicode 模式'],
            ['y', '粘性匹配'],
          ],
        },
      ],
    },
    en: {
      title: 'Regex Cheat Sheet · jkit Online Tool',
      brandTool: 'Regex Cheat Sheet',
      backHome: 'Docs',
      debuggerTitle: 'Regex Debugger',
      presetPlaceholder: 'Custom / choose a preset',
      patternPh: 'Enter a pattern, e.g. ^\\d{3}-\\d{8}$',
      testTextPh: 'Enter the text to test against',
      noMatch: 'No matches — tweak the pattern or flags',
      matchOne: '1 match',
      matchN: '{n} matches',
      matchCapped: '{n} matches (showing first {n})',
      emptyMatch: '(empty match)',
      unnamedGroup: 'Group {i}',
      libraryTitle: 'Common Regex Library',
      searchPh: 'Search name, description or pattern…',
      searchResult: '{n} result(s)',
      emptySearch: 'No common regex matches',
      syntaxTitle: 'Syntax Cheat Sheet',
      copy: 'Copy',
      copied: 'Copied ✓',
      loadToDebugger: '↓ Load into debugger',
      footPrivacy: '🔒 Everything runs locally in your browser — test text is never uploaded.',
      footCredit1: 'Feature parity and preset data with FeHelper "Regex Tool"',
      invalidRegex: 'Invalid regex: {msg}',
      flagTips: {
        g: 'g: global — find all matches',
        i: 'i: ignore case',
        m: 'm: multiline — ^ and $ match per line',
        s: 's: DotAll — . matches line breaks',
        u: 'u: Unicode mode',
        y: 'y: sticky — anchor at lastIndex',
      },
      syntax: [
        {
          title: 'Characters & Classes',
          rows: [
            ['.', 'Any character (incl. newline with s)'],
            ['\\d  \\D', 'Digit / non-digit'],
            ['\\w  \\W', 'Word char [A-Za-z0-9_] / non-word'],
            ['\\s  \\S', 'Whitespace / non-whitespace'],
            ['[abc]  [^abc]', 'Character set / negated set'],
            ['[a-z0-9]', 'Character range'],
            ['\\b  \\B', 'Word boundary / non-boundary'],
            ['^  $', 'Start / end (of line with m)'],
          ],
        },
        {
          title: 'Quantifiers',
          rows: [
            ['*', '0 or more times'],
            ['+', '1 or more times'],
            ['?', '0 or 1 time'],
            ['{n}', 'Exactly n times'],
            ['{n,}', 'n or more times'],
            ['{n,m}', 'Between n and m times'],
            ['*?  +?  ??', 'Lazy versions (greedy by default)'],
          ],
        },
        {
          title: 'Groups & References',
          rows: [
            ['(abc)', 'Capturing group'],
            ['(?:abc)', 'Non-capturing group'],
            ['(?<name>abc)', 'Named capturing group'],
            ['a|b', 'Alternation: a or b'],
            ['\\1  $1', 'Backreference / group in replacement'],
            ['(?=x)  (?!x)', 'Positive / negative lookahead'],
            ['(?<=x)  (?<!x)', 'Positive / negative lookbehind'],
          ],
        },
        {
          title: 'Flags',
          rows: [
            ['g', 'Global match'],
            ['i', 'Case-insensitive'],
            ['m', 'Multiline'],
            ['s', 'DotAll'],
            ['u', 'Unicode mode'],
            ['y', 'Sticky'],
          ],
        },
      ],
    },
  };

  var lang = localStorage.getItem('jkit-tool-lang') || 'zh';
  function t(key) {
    return I18N[lang][key];
  }
  function fmt(key, vars) {
    return String(t(key)).replace(/\{(\w+)\}/g, function (_, k) {
      return vars[k] != null ? vars[k] : '';
    });
  }

  function applyI18n() {
    document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
    document.title = t('title');
    var nodes = document.querySelectorAll('[data-i18n]');
    for (var i = 0; i < nodes.length; i++) {
      var key = nodes[i].getAttribute('data-i18n');
      // 缺键时保留 HTML 里的兜底文案，避免把标题抹成空白
      if (t(key) != null) nodes[i].textContent = t(key);
    }
    var phNodes = document.querySelectorAll('[data-i18n-ph]');
    for (var j = 0; j < phNodes.length; j++) {
      phNodes[j].placeholder = t(phNodes[j].getAttribute('data-i18n-ph'));
    }
    langBtn.textContent = lang === 'zh' ? 'EN' : '中';
    // 占位 option 文本
    presetSelect.options[0].textContent = t('presetPlaceholder');
    renderLibraryDesc();
    renderSyntax();
    renderLibrary();
    updateFlagHint();
  }

  langBtn.addEventListener('click', function () {
    lang = lang === 'zh' ? 'en' : 'zh';
    localStorage.setItem('jkit-tool-lang', lang);
    applyI18n();
    if (modalEntry) renderModal(modalEntry);
  });

  /* ---------------- 主题 ---------------- */
  var theme =
    localStorage.getItem('jkit-tool-theme') ||
    (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
      ? 'dark'
      : 'light');
  document.documentElement.setAttribute('data-theme', theme);
  themeBtn.addEventListener('click', function () {
    theme = theme === 'dark' ? 'light' : 'dark';
    localStorage.setItem('jkit-tool-theme', theme);
    document.documentElement.setAttribute('data-theme', theme);
  });

  /* ---------------- 工具函数 ---------------- */
  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  // 数据里的 js 字段是 /pattern/flags 字面量，拆出裸 pattern 与 flags，
  // pattern 里被转义的 \/ 不算分隔符。
  function parseJsLiteral(literal) {
    for (var i = 1; i < literal.length; i++) {
      if (literal[i] === '/') {
        var backslashes = 0;
        for (var j = i - 1; j >= 0 && literal[j] === '\\'; j--) backslashes++;
        if (backslashes % 2 === 0) {
          return { body: literal.slice(1, i), flags: literal.slice(i + 1) };
        }
      }
    }
    return { body: literal, flags: '' };
  }

  function sanitizeFlags(raw) {
    var seen = {};
    var out = '';
    for (var i = 0; i < raw.length; i++) {
      var f = raw[i].toLowerCase();
      if (FLAG_LIST.indexOf(f) >= 0 && !seen[f]) {
        seen[f] = true;
        out += f;
      }
    }
    return out;
  }

  /* ---------------- 本地状态 ---------------- */
  var STORE_KEY = 'jkit-regexp-state-v1';
  try {
    var saved = JSON.parse(localStorage.getItem(STORE_KEY) || 'null');
    if (saved) {
      if (typeof saved.pattern === 'string') patternInput.value = saved.pattern;
      if (typeof saved.flags === 'string') flagsInput.value = sanitizeFlags(saved.flags);
      if (typeof saved.text === 'string') testInput.value = saved.text;
    }
  } catch (e) {
    /* 损坏的本地存储直接忽略 */
  }
  if (!testInput.value) {
    testInput.value =
      lang === 'en'
        ? 'Today is 2026-10-10; jkit 2.0.4 was released on 2025-03-18.'
        : '今天是 2026-10-10，jkit 2.0.4 发布于 2025-03-18。';
  }
  if (!patternInput.value) patternInput.value = '(\\d{4})-(\\d{2})-(\\d{2})';

  function persist() {
    try {
      localStorage.setItem(
        STORE_KEY,
        JSON.stringify({ pattern: patternInput.value, flags: flagsInput.value, text: testInput.value })
      );
    } catch (e) {
      /* 隐私模式下静默失败 */
    }
  }

  /* ---------------- 标志位 chips ---------------- */
  var flagChips = document.querySelectorAll('.flag-chip');
  function syncChips() {
    for (var i = 0; i < flagChips.length; i++) {
      var on = flagsInput.value.indexOf(flagChips[i].getAttribute('data-flag')) >= 0;
      flagChips[i].classList.toggle('on', on);
    }
  }
  function updateFlagHint() {
    var active = FLAG_LIST.filter(function (f) {
      return flagsInput.value.indexOf(f) >= 0;
    });
    flagHint.textContent = active.length ? t('flagTips')[active[0]] : t('flagTips').g;
  }
  for (var ci = 0; ci < flagChips.length; ci++) {
    flagChips[ci].addEventListener('click', function () {
      var f = this.getAttribute('data-flag');
      var set = flagsInput.value.split('');
      var idx = set.indexOf(f);
      if (idx >= 0) set.splice(idx, 1);
      else set.push(f);
      flagsInput.value = FLAG_LIST.filter(function (x) {
        return set.indexOf(x) >= 0;
      }).join('');
      syncChips();
      updateFlagHint();
      runMatch();
      persist();
    });
  }
  flagsInput.addEventListener('input', function () {
    var pos = flagsInput.selectionStart;
    flagsInput.value = sanitizeFlags(flagsInput.value);
    flagsInput.setSelectionRange(pos, pos);
    syncChips();
    updateFlagHint();
    runMatch();
    persist();
  });

  /* ---------------- 匹配引擎 ---------------- */
  var selectedMatch = null;

  function runMatch() {
    var body = patternInput.value;
    var flags = flagsInput.value;
    var text = testInput.value;
    selectedMatch = null;

    if (!body) {
      errorMsg.hidden = true;
      statusMsg.textContent = '';
      highlightView.hidden = true;
      matchList.innerHTML = '';
      return;
    }

    var re;
    try {
      re = new RegExp(body, flags);
    } catch (e) {
      errorMsg.hidden = false;
      errorMsg.textContent = fmt('invalidRegex', { msg: e.message });
      statusMsg.textContent = '';
      highlightView.hidden = true;
      matchList.innerHTML = '';
      return;
    }
    errorMsg.hidden = true;

    var matches = [];
    var capped = false;
    var lastIndex = 0;
    re.lastIndex = 0;
    // 手动推进 lastIndex，零宽匹配也不会死循环
    while (lastIndex <= text.length) {
      re.lastIndex = lastIndex;
      var m = re.exec(text);
      if (!m) break;
      var groups = [];
      var gi;
      for (gi = 1; gi < m.length; gi++) {
        groups.push({ name: fmt('unnamedGroup', { i: gi }), value: m[gi] });
      }
      if (m.groups) {
        Object.keys(m.groups).forEach(function (name) {
          if (m.groups[name] !== undefined) groups.push({ name: name, value: m.groups[name] });
        });
      }
      matches.push({
        text: m[0],
        start: m.index,
        end: m.index + m[0].length,
        groups: groups,
      });
      if (matches.length >= MAX_MATCHES) {
        capped = true;
        break;
      }
      if (!re.global && !re.sticky) break;
      if (m[0].length === 0) lastIndex = m.index + 1;
      else lastIndex = m.index + m[0].length;
    }

    renderStatus(matches.length, capped);
    renderHighlight(text, matches);
    renderMatchList(matches);
  }

  function renderStatus(n, capped) {
    if (!n) {
      statusMsg.textContent = t('noMatch');
      statusMsg.className = 'status-msg empty';
    } else {
      statusMsg.className = 'status-msg ok';
      statusMsg.textContent =
        n === 1 && !capped ? t('matchOne') : fmt(capped ? 'matchCapped' : 'matchN', { n: n });
    }
  }

  function renderHighlight(text, matches) {
    if (!matches.length) {
      highlightView.hidden = true;
      return;
    }
    var html = '';
    var cursor = 0;
    matches.forEach(function (m, i) {
      if (m.start > cursor) html += escapeHtml(text.slice(cursor, m.start));
      var inner = m.text === '' ? '∅' : escapeHtml(m.text);
      html +=
        '<span class="hit' +
        (m.text === '' ? ' hit-empty' : '') +
        '" data-idx="' +
        i +
        '">' +
        inner +
        '</span>';
      cursor = m.end;
    });
    if (cursor < text.length) html += escapeHtml(text.slice(cursor));
    highlightCode.innerHTML = html;
    highlightView.hidden = false;
  }

  function renderMatchList(matches) {
    if (!matches.length) {
      matchList.innerHTML = '';
      return;
    }
    var shown = matches.slice(0, ROW_LIMIT);
    var html = shown
      .map(function (m, i) {
        var groups = m.groups
          .map(function (g) {
            return (
              '<span class="group-tag">' +
              escapeHtml(g.name) +
              '=<q>' +
              escapeHtml(g.value == null ? '' : g.value) +
              '</q></span>'
            );
          })
          .join('');
        var text = m.text === '' ? t('emptyMatch') : escapeHtml(m.text);
        return (
          '<div class="match-row" data-idx="' +
          i +
          '">' +
          '<span class="match-index">' +
          (i + 1) +
          '</span>' +
          '<code class="match-text">' +
          text +
          '</code>' +
          '<span class="match-pos">[' +
          m.start +
          '…' +
          m.end +
          ')</span>' +
          (groups ? '<span class="match-groups">' + groups + '</span>' : '') +
          '</div>'
        );
      })
      .join('');
    if (matches.length > ROW_LIMIT) {
      var more = lang === 'en' ? '… {n} more matches not expanded' : '… 另有 {n} 条匹配未展开';
      html += '<div class="more-tip">' + more.replace('{n}', matches.length - ROW_LIMIT) + '</div>';
    }
    matchList.innerHTML = html;
  }

  function toggleSelected(idx) {
    selectedMatch = selectedMatch === idx ? null : idx;
    var nodes = document.querySelectorAll('[data-idx]');
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].classList.toggle('active', Number(nodes[i].getAttribute('data-idx')) === selectedMatch);
    }
  }
  matchList.addEventListener('click', function (e) {
    var row = e.target.closest('.match-row');
    if (row) toggleSelected(Number(row.getAttribute('data-idx')));
  });
  highlightCode.addEventListener('click', function (e) {
    var hit = e.target.closest('.hit');
    if (hit) toggleSelected(Number(hit.getAttribute('data-idx')));
  });

  /* ---------------- 预设下拉 ---------------- */
  function entryTitle(e) {
    return lang === 'zh' ? e.title.zh : e.title.en;
  }
  function catName(c) {
    return lang === 'zh' ? c.zh : c.en;
  }

  DATA.categories.forEach(function (c) {
    var group = document.createElement('optgroup');
    group.label = c.zh; // 语言切换时刷新
    group.setAttribute('data-cat', c.zh);
    DATA.entries
      .filter(function (e) {
        return e.cat === c.zh;
      })
      .forEach(function (e) {
        var opt = document.createElement('option');
        opt.value = e.key;
        opt.textContent = entryTitle(e);
        group.appendChild(opt);
      });
    presetSelect.appendChild(group);
  });

  function refreshPresetLabels() {
    for (var i = 0; i < presetSelect.options.length; i++) {
      var opt = presetSelect.options[i];
      if (opt.parentElement.tagName === 'OPTGROUP') {
        var groupCn = opt.parentElement.getAttribute('data-cat');
        opt.parentElement.label = catName(DATA.categories.find(function (c) {
          return c.zh === groupCn;
        }) || { zh: groupCn, en: groupCn });
      }
      if (opt.value) {
        var e = DATA.entries.find(function (x) {
          return x.key === opt.value;
        });
        if (e) opt.textContent = entryTitle(e);
      }
    }
    presetSelect.options[0].textContent = t('presetPlaceholder');
  }

  presetSelect.addEventListener('change', function () {
    var entry = DATA.entries.find(function (e) {
      return e.key === presetSelect.value;
    });
    if (entry) loadEntry(entry);
  });

  /* ---------------- 常用正则库 ---------------- */
  function renderLibraryDesc() {
    var zh = '共 ' + DATA.entries.length + ' 条，按场景分类；点击卡片查看 JavaScript / Python / PHP / Java 四种写法并一键填入调试器。';
    var en =
      DATA.entries.length +
      ' patterns grouped by scenario. Click a card for JavaScript / Python / PHP / Java variants and to load it into the debugger.';
    $('libraryDesc').textContent = lang === 'zh' ? zh : en;
  }

  function renderLibrary() {
    refreshPresetLabels();
    var kw = searchInput.value.trim().toLowerCase();
    var filtered = DATA.entries;
    if (kw) {
      filtered = DATA.entries.filter(function (e) {
        return [e.title.zh, e.title.en, e.desc.zh, e.desc.en, e.key, e.p.js, e.p.java]
          .join('\n')
          .toLowerCase()
          .indexOf(kw) >= 0;
      });
      searchCount.textContent = fmt('searchResult', { n: filtered.length });
    } else {
      searchCount.textContent = '';
    }
    emptySearch.hidden = filtered.length > 0;

    var html = DATA.categories
      .map(function (c) {
        var items = filtered.filter(function (e) {
          return e.cat === c.zh;
        });
        if (!items.length) return '';
        var cards = items
          .map(function (e) {
            return (
              '<button type="button" class="rx-item" data-key="' +
              e.key +
              '">' +
              '<span class="rx-item-title">' +
              escapeHtml(entryTitle(e)) +
              '</span>' +
              '<code class="rx-item-pattern">' +
              escapeHtml(e.p.js) +
              '</code>' +
              '</button>'
            );
          })
          .join('');
        return (
          '<div class="rx-group"><h2 class="rx-group-title">' +
          escapeHtml(catName(c)) +
          '<span class="rx-group-count">' +
          items.length +
          '</span></h2><div class="rx-grid">' +
          cards +
          '</div></div>'
        );
      })
      .join('');
    libraryContainer.innerHTML = html;
  }

  let searchTimer;
  searchInput.addEventListener('input', function () {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(renderLibrary, 120);
  });
  libraryContainer.addEventListener('click', function (e) {
    var card = e.target.closest('.rx-item');
    if (!card) return;
    var entry = DATA.entries.find(function (x) {
      return x.key === card.getAttribute('data-key');
    });
    if (entry) openModal(entry);
  });

  /* ---------------- 语法速查 ---------------- */
  function renderSyntax() {
    syntaxContainer.innerHTML = t('syntax')
      .map(function (g) {
        var rows = g.rows
          .map(function (r) {
            return (
              '<tr><td class="rx-token"><code>' +
              escapeHtml(r[0]) +
              '</code></td><td>' +
              escapeHtml(r[1]) +
              '</td></tr>'
            );
          })
          .join('');
        return '<div class="rx-syntax-group"><h2 class="rx-group-title">' + escapeHtml(g.title) + '</h2><table class="rx-table"><tbody>' + rows + '</tbody></table></div>';
      })
      .join('');
  }

  /* ---------------- 弹窗 ---------------- */
  var modalEntry = null;

  function openModal(entry) {
    modalEntry = entry;
    renderModal(entry);
    modalMask.hidden = false;
    document.body.style.overflow = 'hidden';
  }
  function closeModal() {
    modalMask.hidden = true;
    modalEntry = null;
    document.body.style.overflow = '';
  }

  function renderModal(entry) {
    modalTitle.textContent = entryTitle(entry);
    modalDesc.textContent = lang === 'zh' ? entry.desc.zh : entry.desc.en;
    var langs = [
      ['JavaScript', entry.p.js],
      ['Python', entry.p.py],
      ['PHP', entry.p.php],
      ['Java', entry.p.java],
    ];
    modalCodes.innerHTML = langs
      .map(function (l) {
        return (
          '<div class="rx-code-block"><div class="rx-code-head"><span>' +
          l[0] +
          '</span><button type="button" class="btn btn-sm rx-copy" data-code="' +
          encodeURIComponent(l[1]) +
          '">' +
          t('copy') +
          '</button></div><pre><code>' +
          escapeHtml(l[1]) +
          '</code></pre></div>'
        );
      })
      .join('');
  }

  modalCodes.addEventListener('click', function (e) {
    var btn = e.target.closest('.rx-copy');
    if (!btn) return;
    var code = decodeURIComponent(btn.getAttribute('data-code'));
    copyText(code, function () {
      btn.textContent = t('copied');
      btn.classList.add('copied');
      setTimeout(function () {
        btn.textContent = t('copy');
        btn.classList.remove('copied');
      }, 1500);
    });
  });

  function copyText(text, done) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () {
        legacyCopy(text, done);
      });
    } else {
      legacyCopy(text, done);
    }
  }
  function legacyCopy(text, done) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand('copy');
      done();
    } catch (e) {
      /* 剪贴板被禁用时忽略 */
    }
    document.body.removeChild(ta);
  }

  function loadEntry(entry) {
    var parsed = parseJsLiteral(entry.p.js);
    patternInput.value = parsed.body;
    flagsInput.value = parsed.flags || 'g';
    syncChips();
    updateFlagHint();
    runMatch();
    persist();
    $('debugger').scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
  loadBtn.addEventListener('click', function () {
    if (modalEntry) {
      loadEntry(modalEntry);
      closeModal();
    }
  });
  modalClose.addEventListener('click', closeModal);
  modalMask.addEventListener('click', function (e) {
    if (e.target === modalMask) closeModal();
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && !modalMask.hidden) closeModal();
  });

  /* ---------------- 调试器输入 ---------------- */
  patternInput.addEventListener('input', function () {
    runMatch();
    persist();
    // 手动改动后取消下拉选中态
    var entry = DATA.entries.find(function (e) {
      return e.key === presetSelect.value;
    });
    if (entry && parseJsLiteral(entry.p.js).body !== patternInput.value) presetSelect.value = '';
  });
  testInput.addEventListener('input', function () {
    runMatch();
    persist();
  });

  applyI18n();
  syncChips();
  runMatch();
})();
