package com.alianga.jkit;

import com.alianga.jkit.log.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Created by 郑明亮 on 2017/11/17 12:03.
 */

/**
 * @author 郑明亮 @email 1072307340@qq.com
 * @version 1.0
 * @time 2017/11/17 12:03
 */
public class IOUtils {
    private static final Log log = Log.get(IOUtils.class);

    private IOUtils() {
        throw new UnsupportedOperationException("you can not instant me");
    }

    // -----------------------------------------------------------------------
    // UTF-8 编解码（高性能，供 JSON 序列化使用）
    // -----------------------------------------------------------------------

    /**
     * 读取 UTF-8 编码的字节数组并转换为字符数组。
     *
     * @param bytes UTF-8 编码的字节数组
     * @return 对应的字符数组
     */
    public static char[] readUTF8Bytes(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        int len = bytes.length;
        char[] chars = new char[len];
        int charLen = readUTF8Bytes(bytes, chars);
        if (charLen != len) {
            chars = java.util.Arrays.copyOf(chars, charLen);
        }
        return chars;
    }

    /**
     * 将字符数组按 UTF-8 编码写入目标字节数组。
     *
     * @param input 源字符数组
     * @param offset 源偏移
     * @param len 字符长度
     * @param output 目标字节数组
     * @return 实际写入的字节数
     */
    public static int encodeUTF8(char[] input, int offset, int len, byte[] output) {
        int count = 0;
        for (int i = offset, end = offset + len; i < end; ++i) {
            char c = input[i];
            if (c < 0x80) {
                output[count++] = (byte) c;
            } else if (c < 0x800) {
                int h = c >> 6;
                int l = c & 0x3F;
                output[count++] = (byte) (0xAF | h);
                output[count++] = (byte) (0x8F | l);
            } else {
                int h = c >> 12;
                int m = (c >> 6) & 0x3F;
                int l = c & 0x3F;
                output[count++] = (byte) (0xE0 | h);
                output[count++] = (byte) (0x80 | m);
                output[count++] = (byte) (0x80 | l);
            }
        }
        return count;
    }

    /**
     * 读取 UTF-8 编码的字节到指定字符数组。
     *
     * @param bytes 源字节数组
     * @param chars 目标字符数组
     * @return 实际解析的字符数
     */
    public static int readUTF8Bytes(byte[] bytes, char[] chars) {
        return readUTF8Bytes(bytes, 0, bytes.length, chars, 0);
    }

    /**
     * 读取 UTF-8 编码的字节到指定字符数组（带偏移）。
     *
     * <pre>
     * 一个字节 0000 0000-0000 007F | 0xxxxxxx
     * 二个字节 0000 0080-0000 07FF | 110xxxxx 10xxxxxx
     * 三个字节 0000 0800-0000 FFFF | 1110xxxx 10xxxxxx 10xxxxxx
     * 四个字节 0001 0000-0010 FFFF | 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
     * </pre>
     *
     * @param bytes 源字节数组
     * @param offset 字节偏移
     * @param len 字节长度
     * @param chars 目标字符数组
     * @param cOffset 字符偏移
     * @return 实际解析的字符数
     */
    public static int readUTF8Bytes(byte[] bytes, int offset, int len, char[] chars, int cOffset) {
        if (bytes == null) {
            return 0;
        }
        int charLen = cOffset;
        for (int j = offset, max = offset + len; j < max; ++j) {
            byte b = bytes[j];
            if (b >= 0) {
                chars[charLen++] = (char) b;
                continue;
            }
            int s = b >> 4;
            switch (s) {
                case -1:
                    // 1111 4个字节
                    if (j < max - 3) {
                        byte b1 = bytes[++j];
                        byte b2 = bytes[++j];
                        byte b3 = bytes[++j];
                        int a = ((b & 0x7) << 18) | ((b1 & 0x3f) << 12) | ((b2 & 0x3f) << 6) | (b3 & 0x3f);
                        if (Character.isSupplementaryCodePoint(a)) {
                            chars[charLen++] = (char) ((a >>> 10)
                                    + (Character.MIN_HIGH_SURROGATE - (Character.MIN_SUPPLEMENTARY_CODE_POINT >>> 10)));
                            chars[charLen++] = (char) ((a & 0x3ff) + Character.MIN_LOW_SURROGATE);
                        } else {
                            chars[charLen++] = (char) a;
                        }
                        break;
                    } else {
                        throw new UnsupportedOperationException("utf-8 character error ");
                    }
                case -2:
                    // 1110 3个字节
                    if (j < max - 2) {
                        byte b1 = bytes[++j];
                        byte b2 = bytes[++j];
                        int a = ((b & 0xf) << 12) | ((b1 & 0x3f) << 6) | (b2 & 0x3f);
                        chars[charLen++] = (char) a;
                        break;
                    } else {
                        throw new UnsupportedOperationException("utf-8 character error ");
                    }
                case -3:
                    // 1101 和 1100都按2字节处理
                case -4:
                    // 1100 2个字节
                    if (j < max - 1) {
                        byte b1 = bytes[++j];
                        int a = ((b & 0x1f) << 6) | (b1 & 0x3f);
                        chars[charLen++] = (char) a;
                        break;
                    } else {
                        throw new UnsupportedOperationException("utf-8 character error ");
                    }
                default:
                    throw new UnsupportedOperationException("utf-8 character error ");
            }
        }
        return charLen;
    }

    // -----------------------------------------------------------------------
    // 基于 ThreadLocal 缓冲的流读取（供 JSON 解析使用）
    // -----------------------------------------------------------------------

    private static final ThreadLocal<byte[]> BYTES_2048_TL = ThreadLocal.withInitial(() -> new byte[2048]);

    private static final ThreadLocal<char[]> CHARS_1024_TL = ThreadLocal.withInitial(() -> new char[1024]);

    /**
     * 从输入流读取完整字节数组（内部使用 ThreadLocal 缓冲，适合高频调用场景）。
     *
     * @param is 输入流
     * @return 读取的字节数组
     * @throws IOException 读取异常
     */
    public static byte[] readBytes(InputStream is) throws IOException {
        try {
            byte[] tmp = BYTES_2048_TL.get();
            int len = tmp.length;
            int count;
            int readedCount = 0;
            while ((count = is.read(tmp, readedCount, len)) > -1) {
                readedCount += count;
                if (count < len) {
                    len -= count;
                } else {
                    len = tmp.length << 1;
                    tmp = java.util.Arrays.copyOf(tmp, tmp.length + len);
                }
            }
            return java.util.Arrays.copyOf(tmp, readedCount);
        } finally {
            is.close();
        }
    }

    /**
     * 从输入流读取字符数组（使用默认字符集）。
     *
     * @param is 输入流
     * @return 读取的字符数组
     * @throws IOException 读取异常
     */
    public static char[] readAsChars(InputStream is) throws IOException {
        return readAsChars(is, Charset.defaultCharset());
    }

    /**
     * 从输入流读取字符数组。
     *
     * @param is 输入流
     * @param charset 字符集
     * @return 读取的字符数组
     * @throws IOException 读取异常
     */
    public static char[] readAsChars(InputStream is, Charset charset) throws IOException {
        InputStreamReader isr = null;
        try {
            isr = new InputStreamReader(is, charset);
            char[] tmp = CHARS_1024_TL.get();
            int len = tmp.length;
            int count;
            int readedCount = 0;
            while ((count = isr.read(tmp, readedCount, len)) > -1) {
                readedCount += count;
                if (count < len) {
                    len -= count;
                } else {
                    len = tmp.length << 1;
                    tmp = java.util.Arrays.copyOf(tmp, tmp.length + len);
                }
            }
            return java.util.Arrays.copyOf(tmp, readedCount);
        } finally {
            if (isr != null) {
                isr.close();
            }
            is.close();
        }
    }

    /**
     * 将输入流转换为byte[]
     *
     * @param is 输入流
     * @return 读取到的内容
     */
    public static byte[] isToBytes(InputStream is) {
        ByteArrayOutputStream temp = null;
        byte[] buffer = new byte[1024];
        int len = 0;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            while ((len = is.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            bos.flush();
            temp = bos;
        } catch (IOException e) {
            log.error("输入流转字节异常", e);
        }

        return temp != null ? temp.toByteArray() : new byte[0];
    }

    /**
     * 将输入流转为字符串，默认编码为UTF-8
     *
     * @param inputStream 输入流
     * @return 读取到的内容
     */
    public static String is2String(InputStream inputStream) {
        if (inputStream == null) {
            return null;
        }
        return new String(isToBytes(inputStream), StandardCharsets.UTF_8);
    }

    /**
     * 将输入流转为指定编码的字符串
     *
     * @param inputStream 输入流
     * @param charset 指定编码，如果编码不存在，则可能会抛出异常
     * @return 读取到的内容
     */
    public static String is2String(InputStream inputStream, String charset) {
        return new String(isToBytes(inputStream), Charset.forName(charset));
    }

    /**
     * 关闭流，忽略关闭过程中出现的异常（仅记录错误日志）
     *
     * @param closeable 待关闭的流，为 {@code null} 时不做任何处理
     */
    public static void close(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception e) {
            log.error("流关闭异常", e);
        }
    }

    /**
     * 关闭多个流
     *
     * @param closeable 待关闭的流数组，数组本身或其中的元素为 {@code null} 时会被跳过
     */
    public static void close(AutoCloseable... closeable) {
        try {
            if (closeable != null) {
                for (AutoCloseable item : closeable) {
                    close(item);
                }
            }

        } catch (Exception e) {
            log.error("流关闭异常", e);
        }
    }

    /**
     * 刷新流，忽略刷新过程中出现的异常（仅记录错误日志）
     *
     * @param flushable 待刷新的流，为 {@code null} 时不做任何处理
     */
    public static void flush(Flushable flushable) {
        try {
            if (flushable != null) {
                flushable.flush();
            }
        } catch (IOException e) {
            log.error("刷盘异常", e);
        }
    }

    /**
     * 刷新多个
     *
     * @param flushable 待刷新的流数组，数组为 {@code null} 时不做任何处理
     */
    public static void flush(Flushable... flushable) {
        try {
            if (flushable != null) {
                for (Flushable item : flushable) {
                    item.flush();
                }

            }
        } catch (IOException e) {
            log.error("刷盘异常", e);
        }
    }

    /**
     * 获取文件的输入流
     *
     * @param file 目标文件
     * @return 文件输入流，文件不存在时记录错误日志并返回 {@code null}
     */
    public static InputStream getInputStream(File file) {
        try {
            return new FileInputStream(file);
        } catch (FileNotFoundException e) {
            log.error("文件不存在", e);
        }
        return null;

    }

    /**
     * 根据文件路径获取文件的输入流
     *
     * @param path 文件路径
     * @return 文件输入流，文件不存在时记录错误日志并返回 {@code null}
     */
    public static InputStream getInputStream(String path) {
        return getInputStream(new File(path));
    }

    /**
     * 将字节数组包装为输入流
     *
     * @param bytes 字节数组
     * @return 基于该字节数组的 {@link ByteArrayInputStream}
     */
    public static InputStream getInputStream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    /**
     * 获取文件的字符读取流
     *
     * @param file 目标文件
     * @return 文件字符读取流，文件不存在时记录错误日志并返回 {@code null}
     */
    public static Reader getReader(File file) {
        try {
            return new FileReader(file);
        } catch (FileNotFoundException e) {
            log.error("文件不存在", e);
        }
        return null;

    }

    /**
     * 将输入流包装为 UTF-8 编码的字符读取流
     *
     * @param inputStream 输入流
     * @return 使用 UTF-8 解码的 {@link InputStreamReader}
     */
    public static Reader getReader(InputStream inputStream) {
        return new InputStreamReader(inputStream, StandardCharsets.UTF_8);
    }

    /**
     * 根据文件路径获取文件的字符读取流
     *
     * @param path 文件路径
     * @return 文件字符读取流，文件不存在时记录错误日志并返回 {@code null}
     */
    public static Reader getReader(String path) {
        return getReader(new File(path));
    }

    /**
     * 以指定编码读取文本文件的全部内容，并包装为字符读取流
     *
     * @param path 文件路径
     * @param charset 读取文件内容使用的字符编码
     * @return 基于已读取文本内容的 {@link StringReader}
     */
    public static Reader getReader(String path, Charset charset) {
        return new StringReader(FileUtils.readTxtFile(path, charset.name()));
    }

}
