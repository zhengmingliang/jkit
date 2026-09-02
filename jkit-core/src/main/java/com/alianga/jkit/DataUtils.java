package com.alianga.jkit;

import com.alianga.jkit.math.Numbers;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 数据工具类（精简版）。
 * <p>
 * 由 ZmlTools 的 {@code DataUtils} 迁移而来，原类同时依赖 fastjson、Guava、
 * OkHttp、Jsoup、Servlet 与 SLF4J；此处仅保留被本库其他工具类使用的纯 JDK 方法，
 * 其余能力请使用本库对应的专项工具类。
 *
 * @since 1.0.0
 */
public class DataUtils {
    private DataUtils() {
    }

    /**
     * 忽略大小写地判断源字符串是否与任一目标相等。
     *
     * @param source 源字符串，为 {@code null} 时返回 {@code false}
     * @param targets 目标值，为 {@code null} 时返回 {@code false}；其中的 {@code null} 元素会被跳过
     * @return 命中任一目标返回 {@code true}
     */
    public static boolean orEqualsIgnoreCase(String source, Object... targets) {
        if (source == null) {
            return false;
        }
        if (targets == null) {
            return false;
        }

        for (Object target : targets) {
            // 跳过 null 元素，否则 target.toString() 会抛 NPE
            if (target == null) {
                continue;
            }
            if (source.equalsIgnoreCase(target.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提取字符串中的第一个数字（含正负号与小数点）。
     *
     * @param text 源文本
     * @return 数字字符串，无法提取时返回空字符串
     * @see Numbers#getFirstNumber(String)
     */
    public static String getFirstNumber(String text) {
        return Numbers.getFirstNumber(text);
    }

    private static final String LOCAL_IP = "127.0.0.1";

    /**
     * 获取本机所有 IPv4 地址（排除回环地址）。
     *
     * @return IP 列表，可能为空
     */
    public static List<String> getLocalHostIPs() {
        List<String> res = new ArrayList<String>();
        Enumeration<?> netInterfaces;
        try {
            netInterfaces = NetworkInterface.getNetworkInterfaces();
            while (netInterfaces.hasMoreElements()) {
                NetworkInterface ni = (NetworkInterface) netInterfaces.nextElement();
                Enumeration<?> nii = ni.getInetAddresses();
                while (nii.hasMoreElements()) {
                    InetAddress inetAddress = (InetAddress) nii.nextElement();
                    String ip = inetAddress.getHostAddress();
                    if (ip.indexOf(':') == -1 && !LOCAL_IP.equals(ip)) {
                        res.add(ip);
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return res;
    }

    /**
     * 获取本机 IP（最后一个非回环 IPv4 地址），获取失败时回退到 {@code 127.0.0.1}。
     *
     * @return 本机 IP
     */
    public static String getLocalHostIP() {
        List<String> localHostIPs = getLocalHostIPs();
        if (localHostIPs != null && !localHostIPs.isEmpty()) {
            return localHostIPs.get(localHostIPs.size() - 1);
        }
        return LOCAL_IP;
    }
}
