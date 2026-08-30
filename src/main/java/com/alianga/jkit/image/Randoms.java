package com.alianga.jkit.image;

import java.security.SecureRandom;
import java.util.Random;

/**
 * <p>随机工具类</p>
 *
 * @author wuhongjun
 * @version 1.0
 */
public class Randoms {
    private static final Random RANDOM = new SecureRandom();
    //定义验证码字符.去除了O和I等容易混淆的字母
    /**
     * 验证码可用字符表，已剔除容易混淆的字母（如 O、I、l）和数字 0、1。
     */
    public static final char[] ALPHA =
            {'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'G', 'K', 'M', 'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X',
                    'Y', 'Z',
                    'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'm', 'n', 'p', 'q', 'r', 's', 't', 'u',
                    'v', 'w', 'x', 'y', 'z', '2', '3', '4', '5', '6', '7', '8', '9'};

    /**
     * 产生两个数之间的随机数
     *
     * @param min 小数
     * @param max 比min大的数
     * @return int 随机数字
     */
    public static int num(int min, int max) {
        return min + RANDOM.nextInt(max - min);
    }

    /**
     * 产生0--num的随机数,不包括num
     *
     * @param num 数字
     * @return int 随机数字
     */
    public static int num(int num) {
        return RANDOM.nextInt(num);
    }

    /**
     * 从 {@link #ALPHA} 字符表中随机取一个字符。
     *
     * @return 随机的验证码字符
     */
    public static char alpha() {
        return ALPHA[num(0, ALPHA.length)];
    }
}
