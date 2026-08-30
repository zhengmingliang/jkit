package com.alianga.jkit;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本编码检测。
 *
 * <p>检测顺序：BOM → XML/HTML 声明 → ISO-2022/HZ → 合法 UTF-8 → UTF-16/32 空字节特征 →
 * GBK/GB18030、Big5、Shift_JIS、EUC-JP、EUC-KR 的字节结构打分。合法 UTF-8 优先于 GBK，
 * 避免旧字频算法把 UTF-8 中文判成 GB2312。</p>
 */
public final class EncodingDetect {
    private static final int SAMPLE_LIMIT = 64 * 1024;

    private static final Pattern CHARSET_DECL = Pattern.compile(
            "(?:charset|encoding)\\s*=\\s*['\"]?([A-Za-z0-9._\\-]+)",
            Pattern.CASE_INSENSITIVE);

    private EncodingDetect() {
        throw new UnsupportedOperationException("you cannot instant me");
    }

    /**
     * 检测文件编码，返回 Java 字符集名。文件不存在或读失败时返回 {@code UTF-8}。
     *
     * @param filePath 文件路径
     * @return 字符集名，如 {@code UTF-8}、{@code GBK}
     */
    public static String getJavaEncode(String filePath) {
        if (filePath == null) {
            return StandardCharsets.UTF_8.name();
        }
        try {
            return detect(new File(filePath)).name();
        } catch (IOException e) {
            return StandardCharsets.UTF_8.name();
        }
    }

    /**
     * 检测字节数组编码，返回 Java 字符集名。
     *
     * @param text 原始字节
     * @return 字符集名
     */
    public static String getUrlEncode(byte[] text) {
        return detect(text).name();
    }

    /**
     * 检测文件的字符编码，最多读取前 64KB 作为样本。
     *
     * @param file 待检测的文件，为 {@code null} 或不是普通文件时返回 {@code UTF-8}
     * @return 检测出的字符集
     * @throws IOException 读取文件失败时抛出
     */
    public static Charset detect(File file) throws IOException {
        if (file == null || !file.isFile()) {
            return StandardCharsets.UTF_8;
        }
        long length = file.length();
        int n = (int) Math.min(length, SAMPLE_LIMIT);
        byte[] buf = new byte[n];
        FileInputStream in = new FileInputStream(file);
        try {
            int read = 0;
            while (read < n) {
                int c = in.read(buf, read, n - read);
                if (c < 0) {
                    break;
                }
                read += c;
            }
            if (read < buf.length) {
                buf = Arrays.copyOf(buf, Math.max(read, 0));
            }
            return detect(buf);
        } finally {
            in.close();
        }
    }

    /**
     * 检测输入流的字符编码，最多读取前 64KB 作为样本。
     *
     * @param in 待检测的输入流，为 {@code null} 时返回 {@code UTF-8}；方法不会关闭该流
     * @return 检测出的字符集
     * @throws IOException 读取流失败时抛出
     */
    public static Charset detect(InputStream in) throws IOException {
        if (in == null) {
            return StandardCharsets.UTF_8;
        }
        byte[] buf = new byte[SAMPLE_LIMIT];
        int read = 0;
        int n;
        while (read < buf.length && (n = in.read(buf, read, buf.length - read)) >= 0) {
            read += n;
        }
        return detect(read == buf.length ? buf : Arrays.copyOf(buf, read));
    }

    /**
     * 检测字节数组的字符编码。
     *
     * @param data 待检测的原始字节，为 {@code null} 或空数组时返回 {@code US-ASCII}
     * @return 检测出的字符集
     */
    public static Charset detect(byte[] data) {
        if (data == null || data.length == 0) {
            return StandardCharsets.US_ASCII;
        }
        return detect(data, data.length);
    }

    /**
     * 检测字节数组前 {@code length} 个字节的字符编码。
     *
     * <p>依次尝试 BOM、XML/HTML 声明、ISO-2022/HZ 转义、空字节特征的 UTF-16/32、合法 UTF-8，
     * 最后按字节结构对 GB18030/GBK、Big5、Shift_JIS、EUC-JP、EUC-KR 及 Latin 系列打分。</p>
     *
     * @param data   待检测的原始字节，为 {@code null} 时返回 {@code US-ASCII}
     * @param length 参与检测的字节长度，超过数组长度时按数组长度处理；小于等于 0 时返回 {@code US-ASCII}
     * @return 检测出的字符集
     */
    public static Charset detect(byte[] data, int length) {
        if (data == null || length <= 0) {
            return StandardCharsets.US_ASCII;
        }
        int len = Math.min(length, data.length);

        Charset bom = detectBom(data, len);
        if (bom != null) {
            return bom;
        }

        Charset declared = sniffDeclaration(data, len);
        if (declared != null) {
            return declared;
        }

        Charset iso2022 = detectIso2022(data, len);
        if (iso2022 != null) {
            return iso2022;
        }

        int nulls = countNull(data, len);
        if (len >= 16 && nulls * 4 >= len) {
            Charset utf16 = detectUtf16Or32(data, len);
            if (utf16 != null) {
                return utf16;
            }
        }

        if (isUtf8(data, len)) {
            return isAscii(data, len) ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8;
        }

        Charset utf16 = detectUtf16Or32(data, len);
        if (utf16 != null) {
            return utf16;
        }

        return detectByStructure(data, len);
    }

    /**
     * 按检测结果解码。
     *
     * @param data 原始字节
     * @return 解码文本
     */
    public static String decode(byte[] data) {
        if (data == null) {
            return null;
        }
        Charset charset = detect(data);
        int offset = bomLength(data, data.length, charset);
        return new String(data, offset, data.length - offset, charset);
    }

    /**
     * 读取整个文件并按检测出的编码解码为字符串，自动跳过 BOM。
     *
     * @param file 待读取的文件，为 {@code null} 或不是普通文件时返回空字符串
     * @return 解码后的文本
     * @throws IOException 读取失败，或文件长度超过 {@link Integer#MAX_VALUE} 时抛出
     */
    public static String decode(File file) throws IOException {
        if (file == null || !file.isFile()) {
            return "";
        }
        long length = file.length();
        if (length > Integer.MAX_VALUE) {
            throw new IOException("file too large to decode: " + file);
        }
        byte[] data = new byte[(int) length];
        FileInputStream in = new FileInputStream(file);
        try {
            int read = 0;
            while (read < data.length) {
                int c = in.read(data, read, data.length - read);
                if (c < 0) {
                    break;
                }
                read += c;
            }
            if (read < data.length) {
                data = Arrays.copyOf(data, read);
            }
        } finally {
            in.close();
        }
        return decode(data);
    }

    static Charset detectBom(byte[] data, int len) {
        if (len >= 4) {
            int b0 = data[0] & 0xFF;
            int b1 = data[1] & 0xFF;
            int b2 = data[2] & 0xFF;
            int b3 = data[3] & 0xFF;
            if (b0 == 0x00 && b1 == 0x00 && b2 == 0xFE && b3 == 0xFF) {
                return Charset.forName("UTF-32BE");
            }
            if (b0 == 0xFF && b1 == 0xFE && b2 == 0x00 && b3 == 0x00) {
                return Charset.forName("UTF-32LE");
            }
        }
        if (len >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (len >= 2) {
            int b0 = data[0] & 0xFF;
            int b1 = data[1] & 0xFF;
            if (b0 == 0xFE && b1 == 0xFF) {
                return StandardCharsets.UTF_16BE;
            }
            if (b0 == 0xFF && b1 == 0xFE) {
                return StandardCharsets.UTF_16LE;
            }
        }
        return null;
    }

    static int bomLength(byte[] data, int len, Charset charset) {
        Charset bom = detectBom(data, len);
        if (bom == null || charset == null || !bom.equals(charset)) {
            return 0;
        }
        if (bom == StandardCharsets.UTF_8) {
            return 3;
        }
        if (bom == StandardCharsets.UTF_16BE || bom == StandardCharsets.UTF_16LE) {
            return 2;
        }
        return 4;
    }

    private static Charset sniffDeclaration(byte[] data, int len) {
        int n = Math.min(len, 512);
        for (int i = 0; i < n; i++) {
            int b = data[i] & 0xFF;
            if (b == 0 || b >= 0x80) {
                return null;
            }
        }
        String head = new String(data, 0, n, StandardCharsets.US_ASCII);
        Matcher matcher = CHARSET_DECL.matcher(head);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Charset.forName(matcher.group(1).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Charset detectIso2022(byte[] data, int len) {
        for (int i = 0; i < len - 2; i++) {
            if (data[i] != 0x1B) {
                continue;
            }
            int a = data[i + 1] & 0xFF;
            int b = data[i + 2] & 0xFF;
            if (a == '$' && (b == 'B' || b == '@' || b == '(')) {
                return charsetOrNull("ISO-2022-JP");
            }
            if (a == '$' && b == ')' && i + 3 < len) {
                int c = data[i + 3] & 0xFF;
                if (c == 'C') {
                    return charsetOrNull("ISO-2022-KR");
                }
                if (c == 'A' || c == 'G') {
                    return charsetOrNull("ISO-2022-CN");
                }
            }
        }
        int hz = 0;
        for (int i = 0; i < len - 1; i++) {
            if (data[i] == '~' && data[i + 1] == '{') {
                hz++;
            }
        }
        if (hz > 0) {
            return StandardCharsets.US_ASCII;
        }
        return null;
    }

    private static boolean isAscii(byte[] data, int len) {
        for (int i = 0; i < len; i++) {
            int c = data[i] & 0xFF;
            if (c == 0x09 || c == 0x0A || c == 0x0D) {
                continue;
            }
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static int countNull(byte[] data, int len) {
        int n = 0;
        for (int i = 0; i < len; i++) {
            if (data[i] == 0) {
                n++;
            }
        }
        return n;
    }

    static boolean isUtf8(byte[] data, int len) {
        int i = 0;
        while (i < len) {
            int c = data[i] & 0xFF;
            if (c < 0x80) {
                i++;
                continue;
            }
            int need;
            int min;
            if ((c & 0xE0) == 0xC0) {
                need = 1;
                min = 0x80;
                if (c < 0xC2) {
                    return false;
                }
            } else if ((c & 0xF0) == 0xE0) {
                need = 2;
                min = 0x800;
            } else if ((c & 0xF8) == 0xF0) {
                need = 3;
                min = 0x10000;
                if (c > 0xF4) {
                    return false;
                }
            } else {
                return false;
            }
            if (i + need >= len) {
                return false;
            }
            int cp = c & (0xFF >> (need + 1));
            for (int j = 1; j <= need; j++) {
                int n = data[i + j] & 0xFF;
                if ((n & 0xC0) != 0x80) {
                    return false;
                }
                cp = (cp << 6) | (n & 0x3F);
            }
            if (cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
                return false;
            }
            i += need + 1;
        }
        return true;
    }

    private static Charset detectUtf16Or32(byte[] data, int len) {
        if (len < 4) {
            return null;
        }
        int evenZero = 0;
        int oddZero = 0;
        int printableEven = 0;
        int printableOdd = 0;
        int pairs = len / 2;
        for (int i = 0; i + 1 < len; i += 2) {
            int hi = data[i] & 0xFF;
            int lo = data[i + 1] & 0xFF;
            if (hi == 0) {
                evenZero++;
            }
            if (lo == 0) {
                oddZero++;
            }
            if (lo >= 0x20 && lo < 0x7F) {
                printableOdd++;
            }
            if (hi >= 0x20 && hi < 0x7F) {
                printableEven++;
            }
        }
        if (pairs >= 8) {
            if (evenZero > pairs * 0.3 && printableOdd > pairs * 0.3) {
                return StandardCharsets.UTF_16BE;
            }
            if (oddZero > pairs * 0.3 && printableEven > pairs * 0.3) {
                return StandardCharsets.UTF_16LE;
            }
        }
        return null;
    }

    private static Charset detectByStructure(byte[] data, int len) {
        int best = Integer.MIN_VALUE;
        Charset charset = null;
        if (hasGb18030FourByte(data, len)) {
            int score = scoreCandidate(data, len, charsetOrNull("GB18030"), 4, -3, -3);
            if (score > best) {
                best = score;
                charset = charsetOrNull("GB18030");
            }
        }
        int[] hanW = {4, 4, 1, 1, 1};
        int[] hangulW = {-3, -3, -3, -3, 6};
        int[] kanaW = {-3, -3, 6, 6, -3};
        String[] names = {"GBK", "Big5", "Shift_JIS", "EUC-JP", "EUC-KR"};
        for (int i = 0; i < names.length; i++) {
            Charset candidate = charsetOrNull(names[i]);
            int score = scoreCandidate(data, len, candidate, hanW[i], hangulW[i], kanaW[i]);
            if (score > best) {
                best = score;
                charset = candidate;
            }
        }
        int latin = scoreLatin(data, len);
        if (latin > best) {
            best = latin;
            charset = hasWindows1252(data, len)
                    ? charsetOrNull("windows-1252")
                    : StandardCharsets.ISO_8859_1;
        }
        if (best <= 0 || charset == null) {
            return StandardCharsets.ISO_8859_1;
        }
        return charset;
    }

    private static int scoreCandidate(byte[] data, int len, Charset candidate, int hanW, int hangulW, int kanaW) {
        if (candidate == null) {
            return Integer.MIN_VALUE / 4;
        }
        String text = decodeStrict(data, len, candidate);
        if (text == null) {
            return Integer.MIN_VALUE / 4;
        }
        int score = scriptScore(text, hanW, hangulW, kanaW);
        if (nameEquals(candidate, "Big5")) {
            score += countBig5LowTrails(data, len) * 3;
        }
        return score;
    }

    private static String decodeStrict(byte[] data, int len, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(data, 0, len)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static int scriptScore(String text, int hanW, int hangulW, int kanaW) {
        int han = 0;
        int hangul = 0;
        int kana = 0;
        int halfKana = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp <= 0x7F) {
                continue;
            }
            if (cp >= 0x4E00 && cp <= 0x9FFF) {
                han++;
            } else if (cp >= 0xAC00 && cp <= 0xD7A3) {
                hangul++;
            } else if (cp >= 0x3040 && cp <= 0x30FF) {
                kana++;
            } else if (cp >= 0xFF61 && cp <= 0xFF9F) {
                halfKana++;
            } else {
                other++;
            }
        }
        int cjk = han + hangul + kana + halfKana;
        if (cjk == 0 && other == 0) {
            return 0;
        }
        int score = han * hanW + hangul * hangulW + kana * kanaW + halfKana - other;
        if (kanaW > 0 && kana >= 2 && kana + kana >= han) {
            score += 80;
        }
        if (hangulW > 0 && hangul >= 2 && hangul > han * 2) {
            score += 80;
        }
        return score;
    }

    private static int countBig5LowTrails(byte[] data, int len) {
        int n = 0;
        int i = 0;
        while (i + 1 < len) {
            int c = data[i] & 0xFF;
            if (c < 0x80) {
                i++;
                continue;
            }
            int d = data[i + 1] & 0xFF;
            if (c >= 0xA1 && c <= 0xF9 && d >= 0x40 && d <= 0x7E) {
                n++;
                i += 2;
            } else {
                i++;
            }
        }
        return n;
    }

    private static boolean hasGb18030FourByte(byte[] data, int len) {
        for (int i = 0; i + 3 < len; i++) {
            int c = data[i] & 0xFF;
            if (c < 0x80) {
                continue;
            }
            if (c >= 0x81 && c <= 0xFE
                    && (data[i + 1] & 0xFF) >= 0x30 && (data[i + 1] & 0xFF) <= 0x39
                    && (data[i + 2] & 0xFF) >= 0x81 && (data[i + 2] & 0xFF) <= 0xFE
                    && (data[i + 3] & 0xFF) >= 0x30 && (data[i + 3] & 0xFF) <= 0x39) {
                return true;
            }
        }
        return false;
    }

    private static int scoreLatin(byte[] data, int len) {
        int high = 0;
        int control = 0;
        for (int i = 0; i < len; i++) {
            int c = data[i] & 0xFF;
            if (c >= 0x80) {
                high++;
                if (c < 0xA0 && c != 0x80 && c != 0x82 && c != 0x8A && c != 0x8E
                        && c != 0x91 && c != 0x92 && c != 0x93 && c != 0x94 && c != 0x96
                        && c != 0x97 && c != 0x9A && c != 0x9E && c != 0x9F) {
                    control++;
                }
            }
        }
        if (high == 0) {
            return 0;
        }
        return high - control * 4;
    }

    private static boolean hasWindows1252(byte[] data, int len) {
        for (int i = 0; i < len; i++) {
            int c = data[i] & 0xFF;
            if (c == 0x80 || c == 0x82 || c == 0x8A || c == 0x8E || c == 0x91 || c == 0x92
                    || c == 0x93 || c == 0x94 || c == 0x96 || c == 0x97 || c == 0x9A
                    || c == 0x9E || c == 0x9F) {
                return true;
            }
        }
        return false;
    }

    private static Charset charsetOrNull(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean nameEquals(Charset charset, String expected) {
        if (charset == null || expected == null) {
            return false;
        }
        String a = charset.name().toLowerCase(Locale.ROOT).replace("_", "-");
        String b = expected.toLowerCase(Locale.ROOT).replace("_", "-");
        if (a.equals(b)) {
            return true;
        }
        for (String alias : charset.aliases()) {
            if (alias.toLowerCase(Locale.ROOT).replace("_", "-").equals(b)) {
                return true;
            }
        }
        return false;
    }
}
