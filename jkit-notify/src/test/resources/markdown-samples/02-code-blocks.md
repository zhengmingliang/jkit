代码块与语法高亮
===

不同语言的围栏代码块，用于检查等宽字体、行高、横向滚动和 GitHub Pretty Lights 配色。

Shell
----

### 常用命令

```shell
$ yazi
$ yazi ~/Projects
$ ya pkg add yazi-rs/flavors:catppuccin-mocha
$ taplo check --schema https://yazi-rs.github.io/schemas/theme.json theme.toml
```

### Bash 函数

```bash
function y() {
  local tmp="$(mktemp -t "yazi-cwd.XXXXXX")"
  yazi "$@" --cwd-file="$tmp"
  if cwd="$(cat -- "$tmp")" && [ -n "$cwd" ] && [ "$cwd" != "$PWD" ]; then
    builtin cd -- "$cwd"
  fi
  rm -f -- "$tmp"
}
```

Java 与 TOML
----

### Java

```java
// 注释
public class Demo {
    public static void main(String[] args) {
        System.out.println("hello");
    }
}
```

### TOML

```toml
# yazi.toml
[mgr]
show_hidden = true
ratio = [1, 4, 3]

[[plugin.deps]]
use = "owner/my-plugin"
rev = "=9a1129c"
```

PowerShell 与 JSON
----

### 环境变量

```powershell
$env:YAZI_FILE_ONE = "C:\Program Files\Git\usr\bin\file.exe"
```

### JSON

```json
{
  "name": "jkit",
  "version": "2.0.5",
  "enabled": true,
  "count": 3
}
```
