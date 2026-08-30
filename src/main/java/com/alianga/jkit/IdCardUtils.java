package com.alianga.jkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 身份证号工具：生成、校验、解析（发证地 / 出生日期 / 年龄 / 性别）。
 * 行政区划按 GB/T 2260 以「代码 → 名称」单表存储，包含已撤并的历史区划，
 * 解析时 O(1) 查省、市、区县。
 *
 * @author 郑明亮
 */
public class IdCardUtils {
    private static final String AREA_RESOURCE = "/idcard-areas.txt";
    /** 行政区划代码 → 名称（含历史区划，一份数据） */
    private static final Map<Integer, String> AREA_BY_CODE;
    /** 可用于生成身份证的区划代码（县级，以及无下辖县的地级如东莞） */
    private static final int[] GENERATE_AREA_CODES;

    static {
        Map<Integer, String> byCode = new HashMap<Integer, String>(8192);
        InputStream in = IdCardUtils.class.getResourceAsStream(AREA_RESOURCE);
        if (in == null) {
            throw new ExceptionInInitializerError("missing classpath resource " + AREA_RESOURCE);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() < 7) {
                    continue;
                }
                int code = Integer.parseInt(line.substring(0, 6));
                String name = line.substring(6).trim();
                if (!name.isEmpty()) {
                    byCode.put(code, name);
                }
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
        AREA_BY_CODE = Collections.unmodifiableMap(byCode);
        GENERATE_AREA_CODES = buildGenerateCodes(byCode);
    }

    /**
     * 受保护的构造方法，本类为静态工具类，不应被直接实例化，仅允许子类继承调用。
     */
    protected IdCardUtils() {
    }

    private static int[] buildGenerateCodes(Map<Integer, String> byCode) {
        Set<Integer> countyPrefixes = new HashSet<Integer>();
        for (Integer code : byCode.keySet()) {
            if (code != null && code.intValue() % 100 != 0) {
                countyPrefixes.add(code.intValue() / 100);
            }
        }
        List<Integer> codes = new ArrayList<Integer>(byCode.size());
        for (Map.Entry<Integer, String> entry : byCode.entrySet()) {
            int code = entry.getKey().intValue();
            if (code % 10000 == 0) {
                continue;
            }
            if (code % 100 != 0) {
                codes.add(code);
                continue;
            }
            if (!countyPrefixes.contains(code / 100) && !isGenericAreaName(entry.getValue())) {
                codes.add(code);
            }
        }
        int[] array = new int[codes.size()];
        for (int i = 0; i < codes.size(); i++) {
            array[i] = codes.get(i).intValue();
        }
        return array;
    }

    /**
     * 按行政区划代码查询名称（含已撤并历史区划）。
     *
     * @param code 6 位数字代码，如 110101
     * @return 名称，未知时为 {@code null}
     */
    public static String getAreaName(int code) {
        return AREA_BY_CODE.get(code);
    }

    /**
     * @return 只读的「代码 → 名称」表
     */
    public static Map<Integer, String> getAreaNames() {
        return AREA_BY_CODE;
    }

    /**
     * 生成 18 位随机身份证号。
     *
     * @return 身份证号码
     */
    public static String generate() {
        return generate(18, 50);
    }

    /**
     * 生成指定年龄区间的 18 位随机身份证号。
     *
     * @param minAge 最小年龄
     * @param maxAge 最大年龄
     * @return 身份证号码
     */
    public static String generate(int minAge, int maxAge) {
        StringBuilder generater = new StringBuilder(18);
        generater.append(randomAreaCode());
        generater.append(randomBirthday(minAge, maxAge));
        generater.append(randomCode());
        generater.append(calcTrailingNumber(generater.toString().toCharArray()));
        return generater.toString();
    }

    private static String randomAreaCode() {
        int code = GENERATE_AREA_CODES[RandomUtils.nextInt(GENERATE_AREA_CODES.length)];
        return pad6(code);
    }

    private static String pad6(int code) {
        String text = Integer.toString(code);
        if (text.length() >= 6) {
            return text;
        }
        StringBuilder builder = new StringBuilder(6);
        for (int i = text.length(); i < 6; i++) {
            builder.append('0');
        }
        return builder.append(text).toString();
    }

    private static String randomBirthday(int minAge, int maxAge) {
        Calendar birthday = Calendar.getInstance();
        int year = birthday.get(Calendar.YEAR) - RandomUtils.getNum(minAge, maxAge);
        birthday.set(Calendar.YEAR, year);
        birthday.set(Calendar.MONTH, RandomUtils.getNum(1, 12));
        birthday.set(Calendar.DATE, RandomUtils.getNum(1, 31));
        StringBuilder builder = new StringBuilder(8);
        builder.append(year);
        long month = birthday.get(Calendar.MONTH) + 1;
        if (month < 10) {
            builder.append('0');
        }
        builder.append(month);
        long date = birthday.get(Calendar.DATE);
        if (date < 10) {
            builder.append('0');
        }
        builder.append(date);
        return builder.toString();
    }

    /**
     * 计算 18 位身份证校验位（GB 11643）。
     *
     * @param chars 至少 17 位数字
     * @return 校验位，输入不足时为空格
     */
    public static char calcTrailingNumber(char[] chars) {
        if (chars.length < 17) {
            return ' ';
        }
        int[] c = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] r = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int result = 0;
        for (int i = 0; i < 17; i++) {
            result += c[i] * (chars[i] - '0');
        }
        return r[result % 11];
    }

    private static String randomCode() {
        int code = RandomUtils.nextInt(1000);
        if (code < 10) {
            return "00" + code;
        }
        if (code < 100) {
            return "0" + code;
        }
        return Integer.toString(code);
    }

    /**
     * 校验身份证号是否合法，支持 15 位、18 位（末位 x/X 均可）。
     *
     * @param idCard 身份证号码
     * @return 格式、出生日期、校验位均合法时返回 true
     */
    public static boolean isValid(String idCard) {
        return parse(idCard) != null;
    }

    /**
     * 校验并解析身份证号，提取发证地、出生日期、年龄、性别。
     * 15 位号码按 GB 11643 升位为 18 位（年份补 19、补校验位）。
     *
     * @param idCard 身份证号码
     * @return 解析结果；号码为空或非法时返回 {@code null}
     */
    public static IdCardInfo parse(String idCard) {
        if (idCard == null) {
            return null;
        }
        String id = idCard.trim();
        if (id.length() == 15) {
            if (!isAllDigits(id)) {
                return null;
            }
            id = id.substring(0, 6) + "19" + id.substring(6);
            try {
                id = id + calcTrailingNumber(id.toCharArray());
            } catch (RuntimeException e) {
                return null;
            }
        } else if (id.length() == 18) {
            id = id.toUpperCase();
            if (!isEighteenBody(id)) {
                return null;
            }
            char expected;
            try {
                expected = calcTrailingNumber(id.toCharArray());
            } catch (RuntimeException e) {
                return null;
            }
            if (id.charAt(17) != expected) {
                return null;
            }
        } else {
            return null;
        }
        if (id.charAt(0) < '1' || id.charAt(0) > '9') {
            return null;
        }

        String areaCodeText = id.substring(0, 6);
        String birthdayText = id.substring(6, 14);
        Calendar birthday = parseBirthday(birthdayText);
        if (birthday == null) {
            return null;
        }
        Date birthdayDate = birthday.getTime();
        Calendar today = Calendar.getInstance();
        if (birthdayDate.after(today.getTime())) {
            return null;
        }

        int genderDigit = id.charAt(16) - '0';
        boolean male = (genderDigit & 1) == 1;
        int areaCodeValue = Integer.parseInt(areaCodeText);
        String province = lookupAreaName(provinceCode(areaCodeValue));
        String city = lookupAreaName(cityCode(areaCodeValue));
        String district = lookupAreaName(areaCodeValue);
        if (isGenericAreaName(city) || (city != null && city.equals(province))) {
            city = null;
        }
        if (isGenericAreaName(district)) {
            district = null;
        }
        String issuePlace = buildIssuePlace(province, city, district, areaCodeText);
        return new IdCardInfo(id, areaCodeText, province, city, district, issuePlace,
                birthdayDate, formatBirthday(birthdayText), calcAge(birthday, today),
                male ? "男" : "女", male);
    }

    private static int provinceCode(int areaCode) {
        return areaCode / 10000 * 10000;
    }

    private static int cityCode(int areaCode) {
        return areaCode / 100 * 100;
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isEighteenBody(String id) {
        for (int i = 0; i < 17; i++) {
            char c = id.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        char last = id.charAt(17);
        return (last >= '0' && last <= '9') || last == 'X';
    }

    private static Calendar parseBirthday(String yyyyMMdd) {
        int year;
        int month;
        int day;
        try {
            year = Integer.parseInt(yyyyMMdd.substring(0, 4));
            month = Integer.parseInt(yyyyMMdd.substring(4, 6));
            day = Integer.parseInt(yyyyMMdd.substring(6, 8));
        } catch (NumberFormatException e) {
            return null;
        }
        if (year < 1900 || month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setLenient(false);
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        try {
            calendar.getTime();
        } catch (Exception e) {
            return null;
        }
        return calendar;
    }

    private static int calcAge(Calendar birthday, Calendar today) {
        int age = today.get(Calendar.YEAR) - birthday.get(Calendar.YEAR);
        int monthDelta = today.get(Calendar.MONTH) - birthday.get(Calendar.MONTH);
        if (monthDelta < 0
                || (monthDelta == 0 && today.get(Calendar.DAY_OF_MONTH) < birthday.get(Calendar.DAY_OF_MONTH))) {
            age--;
        }
        return age;
    }

    private static String formatBirthday(String yyyyMMdd) {
        return yyyyMMdd.substring(0, 4) + "-" + yyyyMMdd.substring(4, 6) + "-" + yyyyMMdd.substring(6, 8);
    }

    private static String lookupAreaName(int code) {
        return AREA_BY_CODE.get(code);
    }

    private static boolean isGenericAreaName(String name) {
        return "市辖区".equals(name)
                || "县".equals(name)
                || "省直辖县级行政区划".equals(name)
                || "自治区直辖县级行政区划".equals(name);
    }

    private static String buildIssuePlace(String province, String city, String district, String areaCodeText) {
        StringBuilder builder = new StringBuilder();
        appendArea(builder, province);
        if (city != null && !city.equals(province)) {
            appendArea(builder, city);
        }
        if (district != null && !district.equals(city) && !district.equals(province)) {
            appendArea(builder, district);
        }
        if (builder.length() == 0) {
            if (district != null) {
                builder.append(district);
            } else {
                builder.append(areaCodeText);
            }
        }
        return builder.toString();
    }

    private static void appendArea(StringBuilder builder, String name) {
        if (name == null || name.isEmpty() || isGenericAreaName(name)) {
            return;
        }
        builder.append(name);
    }

    static Map<String, Integer> legacyNameToCode() {
        Map<String, Integer> map = new HashMap<String, Integer>(AREA_BY_CODE.size() * 2);
        for (Map.Entry<Integer, String> entry : AREA_BY_CODE.entrySet()) {
            map.put(entry.getValue(), entry.getKey());
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * 身份证号解析结果。
     */
    public static final class IdCardInfo {
        private final String idNumber;
        private final String areaCode;
        private final String province;
        private final String city;
        private final String district;
        private final String issuePlace;
        private final Date birthday;
        private final String birthdayString;
        private final int age;
        private final String gender;
        private final boolean male;

        private IdCardInfo(String idNumber, String areaCode, String province, String city, String district,
                           String issuePlace, Date birthday, String birthdayString, int age, String gender,
                           boolean male) {
            this.idNumber = idNumber;
            this.areaCode = areaCode;
            this.province = province;
            this.city = city;
            this.district = district;
            this.issuePlace = issuePlace;
            this.birthday = birthday;
            this.birthdayString = birthdayString;
            this.age = age;
            this.gender = gender;
            this.male = male;
        }

        /**
         * @return 18 位身份证号（15 位已升位）
         */
        public String getIdNumber() {
            return idNumber;
        }

        /**
         * @return 6 位行政区划代码
         */
        public String getAreaCode() {
            return areaCode;
        }

        /**
         * @return 省份 / 直辖市 / 自治区
         */
        public String getProvince() {
            return province;
        }

        /**
         * @return 地级市 / 地区 / 自治州，可能为空
         */
        public String getCity() {
            return city;
        }

        /**
         * @return 区县，可能为空
         */
        public String getDistrict() {
            return district;
        }

        /**
         * @return 发证地（省 + 市 + 区县）
         */
        public String getIssuePlace() {
            return issuePlace;
        }

        /**
         * @return 出生日期
         */
        public Date getBirthday() {
            return birthday == null ? null : new Date(birthday.getTime());
        }

        /**
         * @return 出生日期字符串，格式 yyyy-MM-dd
         */
        public String getBirthdayString() {
            return birthdayString;
        }

        /**
         * @return 按当前日期计算的周岁
         */
        public int getAge() {
            return age;
        }

        /**
         * @return 性别：男 / 女
         */
        public String getGender() {
            return gender;
        }

        /**
         * @return 男性为 true
         */
        public boolean isMale() {
            return male;
        }

        @Override
        public String toString() {
            return "IdCardInfo{" +
                    "idNumber='" + idNumber + '\'' +
                    ", areaCode='" + areaCode + '\'' +
                    ", province='" + province + '\'' +
                    ", city='" + city + '\'' +
                    ", district='" + district + '\'' +
                    ", issuePlace='" + issuePlace + '\'' +
                    ", birthday=" + birthdayString +
                    ", age=" + age +
                    ", gender='" + gender + '\'' +
                    '}';
        }
    }
}
