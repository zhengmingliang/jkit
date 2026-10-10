/*
 * Excel/CSV 转 JSON 工具（jkit 在线工具）
 *
 * 功能对标 FeHelper「Excel/CSV 转 JSON」：
 *   https://github.com/zxlie/FeHelper/blob/master/apps/excel2json/index.js （MIT License,
 *   Copyright (c) 2019 zhaoxianlie），在其基础上补齐了带引号 CSV 解析、多工作表选择、
 *   日期单元格（ISO 字符串）、结果下载、深色模式与中英文切换。
 * Excel(.xlsx/.xls) 解析由 SheetJS 0.18.5（Apache-2.0）在本地完成，文件不上传。
 */
(function () {
  'use strict';

  var $ = function (id) {
    return document.getElementById(id);
  };

  var fileInput = $('fileInput');
  var fileBtn = $('fileBtn');
  var sheetSelect = $('sheetSelect');
  var tableNameInput = $('tableName');
  var pasteInput = $('pasteInput');
  var jsonOutput = $('jsonOutput');
  var errorMsg = $('errorMsg');
  var statusMsg = $('statusMsg');
  var convertBtn = $('convertBtn');
  var sqlBtn = $('sqlBtn');
  var copyBtn = $('copyBtn');
  var downloadBtn = $('downloadBtn');
  var clearBtn = $('clearBtn');
  var langBtn = $('langBtn');
  var themeBtn = $('themeBtn');

  /* ---------------- 国际化 ---------------- */
  var I18N = {
    zh: {
      title: 'Excel/CSV 转 JSON · jkit 在线工具',
      brandTool: 'Excel/CSV 转 JSON',
      backHome: '返回文档',
      pickFile: '选择 Excel/CSV 文件',
      examples: '示例：',
      exSimple: '简单表格',
      exUser: '用户信息',
      exScore: '成绩单',
      tableName: 'SQL 表名',
      inputTitle: '输入（CSV / TSV / 单列文本，首行为表头）',
      inputPh: '可直接粘贴表格内容：支持 CSV（逗号分隔）、TSV（Tab 分隔，可从 Excel 直接粘贴）或单列多行文本',
      outputTitle: '输出结果',
      clear: '清空',
      toJson: '转换为 JSON',
      toSql: '转换为 SQL Insert',
      copy: '复制',
      download: '下载',
      outputPh: '转换结果将显示在这里',
      footPrivacy: '🔒 所有解析与转换均在本地浏览器中完成，文件不会上传到任何服务器。',
      footCredit1: '功能对标 FeHelper「Excel/CSV 转 JSON」',
      footCredit2: 'Excel 解析使用 SheetJS',
      footNotices: '第三方版权声明',
      copied: '已复制 ✓',
      errNoInput: '请上传文件或粘贴表格数据！',
      errNeedRows: '内容格式不正确，至少需要表头和一行数据！',
      errUnsupported: '仅支持 CSV、TSV 或单列表格的粘贴内容！',
      errParseCsv: '粘贴内容（CSV）解析失败，请检查格式！',
      errParseTsv: '粘贴内容（TSV）解析失败，请检查格式！',
      errParseSingle: '单列内容解析失败，请检查格式！',
      errExcel: 'Excel 文件解析失败，请确认文件格式！',
      errCsv: 'CSV 文件解析失败，请确认内容格式！',
      errExt: '仅支持 Excel（.xlsx/.xls）或 CSV 文件！',
      errNoXlsx: 'Excel 解析库未加载，请刷新页面后重试。',
      errEmptySheet: '该工作表没有数据！',
      errNoSqlData: '没有可用数据生成 SQL！',
      errNoCopy: '暂无内容可复制！',
      errNoDownload: '暂无内容可下载！',
      loaded: function (name, sheet, n) {
        return '已加载 ' + name + '（工作表：' + sheet + '）：' + n + ' 行数据';
      },
    },
    en: {
      title: 'Excel/CSV to JSON · jkit Online Tool',
      brandTool: 'Excel/CSV to JSON',
      backHome: 'Docs',
      pickFile: 'Choose Excel/CSV File',
      examples: 'Examples: ',
      exSimple: 'Simple',
      exUser: 'Users',
      exScore: 'Scores',
      tableName: 'SQL table',
      inputTitle: 'Input (CSV / TSV / single column, first row is header)',
      inputPh:
        'Paste tabular data here: CSV (comma-separated), TSV (tab-separated, paste straight from Excel) or a single column',
      outputTitle: 'Output',
      clear: 'Clear',
      toJson: 'Convert to JSON',
      toSql: 'Convert to SQL Insert',
      copy: 'Copy',
      download: 'Download',
      outputPh: 'Conversion result will appear here',
      footPrivacy:
        '🔒 Everything is parsed and converted locally in your browser — no file is uploaded.',
      footCredit1: 'Feature parity with FeHelper "Excel/CSV to JSON"',
      footCredit2: 'Excel parsing powered by SheetJS',
      footNotices: 'Third-party notices',
      copied: 'Copied ✓',
      errNoInput: 'Please upload a file or paste tabular data first!',
      errNeedRows: 'Invalid content: a header row plus at least one data row are required!',
      errUnsupported: 'Only CSV, TSV or single-column text is supported!',
      errParseCsv: 'Failed to parse pasted CSV, please check the format!',
      errParseTsv: 'Failed to parse pasted TSV, please check the format!',
      errParseSingle: 'Failed to parse single-column content, please check the format!',
      errExcel: 'Failed to parse the Excel file, please verify the file format!',
      errCsv: 'Failed to parse the CSV file, please verify its content!',
      errExt: 'Only Excel (.xlsx/.xls) or CSV files are supported!',
      errNoXlsx: 'Excel parser library is not loaded, please reload the page.',
      errEmptySheet: 'This worksheet has no data!',
      errNoSqlData: 'No data available to generate SQL!',
      errNoCopy: 'Nothing to copy!',
      errNoDownload: 'Nothing to download!',
      loaded: function (name, sheet, n) {
        return 'Loaded ' + name + ' (sheet: ' + sheet + '): ' + n + ' rows';
      },
    },
  };

  var lang = localStorage.getItem('jkit-tool-lang') || 'zh';
  function t(key) {
    return I18N[lang][key];
  }

  function applyI18n() {
    document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
    document.title = t('title');
    var nodes = document.querySelectorAll('[data-i18n]');
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].textContent = t(nodes[i].getAttribute('data-i18n'));
    }
    var phNodes = document.querySelectorAll('[data-i18n-ph]');
    for (var j = 0; j < phNodes.length; j++) {
      phNodes[j].placeholder = t(phNodes[j].getAttribute('data-i18n-ph'));
    }
    langBtn.textContent = lang === 'zh' ? 'EN' : '中';
  }

  langBtn.addEventListener('click', function () {
    lang = lang === 'zh' ? 'en' : 'zh';
    localStorage.setItem('jkit-tool-lang', lang);
    applyI18n();
    // 状态/错误文案是生成时语言，切换语言后清掉避免残留另一种语言
    statusMsg.textContent = '';
    errorMsg.textContent = '';
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

  /* ---------------- 状态 ---------------- */
  var workbook = null; // XLSX 读入的工作簿（仅 Excel 文件）
  var currentFileStem = ''; // 上传文件名（不含扩展名），用于下载命名
  var lastMode = 'json'; // 输出区当前内容类型，决定下载扩展名

  function clearError() {
    errorMsg.textContent = '';
  }
  function showError(msg) {
    statusMsg.textContent = '';
    errorMsg.textContent = msg;
  }
  function showStatus(msg) {
    errorMsg.textContent = '';
    statusMsg.textContent = msg;
  }

  /* ---------------- 文本表格解析（CSV / TSV，支持引号） ---------------- */

  // 自动识别数字，规则与 FeHelper 版保持一致
  function parseValue(val) {
    if (/^-?\d+(\.\d+)?$/.test(val)) {
      return Number(val);
    }
    return val;
  }

  // 支持双引号包裹（字段内可含分隔符、换行、"" 转义）；返回未加工的二维数组
  function splitDelimited(text, sep) {
    if (text.charCodeAt(0) === 0xfeff) {
      text = text.slice(1); // 去 BOM
    }
    var rows = [];
    var row = [];
    var field = '';
    var inQuotes = false;

    function endRow() {
      row.push(field);
      rows.push(row);
      row = [];
      field = '';
    }

    for (var i = 0; i < text.length; i++) {
      var c = text[i];
      if (inQuotes) {
        if (c === '"') {
          if (text[i + 1] === '"') {
            field += '"';
            i++;
          } else {
            inQuotes = false;
          }
        } else if (c === '\r') {
          field += '\n';
          if (text[i + 1] === '\n') i++;
        } else if (c === '\n') {
          field += '\n';
        } else {
          field += c;
        }
      } else if (c === '"' && field === '') {
        inQuotes = true;
      } else if (c === sep) {
        row.push(field);
        field = '';
      } else if (c === '\n') {
        endRow();
      } else if (c === '\r') {
        if (text[i + 1] === '\n') i++;
        endRow();
      } else {
        field += c;
      }
    }
    if (field !== '' || row.length > 0) {
      endRow();
    }
    // 单元格 trim，丢弃整行为空的记录
    return rows
      .map(function (r) {
        return r.map(function (v) {
          return v.trim();
        });
      })
      .filter(function (r) {
        return r.some(function (v) {
          return v !== '';
        });
      });
  }

  // 二维数组转对象数组，首行表头；数字自动转 Number
  function rowsToObjects(rows) {
    var headers = rows[0];
    return rows.slice(1).map(function (values) {
      var obj = {};
      headers.forEach(function (h, idx) {
        if (h === '') return;
        obj[h] = parseValue(values[idx] != null ? values[idx] : '');
      });
      return obj;
    });
  }

  // 与 FeHelper 相同的嗅探顺序：TSV → CSV → 单列
  function parsePasted(text) {
    if (text.indexOf('\t') >= 0 && text.indexOf('\n') >= 0) {
      return { rows: rowsToObjects(splitDelimited(text, '\t')), errKey: 'errParseTsv' };
    }
    if (text.indexOf(',') >= 0 && text.indexOf('\n') >= 0) {
      return { rows: rowsToObjects(splitDelimited(text, ',')), errKey: 'errParseCsv' };
    }
    if (text.indexOf(',') < 0 && text.indexOf('\t') < 0 && text.indexOf('\n') >= 0) {
      return { rows: rowsToObjects(splitDelimited(text, ',')), errKey: 'errParseSingle' };
    }
    return { unsupported: true };
  }

  function rowsFromPastedText() {
    var text = pasteInput.value.trim();
    if (!text) {
      showError(t('errNoInput'));
      return null;
    }
    var result = parsePasted(text);
    if (result.unsupported) {
      showError(t('errUnsupported'));
      return null;
    }
    if (!result.rows.length) {
      showError(t('errNeedRows'));
      return null;
    }
    return result.rows;
  }

  /* ---------------- SQL Insert ---------------- */
  function sqlValue(v) {
    if (typeof v === 'number' || typeof v === 'boolean') {
      return String(v);
    }
    if (v == null) {
      return 'NULL';
    }
    if (v instanceof Date) {
      v = v.toISOString();
    }
    return "'" + String(v).replace(/'/g, "''") + "'";
  }

  function toSqlInsert(rows, table) {
    var cols = Object.keys(rows[0]);
    var values = rows
      .map(function (r) {
        return '(' + cols.map(function (c) { return sqlValue(r[c]); }).join(', ') + ')';
      })
      .join(',\n');
    return 'INSERT INTO ' + table + ' (' + cols.join(', ') + ') VALUES\n' + values + ';';
  }

  function safeTableName(raw) {
    var name = (raw || '').trim().replace(/[^A-Za-z0-9_$]/g, '_');
    return name || 'my_table';
  }

  /* ---------------- 输出 ---------------- */
  function renderJson(rows) {
    jsonOutput.value = JSON.stringify(rows, null, 2);
    lastMode = 'json';
  }

  /* ---------------- 转换按钮 ---------------- */
  convertBtn.addEventListener('click', function () {
    clearError();
    var rows = rowsFromPastedText();
    if (!rows) return;
    renderJson(rows);
  });

  sqlBtn.addEventListener('click', function () {
    clearError();
    var rows = rowsFromPastedText();
    if (!rows) return;
    jsonOutput.value = toSqlInsert(rows, safeTableName(tableNameInput.value));
    lastMode = 'sql';
  });

  /* ---------------- Excel / CSV 文件 ---------------- */
  fileBtn.addEventListener('click', function () {
    fileInput.click();
  });

  function loadSheet(sheetName) {
    var sheet = workbook.Sheets[sheetName];
    var rows = XLSX.utils.sheet_to_json(sheet, { defval: '' });
    pasteInput.value = XLSX.utils.sheet_to_csv(sheet).replace(/\n+$/, '');
    if (!rows.length) {
      jsonOutput.value = '';
      showError(t('errEmptySheet'));
      return;
    }
    renderJson(rows);
    showStatus(t('loaded')(currentFileStem, sheetName, rows.length));
  }

  function renderSheetOptions(names, selected) {
    if (names.length <= 1) {
      sheetSelect.hidden = true;
      sheetSelect.innerHTML = '';
      return;
    }
    sheetSelect.innerHTML = names
      .map(function (n) {
        var sel = n === selected ? ' selected' : '';
        return '<option value="' + n.replace(/"/g, '&quot;') + '"' + sel + '>' + n + '</option>';
      })
      .join('');
    sheetSelect.hidden = false;
  }

  sheetSelect.addEventListener('change', function () {
    clearError();
    loadSheet(sheetSelect.value);
  });

  fileInput.addEventListener('change', function (e) {
    clearError();
    var file = e.target.files[0];
    if (!file) return;
    var dot = file.name.lastIndexOf('.');
    var ext = dot >= 0 ? file.name.slice(dot + 1).toLowerCase() : '';
    currentFileStem = dot > 0 ? file.name.slice(0, dot) : file.name;

    if (ext === 'xlsx' || ext === 'xls') {
      if (typeof XLSX === 'undefined') {
        showError(t('errNoXlsx'));
        return;
      }
      var reader = new FileReader();
      reader.onload = function (evt) {
        try {
          var data = new Uint8Array(evt.target.result);
          workbook = XLSX.read(data, { type: 'array', cellDates: true });
          var firstSheet = workbook.SheetNames[0];
          renderSheetOptions(workbook.SheetNames, firstSheet);
          loadSheet(firstSheet);
        } catch (err) {
          showError(t('errExcel'));
        }
      };
      reader.onerror = function () {
        showError(t('errExcel'));
      };
      reader.readAsArrayBuffer(file);
    } else if (ext === 'csv') {
      workbook = null;
      sheetSelect.hidden = true;
      sheetSelect.innerHTML = '';
      var textReader = new FileReader();
      textReader.onload = function (evt) {
        try {
          pasteInput.value = evt.target.result;
          var rows = rowsFromPastedText();
          if (rows) {
            renderJson(rows);
            showStatus(t('loaded')(currentFileStem + '.csv', 'CSV', rows.length));
          }
        } catch (err) {
          showError(t('errCsv'));
        }
      };
      textReader.onerror = function () {
        showError(t('errCsv'));
      };
      textReader.readAsText(file);
    } else {
      showError(t('errExt'));
    }
    // 允许重复选择同一个文件
    fileInput.value = '';
  });

  /* ---------------- 示例 ---------------- */
  var EXAMPLES = {
    simple: '姓名,年龄,城市\n张三,18,北京\n李四,22,上海',
    user:
      'ID,用户名,邮箱\n1,alice,alice@example.com\n2,bob,bob@example.com\n3,charlie,charlie@example.com',
    score: '学号,姓名,数学,语文,英语\n1001,王小明,90,88,92\n1002,李小红,85,91,87\n1003,张大伟,78,80,85',
  };

  var exampleBtns = document.querySelectorAll('[data-example]');
  Array.prototype.forEach.call(exampleBtns, function (btn) {
    btn.addEventListener('click', function () {
      var type = btn.getAttribute('data-example');
      if (!EXAMPLES[type]) return;
      workbook = null;
      sheetSelect.hidden = true;
      sheetSelect.innerHTML = '';
      pasteInput.value = EXAMPLES[type];
      jsonOutput.value = '';
      currentFileStem = '';
      convertBtn.click();
    });
  });

  /* ---------------- 复制 ---------------- */
  copyBtn.addEventListener('click', function () {
    if (!jsonOutput.value) {
      showError(t('errNoCopy'));
      return;
    }
    var done = function () {
      copyBtn.textContent = t('copied');
      copyBtn.classList.add('copied');
      setTimeout(function () {
        copyBtn.textContent = t('copy');
        copyBtn.classList.remove('copied');
      }, 1500);
      clearError();
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(jsonOutput.value).then(done, function () {
        legacyCopy();
      });
    } else {
      legacyCopy();
    }
  });

  function legacyCopy() {
    jsonOutput.select();
    try {
      document.execCommand('copy');
    } catch (e) {
      /* 老浏览器且剪贴板被禁用时忽略 */
    }
  }

  /* ---------------- 下载 ---------------- */
  downloadBtn.addEventListener('click', function () {
    if (!jsonOutput.value) {
      showError(t('errNoDownload'));
      return;
    }
    var isSql = lastMode === 'sql';
    var mime = isSql ? 'text/plain;charset=utf-8' : 'application/json;charset=utf-8';
    var blob = new Blob([jsonOutput.value], { type: mime });
    var url = URL.createObjectURL(blob);
    var stamp =
      new Date().toISOString().slice(0, 10) +
      '-' +
      new Date().toTimeString().slice(0, 8).replace(/:/g, '');
    var base = currentFileStem || 'excel2json';
    var a = document.createElement('a');
    a.href = url;
    a.download = base + '-' + stamp + (isSql ? '.sql' : '.json');
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(function () {
      URL.revokeObjectURL(url);
    }, 1000);
  });

  /* ---------------- 清空 ---------------- */
  clearBtn.addEventListener('click', function () {
    workbook = null;
    currentFileStem = '';
    pasteInput.value = '';
    jsonOutput.value = '';
    sheetSelect.hidden = true;
    sheetSelect.innerHTML = '';
    fileInput.value = '';
    clearError();
    statusMsg.textContent = '';
  });

  applyI18n();
})();
