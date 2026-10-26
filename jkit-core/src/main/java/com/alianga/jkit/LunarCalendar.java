package com.alianga.jkit;

import com.alianga.jkit.json.internal.beans.LunarDate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 农历（阴阳合历）工具：公历 ↔ 农历互转、中文农历表达式解析、干支 / 生肖 / 星座 / 节气。
 *
 * <p>底层复用 {@link LunarDate}（天文台数据，支持范围公历 1900-01-31 ~ 2050-01-23，
 * 对应农历 1900 ~ 2049 年）。纯逻辑、无 JavaFX / i18n 依赖，供工具箱多个模块复用
 * （转换工具的农历页、计算器的日期互转等）。</p>
 *
 * <p>注意 {@code import} 该类时不要依赖 {@code com.alianga.jkit.json.internal.beans}
 * 里的其它内部类—— LunarDate 虽在 internal 包，但它是 json 模块沉淀的天文历法实现，
 * 是本类唯一的底层依赖。</p>
 *
 * @author 郑明亮
 */
public class LunarCalendar {
    /** 数据范围说明（供 UI 提示）。 */
    public static final String RANGE_HINT = "农历数据范围：1900 ~ 2049 年（公历 1900-01-31 ~ 2050-01-23）";

    /** 超出范围时的一行简短说明。 */
    public static final String OUT_OF_RANGE = "超出 1900 ~ 2049 农历数据范围";

    /** 中文农历无法识别时的提示。 */
    public static final String UNRECOGNIZED_CHINESE =
            "无法识别的中文农历，示例：2024年正月初一 / 二〇二四年闰二月初五 / 甲辰年腊月三十";

    private static final String[] LUNAR_MONTHS =
            {"正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "冬月", "腊月"};

    private static final String[] LUNAR_DAYS = {
            "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"};

    private static final String[] HEAVENLY_STEMS = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
    private static final String[] EARTHLY_BRANCHES =
            {"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};
    private static final String[] CHINESE_ZODIAC =
            {"鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪"};

    /** 二十四节气中的十二节气（交节当天返回，按月份映射；索引 = (月份-1)*2）。 */
    private static final String[] SOLAR_TERMS = {
            "小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨", "立夏", "小满",
            "芒种", "夏至", "小暑", "大暑", "立秋", "处暑", "白露", "秋分", "寒露", "霜降",
            "立冬", "小雪", "大雪", "冬至"};

    /** 十天干 / 十二地支（用于「甲辰年」这类干支纪年输入）。 */
    private static final String GANZHI_STEMS = "甲乙丙丁戊己庚辛壬癸";
    private static final String GANZHI_BRANCHES = "子丑寅卯辰巳午未申酉戌亥";

    /** 中文数字字符 → 数值（阿拉伯数字、〇/零占位符、一至九、十）。 */
    private static final Map<Character, Integer> CN_DIGIT = new HashMap<>();

    static {
        CN_DIGIT.put('〇', 0);
        CN_DIGIT.put('零', 0);
        CN_DIGIT.put('一', 1);
        CN_DIGIT.put('二', 2);
        CN_DIGIT.put('三', 3);
        CN_DIGIT.put('四', 4);
        CN_DIGIT.put('五', 5);
        CN_DIGIT.put('六', 6);
        CN_DIGIT.put('七', 7);
        CN_DIGIT.put('八', 8);
        CN_DIGIT.put('九', 9);
        CN_DIGIT.put('十', 10);
    }

    /** 农历月中文写法 → 月份数（最长优先匹配）。 */
    private static final Map<String, Integer> MONTH_CN = new HashMap<>();
    /** 月份写法按长度降序，避免「十一月」被「十」误匹配。 */
    private static final List<String> MONTH_ORDER = new ArrayList<>();

    static {
        String[] names = {"正月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月",
                "十月", "冬月", "腊月", "十一月", "十二月"};
        int[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 11, 12};
        for (int i = 0; i < names.length; i++) {
            MONTH_CN.put(names[i], values[i]);
        }
        MONTH_ORDER.addAll(MONTH_CN.keySet());
        MONTH_ORDER.sort((a, b) -> Integer.compare(b.length(), a.length()));
    }

    private LunarCalendar() {
    }

    /**
     * 公历日期 → 农历信息。
     *
     * @return 超出数据范围时 {@link LunarInfo#supported()} 为 {@code false}
     */
    public static LunarInfo fromGregorian(int year, int month, int day) {
        LunarDate ld = new LunarDate(year, month, day);
        long t = ld.getTime();
        if (t < LunarDate.LUNAR_DATA_TIME_BEGIN || t >= LunarDate.LUNAR_DATA_TIME_END) {
            return LunarInfo.unsupported(year, month, day);
        }
        return build(ld, year, month, day);
    }

    /**
     * 公历日期 → 农历信息。
     *
     * @param date 公历日期
     * @return 农历信息
     */
    public static LunarInfo fromGregorian(LocalDate date) {
        return fromGregorian(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    /**
     * 农历日期（含是否闰月）→ 公历日期及等价农历信息。
     *
     * <p>农历 → 公历通过扫描对应公历年份区间精确匹配（含闰月），保证与正向换算互逆。</p>
     *
     * @return 年份越界或该农历日期不存在时 {@link LunarInfo#supported()} 为 {@code false}
     */
    public static LunarInfo toGregorian(int lunarYear, int lunarMonth, boolean leap, int lunarDay) {
        if (lunarYear < 1900 || lunarYear > 2049) {
            return LunarInfo.unsupported();
        }
        for (int gy = lunarYear - 1; gy <= lunarYear + 1; gy++) {
            if (gy < 1900) {
                continue;
            }
            // 农历年 Y 的日期只可能落在公历 Y-1 年末 ~ Y+1 年初，极值年只需扫到 2 月
            int months = (gy == lunarYear + 1) ? 2 : 12;
            for (int gm = 1; gm <= months; gm++) {
                int days = YearMonth.of(gy, gm).lengthOfMonth();
                for (int gd = 1; gd <= days; gd++) {
                    LunarDate cand = new LunarDate(gy, gm, gd);
                    long t = cand.getTime();
                    if (t < LunarDate.LUNAR_DATA_TIME_BEGIN || t >= LunarDate.LUNAR_DATA_TIME_END) {
                        continue;
                    }
                    if (cand.getLunarYear() == lunarYear
                            && cand.getLunarMonth() == lunarMonth
                            && cand.isLeap() == leap
                            && cand.getLunarDay() == lunarDay) {
                        return build(cand, gy, gm, gd);
                    }
                }
            }
        }
        return LunarInfo.invalid(lunarYear, lunarMonth, leap, lunarDay);
    }

    private static LunarInfo build(LunarDate ld, int gy, int gm, int gd) {
        int ly = ld.getLunarYear();
        int lm = ld.getLunarMonth();
        int ldDay = ld.getLunarDay();
        boolean leap = ld.isLeap();

        String ganzhiYear = HEAVENLY_STEMS[ld.getStemsYearIndex()] + EARTHLY_BRANCHES[ld.getBranchesYearIndex()];
        String ganzhiMonth = HEAVENLY_STEMS[ld.getStemsMonthIndex()] + EARTHLY_BRANCHES[ld.getBranchesMonthIndex()];
        String ganzhiDay = HEAVENLY_STEMS[ld.getStemsDayIndex()] + EARTHLY_BRANCHES[ld.getBranchesDayIndex()];
        String zodiac = CHINESE_ZODIAC[ld.getBranchesYearIndex()];
        String constellation = ld.getConstellation();
        int solarTermIdx = ld.getSolarTerms();
        String solarTerm = solarTermIdx > 0 ? SOLAR_TERMS[(solarTermIdx - 1) * 2] : null;

        return new LunarInfo(true, "OK",
                gy, gm, gd,
                ly, lm, ldDay, leap,
                LUNAR_MONTHS[lm - 1], LUNAR_DAYS[ldDay - 1],
                ganzhiYear, ganzhiMonth, ganzhiDay,
                zodiac, constellation, solarTerm);
    }

    /**
     * 公历日期的一行农历摘要，如「农历 甲辰年正月初一 · 龙年 · 水瓶座 · 立春」。
     *
     * <p>只给最常看的几项（农历日期、干支年、生肖、星座，交节当天附节气）；
     * 超出数据范围时返回 {@link #OUT_OF_RANGE}，保证文案始终非空。</p>
     */
    public static String summary(LocalDate date) {
        return summary(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    /** {@link #summary(LocalDate)} 的年月日版本。 */
    public static String summary(int year, int month, int day) {
        LunarInfo info = fromGregorian(year, month, day);
        if (!info.supported()) {
            return OUT_OF_RANGE;
        }
        StringBuilder text = new StringBuilder("农历 ")
                .append(info.ganzhiYear()).append("年").append(info.lunarDateText())
                .append(" · ").append(info.zodiac()).append("年");
        if (info.constellation() != null && !info.constellation().isEmpty()) {
            text.append(" · ").append(info.constellation());
        }
        if (info.solarTerm() != null) {
            text.append(" · ").append(info.solarTerm());
        }
        return text.toString();
    }

    /**
     * 中文农历表达式 → 公历信息，如「2024年正月初一」「二〇二四年闰二月初五」「甲辰年正月初一」。
     *
     * <p>支持：年份为阿拉伯数字 / 中文数字（如 二〇二四）/ 干支（如 甲辰，可能对应多个年份，
     * 返回候选歧义说明）；月份为「正月…腊月 / 十一月 / 十二月」或阿拉伯数字；
     * 日期为「初一…三十」或阿拉伯数字；闰月用「闰」前缀。</p>
     *
     * @return 无法识别或超出数据范围时 {@link LunarInfo#supported()} 为 {@code false}
     */
    public static LunarInfo parseChinese(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return LunarInfo.unsupported();
        }
        String s = raw.replaceAll("\\s+", "");
        int n = s.length();
        int i = 0;

        // 年份（阿拉伯 / 中文数字 / 干支）
        Integer year = null;
        String ganzhi = null;
        if (i < n && Character.isDigit(s.charAt(i))) {
            int j = i;
            while (j < n && Character.isDigit(s.charAt(j)) && (j - i) < 4) {
                j++;
            }
            year = Integer.parseInt(s.substring(i, j));
            i = j;
        } else if (i + 1 < n && isGanzhi(s.charAt(i), s.charAt(i + 1))) {
            ganzhi = s.substring(i, i + 2);
            i += 2;
        } else {
            String digits = readChineseDigits(s, i);
            if (!digits.isEmpty()) {
                year = Integer.parseInt(digits);
                i += digits.length();
            }
        }
        if (year == null && ganzhi == null) {
            return LunarInfo.unsupported(UNRECOGNIZED_CHINESE);
        }
        if (i < n && s.charAt(i) == '年') {
            i++;
        }

        // 闰月
        boolean leap = false;
        if (i < n && s.charAt(i) == '闰') {
            leap = true;
            i++;
        }

        // 月份
        int month = -1;
        if (i < n && Character.isDigit(s.charAt(i))) {
            int j = i;
            while (j < n && Character.isDigit(s.charAt(j)) && (j - i) < 2) {
                j++;
            }
            month = Integer.parseInt(s.substring(i, j));
            i = j;
        } else {
            for (String name : MONTH_ORDER) {
                if (s.startsWith(name, i)) {
                    month = MONTH_CN.get(name);
                    i += name.length();
                    break;
                }
            }
        }
        if (month < 1 || month > 12) {
            return LunarInfo.invalid(year != null ? year : 0, month, leap, 0);
        }
        if (i < n && s.charAt(i) == '月') {
            i++;
        }

        // 日期
        int day = readChineseDay(s, i);
        if (day < 1 || day > 30) {
            return LunarInfo.invalid(year != null ? year : 0, month, leap, day);
        }
        // readChineseDay 不回传消费长度，这里按「读掉两位中文日 / 一到两位数字」推进
        if (Character.isDigit(s.charAt(i))) {
            int j = i;
            while (j < n && Character.isDigit(s.charAt(j))) {
                j++;
            }
            i = j;
        } else if (s.startsWith("廿十", i) || s.startsWith("三十", i)
                || s.startsWith("初十", i) || (s.charAt(i) != '初' && s.charAt(i) != '廿' && s.charAt(i) != '十'
                && i + 1 < n && isDayPair(s, i))) {
            i += 2;
        } else if (s.charAt(i) == '初' || s.charAt(i) == '廿' || s.charAt(i) == '十') {
            // 初X / 廿X / 十X / 二十 / 三十：两位
            i += (s.startsWith("二十", i) || s.startsWith("三十", i) || s.startsWith("初十", i)
                    || s.startsWith("廿十", i)) ? 2 : 2;
        } else {
            return LunarInfo.invalid(year != null ? year : 0, month, leap, day);
        }
        if (i < n && (s.charAt(i) == '日' || s.charAt(i) == '号')) {
            i++;
        }
        // 残留未消费的日期字符（如「初三十一」的「十一」）→ 非法
        if (i < n && (Character.isDigit(s.charAt(i)) || isDayChar(s.charAt(i)))) {
            return LunarInfo.invalid(year != null ? year : 0, month, leap, day);
        }

        if (year != null) {
            return toGregorian(year, month, leap, day);
        }

        // 干支年：收集数据范围内所有匹配年份，逐个反查
        List<Integer> matching = new ArrayList<>();
        for (int y = 1900; y <= 2049; y++) {
            if (ganzhiStems(y) == GANZHI_STEMS.indexOf(ganzhi.charAt(0))
                    && ganzhiBranches(y) == GANZHI_BRANCHES.indexOf(ganzhi.charAt(1))) {
                matching.add(y);
            }
        }
        List<LunarInfo> valid = new ArrayList<>();
        for (int y : matching) {
            LunarInfo info = toGregorian(y, month, leap, day);
            if (info.supported()) {
                valid.add(info);
            }
        }
        if (valid.isEmpty()) {
            return LunarInfo.unsupported(UNRECOGNIZED_CHINESE);
        }
        if (valid.size() == 1) {
            return valid.get(0);
        }
        // 多个候选：列出来让用户指定公历年份
        StringBuilder hint = new StringBuilder("「").append(ganzhi).append("年")
                .append(leap ? "闰" : "").append(monthName(month)).append(dayName(day))
                .append("」对应多个年份：");
        for (LunarInfo v : valid) {
            hint.append(v.gregorianText()).append(' ');
        }
        hint.append("——请改用公历年份，如 ").append(matching.get(0)).append("年")
                .append(monthName(month)).append(dayName(day));
        return LunarInfo.ambiguous(hint.toString());
    }

    // ===================== 批量多行转换 =====================

    /** 批量转换模式。 */
    public enum BatchMode {
        /** 公历 → 农历 */
        GREGORIAN_TO_LUNAR,
        /** 农历 → 公历 */
        LUNAR_TO_GREGORIAN,
        /** 中文农历 → 公历 */
        CHINESE_TO_GREGORIAN
    }

    /**
     * 批量转换的单行结果。
     * {@code output} 为「输入 → 结果」整行展示文本；{@code result} 为纯输出
     * （不含输入前缀），供带表头复制 / CSV 导出。
     */
    public static final class BatchLine {
        private final String input;
        private final boolean ok;
        private final String output;
        private final String result;

        public BatchLine(String input, boolean ok, String output, String result) {
            this.input = input;
            this.ok = ok;
            this.output = output;
            this.result = result;
        }

        public String input() {
            return input;
        }

        public boolean ok() {
            return ok;
        }

        public String output() {
            return output;
        }

        public String result() {
            return result;
        }
    }

    /** 批量转换的整批结果。 */
    public static final class BatchResult {
        private final List<BatchLine> lines;
        private final int total;
        private final int ok;

        public BatchResult(List<BatchLine> lines, int total, int ok) {
            this.lines = lines;
            this.total = total;
            this.ok = ok;
        }

        public List<BatchLine> lines() {
            return lines;
        }

        public int total() {
            return total;
        }

        public int ok() {
            return ok;
        }

        /** 各结果行拼接（每行已是「输入 → 输出」或「输入 → ✗ 原因」的完整展示文本）。 */
        public String render() {
            StringBuilder sb = new StringBuilder();
            for (BatchLine line : lines) {
                sb.append(line.output()).append('\n');
            }
            return sb.toString();
        }

        /** 带表头的两列表格（制表符分隔，可直接粘贴到 Excel / 表格软件）。 */
        public String renderTable() {
            StringBuilder sb = new StringBuilder("输入\t输出\n");
            for (BatchLine line : lines) {
                sb.append(line.input()).append('\t').append(line.result()).append('\n');
            }
            return sb.toString();
        }

        /** 标准 CSV（逗号分隔、带表头，字段含逗号/引号/换行时按 RFC 4180 转义）。 */
        public String renderCsv() {
            StringBuilder sb = new StringBuilder("输入,输出\n");
            for (BatchLine line : lines) {
                sb.append(csvField(line.input())).append(',').append(csvField(line.result())).append('\n');
            }
            return sb.toString();
        }
    }

    /** CSV 字段转义：含逗号 / 双引号 / 换行时用双引号包裹，内部双引号加倍。 */
    private static String csvField(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * 批量多行转换：按 {@code mode} 把每行独立转换，空行自动跳过。
     *
     * <p>公历 → 农历：每行一个公历日期（yyyy-MM-dd / yyyy/M/d / yyyyMMdd）。
     * 农历 → 公历：每行「农历年 月 日」（如 2024 1 1，闰月写 闰2）。
     * 中文农历 → 公历：每行一个中文农历表达式（见 {@link #parseChinese}）。</p>
     */
    public static BatchResult batch(String input, BatchMode mode) {
        List<BatchLine> lines = new ArrayList<>();
        if (input != null) {
            for (String raw : input.split("\n", -1)) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                BatchLine converted;
                if (mode == BatchMode.GREGORIAN_TO_LUNAR) {
                    converted = gregorianLine(line);
                } else if (mode == BatchMode.LUNAR_TO_GREGORIAN) {
                    converted = lunarLine(line);
                } else {
                    converted = chineseLine(line);
                }
                lines.add(converted);
            }
        }
        int okCount = 0;
        for (BatchLine l : lines) {
            if (l.ok()) {
                okCount++;
            }
        }
        return new BatchResult(lines, lines.size(), okCount);
    }

    private static BatchLine gregorianLine(String line) {
        LocalDate d = parseGregorianDate(line);
        if (d == null) {
            return new BatchLine(line, false, line + " → ✗ 无法识别的公历日期", "✗ 无法识别的公历日期");
        }
        LunarInfo info = fromGregorian(d.getYear(), d.getMonthValue(), d.getDayOfMonth());
        if (!info.supported()) {
            return new BatchLine(line, false, line + " → ✗ " + OUT_OF_RANGE, "✗ " + OUT_OF_RANGE);
        }
        return new BatchLine(line, true, line + " → " + summary(d), summary(d));
    }

    private static BatchLine lunarLine(String line) {
        int[] p = parseNumericLunar(line);
        if (p == null) {
            String msg = "✗ 格式应为 农历年 月 日，如 2024 1 1（闰月写 闰2）";
            return new BatchLine(line, false, line + " → " + msg, msg);
        }
        int ly = p[0];
        int lm = p[1];
        int leap = p[2];
        int ld = p[3];
        if (ly < 1900 || ly > 2049 || lm < 1 || lm > 12 || ld < 1 || ld > 30) {
            String msg = "✗ 农历需在 1900~2049 年 / 月 1~12 / 日 1~30";
            return new BatchLine(line, false, line + " → " + msg, msg);
        }
        LunarInfo info = toGregorian(ly, lm, leap == 1, ld);
        if (!info.supported()) {
            String msg = "✗ " + info.status();
            return new BatchLine(line, false, line + " → " + msg, msg);
        }
        String res = info.gregorianText() + ' ' + info.weekdayText();
        return new BatchLine(line, true, line + " → " + res, res);
    }

    private static BatchLine chineseLine(String line) {
        LunarInfo info = parseChinese(line);
        if (info.supported()) {
            String res = info.gregorianText() + ' ' + info.weekdayText()
                    + " / " + info.ganzhiYear() + "年 " + info.lunarDateText();
            return new BatchLine(line, true, line + " → " + res, res);
        }
        String msg = "✗ " + info.status();
        return new BatchLine(line, false, line + " → " + msg, msg);
    }

    /** 容忍的公历日期解析：yyyy-MM-dd / yyyy/M/d / yyyy.M.d / yyyyMMdd。无法解析返回 null。 */
    public static LocalDate parseGregorianDate(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(t, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (java.time.DateTimeException ignore) {
            // 继续尝试其它分隔符
        }
        String[] parts = t.split("[-/.]");
        if (parts.length == 3) {
            try {
                return LocalDate.of(Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim()));
            } catch (NumberFormatException | java.time.DateTimeException ex) {
                return null;
            }
        }
        if (t.length() == 8 && t.chars().allMatch(Character::isDigit)) {
            try {
                return LocalDate.parse(t, DateTimeFormatter.BASIC_ISO_DATE);
            } catch (java.time.DateTimeException ex) {
                return null;
            }
        }
        return null;
    }

    private static final Pattern LUNAR_LINE = Pattern.compile(
            "^\\s*(\\d{4})\\s*[-/.年月]?\\s*(闰?)\\s*(\\d{1,2})\\s*[-/.月]?\\s*(\\d{1,2})\\s*(?:日|号)?\\s*$");

    /** 农历数字行解析：2024 1 1 / 2024-1-1 / 2024 闰2 1，返回 {年,月,闰(0/1),日}，无法解析返回 null。 */
    public static int[] parseNumericLunar(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = LUNAR_LINE.matcher(s);
        if (!m.matches()) {
            return null;
        }
        try {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(3));
            int d = Integer.parseInt(m.group(4));
            int leap = m.group(2).isEmpty() ? 0 : 1;
            return new int[]{y, mo, leap, d};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 读取连续的中文数字字符，拼接成数字串（用于年份，如 二〇二四 → 2024）。 */
    private static String readChineseDigits(String s, int i) {
        StringBuilder sb = new StringBuilder();
        while (i < s.length() && CN_DIGIT.containsKey(s.charAt(i))) {
            sb.append(CN_DIGIT.get(s.charAt(i)));
            i++;
        }
        return sb.toString();
    }

    /** 解析中文农历日（初一…三十 或 阿拉伯数字），返回 1~30，区间外返回 -1。 */
    private static int readChineseDay(String s, int i) {
        if (i >= s.length()) {
            return -1;
        }
        char c = s.charAt(i);
        if (Character.isDigit(c)) {
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j)) && (j - i) < 2) {
                j++;
            }
            return Integer.parseInt(s.substring(i, j));
        }
        String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
        String three = i + 2 < s.length() ? s.substring(i, i + 3) : "";
        if (two.equals("初十")) {
            return 10;
        }
        if (two.equals("十一") || two.equals("廿一")) {
            return 11;
        }
        if (two.equals("十二") || two.equals("廿二")) {
            return 12;
        }
        if (two.equals("十三") || two.equals("廿三")) {
            return 13;
        }
        if (two.equals("十四") || two.equals("廿四")) {
            return 14;
        }
        if (two.equals("十五") || two.equals("廿五")) {
            return 15;
        }
        if (two.equals("十六") || two.equals("廿六")) {
            return 16;
        }
        if (two.equals("十七") || two.equals("廿七")) {
            return 17;
        }
        if (two.equals("十八") || two.equals("廿八")) {
            return 18;
        }
        if (two.equals("十九") || two.equals("廿九")) {
            return 19;
        }
        if (two.equals("二十")) {
            return 20;
        }
        if (two.equals("三十")) {
            return 30;
        }
        if (three.equals("廿十")) {
            return 20;
        }
        // 初X / 廿X / 十X 拆解
        if (c == '初') {
            return cnDigitValue(peek(s, i + 1));
        }
        if (c == '廿') {
            int v = cnDigitValue(peek(s, i + 1));
            return 20 + (v < 0 ? 0 : v);
        }
        if (c == '十') {
            int v = cnDigitValue(peek(s, i + 1));
            return 10 + (v < 0 ? 0 : v);
        }
        return -1;
    }

    /** 两位中文日（初X / 廿X / 十X / 二十 / 三十等）能否在 i 处组成合法日。 */
    private static boolean isDayPair(String s, int i) {
        int v = readChineseDay(s, i);
        return v >= 10 && v <= 30;
    }

    /** 是否属于日期用字（初 / 廿 / 十 / 一至九 / 〇零）。 */
    private static boolean isDayChar(char c) {
        return c == '初' || c == '廿' || c == '十' || CN_DIGIT.containsKey(c);
    }

    private static char peek(String s, int i) {
        return i < s.length() ? s.charAt(i) : ' ';
    }

    private static int cnDigitValue(char c) {
        Integer v = CN_DIGIT.get(c);
        return v == null ? -1 : v;
    }

    private static boolean isGanzhi(char a, char b) {
        return GANZHI_STEMS.indexOf(a) >= 0 && GANZHI_BRANCHES.indexOf(b) >= 0;
    }

    private static int ganzhiStems(int year) {
        return ((year - 4) % 10 + 10) % 10;
    }

    private static int ganzhiBranches(int year) {
        return ((year - 4) % 12 + 12) % 12;
    }

    private static String monthName(int m) {
        return m >= 1 && m <= 12 ? LUNAR_MONTHS[m - 1] : String.valueOf(m);
    }

    private static String dayName(int d) {
        return d >= 1 && d <= 30 ? LUNAR_DAYS[d - 1] : String.valueOf(d);
    }

    /** 农历换算结果（含公历与农历两侧信息，文本字段均为展示就绪的中文）。 */
    public static final class LunarInfo {
        private final boolean supported;
        private final String status;
        private final int gregorianYear;
        private final int gregorianMonth;
        private final int gregorianDay;
        private final int lunarYear;
        private final int lunarMonth;
        private final int lunarDay;
        private final boolean leap;
        private final String lunarMonthText;
        private final String lunarDayText;
        private final String ganzhiYear;
        private final String ganzhiMonth;
        private final String ganzhiDay;
        private final String zodiac;
        private final String constellation;
        private final String solarTerm;

        private static final String[] WEEKDAYS =
                {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

        private LunarInfo(boolean supported, String status,
                          int gregorianYear, int gregorianMonth, int gregorianDay,
                          int lunarYear, int lunarMonth, int lunarDay, boolean leap,
                          String lunarMonthText, String lunarDayText,
                          String ganzhiYear, String ganzhiMonth, String ganzhiDay,
                          String zodiac, String constellation, String solarTerm) {
            this.supported = supported;
            this.status = status;
            this.gregorianYear = gregorianYear;
            this.gregorianMonth = gregorianMonth;
            this.gregorianDay = gregorianDay;
            this.lunarYear = lunarYear;
            this.lunarMonth = lunarMonth;
            this.lunarDay = lunarDay;
            this.leap = leap;
            this.lunarMonthText = lunarMonthText;
            this.lunarDayText = lunarDayText;
            this.ganzhiYear = ganzhiYear;
            this.ganzhiMonth = ganzhiMonth;
            this.ganzhiDay = ganzhiDay;
            this.zodiac = zodiac;
            this.constellation = constellation;
            this.solarTerm = solarTerm;
        }

        public static LunarInfo unsupported(int gy, int gm, int gd) {
            return new LunarInfo(false, RANGE_HINT, gy, gm, gd, 0, 0, 0, false,
                    "", "", "", "", "", "", "", "");
        }

        public static LunarInfo unsupported() {
            return new LunarInfo(false, RANGE_HINT, 0, 0, 0, 0, 0, 0, false,
                    "", "", "", "", "", "", "", "");
        }

        public static LunarInfo unsupported(String message) {
            return new LunarInfo(false, message, 0, 0, 0, 0, 0, 0, false,
                    "", "", "", "", "", "", "", "");
        }

        public static LunarInfo invalid(int ly, int lm, boolean leap, int ld) {
            return new LunarInfo(false, "无效的农历日期", 0, 0, 0, ly, lm, ld, leap,
                    "", "", "", "", "", "", "", "");
        }

        /** 干支年对应多个公历年份，无法唯一确定时返回候选说明（supported 为 false）。 */
        public static LunarInfo ambiguous(String message) {
            return new LunarInfo(false, message, 0, 0, 0, 0, 0, 0, false,
                    "", "", "", "", "", "", "", "");
        }

        public boolean supported() {
            return supported;
        }

        public String status() {
            return status;
        }

        public int gregorianYear() {
            return gregorianYear;
        }

        public int gregorianMonth() {
            return gregorianMonth;
        }

        public int gregorianDay() {
            return gregorianDay;
        }

        public int lunarYear() {
            return lunarYear;
        }

        public int lunarMonth() {
            return lunarMonth;
        }

        public int lunarDay() {
            return lunarDay;
        }

        public boolean leap() {
            return leap;
        }

        public String lunarMonthText() {
            return lunarMonthText;
        }

        public String lunarDayText() {
            return lunarDayText;
        }

        public String ganzhiYear() {
            return ganzhiYear;
        }

        public String ganzhiMonth() {
            return ganzhiMonth;
        }

        public String ganzhiDay() {
            return ganzhiDay;
        }

        public String zodiac() {
            return zodiac;
        }

        public String constellation() {
            return constellation;
        }

        public String solarTerm() {
            return solarTerm;
        }

        /** 农历日期文本，如「正月初一」「闰二月初五」。 */
        public String lunarDateText() {
            return (leap ? "闰" : "") + lunarMonthText + lunarDayText;
        }

        /** 公历日期文本，形如 2024-02-10。 */
        public String gregorianText() {
            return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d",
                    gregorianYear, gregorianMonth, gregorianDay);
        }

        /** 公历星期文本，如「星期六」；不支持时返回空串。 */
        public String weekdayText() {
            if (!supported) {
                return "";
            }
            DayOfWeek dow = LocalDate.of(gregorianYear, gregorianMonth, gregorianDay).getDayOfWeek();
            return WEEKDAYS[dow.getValue() % 7];
        }
    }
}
