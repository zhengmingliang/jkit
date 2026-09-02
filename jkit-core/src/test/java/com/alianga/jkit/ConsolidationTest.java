package com.alianga.jkit;

import com.alianga.jkit.beans.ObjectUtils;
import com.alianga.jkit.io.ByteUtils;
import com.alianga.jkit.io.FileType;
import com.alianga.jkit.thread.ExecutorServiceUtil;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 工具类合并/去重后的行为一致性测试。
 *
 * <p>本类的每个用例都对应一处「多份实现合并为一份」的改动，断言的是<b>合并后与合并前行为等价</b>，
 * 因此金标准取自 JDK 原生实现或改动前写死的期望值，而不是取自被测代码自身。
 */
public class ConsolidationTest {

    // ==================== base64：EncryptUtils 转调 Base64Utils ====================

    /**
     * EncryptUtils 的 base64 由 java.util.Base64 换成 Base64Utils 后，输出必须逐字节一致。
     * 金标准用 JDK 的 Base64。
     */
    @Test
    public void base64Encode_matchesJdk() {
        String[] samples = {"", "a", "ab", "abc", "abcd", "hello world",
                "中文测试内容", "特殊字符 +/=\n\t", "\u0000\u0001\u007f"};
        for (String s : samples) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            assertEquals("encode(String) 与 JDK 不一致: " + s,
                    Base64.getEncoder().encodeToString(utf8), EncryptUtils.base64Encode(s));
            assertArrayEquals("encode(byte[]) 与 JDK 不一致: " + s,
                    Base64.getEncoder().encode(utf8), EncryptUtils.base64Encode(utf8));
        }
    }

    @Test
    public void base64Decode_matchesJdk() {
        String[] samples = {"", "a", "abc", "hello world", "中文测试内容"};
        for (String s : samples) {
            String encoded = Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
            assertEquals("decode 与 JDK 不一致: " + s, s, EncryptUtils.base64Decode(encoded));
        }
    }

    @Test
    public void base64_nullHandlingUnchanged() {
        assertNull(EncryptUtils.base64Encode((String) null));
        assertNull(EncryptUtils.base64Encode((File) null));
        assertNull(EncryptUtils.base64Decode(null));
    }

    /**
     * Base64Utils.encodeString/decodeString 原来用平台默认编码，改为显式 UTF-8。
     * 断言中文可以正确往返，并且与 JDK + UTF-8 的结果一致。
     */
    @Test
    public void base64Coder_stringApiUsesUtf8() {
        String text = "中文 UTF-8 往返测试";
        String encoded = Base64Utils.encodeString(text);
        assertEquals(Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)), encoded);
        assertEquals(text, Base64Utils.decodeString(encoded));
    }

    /**
     * 原 Base64Utils.encodeFileWithPrefix 对任意文件都硬编码 data:image/png。
     * 改为按文件实际 MIME 类型，且必须与 EncryptUtils 的同名能力一致。
     */
    @Test
    public void encodeFileWithPrefix_usesRealContentType() throws IOException {
        File txt = File.createTempFile("jkit-consolidation-", ".txt");
        try {
            Files.write(txt.toPath(), "hello jkit".getBytes(StandardCharsets.UTF_8));

            String prefixed = Base64Utils.encodeFileWithPrefix(txt);
            String contentType = FileUtils.getContentType(txt);

            assertNotNull("getContentType 不允许返回 null", contentType);
            assertTrue("前缀应使用文件实际 MIME 类型，实际为: " + prefixed,
                    prefixed.startsWith("data:" + contentType + ";base64,"));
            assertFalse("不应再硬编码 image/png: " + prefixed, prefixed.startsWith("data:image/png;"));

            // 两个入口应给出同样的结果
            assertEquals(EncryptUtils.base64EncodeWithPrefix(txt), prefixed);
            // 去掉前缀后应能解回原文
            String body = prefixed.substring(prefixed.indexOf(",") + 1);
            assertEquals("hello jkit", new String(Base64Utils.decode(body), StandardCharsets.UTF_8));
        } finally {
            assertTrue(txt.delete() || !txt.exists());
        }
    }

    // ==================== hex：三份实现合并到 ByteUtils ====================

    /**
     * 合并后必须保持大小写差异：摘要与文件头识别依赖小写，ByteUtils.toHexString 历史上是大写。
     */
    @Test
    public void hex_caseSemanticsPreserved() {
        byte[] data = {(byte) 0x00, (byte) 0x0f, (byte) 0x10, (byte) 0xab, (byte) 0xff};
        assertEquals("000f10abff", ByteUtils.toHexStringLower(data));
        assertEquals("000F10ABFF", ByteUtils.toHexString(data));
        assertEquals("00-0F-10-AB-FF-", ByteUtils.toHexString(data, '-'));
    }

    @Test
    public void hex_zeroPaddingForAllByteValues() {
        for (int i = 0; i < 256; i++) {
            byte[] one = {(byte) i};
            String lower = ByteUtils.toHexStringLower(one);
            assertEquals("每字节必须固定两位: " + i, 2, lower.length());
            assertEquals("值不正确: " + i, String.format("%02x", i), lower);
            assertEquals("大写版本不正确: " + i, String.format("%02X", i), ByteUtils.toHexString(one));
        }
    }

    /**
     * 两个 bytesToHexString 入口原本对 null/空数组返回 null，合并后必须保持。
     *
     * <p>另外这里顺带守住一个曾经的严重 bug：{@code FileType} 的静态初始化会去加载
     * 项目里并不存在的 {@code /filetype.properties}，{@code Properties.load(null)} 抛的是 NPE
     * 而不是 IOException，于是静态初始化失败、整个 {@code FileType} 类因
     * {@code ExceptionInInitializerError} 永久不可用（连带 base64 data-URI 解码也一起崩）。
     */
    @Test
    public void hex_nullAndEmptySemanticsPreserved() {
        assertNull(ByteUtils.toHexStringLower(null));
        assertEquals("", ByteUtils.toHexStringLower(new byte[0]));

        assertNull(EncryptUtils.bytesToHexString(null));
        assertNull(EncryptUtils.bytesToHexString(new byte[0]));
        assertNull(FileType.bytesToHexString(null));
        assertNull(FileType.bytesToHexString(new byte[0]));

        byte[] data = {(byte) 0x01, (byte) 0xa0};
        assertEquals("01a0", EncryptUtils.bytesToHexString(data));
        assertEquals("01a0", FileType.bytesToHexString(data));
    }

    /**
     * MIME 映射资源缺失时，FileType 必须能正常初始化并降级，而不是让整个类不可用。
     */
    @Test
    public void fileType_initializesWithoutOptionalMimeResources() {
        // 触发静态初始化：资源缺失也不应抛 ExceptionInInitializerError
        assertFalse("文件头映射表应已初始化", FileType.FILE_TYPE_MAP.isEmpty());
        assertEquals("png", FileType.FILE_TYPE_MAP.get("89504e470d0a1a0a0000"));
        // 映射表缺失时查询降级为空串，不抛异常
        assertNotNull(FileType.getSuffixByMimeType("image/png"));
        assertNotNull(FileType.getMimeTypeBySuffix("png"));
    }

    /**
     * FileType 不可用会导致 data-URI 形式的 base64 解码整体失败，这里做端到端验证。
     */
    @Test
    public void base64DecodeFile_handlesDataUriPrefix() throws IOException {
        File dir = Files.createTempDirectory("jkit-consolidation-").toFile();
        try {
            byte[] payload = "data uri payload".getBytes(StandardCharsets.UTF_8);
            String dataUri = "data:text/plain;base64," + Base64Utils.encodeToString(payload);

            File decoded = EncryptUtils.base64DecodeFile(dataUri, dir.getAbsolutePath());

            assertNotNull(decoded);
            assertTrue("解码后的文件应存在: " + decoded, decoded.isFile());
            assertArrayEquals(payload, Files.readAllBytes(decoded.toPath()));
        } finally {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    child.delete();
                }
            }
            dir.delete();
        }
    }

    /**
     * hex 合并最大的风险是改掉摘要输出，用写死的标准 MD5/SHA 值兜底。
     */
    @Test
    public void hex_digestOutputUnchanged() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", EncryptUtils.md5("hello"));
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", EncryptUtils.md5(""));
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", EncryptUtils.sha1("hello"));
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                EncryptUtils.sha256("hello"));
    }

    /**
     * HMAC 走的是原 byte2Hex（已删除），换成 ByteUtils 后要与 JDK Mac 的结果一致。
     */
    @Test
    public void hex_hmacMatchesJdk() throws Exception {
        String message = "hello jkit";
        String secret = "s3cr3t";
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(), "HmacSHA256"));
        byte[] expected = mac.doFinal(message.getBytes());

        assertEquals(ByteUtils.toHexStringLower(expected), EncryptUtils.sha256_HMAC(message, secret));
    }

    @Test
    public void hex_roundTripWithHexString2Bytes() {
        byte[] data = "任意二进制内容 binary".getBytes(StandardCharsets.UTF_8);
        // hexString2Bytes 大小写都要能还原
        assertArrayEquals(data, ByteUtils.hexString2Bytes(ByteUtils.toHexString(data)));
        assertArrayEquals(data, ByteUtils.hexString2Bytes(ByteUtils.toHexStringLower(data)));
    }

    @Test
    public void hex_matchesMessageDigestHexOfBytes() throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest("jkit".getBytes(StandardCharsets.UTF_8));
        assertEquals(EncryptUtils.bytesToHexString(digest), ByteUtils.toHexStringLower(digest));
    }

    // ==================== cleanPath：两份实现合并为一份 ====================

    /**
     * StringUtils 与 FileUtils 两个入口必须给出完全相同的结果（同一实现）。
     */
    @Test
    public void cleanPath_bothEntriesAgree() {
        String[] inputs = {null, "", "/", ".", "..", "a", "a/b/c",
                "a/../b/./c", "a/..", "./a", "a/./b", "a//b",
                "/a/../b", "../a", "../../a", "a/b/../../c",
                "file:core/../core/io/Resource.class",
                "classpath:/a/../b", "C:\\dir\\..\\file.txt", "\\\\server\\share\\x\\..\\y",
                "a/b/", "/a/b/", "http://host/a/../b"};
        for (String in : inputs) {
            assertEquals("两个入口结果应一致: " + in, StringUtils.cleanPath(in), FileUtils.cleanPath(in));
        }
    }

    /**
     * 合并采用了较新的实现，这里锁定改动前后都成立的关键行为，防止回退。
     */
    @Test
    public void cleanPath_knownResults() {
        assertNull(StringUtils.cleanPath(null));
        assertEquals("", StringUtils.cleanPath(""));
        assertEquals("b/c", StringUtils.cleanPath("a/../b/./c"));
        assertEquals("a/b", StringUtils.cleanPath("a/./b"));
        assertEquals("c", StringUtils.cleanPath("a/b/../../c"));
        assertEquals("../a", StringUtils.cleanPath("../a"));
        assertEquals("a/b/c", StringUtils.cleanPath("a/b/c"));
        // Windows 分隔符统一成 /
        assertEquals("C:/file.txt", StringUtils.cleanPath("C:\\dir\\..\\file.txt"));
        // 前缀要保留，".." 只吃掉前缀之后的片段
        assertEquals("file:core/io/Resource.class",
                StringUtils.cleanPath("file:core/../core/io/Resource.class"));
    }

    @Test
    public void pathEquals_usesNormalizedComparison() {
        assertTrue(StringUtils.pathEquals("a/./b", "a/b"));
        assertTrue(StringUtils.pathEquals("a/b/../b", "a/b"));
        assertTrue(StringUtils.pathEquals("C:\\a\\b", "C:/a/b"));
        assertFalse(StringUtils.pathEquals("a/b", "a/c"));
    }

    // ==================== getObjectFieldValue 消歧义 ====================

    /**
     * 这是本次改动要暴露的坑：两个类的同名同签名方法查找规则不同。
     *
     * <p>注意 {@code ObjectUtils.getPropertyValue} 默认<b>不会</b>执行 getter 方法体——
     * 只要存在类型兼容的同名字段，它就用 Unsafe 直读字段。这里用一个「getter 会加工返回值」的
     * bean 把这个容易踩坑的行为钉死，避免以后有人误以为它会走 getter。
     */
    @Test
    public void propertyAccess_bypassesGetterBodyByDefault() {
        DivergentBean bean = new DivergentBean("raw");

        // 直读字段
        assertEquals("raw", ReflectionUtils.getDeclaredFieldValue(bean, "name"));
        // 按属性名查找，但默认同样直读字段，getter 里的 toUpperCase()+"!" 被跳过
        assertEquals("默认不应执行 getter 方法体", "raw", ObjectUtils.getPropertyValue(bean, "name"));
    }

    /**
     * 计算属性（只有 getter、没有同名字段）是两者真正的分界线：
     * 按属性名能读到计算结果，按字段名则读不到。
     */
    @Test
    public void propertyAccess_readsComputedPropertyWithoutField() {
        DivergentBean bean = new DivergentBean("raw");

        // upper 是纯计算属性，没有 upper 字段
        assertEquals("RAW!", ObjectUtils.getPropertyValue(bean, "upper"));
        assertNull("没有 upper 字段，按字段名读不到", ReflectionUtils.getDeclaredFieldValue(bean, "upper"));
    }

    /**
     * 两者的另一处差异：没有 getter 的私有字段只能用 getDeclaredFieldValue 读到。
     */
    @Test
    public void fieldLookup_doesNotRequireGetter() {
        NoGetterBean bean = new NoGetterBean();
        assertEquals("hidden-value", ReflectionUtils.getDeclaredFieldValue(bean, "hidden"));
    }

    /**
     * 保留的 @Deprecated 旧名字必须与新名字行为完全一致，保证兼容。
     */
    @Test
    @SuppressWarnings("deprecation")
    public void deprecatedAliases_delegateIdentically() {
        DivergentBean bean = new DivergentBean("raw");
        assertEquals(ReflectionUtils.getDeclaredFieldValue(bean, "name"),
                ReflectionUtils.getObjectFieldValue(bean, "name"));
        assertEquals(ObjectUtils.getPropertyValue(bean, "name"),
                ObjectUtils.getObjectFieldValue(bean, "name"));
    }

    @Test
    public void fieldAccess_walksSuperclassAndMissingFieldIsNull() {
        ChildBean child = new ChildBean();
        assertEquals("parent-value", ReflectionUtils.getDeclaredFieldValue(child, "parentField"));
        assertEquals("child-value", ReflectionUtils.getDeclaredFieldValue(child, "childField"));
        assertNull(ReflectionUtils.getDeclaredFieldValue(child, "noSuchField"));
    }

    public static class DivergentBean {
        private String name;

        public DivergentBean() {
        }

        public DivergentBean(String name) {
            this.name = name;
        }

        /** 有同名字段的 getter：方法体不会被执行。 */
        public String getName() {
            return name == null ? null : name.toUpperCase() + "!";
        }

        public void setName(String name) {
            this.name = name;
        }

        /** 计算属性：没有 upper 字段，只能靠调用方法拿到值。 */
        public String getUpper() {
            return name == null ? null : name.toUpperCase() + "!";
        }
    }

    public static class NoGetterBean {
        private String hidden = "hidden-value";
    }

    public static class ParentBean {
        private String parentField = "parent-value";

        public String getParentField() {
            return parentField;
        }
    }

    public static class ChildBean extends ParentBean {
        private String childField = "child-value";

        public String getChildField() {
            return childField;
        }
    }

    // ==================== Content-Disposition 解析合并 ====================

    @Test
    public void parseFileName_handlesPlainAndRfc5987() {
        assertEquals("a.txt", HttpUtils.parseFileName("attachment; filename=a.txt", null));
        assertEquals("a b.txt", HttpUtils.parseFileName("attachment; filename=\"a b.txt\"", null));
        assertEquals("中文.txt",
                HttpUtils.parseFileName("attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87.txt", null));
        assertEquals("inline 也要能解析",
                "x.bin", HttpUtils.parseFileName("inline; filename=x.bin", null));
    }

    @Test
    public void parseFileName_fallsBackToDefault() {
        assertEquals("fallback.bin", HttpUtils.parseFileName(null, "fallback.bin"));
        assertEquals("fallback.bin", HttpUtils.parseFileName("attachment", "fallback.bin"));
        // defaultName 为 null 时归一为空串，不能抛 NPE
        assertEquals("", HttpUtils.parseFileName(null, null));
        assertEquals("", HttpUtils.parseFileName("attachment", null));
    }

    /**
     * FileUtils 的响应头入口现在共用 HttpUtils 的解析实现，
     * 并且要能容忍服务端返回的响应头名大小写不固定。
     */
    @Test
    public void getFileNameFromHttp_isHeaderNameCaseInsensitive() {
        Map<String, List<String>> lower = new HashMap<String, List<String>>();
        lower.put("content-disposition", Arrays.asList("attachment; filename=\"report.pdf\""));
        assertEquals("report.pdf", FileUtils.getFileNameFromHttp(lower));

        Map<String, List<String>> canonical = new LinkedHashMap<String, List<String>>();
        canonical.put("Content-Disposition", Arrays.asList("attachment; filename=report.pdf"));
        assertEquals("report.pdf", FileUtils.getFileNameFromHttp(canonical));

        Map<String, List<String>> upper = new HashMap<String, List<String>>();
        upper.put("CONTENT-DISPOSITION", Arrays.asList("attachment; filename=report.pdf"));
        assertEquals("report.pdf", FileUtils.getFileNameFromHttp(upper));
    }

    @Test
    public void getFileNameFromHttp_nullWhenAbsent() {
        assertNull(FileUtils.getFileNameFromHttp((Map<String, List<String>>) null));
        assertNull(FileUtils.getFileNameFromHttp(new HashMap<String, List<String>>()));

        Map<String, List<String>> noName = new HashMap<String, List<String>>();
        noName.put("Content-Disposition", Arrays.asList("attachment"));
        assertNull(FileUtils.getFileNameFromHttp(noName));

        Map<String, List<String>> emptyValues = new HashMap<String, List<String>>();
        emptyValues.put("Content-Disposition", new ArrayList<String>());
        assertNull(FileUtils.getFileNameFromHttp(emptyValues));
    }

    // ==================== 匿名类改 lambda 后的行为验证 ====================

    /**
     * ExecutorServiceUtil 的 ThreadFactory 由带实例字段的匿名类改成 lambda + 静态计数器，
     * 线程命名与 daemon 属性必须保持不变，且计数器要继续递增。
     */
    @Test
    public void threadFactory_keepsNamingAndDaemonAfterLambdaRewrite() throws Exception {
        final AtomicReference<String> name1 = new AtomicReference<String>();
        final AtomicReference<String> name2 = new AtomicReference<String>();
        final AtomicReference<Boolean> daemon = new AtomicReference<Boolean>();

        Thread t1 = ExecutorServiceUtil.getDefaultThreadFactory().newThread(() -> {
            name1.set(Thread.currentThread().getName());
            daemon.set(Thread.currentThread().isDaemon());
        });
        Thread t2 = ExecutorServiceUtil.getDefaultThreadFactory().newThread(() ->
                name2.set(Thread.currentThread().getName()));
        t1.start();
        t2.start();
        t1.join(5000);
        t2.join(5000);

        assertTrue("线程名前缀应为 alianga-，实际为: " + name1.get(), name1.get().startsWith("alianga-"));
        assertTrue("线程名前缀应为 alianga-，实际为: " + name2.get(), name2.get().startsWith("alianga-"));
        assertTrue("线程应为 daemon", daemon.get());

        int seq1 = Integer.parseInt(name1.get().substring("alianga-".length()));
        int seq2 = Integer.parseInt(name2.get().substring("alianga-".length()));
        assertTrue("计数器应递增: " + seq1 + " -> " + seq2, seq2 > seq1);
    }

    @Test
    public void executorService_stillRunsTasksAfterLambdaRewrite() throws Exception {
        ExecutorService pool = ExecutorServiceUtil.newSingleExecutorService();
        try {
            assertEquals("ok", pool.submit(() -> "ok").get(5, TimeUnit.SECONDS));
        } finally {
            ExecutorServiceUtil.shutdown(pool);
        }
    }

    /**
     * ClassStrucWrap 的 getter 排序原来用匿名 Comparator，改 lambda 后排序结果必须不变（按名称升序）。
     */
    @Test
    public void classStrucWrap_getterOrderStillSortedByName() {
        List<String> names = new ArrayList<String>();
        for (com.alianga.jkit.reflect.GetterInfo info
                : com.alianga.jkit.reflect.ClassStrucWrap.get(ChildBean.class).getGetterInfos()) {
            names.add(info.getName());
        }
        List<String> sorted = new ArrayList<String>(names);
        java.util.Collections.sort(sorted);
        assertEquals("getter 应按名称升序", sorted, names);
    }
}
