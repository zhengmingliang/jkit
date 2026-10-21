组件速查表
===

一份模拟 quickref.cn 排版的速查文档：H2 分区、H3 卡片、跨列跨行与属性注释。

安装
----

### 包管理器
<!--rehype:wrap-class=col-span-2 row-span-2-->

系统 | 命令
:- | :-
Debian | `sudo apt install jkit`
macOS | `brew install jkit`
Windows | `winget install jkit`
<!--rehype:className=show-header left-align-->

### 版本检查

```shell
$ jkit --version
```

### 依赖
<!--rehype:wrap-class=row-span-2-->

工具 | 用途
:- | :-
`jdk` | Java 运行时
`maven` | 构建工具
`git` | 版本控制

配置
----

### 配置文件
<!--rehype:wrap-class=col-span-2-->

文件 | 说明
:- | :-
`config.yml` | 主配置
`theme.yml` | 主题配置
`mail.yml` | 邮件配置
<!--rehype:className=show-header-->

### 示例

```yaml
jkit:
  theme: yazi
  highlight: true
```

快捷键
----

### 常用操作
<!--rehype:wrap-class=row-span-2-->

键位 | 操作
:- | :-
`Ctrl` + `C` | 复制
`Ctrl` + `V` | 粘贴
`Ctrl` + `Z` | 撤销
<!--rehype:className=shortcuts left-align-->

### 搜索

键位 | 操作
:- | :-
`/` | 搜索
`n` | 下一个
`N` | 上一个
<!--rehype:className=shortcuts left-align-->

另见
----

- [项目主页](https://github.com/zhengmingliang/jkit)
- [使用文档](https://github.com/zhengmingliang/jkit/blob/main/README.md)
