#!/usr/bin/env bash
#
# verify-jar-version.sh - 验证 class 文件编译的 JDK major version
#
# 用法: ./verify-jar-version.sh <target>
#   target 可以是:
#     - JAR 文件 (如 jkit-1.0.0.jar)
#     - 目录     (如 target/classes/)
#     - 单个 .class 文件
#
# 验证规则 (基于 pom.xml 的 multi-release JAR 配置):
#   com/alianga/jkit/...         -> Java 8  (major version 52)
#   META-INF/versions/9/...      -> Java 9  (major version 53)
#   META-INF/versions/11/...     -> Java 11 (major version 55)
#
# .class 文件头第 7-8 字节 (大端序) 即为 major_version

set -euo pipefail

# ─── 颜色 ───
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

# ─── 用法说明 ───
usage() {
    echo -e "${RED}用法: $0 <jar-file | directory | class-file>${RESET}"
    echo ""
    echo "  支持的输入类型:"
    echo "    JAR 文件        ./verify-jar-version.sh target/jkit-1.0.0.jar"
    echo "    目录            ./verify-jar-version.sh target/classes/"
    echo "    单个 .class     ./verify-jar-version.sh target/classes/com/alianga/jkit/Main.class"
    exit 1
}

# ─── 参数检查 ───
if [[ $# -ne 1 ]]; then
    usage
fi

INPUT_PATH="$(readlink -f "$1")"

if [[ ! -e "$INPUT_PATH" ]]; then
    echo -e "${RED}错误: 路径不存在 - $INPUT_PATH${RESET}"
    exit 1
fi

# ─── 前置检查 ───
if ! command -v xxd &>/dev/null && ! command -v od &>/dev/null; then
    echo -e "${RED}错误: 未找到 xxd 或 od 命令，无法读取 class 文件头${RESET}"
    exit 1
fi

# ─── 读取 class 文件 major version 的函数 ───
read_major_version() {
    local class_file="$1"
    if command -v xxd &>/dev/null; then
        local hex
        hex=$(xxd -s 6 -l 2 -p "$class_file" 2>/dev/null)
        if [[ -z "$hex" ]]; then
            echo ""
            return
        fi
        printf "%d" "0x${hex}"
    else
        local val
        val=$(od -A n -t x1 -j 6 -N 2 "$class_file" 2>/dev/null | tr -d ' ')
        if [[ -z "$val" ]]; then
            echo ""
            return
        fi
        local hi lo
        hi=$(echo "$val" | cut -c1-2)
        lo=$(echo "$val" | cut -c3-4)
        printf "%d" "0x${hi}${lo}"
    fi
}

# ─── 校验 .class 文件魔数 ───
verify_magic() {
    local class_file="$1"
    local magic
    if command -v xxd &>/dev/null; then
        magic=$(xxd -s 0 -l 4 -p "$class_file" 2>/dev/null)
    else
        magic=$(od -A n -t x1 -j 0 -N 4 "$class_file" 2>/dev/null | tr -d ' ')
    fi
    if [[ "$magic" != "cafebabe" ]]; then
        echo -e "${YELLOW}警告: 非标准 class 文件 (magic=$magic): $class_file${RESET}"
        return 1
    fi
    return 0
}

# ─── 版本号映射表 ───
get_expected_version() {
    local class_path="$1"
    case "$class_path" in
        META-INF/versions/9/*)   echo 53 ;;  # Java 9
        META-INF/versions/11/*)  echo 55 ;;  # Java 11
        com/alianga/jkit/*)      echo 52 ;;  # Java 8
        *)                       echo ""  ;;
    esac
}

get_expected_label() {
    local class_path="$1"
    case "$class_path" in
        META-INF/versions/9/*)   echo "Java 9 (53)"  ;;
        META-INF/versions/11/*)  echo "Java 11 (55)" ;;
        com/alianga/jkit/*)      echo "Java 8 (52)"  ;;
        *)                       echo ""              ;;
    esac
}

get_category_name() {
    local class_path="$1"
    case "$class_path" in
        META-INF/versions/9/*)   echo "META-INF/versions/9 (Java 9)"  ;;
        META-INF/versions/11/*)  echo "META-INF/versions/11 (Java 11)" ;;
        com/alianga/jkit/*)      echo "com/alianga/jkit (Java 8)"     ;;
        *)                       echo "未匹配规则" ;;
    esac
}

version_name() {
    case "$1" in
        52) echo "Java 8"   ;; 53) echo "Java 9"   ;; 54) echo "Java 10" ;;
        55) echo "Java 11"  ;; 56) echo "Java 12"  ;; 57) echo "Java 13" ;;
        58) echo "Java 14"  ;; 59) echo "Java 15"  ;; 60) echo "Java 16" ;;
        61) echo "Java 17"  ;; 62) echo "Java 18"  ;; 63) echo "Java 19" ;;
        64) echo "Java 20"  ;; 65) echo "Java 21"  ;; 66) echo "Java 22" ;;
        67) echo "Java 23"  ;; 68) echo "Java 24"  ;; 69) echo "Java 25" ;;
        *)  echo "Unknown ($1)" ;;
    esac
}

# ─── 检查单个 .class 文件 ───
check_single_class() {
    local class_file="$1"
    local class_path="$2"   # 用于匹配规则的路径 (相对于根目录)

    total_checked=$((total_checked + 1))

    local expected
    expected=$(get_expected_version "$class_path")
    if [[ -z "$expected" ]]; then
        total_skipped=$((total_skipped + 1))
        return
    fi

    local expected_label
    expected_label=$(get_expected_label "$class_path")
    local category
    category=$(get_category_name "$class_path")
    category_total["$category"]=$(( ${category_total["$category"]:-0} + 1 ))

    # 验证 magic number
    if ! verify_magic "$class_file" >/dev/null 2>&1; then
        total_failed=$((total_failed + 1))
        category_fail["$category"]=$(( ${category_fail["$category"]:-0} + 1 ))
        return
    fi

    # 读取 major version
    local actual
    actual=$(read_major_version "$class_file")
    if [[ -z "$actual" ]]; then
        echo -e "  ${RED}✗ 无法读取版本: $class_path${RESET}"
        total_failed=$((total_failed + 1))
        category_fail["$category"]=$(( ${category_fail["$category"]:-0} + 1 ))
        return
    fi

    local actual_name
    actual_name=$(version_name "$actual")

    if [[ "$actual" -eq "$expected" ]]; then
        echo -e "  ${GREEN}✓${RESET} ${class_path}"
        echo -e "      期望: ${expected_label}  实际: ${actual_name} (${actual}) ${GREEN}PASS${RESET}"
        total_passed=$((total_passed + 1))
        category_pass["$category"]=$(( ${category_pass["$category"]:-0} + 1 ))
    else
        echo -e "  ${RED}✗${RESET} ${class_path}"
        echo -e "      期望: ${expected_label}  实际: ${actual_name} (${actual}) ${RED}FAIL${RESET}"
        total_failed=$((total_failed + 1))
        category_fail["$category"]=$(( ${category_fail["$category"]:-0} + 1 ))
    fi
}

# ─── 统计变量 ───
total_checked=0
total_passed=0
total_failed=0
total_skipped=0

declare -A category_total
declare -A category_pass
declare -A category_fail

# ══════════════════════════════════════════════════════
#  根据输入类型分发处理
# ══════════════════════════════════════════════════════

if [[ -f "$INPUT_PATH" && "$INPUT_PATH" == *.class ]]; then
    # ── 模式 1: 单个 .class 文件 ──
    echo -e "${BOLD}╔══════════════════════════════════════════════════════╗${RESET}"
    echo -e "${BOLD}║   Class File Version Verifier  (单文件模式)        ║${RESET}"
    echo -e "${BOLD}╚══════════════════════════════════════════════════════╝${RESET}"
    echo ""
    echo -e "${CYAN}Class 文件:${RESET} $INPUT_PATH"
    echo ""

    # 从完整路径中提取 JAR 内路径: 优先找 target/classes/ 或 classes/ 之后的部分
    single_class_path=""
    if [[ "$INPUT_PATH" =~ target/classes/(.*) ]]; then
        single_class_path="${BASH_REMATCH[1]}"
    elif [[ "$INPUT_PATH" =~ classes/(.*) ]]; then
        single_class_path="${BASH_REMATCH[1]}"
    fi
    # 若无法提取, 则回退到文件名
    if [[ -z "$single_class_path" ]]; then
        single_class_path="$(basename "$INPUT_PATH")"
    fi

    check_single_class "$INPUT_PATH" "$single_class_path"

elif [[ -d "$INPUT_PATH" ]]; then
    # ── 模式 2: 目录 ──
    echo -e "${BOLD}╔══════════════════════════════════════════════════════╗${RESET}"
    echo -e "${BOLD}║   Class File Version Verifier  (目录模式)          ║${RESET}"
    echo -e "${BOLD}╚══════════════════════════════════════════════════════╝${RESET}"
    echo ""
    echo -e "${CYAN}扫描目录:${RESET} $INPUT_PATH"
    echo ""

    while IFS= read -r -d '' class_file; do
        # class_file 是绝对路径, 截取输入目录之后的部分作为相对路径
        rel_path="${class_file#"$INPUT_PATH"/}"
        check_single_class "$class_file" "$rel_path"
    done < <(find "$INPUT_PATH" -name "*.class" -type f -print0 | sort -z)

elif [[ -f "$INPUT_PATH" ]] && command -v jar &>/dev/null; then
    # ── 模式 3: JAR 文件 ──
    TMP_DIR=$(mktemp -d)
    trap 'rm -rf "$TMP_DIR"' EXIT

    echo -e "${BOLD}╔══════════════════════════════════════════════════════╗${RESET}"
    echo -e "${BOLD}║   Class File Version Verifier  (JAR 模式)          ║${RESET}"
    echo -e "${BOLD}╚══════════════════════════════════════════════════════╝${RESET}"
    echo ""
    echo -e "${CYAN}JAR 文件:${RESET} $INPUT_PATH"
    echo -e "${CYAN}文件大小:${RESET} $(ls -lh "$INPUT_PATH" | awk '{print $5}')"
    echo ""

    cd "$TMP_DIR"
    jar xf "$INPUT_PATH" 2>/dev/null

    while IFS= read -r -d '' class_file; do
        jar_path="${class_file#./}"
        check_single_class "$class_file" "$jar_path"
    done < <(find . -name "*.class" -type f -print0 | sort -z)

else
    echo -e "${RED}错误: 不支持的输入类型${RESET}"
    echo ""
    echo "  当前输入: $INPUT_PATH"
    echo "  请提供 JAR 文件、目录或 .class 文件"
    usage
fi

# ══════════════════════════════════════════════════════
#  输出汇总
# ══════════════════════════════════════════════════════

echo ""
echo -e "${BOLD}══════════════════════════════════════════════════════${RESET}"
echo -e "${BOLD} 分类汇总${RESET}"
echo -e "${BOLD}══════════════════════════════════════════════════════${RESET}"

for category in "${!category_total[@]}"; do
    c_total="${category_total[$category]}"
    c_pass="${category_pass[$category]:-0}"
    c_fail="${category_fail[$category]:-0}"
    if [[ "$c_fail" -eq 0 ]]; then
        echo -e "  ${GREEN}✓${RESET} ${category}: ${c_pass}/${c_total} 通过"
    else
        echo -e "  ${RED}✗${RESET} ${category}: ${c_pass}/${c_total} 通过 (${c_fail} 失败)"
    fi
done

echo ""
echo -e "${BOLD}══════════════════════════════════════════════════════${RESET}"
echo -e "  总计: ${total_checked} 个 class 文件"
echo -e "  ${GREEN}通过: ${total_passed}${RESET}"
if [[ $total_failed -gt 0 ]]; then
    echo -e "  ${RED}失败: ${total_failed}${RESET}"
fi
if [[ $total_skipped -gt 0 ]]; then
    echo -e "  ${YELLOW}跳过: ${total_skipped} (不在检查范围内)${RESET}"
fi
echo -e "${BOLD}══════════════════════════════════════════════════════${RESET}"

if [[ $total_failed -gt 0 ]]; then
    echo ""
    echo -e "${RED}${BOLD}验证失败! 存在 ${total_failed} 个 class 文件的版本不符合预期。${RESET}"
    exit 1
else
    echo ""
    echo -e "${GREEN}${BOLD}验证通过! 所有 class 文件的编译版本均符合预期。${RESET}"
    exit 0
fi
