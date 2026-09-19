package com.alianga.jkit;

import java.util.Arrays;

/**
 * 常用数据的脱敏：手机号、身份证、姓名、邮箱、银行卡、地址、车牌、IP、密码。
 *
 * <p>约定：
 * <ul>
 *   <li>入参为 {@code null} 返回 {@code null}，空串返回空串，不抛异常，可直接埋在日志链路里。</li>
 *   <li>长度不足保留位数时（例如 5 位的手机号）按「最少泄漏」处理：只保留首字符，其余打码。</li>
 *   <li>掩码字符默认 {@code *}，需要换成 {@code #} / {@code ×} 时用带 {@code mask} 参数的重载。</li>
 * </ul>
 *
 * <p>脱敏只用于展示与日志，**不能替代访问控制**：数据本身仍然是明文流转的。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class DesensitizeUtils {
    /**
     * 默认掩码字符。
     */
    public static final char DEFAULT_MASK = '*';

    /**
     * 预定义的数据类型，供配置驱动脱敏（如日志字段白名单）使用。
     *
     * @since 2.0.2
     */
    public enum Type {
        /** 中国大陆手机号：保留前 3 后 4。 */
        PHONE,
        /** 身份证号：保留前 6 后 4。 */
        ID_CARD,
        /** 姓名：保留姓，名全打码。 */
        NAME,
        /** 邮箱：本地部分保留首字符，域名完整保留。 */
        EMAIL,
        /** 银行卡：保留前 4 后 4。 */
        BANK_CARD,
        /** 地址：保留前 6，其余打码。 */
        ADDRESS,
        /** 车牌：保留前 2 后 2。 */
        CAR_NO,
        /** IPv4：保留前两段。 */
        IP,
        /** 密码：全部打码。 */
        PASSWORD,
        /** 兜底：保留前 1 后 1。 */
        DEFAULT
    }

    private DesensitizeUtils() {
    }

    /**
     * 按类型脱敏，便于配置里按字段名指定类型。
     *
     * @param value 原始值，可为 {@code null}
     * @param type 数据类型，为 {@code null} 时按 {@link Type#DEFAULT} 处理
     * @return 脱敏后的值
     */
    public static String desensitize(String value, Type type) {
        if (value == null || type == null) {
            return value == null ? null : defaultMask(value);
        }
        switch (type) {
            case PHONE:
                return phone(value);
            case ID_CARD:
                return idCard(value);
            case NAME:
                return name(value);
            case EMAIL:
                return email(value);
            case BANK_CARD:
                return bankCard(value);
            case ADDRESS:
                return address(value);
            case CAR_NO:
                return carNo(value);
            case IP:
                return ip(value);
            case PASSWORD:
                return password(value);
            default:
                return defaultMask(value);
        }
    }

    /**
     * 手机号脱敏：保留前 3 后 4，如 {@code 138****8000}。
     *
     * @param phone 手机号，可为 {@code null}
     * @return 脱敏后的手机号
     */
    public static String phone(String phone) {
        return mask(phone, 3, 4);
    }

    /**
     * 身份证号脱敏：保留前 6 后 4，如 {@code 110101********1234}。
     *
     * @param idCard 身份证号（15 或 18 位），可为 {@code null}
     * @return 脱敏后的身份证号
     */
    public static String idCard(String idCard) {
        return mask(idCard, 6, 4);
    }

    /**
     * 银行卡号脱敏：保留前 4 后 4，如 {@code 6222********1234}。
     *
     * @param bankCard 卡号（会先去掉空格），可为 {@code null}
     * @return 脱敏后的卡号
     */
    public static String bankCard(String bankCard) {
        if (bankCard == null) {
            return null;
        }
        return mask(bankCard.replace(" ", ""), 4, 4);
    }

    /**
     * 姓名脱敏：保留姓，名全打码（{@code 张三} → {@code 张*}，{@code 欧阳修} → {@code 欧**}）。
     *
     * <p>只按字符数处理，不识别复姓；含非中文（英文名）时按 {@link #defaultMask(String)} 兜底。
     *
     * @param name 姓名，可为 {@code null}
     * @return 脱敏后的姓名
     */
    public static String name(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (!isChineseName(trimmed)) {
            return defaultMask(trimmed);
        }
        return mask(trimmed, 1, 0);
    }

    /**
     * 邮箱脱敏：本地部分保留首字符，域名完整保留，如 {@code z***@example.com}。
     *
     * @param email 邮箱，可为 {@code null}
     * @return 脱敏后的邮箱
     */
    public static String email(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return defaultMask(email);
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() == 1) {
            return DEFAULT_MASK + domain;
        }
        return local.charAt(0) + repeat(DEFAULT_MASK, local.length() - 1) + domain;
    }

    /**
     * 地址脱敏：保留前 6 个字符，其余打码，如 {@code 北京市海淀区****}。
     *
     * @param address 地址，可为 {@code null}
     * @return 脱敏后的地址
     */
    public static String address(String address) {
        return mask(address, 6, 0);
    }

    /**
     * 车牌脱敏：保留前 2 后 2，如 {@code 京A***45}。
     *
     * @param carNo 车牌号，可为 {@code null}
     * @return 脱敏后的车牌号
     */
    public static String carNo(String carNo) {
        return mask(carNo, 2, 2);
    }

    /**
     * IPv4 脱敏：保留前两段，如 {@code 192.168.*.*}。
     *
     * @param ip IPv4 地址，可为 {@code null}
     * @return 脱敏后的地址；不是 IPv4 时按 {@link #defaultMask(String)} 兜底
     */
    public static String ip(String ip) {
        if (ip == null) {
            return null;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return defaultMask(ip);
        }
        return parts[0] + "." + parts[1] + "." + DEFAULT_MASK + "." + DEFAULT_MASK;
    }

    /**
     * 密码脱敏：无论多长都输出 6 个掩码字符，避免泄漏密码长度。
     *
     * @param password 密码，可为 {@code null}
     * @return 固定长度的掩码串
     */
    public static String password(String password) {
        if (password == null) {
            return null;
        }
        return repeat(DEFAULT_MASK, 6);
    }

    /**
     * 兜底脱敏：保留首字符与末字符。
     *
     * @param value 原始值，可为 {@code null}
     * @return 脱敏后的值
     */
    public static String defaultMask(String value) {
        return mask(value, 1, 1);
    }

    /**
     * 通用脱敏：保留头部 {@code keepHead} 个字符与尾部 {@code keepTail} 个字符，中间全部打码。
     *
     * @param value 原始值，可为 {@code null}
     * @param keepHead 保留的前缀字符数，负数按 0 处理
     * @param keepTail 保留的后缀字符数，负数按 0 处理
     * @return 脱敏后的值
     */
    public static String mask(String value, int keepHead, int keepTail) {
        return mask(value, keepHead, keepTail, DEFAULT_MASK);
    }

    /**
     * 通用脱敏，可指定掩码字符。
     *
     * <p>保留位数之和大于等于长度时只保留首字符、其余打码——宁可多打码也不因为「长度异常」
     * 把明文放出去；单字符字段整条都是敏感信息，直接整体打码。
     *
     * @param value 原始值，可为 {@code null}
     * @param keepHead 保留的前缀字符数，负数按 0 处理
     * @param keepTail 保留的后缀字符数，负数按 0 处理
     * @param maskChar 掩码字符
     * @return 脱敏后的值
     */
    public static String mask(String value, int keepHead, int keepTail, char maskChar) {
        if (value == null) {
            return null;
        }
        int len = value.length();
        if (len == 0) {
            return value;
        }
        int head = keepHead < 0 ? 0 : keepHead;
        int tail = keepTail < 0 ? 0 : keepTail;
        if (head + tail >= len) {
            // 保留位数已覆盖全部内容：只保留首字符，其余打码，避免原样返回；
            // 单字符无从保留，整条打码，否则敏感信息原样漏出
            if (len == 1) {
                return String.valueOf(maskChar);
            }
            return value.charAt(0) + repeat(maskChar, len - 1);
        }
        StringBuilder sb = new StringBuilder(len);
        sb.append(value, 0, head);
        sb.append(repeat(maskChar, len - head - tail));
        if (tail > 0) {
            sb.append(value, len - tail, len);
        }
        return sb.toString();
    }

    /**
     * 全部打码，保留原长度。
     *
     * @param value 原始值，可为 {@code null}
     * @return 等长掩码串
     */
    public static String maskAll(String value) {
        return maskAll(value, DEFAULT_MASK);
    }

    /**
     * 全部打码，保留原长度，可指定掩码字符。
     *
     * @param value 原始值，可为 {@code null}
     * @param maskChar 掩码字符
     * @return 等长掩码串
     */
    public static String maskAll(String value, char maskChar) {
        if (value == null) {
            return null;
        }
        return repeat(maskChar, value.length());
    }

    /**
     * 判断是否为纯中文姓名（2–15 个中文字符）。
     *
     * @param value 待判断字符串
     * @return 是中文姓名返回 {@code true}
     */
    private static boolean isChineseName(String value) {
        if (value.length() < 2 || value.length() > 15) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '\u4e00' || c > '\u9fa5') {
                return false;
            }
        }
        return true;
    }

    /**
     * 重复字符生成字符串。
     *
     * @param c 字符
     * @param count 重复次数
     * @return 由 {@code count} 个 {@code c} 组成的字符串
     */
    private static String repeat(char c, int count) {
        if (count <= 0) {
            return "";
        }
        char[] buf = new char[count];
        Arrays.fill(buf, c);
        return new String(buf);
    }
}
