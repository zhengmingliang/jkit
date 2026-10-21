表格样式
===

参考站点的表格默认隐藏表头，只有带 `show-header` 类时才显示；首列代码为绿色。

普通表格
----

### 依赖清单

工具 | 用途
:- | :-
`file` | 文件类型检测，必需
`nerd-fonts` | 推荐，显示图标
`ffmpeg` | 视频缩略图
`jq` | JSON 预览
`poppler` | PDF 预览

### 显示表头

系统 | 命令
:- | :-
Arch Linux | `sudo pacman -S yazi`
Homebrew | `brew install yazi`
Windows Scoop | `scoop install yazi`
<!--rehype:className=show-header left-align-->

快捷键
----

### 导航

键位 | 操作
:- | :-
`h`, `←` | 返回父目录
`j`, `↓` | 光标下移
`k`, `↑` | 光标上移
`l`, `→` | 进入目录
`g` ⇒ `g` | 跳到顶部
<!--rehype:className=shortcuts left-align-->

### 对齐

左对齐 | 居中 | 右对齐
:-- | :-: | --:
`a` | `b` | `c`
`alpha` | `beta` | `gamma`

### 长表格

配置项 | 说明
:- | :-
`mgr.show_hidden` | 是否显示隐藏文件
`mgr.ratio` | 父目录、当前目录、预览面板的宽度比例
`preview.max_width` | 预览面板最大宽度，超长文本会换行
`preview.max_height` | 预览面板最大高度
