package com.alianga.jkit.mock;

import java.security.SecureRandom;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;

/**
 * Mock 数据生成器，按字段类型生成随机值。
 *
 * <p>实现逻辑对齐 FeHelper 数据 Mock 插件，使用同样的中文语料与随机范围，
 * 保证从插件迁移到桌面工具后输出风格一致。</p>
 *
 * @author 郑明亮
 */
public class MockDataGenerator {
    private static final String ALPHANUMERIC =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String ALPHANUMERIC_SYMBOL =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*";
    private static final String HEX = "0123456789ABCDEF";
    private static final String NUMERIC = "0123456789";
    private static final String CHINESE_NUMBERS = "一二三四五六七八九十";

    private static final String[] SURNAMES = {
            "王", "李", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴",
            "徐", "孙", "胡", "朱", "高", "林", "何", "郭", "马", "罗",
            "梁", "宋", "郑", "谢", "韩", "唐", "冯", "于", "董", "萧"
    };

    private static final String[] GIVEN_NAMES = {
            "伟", "芳", "娜", "秀英", "敏", "静", "丽", "强", "磊", "军",
            "洋", "勇", "艳", "杰", "娟", "涛", "明", "超", "秀兰", "霞",
            "平", "刚", "桂英", "永", "健", "鑫", "帅", "莉", "凯", "浩",
            "宇", "琳", "雅", "欣", "晨", "阳", "雪", "晴", "萌", "悦"
    };

    private static final String[] COMPANY_SUFFIXES = {
            "有限公司", "股份有限公司", "科技有限公司", "贸易有限公司", "实业有限公司",
            "投资有限公司", "集团有限公司", "控股有限公司", "发展有限公司", "建设有限公司",
            "咨询有限公司", "服务有限公司"
    };

    private static final String[] COMPANY_PREFIXES = {
            "阿里巴巴", "腾讯", "百度", "京东", "美团", "字节跳动", "滴滴", "小米", "华为", "网易",
            "新浪", "搜狐", "爱奇艺", "快手", "拼多多", "携程", "途牛", "去哪儿", "58同城", "赶集网",
            "优酷", "土豆", "乐视", "暴风", "金山", "猎豹", "360", "蚂蚁金服", "陆金所",
            "恒生电子", "同花顺", "东方财富"
    };

    private static final String[] DEPARTMENTS = {
            "技术部", "产品部", "运营部", "市场部", "销售部", "人事部", "财务部", "行政部", "法务部",
            "客服部", "设计部", "测试部", "运维部", "数据部", "商务部", "品牌部", "公关部", "投资部"
    };

    private static final String[] POSITIONS = {
            "前端工程师", "后端工程师", "全栈工程师", "移动端工程师", "DevOps工程师",
            "产品经理", "项目经理", "技术经理", "运营专员", "市场专员",
            "UI设计师", "UX设计师", "测试工程师", "数据分析师", "算法工程师",
            "架构师", "CTO", "CEO", "COO", "CFO", "总监", "主管", "专员"
    };

    private static final String[] PROVINCES = {
            "北京市", "上海市", "天津市", "重庆市", "河北省", "山西省", "辽宁省", "吉林省", "黑龙江省",
            "江苏省", "浙江省", "安徽省", "福建省", "江西省", "山东省", "河南省", "湖北省", "湖南省",
            "广东省", "海南省", "四川省", "贵州省", "云南省", "陕西省", "甘肃省", "青海省", "台湾省",
            "内蒙古自治区", "广西壮族自治区", "西藏自治区", "宁夏回族自治区", "新疆维吾尔自治区",
            "香港特别行政区", "澳门特别行政区"
    };

    private static final String[] CITIES = {
            "北京", "上海", "广州", "深圳", "杭州", "南京", "武汉", "成都", "西安", "郑州",
            "青岛", "大连", "宁波", "厦门", "福州", "长沙", "济南", "重庆", "天津", "苏州",
            "无锡", "石家庄", "太原", "沈阳", "长春", "哈尔滨", "合肥", "南昌", "昆明", "贵阳",
            "兰州", "银川"
    };

    private static final String[] DISTRICTS = {
            "朝阳区", "海淀区", "西城区", "东城区", "丰台区", "石景山区"
    };

    private static final String[] PHONE_PREFIXES = {
            "130", "131", "132", "133", "134", "135", "136", "137", "138", "139",
            "150", "151", "152", "153", "155", "156", "157", "158", "159",
            "180", "181", "182", "183", "184", "185", "186", "187", "188", "189"
    };

    private static final String[] EMAIL_DOMAINS = {
            "gmail.com", "yahoo.com", "hotmail.com", "outlook.com", "163.com", "126.com", "qq.com",
            "sina.com", "sohu.com", "foxmail.com", "aliyun.com", "yeah.net", "vip.sina.com", "vip.163.com"
    };

    private static final String[] CURRENCIES = {
            "CNY", "USD", "EUR", "JPY", "GBP", "AUD", "CAD", "CHF", "HKD", "SGD"
    };

    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/91.0.4472.124 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/91.0.4472.124 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/91.0.4472.124 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:89.0) Gecko/20100101 Firefox/89.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/14.1.1 Safari/605.1.15"
    };

    private static final String[] MIME_TYPES = {
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/svg+xml",
            "text/html", "text/css", "text/javascript", "text/plain", "text/csv",
            "application/json", "application/xml", "application/pdf", "application/zip",
            "video/mp4", "video/webm", "audio/mp3", "audio/wav", "audio/ogg"
    };

    private static final String[] FILE_EXTENSIONS = {
            "jpg", "png", "gif", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "csv", "json", "xml", "zip", "rar", "mp4", "avi", "mov", "mp3",
            "wav", "html", "css", "js"
    };

    private static final String[] DOMAIN_SUFFIXES = {
            "com", "cn", "net", "org", "edu", "gov", "mil", "int",
            "com.cn", "net.cn", "org.cn", "edu.cn", "gov.cn"
    };

    private static final String[] URL_DOMAINS = {
            "example.com", "test.com", "demo.com", "sample.org", "mock.net"
    };

    private static final String[] URL_PATHS = {
            "/", "/home", "/about", "/contact", "/products", "/services", "/blog"
    };

    private static final String[] URL_SCHEMES = {
            "http", "https"
    };

    private static final String[] BANK_CARD_PREFIXES = {
            "6225", "6222", "6228", "6229", "6227", "6223", "6226"
    };

    private static final String[] CREDIT_CARD_PREFIXES = {
            "4", "5", "6"
    };

    private static final String[] ID_CARD_AREAS = {
            "110000", "120000", "130000", "140000", "150000",
            "210000", "220000", "230000"
    };

    private static final String[] ID_CARD_CHECK = {
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "X"
    };

    private static final Random RANDOM = new SecureRandom();

    /**
     * 根据字段类型生成一个随机值。
     *
     * @param type 字段类型
     * @return 随机值，字符串或数字对象
     */
    public Object generate(MockFieldType type) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case NAME:
                return generateName();
            case EMAIL:
                return generateEmail();
            case PHONE:
                return generatePhone();
            case ID_CARD:
                return generateIdCard();
            case GENDER:
                return generateGender();
            case AGE:
                return generateAge();
            case BIRTHDAY:
                return generateBirthday();
            case ADDRESS:
                return generateAddress();
            case COMPANY:
                return generateCompany();
            case DEPARTMENT:
                return generateDepartment();
            case POSITION:
                return generatePosition();
            case SALARY:
                return generateSalary();
            case BANK_CARD:
                return generateBankCard();
            case CREDIT_CARD:
                return generateCreditCard();
            case PRICE:
                return generatePrice();
            case CURRENCY:
                return generateCurrency();
            case UUID:
                return generateUuid();
            case IP:
                return generateIp();
            case MAC:
                return generateMac();
            case USER_AGENT:
                return generateUserAgent();
            case URL:
                return generateUrl();
            case DOMAIN:
                return generateDomain();
            case PASSWORD:
                return generatePassword();
            case TOKEN:
                return generateToken();
            case COLOR:
                return generateColor();
            case TIMESTAMP:
                return generateTimestamp();
            case FILENAME:
                return generateFilename();
            case MIME_TYPE:
                return generateMimeType();
            case BOOLEAN:
                return generateBoolean();
            case DATE:
                return generateDate();
            default:
                return null;
        }
    }

    /**
     * 生成自定义字段值。
     *
     * @param field 自定义字段定义
     * @return 随机值
     */
    public Object generateCustom(MockCustomField field) {
        if (field == null || field.getType() == null) {
            return randomString(10);
        }
        switch (field.getType()) {
            case STRING:
                return randomString(randomInt(5, 20));
            case NUMBER:
                return randomInt(1, 1000);
            case BOOLEAN:
                return generateBoolean();
            case DATE:
                return generateDate();
            case ARRAY:
                int size = randomInt(1, 5);
                String[] arr = new String[size];
                for (int i = 0; i < size; i++) {
                    arr[i] = randomString(5);
                }
                return arr;
            default:
                return randomString(10);
        }
    }

    /**
     * 生成随机姓名。
     *
     * @return 中文姓名
     */
    public String generateName() {
        String surname = randomChoice(SURNAMES);
        int count = RANDOM.nextDouble() > 0.7 ? 2 : 1;
        StringBuilder given = new StringBuilder();
        for (int i = 0; i < count; i++) {
            given.append(randomChoice(GIVEN_NAMES));
        }
        return surname + given.toString();
    }

    /**
     * 生成随机邮箱。
     *
     * @return 邮箱地址
     */
    public String generateEmail() {
        String local = randomString(randomInt(6, 12));
        String domain = randomChoice(EMAIL_DOMAINS);
        return local + "@" + domain;
    }

    /**
     * 生成随机手机号。
     *
     * @return 11 位手机号
     */
    public String generatePhone() {
        return randomChoice(PHONE_PREFIXES) + randomString(8, NUMERIC);
    }

    /**
     * 生成随机身份证号。
     *
     * @return 18 位身份证号
     */
    public String generateIdCard() {
        String area = randomChoice(ID_CARD_AREAS);
        int year = randomInt(1970, 2000);
        int month = randomInt(1, 12);
        int day = randomInt(1, 28);
        String birth = String.valueOf(year)
                + padLeft(month, 2)
                + padLeft(day, 2);
        String seq = String.valueOf(randomInt(100, 999));
        String check = randomChoice(ID_CARD_CHECK);
        return area.substring(0, 6) + birth + seq + check;
    }

    /**
     * 生成随机性别。
     *
     * @return "男" 或 "女"
     */
    public String generateGender() {
        return RANDOM.nextDouble() > 0.5 ? "男" : "女";
    }

    /**
     * 生成随机年龄。
     *
     * @return 18 到 65 之间的整数
     */
    public int generateAge() {
        return randomInt(18, 65);
    }

    /**
     * 生成随机生日。
     *
     * @return yyyy-MM-dd 格式的日期字符串
     */
    public String generateBirthday() {
        int year = randomInt(1960, 2005);
        int month = randomInt(1, 12);
        int day = randomInt(1, 28);
        return year + "-" + padLeft(month, 2) + "-" + padLeft(day, 2);
    }

    /**
     * 生成随机地址。
     *
     * @return 中文地址
     */
    public String generateAddress() {
        String province = randomChoice(PROVINCES);
        String city = randomChoice(CITIES);
        String district = randomChoice(DISTRICTS);
        String street = randomString(2, CHINESE_NUMBERS) + "街道";
        String number = randomInt(1, 999) + "号";
        return province + city + district + street + number;
    }

    /**
     * 生成随机公司名。
     *
     * @return 公司名称
     */
    public String generateCompany() {
        return randomChoice(COMPANY_PREFIXES) + randomChoice(COMPANY_SUFFIXES);
    }

    /**
     * 生成随机部门。
     *
     * @return 部门名称
     */
    public String generateDepartment() {
        return randomChoice(DEPARTMENTS);
    }

    /**
     * 生成随机职位。
     *
     * @return 职位名称
     */
    public String generatePosition() {
        return randomChoice(POSITIONS);
    }

    /**
     * 生成随机薪资。
     *
     * @return 5000 到 50000 之间的整数
     */
    public int generateSalary() {
        return randomInt(5000, 50000);
    }

    /**
     * 生成随机银行卡号。
     *
     * @return 16 位银行卡号
     */
    public String generateBankCard() {
        return randomChoice(BANK_CARD_PREFIXES) + randomString(12, NUMERIC);
    }

    /**
     * 生成随机信用卡号。
     *
     * @return 16 位信用卡号
     */
    public String generateCreditCard() {
        return randomChoice(CREDIT_CARD_PREFIXES) + randomString(15, NUMERIC);
    }

    /**
     * 生成随机价格。
     *
     * @return 0.01 到 9999.99 之间保留两位小数的数字
     */
    public double generatePrice() {
        return randomDouble(0.01, 9999.99, 2);
    }

    /**
     * 生成随机货币代码。
     *
     * @return 货币代码
     */
    public String generateCurrency() {
        return randomChoice(CURRENCIES);
    }

    /**
     * 生成 UUID。
     *
     * @return 36 位带连字符 UUID
     */
    public String generateUuid() {
        String template = "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == 'x' || c == 'y') {
                int n = RANDOM.nextInt(16);
                int value = c == 'x' ? n : (n & 0x3) | 0x8;
                sb.append(Integer.toHexString(value));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 生成随机 IPv4 地址。
     *
     * @return IP 地址
     */
    public String generateIp() {
        return randomInt(1, 255) + "."
                + randomInt(0, 255) + "."
                + randomInt(0, 255) + "."
                + randomInt(1, 255);
    }

    /**
     * 生成随机 MAC 地址。
     *
     * @return MAC 地址
     */
    public String generateMac() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(randomString(2, HEX));
        }
        return sb.toString();
    }

    /**
     * 生成随机 User Agent。
     *
     * @return User Agent 字符串
     */
    public String generateUserAgent() {
        return randomChoice(USER_AGENTS);
    }

    /**
     * 生成随机 URL。
     *
     * @return URL
     */
    public String generateUrl() {
        String scheme = randomChoice(URL_SCHEMES);
        String domain = randomChoice(URL_DOMAINS);
        String path = randomChoice(URL_PATHS);
        return scheme + "://" + domain + path;
    }

    /**
     * 生成随机域名。
     *
     * @return 域名
     */
    public String generateDomain() {
        String name = randomString(randomInt(5, 15));
        String suffix = randomChoice(DOMAIN_SUFFIXES);
        return name + "." + suffix;
    }

    /**
     * 生成随机密码。
     *
     * @return 8 到 16 位的密码
     */
    public String generatePassword() {
        int length = randomInt(8, 16);
        return randomString(length, ALPHANUMERIC_SYMBOL);
    }

    /**
     * 生成随机 Token。
     *
     * @return 32 位 Token
     */
    public String generateToken() {
        return randomString(32, ALPHANUMERIC);
    }

    /**
     * 生成随机颜色值。
     *
     * @return 十六进制颜色值
     */
    public String generateColor() {
        return "#" + randomString(6, HEX);
    }

    /**
     * 生成随机时间戳。
     *
     * @return 毫秒时间戳
     */
    public long generateTimestamp() {
        return System.currentTimeMillis() + randomLong(-31536000000L, 31536000000L);
    }

    /**
     * 生成随机文件名。
     *
     * @return 文件名
     */
    public String generateFilename() {
        String name = randomString(randomInt(5, 15));
        String ext = randomChoice(FILE_EXTENSIONS);
        return name + "." + ext;
    }

    /**
     * 生成随机 MIME 类型。
     *
     * @return MIME 类型
     */
    public String generateMimeType() {
        return randomChoice(MIME_TYPES);
    }

    /**
     * 生成随机布尔值。
     *
     * @return true 或 false
     */
    public boolean generateBoolean() {
        return RANDOM.nextDouble() > 0.5;
    }

    /**
     * 生成随机日期。
     *
     * @return yyyy-MM-dd 格式的日期字符串
     */
    public String generateDate() {
        Calendar start = Calendar.getInstance();
        start.set(2020, Calendar.JANUARY, 1, 0, 0, 0);
        long startMs = start.getTimeInMillis();
        long nowMs = System.currentTimeMillis();
        long ms = startMs + (long) (RANDOM.nextDouble() * (nowMs - startMs));
        Date date = new Date(ms);
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        return year + "-" + padLeft(month, 2) + "-" + padLeft(day, 2);
    }

    /**
     * 生成指定长度的随机字符串。
     *
     * @param length 长度
     * @return 随机字符串
     */
    public String randomString(int length) {
        return randomString(length, ALPHANUMERIC);
    }

    /**
     * 从指定字符集中生成随机字符串。
     *
     * @param length 长度
     * @param chars  字符集
     * @return 随机字符串
     */
    public String randomString(int length, String chars) {
        if (length <= 0 || chars == null || chars.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * 生成 [min, max] 范围内的随机整数。
     *
     * @param min 最小值
     * @param max 最大值
     * @return 随机整数
     */
    public int randomInt(int min, int max) {
        if (min > max) {
            int tmp = min;
            min = max;
            max = tmp;
        }
        if (min == max) {
            return min;
        }
        return min + RANDOM.nextInt(max - min + 1);
    }

    /**
     * 生成 [min, max] 范围内的随机长整数。
     *
     * @param min 最小值
     * @param max 最大值
     * @return 随机长整数
     */
    public long randomLong(long min, long max) {
        if (min > max) {
            long tmp = min;
            min = max;
            max = tmp;
        }
        if (min == max) {
            return min;
        }
        long bound = max - min + 1;
        long value;
        if (bound <= 0) {
            value = min + (long) (RANDOM.nextDouble() * (max - min + 1));
        } else {
            value = min + (long) (RANDOM.nextDouble() * bound);
        }
        if (value > max) {
            value = max;
        }
        return value;
    }

    /**
     * 生成 [min, max) 范围内的随机浮点数并保留指定小数位。
     *
     * @param min    最小值
     * @param max    最大值
     * @param digits 小数位数
     * @return 随机浮点数
     */
    public double randomDouble(double min, double max, int digits) {
        if (min > max) {
            double tmp = min;
            min = max;
            max = tmp;
        }
        double value = min + RANDOM.nextDouble() * (max - min);
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    private String randomChoice(String[] array) {
        return array[RANDOM.nextInt(array.length)];
    }

    private String padLeft(int value, int length) {
        String s = String.valueOf(value);
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < length; i++) {
            sb.append('0');
        }
        sb.append(s);
        return sb.toString();
    }
}
