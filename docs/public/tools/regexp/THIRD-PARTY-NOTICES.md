# 第三方版权声明

本工具（`/tools/regexp/`）的全部匹配计算均在本地浏览器中完成，不向任何服务器发送测试文本。

## FeHelper（Web 前端助手）

- 来源：<https://github.com/zxlie/FeHelper>（`apps/regexp/`）
- 许可协议：MIT
- 用途：本工具的功能设计（正则可视化调试、常用正则分类速查、JavaScript / Python / PHP
  /Java 四种写法弹窗）以及 `data.js` 中常用正则的初始数据，整理自 FeHelper 同名工具；
  页面代码为本项目重新编写。
- 数据修订：`data.js` 收录时逐条校验（JavaScript、Python 双引擎编译验证
  加功能样本验证），从上游源码原文还原了 19 条因「单反斜杠被 JS 字符串
  吞掉」而语义损坏的正则（典型如 `email-simple` 的 `\w` 退化成 `w`、
  HTML 系列的 `[\s\S]` 退化成 `[sS]`、`double-byte-char` 的 `\x00-\xff`
  退化成 NUL 字符），并修正了 `unicode` 条目的跨语言字面量写法；
  同时补齐了全部条目的英文标题与说明。

```
MIT License

Copyright (c) 2019 zhaoxianlie (xianliezhao@foxmail.com)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
