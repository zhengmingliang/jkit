#!/usr/bin/env bash
#
# class-version-checker.sh - 通用 class 文件 JDK 编译版本扫描工具
#
# 扫描 JAR 包、目录或单个 .class 文件，汇总所有 class 文件的编译 JDK 版本。
#
# 用法:
#   ./class-version-checker.sh <target>...
#   ./class-version-checker.sh target/jkit-1.0.0.jar
#   ./class-version-checker.sh target/classes/
#   ./class-version-checker.sh lib/a.jar lib/b.jar /path/to/classes/
#
# 支持同时传入多个目标，支持混合输入 (JAR + 目录 + class)。

set -euo pipefail

# ─── 颜色 ───
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

# ─── major version → Java 版本名 ───
version_name() {
    case "$1" in
        45) echo "Java 1.1"   ;; 46) echo "Java 1.2"   ;; 47) echo "Java 1.3"   ;;
        48) echo "Java 1.4"   ;; 49) echo "Java 5"     ;; 50) echo "Java 6"     ;;
        51) echo "Java 7"     ;; 52) echo "Java 8"     ;; 53) echo "Java 9"     ;;
        54) echo "Java 10"    ;; 55) echo "Java 11"    ;; 56) echo "Java 12"    ;;
        57) echo "Java 13"    ;; 58) echo "Java 14"    ;; 59) echo "Java 15"    ;;
        60) echo "Java 16"    ;; 61) echo "Java 17"    ;; 62) echo "Java 18"    ;;
        63) echo "Java 19"    ;; 64) echo "Java 20"    ;; 65) echo "Java 21"    ;;
        66) echo "Java 22"    ;; 67) echo "Java 23"    ;; 68) echo "Java 24"    ;;
        69) echo "Java 25"    ;;
        *)  echo "Unknown ($1)" ;;
    esac
}

# ─── major version → 对应的 class 文件 format 版本号 ───
# (minor_version 通常为 0, 这里只取 major)
# 输出格式: "52.0", "65.0" 等
version_format() {
    echo "${1}.0"
}

# ─── 读取 .class 文件 major version ───
read_major_version() {
    local class_file="$1"
    local hex
    if command -v xxd &>/dev/null; then
        hex=$(xxd -s 6 -l 2 -p "$class_file" 2>/dev/null)
    else
        hex=$(od -A n -t x1 -j 6 -N 2 "$class_file" 2>/dev/null | tr -d ' ')
        if [[ -n "$hex" ]]; then
            local hi lo
            hi=$(echo "$hex" | cut -c1-2)
            lo=$(echo "$hex" | cut -c3-4)
            hex="${hi}${lo}"
        fi
    fi
    [[ -z "$hex" ]] && return 1
    printf "%d" "0x${hex}"
}

# ─── 校验魔数 ───
is_valid_class() {
    local class_file="$1"
    local magic
    if command -v xxd &>/dev/null; then
        magic=$(xxd -s 0 -l 4 -p "$class_file" 2>/dev/null)
    else
        magic=$(od -A n -t x1 -j 0 -N 4 "$class_file" 2>/dev/null | tr -d ' ')
    fi
    [[ "$magic" == "cafebabe" ]]
}

# ══════════════════════════════════════════════════════
#  全局统计
# ══════════════════════════════════════════════════════
declare -A g_count          # major_version -> 文件数
declare -A g_classes        # major_version -> class 名列表 (用于 --detail)
g_total=0
g_invalid=0

# ══════════════════════════════════════════════════════
#  参数解析
# ══════════════════════════════════════════════════════
SHOW_DETAIL=false
VERBOSE=false
INPUTS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        -d|--detail)  SHOW_DETAIL=true; shift ;;
        -v|--verbose) VERBOSE=true; shift ;;
        -h|--help)
            echo "用法: $0 [选项] <target>..."
            echo ""
            echo "  支持的输入: JAR 文件 / 目录 / 单个 .class 文件 (可混合传入多个)"
            echo ""
            echo "  选项:"
            echo "    -d, --detail    显示每个 class 文件的详细信息"
            echo "    -v, --verbose   同时显示不在常见路径规则下的 class 文件"
            echo "    -h, --help      显示帮助"
            echo ""
            echo "  示例:"
            echo "    $0 my-app.jar"
            echo "    $0 target/classes/"
            echo "    $0 -d lib/*.jar"
            echo "    $0 -d app.jar lib/*.jar target/classes/"
            exit 0
            ;;
        *)
            INPUTS+=("$1")
            shift
            ;;
    esac
done

if [[ ${#INPUTS[@]} -eq 0 ]]; then
    echo -e "${RED}用法: $0 [选项] <target>...${RESET}"
    echo "  传入 -h 查看帮助"
    exit 1
fi

# ══════════════════════════════════════════════════════
#  处理函数
# ══════════════════════════════════════════════════════

process_class_file() {
    local class_file="$1"
    local display_path="$2"

    if ! is_valid_class "$class_file"; then
        g_invalid=$((g_invalid + 1))
        if $VERBOSE; then
            echo -e "  ${YELLOW}⚠${RESET} 非 class 文件: ${display_path}"
        fi
        return
    fi

    local ver
    if ! ver=$(read_major_version "$class_file"); then
        g_invalid=$((g_invalid + 1))
        if $VERBOSE; then
            echo -e "  ${YELLOW}⚠${RESET} 无法读取: ${display_path}"
        fi
        return
    fi

    g_total=$((g_total + 1))
    g_count["$ver"]=$(( ${g_count["$ver"]:-0} + 1 ))

    if $SHOW_DETAIL; then
        local name
        name=$(version_name "$ver")
        g_classes["$ver"]+="${display_path}"$'\n'
        echo -e "  ${GREEN}✓${RESET} ${display_path}"
        echo -e "    ${DIM}→ ${name} (major ${ver}, format $(version_format "$ver"))${RESET}"
    fi
}

process_directory() {
    local dir="$1"
    echo -e "${CYAN}  📂 扫描目录:${RESET} $dir"
    echo ""

    while IFS= read -r -d '' class_file; do
        local rel_path="${class_file#"$dir"/}"
        process_class_file "$class_file" "$rel_path"
    done < <(find "$dir" -name "*.class" -type f -print0 | sort -z)
}

process_jar() {
    local jar_file="$1"
    local file_size
    file_size=$(ls -lh "$jar_file" | awk '{print $5}')
    echo -e "${CYAN}  📦 扫描 JAR:${RESET} $jar_file ${DIM}($file_size)${RESET}"
    echo ""

    local tmp_dir
    tmp_dir=$(mktemp -d)
    # 注意: 不在此处 trap, 调用方统一清理

    (cd "$tmp_dir" && jar xf "$jar_file" 2>/dev/null)

    while IFS= read -r -d '' class_file; do
        local jar_path="${class_file#"$tmp_dir"/}"
        process_class_file "$class_file" "$jar_path"
    done < <(find "$tmp_dir" -name "*.class" -type f -print0 | sort -z)

    rm -rf "$tmp_dir"
}

process_single_class() {
    local class_file="$1"
    local filename
    filename=$(basename "$class_file")
    echo -e "${CYAN}  📄 扫描 Class:${RESET} $class_file"
    echo ""
    process_class_file "$class_file" "$filename"
}

# ══════════════════════════════════════════════════════
#  主流程
# ══════════════════════════════════════════════════════

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════╗${RESET}"
echo -e "${BOLD}║        Class File JDK Version Checker                   ║${RESET}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════╝${RESET}"
echo ""

for input_path in "${INPUTS[@]}"; do
    target="$(readlink -f "$input_path" 2>/dev/null || true)"

    if [[ ! -e "$target" ]]; then
        echo -e "${RED}  ✗ 路径不存在: $input_path${RESET}"
        echo ""
        continue
    fi

    if [[ -f "$target" && "$target" == *.class ]]; then
        process_single_class "$target"
    elif [[ -d "$target" ]]; then
        process_directory "$target"
    elif [[ -f "$target" && "$target" == *.jar ]]; then
        process_jar "$target"
    elif [[ -f "$target" ]]; then
        echo -e "${YELLOW}  ⚠ 跳过非 class/jar 文件: $input_path${RESET}"
        echo ""
    fi
done

# ══════════════════════════════════════════════════════
#  汇总报告
# ══════════════════════════════════════════════════════

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════╗${RESET}"
echo -e "${BOLD}║  汇总报告                                               ║${RESET}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════╝${RESET}"
echo ""

if [[ $g_total -eq 0 && $g_invalid -eq 0 ]]; then
    echo -e "  ${YELLOW}未发现任何 .class 文件${RESET}"
    exit 0
fi

# 按 major version 排序输出
max_ver=0
min_ver=999

echo -e "  ${BOLD}版本分布:${RESET}"
echo -e "  ${DIM}────────────────────────────────────────────────────────${RESET}"

# 收集所有版本号并排序
sorted_versions=()
for ver in "${!g_count[@]}"; do
    sorted_versions+=("$ver")
    [[ $ver -gt $max_ver ]] && max_ver=$ver
    [[ $ver -lt $min_ver ]] && min_ver=$ver
done
IFS=$'\n' sorted_versions=($(sort -n <<<"${sorted_versions[*]}")); unset IFS

# 计算最大进度条宽度
max_count=0
for ver in "${sorted_versions[@]}"; do
    [[ ${g_count[$ver]} -gt $max_count ]] && max_count=${g_count[$ver]}
done

# 彩色条配置 (低版本暖色, 高版本冷色)
get_bar_color() {
    case "$1" in
        4[5-9]|50|51)          echo "$YELLOW"  ;;  # Java 1.1 ~ 7
        52)                    echo "$GREEN"   ;;  # Java 8
        53)                    echo "$CYAN"    ;;  # Java 9
        54|55)                 echo "$BLUE"    ;;  # Java 10, 11
        56|57|58|59|60)        echo "$MAGENTA" ;;  # Java 12 ~ 16
        6[1-9]|7[0-9])         echo "$RED"     ;;  # Java 17+
        *)                     echo "$RESET"   ;;
    esac
}

for ver in "${sorted_versions[@]}"; do
    count=${g_count[$ver]}
    name=$(version_name "$ver")
    pct=$((count * 100 / g_total))

    # 进度条长度: 固定 30 格
    if [[ $max_count -gt 0 ]]; then
        bar_len=$((count * 30 / max_count))
    else
        bar_len=0
    fi
    [[ $bar_len -lt 1 && $count -gt 0 ]] && bar_len=1
    bar=""
    for ((i=0; i<bar_len; i++)); do bar+="█"; done
    for ((i=bar_len; i<30; i++)); do bar+="░"; done

    color=$(get_bar_color "$ver")

    printf "  ${color}%-20s${RESET}  ${color}%s${RESET}  %4d 个 (%3d%%)\n" \
        "major=$ver (${name})" "$bar" "$count" "$pct"
done

echo -e "  ${DIM}────────────────────────────────────────────────────────${RESET}"
echo ""

# 版本范围
if [[ ${#sorted_versions[@]} -gt 0 ]]; then
    low_name=$(version_name "${sorted_versions[0]}")
    high_name=$(version_name "${sorted_versions[-1]}")

    if [[ ${#sorted_versions[@]} -eq 1 ]]; then
        echo -e "  ${BOLD}编译版本:${RESET} 仅 ${GREEN}${high_name}${RESET} (major ${sorted_versions[0]})"
    else
        echo -e "  ${BOLD}编译版本:${RESET} ${low_name} ~ ${high_name}"
        echo -e "            ${DIM}major ${sorted_versions[0]} ~ ${sorted_versions[-1]}${RESET}"
    fi

    # multi-release 检测
    mr_versions=()
    for ver in "${sorted_versions[@]}"; do
        [[ $ver -ge 53 ]] && mr_versions+=("$ver")
    done
    if [[ ${#mr_versions[@]} -gt 1 ]]; then
        echo -e ""
        echo -e "  ${BOLD}${YELLOW}⚡ Multi-Release JAR 检测:${RESET} 发现 ${#mr_versions[@]} 个不同的 JDK 版本"
        for ver in "${mr_versions[@]}"; do
            echo -e "    ${DIM}• $(version_name "$ver") (major $ver): ${g_count[$ver]} 个文件${RESET}"
        done
    fi
fi

echo ""
echo -e "  ${BOLD}统计:${RESET} ${g_total} 个 class 文件"
if [[ $g_invalid -gt 0 ]]; then
    echo -e "  ${YELLOW}      ${g_invalid} 个非标准 class 文件${RESET}"
fi
echo ""

# ─── detail 模式: 输出每个版本下的文件列表 ───
if $SHOW_DETAIL; then
    echo -e "${BOLD}╔══════════════════════════════════════════════════════════╗${RESET}"
    echo -e "${BOLD}║  文件明细                                               ║${RESET}"
    echo -e "${BOLD}╚══════════════════════════════════════════════════════════╝${RESET}"
    echo ""

    for ver in "${sorted_versions[@]}"; do
        name=$(version_name "$ver")
        color=$(get_bar_color "$ver")
        echo -e "  ${color}▸ ${name} (major $ver) — ${g_count[$ver]} 个文件${RESET}"
        while IFS= read -r -d $'\n' cls; do
            [[ -z "$cls" ]] && continue
            echo -e "    ${DIM}• $cls${RESET}"
        done <<< "${g_classes[$ver]}"
        echo ""
    done
fi

exit 0
