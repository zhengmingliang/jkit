package com.alianga.jkit;

import com.alianga.jkit.collection.Collections;
import com.alianga.jkit.collection.Maps;
import com.alianga.jkit.collection.MultiKeyHashMap;
import com.alianga.jkit.convert.ConvertUtils;
import com.alianga.jkit.expression.Expression;
import com.alianga.jkit.jdk.UnsafeUtils;
import com.alianga.jkit.json.JSON;
import com.alianga.jkit.json.options.WriteOption;
import com.alianga.jkit.math.Number;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 针对补全 javadoc 过程中发现并修复的缺陷的回归测试。
 *
 * <p>每个用例都对应一个具体缺陷，用例名点明修复前的错误行为。</p>
 */
public class BugFixRegressionTest {

    /**
     * 修复前 {@code Assert.isTrue(expectedSize < 0, ...)} 断言方向写反，任何非负入参都抛异常。
     */
    @Test
    public void newHashMapWithExpectedSize_acceptsNonNegativeSize() {
        assertTrue(Maps.newHashMapWithExpectedSize(0).isEmpty());
        assertTrue(Maps.newHashMapWithExpectedSize(10).isEmpty());
        Map<String, String> map = Maps.newHashMapWithExpectedSize(64);
        map.put("k", "v");
        assertEquals("v", map.get("k"));
    }

    /**
     * 负数仍应被拒绝。
     */
    @Test(expected = IllegalArgumentException.class)
    public void newHashMapWithExpectedSize_rejectsNegativeSize() {
        Maps.newHashMapWithExpectedSize(-1);
    }

    /**
     * 修复前最后一个分支也返回 defaultValue，导致方法恒返回默认值。
     */
    @Test
    public void toNoneNullObject_returnsSourceWhenPresent() {
        assertEquals("abc", ConvertUtils.toNoneNullObject("abc", "def"));
        assertEquals(Integer.valueOf(3), ConvertUtils.toNoneNullObject(3, 9));
        // null 与字符串 "null" 仍走默认值
        assertEquals("def", ConvertUtils.toNoneNullObject(null, "def"));
        assertEquals("def", ConvertUtils.toNoneNullObject("null", "def"));
        assertEquals("def", ConvertUtils.toNoneNullObject("NULL", "def"));
    }

    /**
     * 修复前 wrapPrimMap 只在入参是基本类型时才查，拆箱恒不生效。
     */
    @Test
    public void getUnBoxedType_unwrapsWrapperTypes() {
        assertEquals(int.class, TypeUtils.getUnBoxedType(Integer.class));
        assertEquals(long.class, TypeUtils.getUnBoxedType(Long.class));
        assertEquals(boolean.class, TypeUtils.getUnBoxedType(Boolean.class));
        assertEquals(char.class, TypeUtils.getUnBoxedType(Character.class));
        // 已是基本类型或不是包装类型时原样返回
        assertEquals(int.class, TypeUtils.getUnBoxedType(int.class));
        assertEquals(String.class, TypeUtils.getUnBoxedType(String.class));
    }

    /**
     * 修复前 SortedSet 分支返回的是 {@code last()}，与方法名相反。
     */
    @Test
    public void getFirst_onSortedSetReturnsSmallest() {
        TreeSet<Integer> set = new TreeSet<Integer>();
        set.add(5);
        set.add(1);
        set.add(9);
        assertEquals(Integer.valueOf(1), Collections.getFirst(set));
        assertEquals(Integer.valueOf(1), Collections.getFirst(set, 0));
        // 非 SortedSet 仍取迭代器首个元素
        List<String> list = new ArrayList<String>();
        list.add("a");
        list.add("b");
        assertEquals("a", Collections.getFirst(list));
    }

    /**
     * 修复前 int 分支把 {@code field} 当成目标对象传给 putInt，值被写到 Field 对象上。
     */
    @Test
    public void unsafeUtilsSet_writesIntToTargetObject() throws Exception {
        Holder holder = new Holder();
        Field field = Holder.class.getDeclaredField("intValue");
        UnsafeUtils.set(holder, field, 42);
        assertEquals(42, holder.intValue);

        // 其余基本类型分支不受影响，一并校验
        UnsafeUtils.set(holder, Holder.class.getDeclaredField("longValue"), 7L);
        UnsafeUtils.set(holder, Holder.class.getDeclaredField("textValue"), "hi");
        assertEquals(7L, holder.longValue);
        assertEquals("hi", holder.textValue);
    }

    /**
     * 修复前 putValue 直接调 super.put，忽略大小写用的 keyMap 不会登记。
     */
    @Test
    public void multiKeyHashMapPutValue_registersIgnoreCaseIndex() {
        MultiKeyHashMap<String, String> map = new MultiKeyHashMap<String, String>();
        map.putValue("v1", "Content-Type", "contentType");
        assertEquals("v1", map.get("Content-Type"));
        assertEquals("v1", map.get("contentType"));
        // 忽略大小写查找
        assertEquals("v1", map.get("content-type"));
        assertEquals("v1", map.get("CONTENTTYPE"));
    }

    /**
     * 修复前缓存键带上了形参类型，实际却调 {@code getMethod(name)}，重载方法会取错。
     */
    @Test
    public void getDeclaredMethod_respectsParameterTypes() throws Exception {
        Method noArg = ReflectionUtils.getDeclaredMethod(Overloaded.class, "value");
        Method intArg = ReflectionUtils.getDeclaredMethod(Overloaded.class, "value", int.class);
        Method stringArg = ReflectionUtils.getDeclaredMethod(Overloaded.class, "value", String.class);

        assertEquals(0, noArg.getParameterCount());
        assertArrayEquals(new Class<?>[]{int.class}, intArg.getParameterTypes());
        assertArrayEquals(new Class<?>[]{String.class}, stringArg.getParameterTypes());

        Overloaded target = new Overloaded();
        assertEquals("none", noArg.invoke(target));
        assertEquals("int:3", intArg.invoke(target, 3));
        assertEquals("str:x", stringArg.invoke(target, "x"));
    }

    /**
     * 修复前内层 continue 只跳内层循环，exceptNames 没有任何排除效果，且返回的是带空洞的数组。
     */
    @Test
    public void getFieldsNames_excludesGivenNames() {
        String[] all = ReflectionUtils.getFieldsNames(Holder.class);
        assertEquals(3, all.length);

        String[] filtered = ReflectionUtils.getFieldsNames(Holder.class, "intValue", "longValue");
        assertArrayEquals(new String[]{"TextValue"}, filtered);
        for (String name : filtered) {
            assertNotNull(name);
        }
    }

    /**
     * 修复前占位符出现在字符串起始位置时 {@code charAt(-1)} 越界。
     */
    @Test
    public void replaceGroupRegex_handlesPlaceholderAtIndexZero() {
        Map<String, Object> context = new HashMap<String, Object>();
        context.put("name", "tom");
        context.put("age", 18);

        assertEquals("tom is 18", StringUtils.replaceGroupRegex("${name} is ${age}", context));
        assertEquals("hi tom", StringUtils.replaceGroupRegex("hi ${name}", context));
    }

    /**
     * 修复前 {@code FormatOutColonSpace} 缺 break，会穿透到 FormatIndentUseTab，
     * 把前面显式指定的空格缩进强行改回制表符。
     */
    @Test
    public void formatOutColonSpace_doesNotClobberIndentSetting() {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("id", 1);

        String json = JSON.toJsonString(data, WriteOption.FormatIndentUseSpace, WriteOption.FormatOutColonSpace);
        assertTrue("冒号后应补空格: " + json, json.contains(": "));
        assertFalse("显式指定的空格缩进不应被改成制表符: " + json, json.contains("\t"));

        // 显式要求制表符缩进时仍然是制表符
        String tabJson = JSON.toJsonString(data, WriteOption.FormatIndentUseTab, WriteOption.FormatOutColonSpace);
        assertTrue("应保持制表符缩进: " + tabJson, tabJson.contains("\t"));
    }

    /**
     * 修复前 SecureTrustedAccess 白名单登记的是迁移前的旧包名，
     * ElSecureTrustedAccess 构造即抛 UnsupportedOperationException，导致表达式无法用 JavaBean 作上下文。
     */
    @Test
    public void expression_supportsJavaBeanContext() {
        Bean bean = new Bean();
        assertEquals("tom", Expression.eval("name", bean));
        assertEquals(18, Expression.eval("age", bean));
        assertEquals(36L, Expression.eval("age * 2", bean));
    }

    /**
     * 修复前 String.format 多传了 throwable（被忽略），异常原因丢失。
     */
    @Test
    public void expression_unresolvedFieldKeepsCause() {
        try {
            Expression.eval("notExistField", new Bean());
            fail("应抛出 IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("notExistField"));
            assertNotNull("异常原因不应被丢弃", e.getCause());
        }
    }

    /**
     * 修复前 add(String...) 丢弃每次相加的结果，返回值恒等于当前值。
     */
    @Test
    public void numberAddVarargs_accumulates() {
        Number number = new Number("10");
        assertEquals(0, new BigDecimal("16").compareTo(number.add("1", "2", "3")));
        // 入参为空时数值上等于当前值
        assertEquals(0, new BigDecimal("10").compareTo(number.add()));
        assertEquals(0, new BigDecimal("10").compareTo(number.add((String[]) null)));
    }

    /**
     * 修复前 setScale 的返回值被丢弃，scale 不生效。
     */
    @Test
    public void numberScale_takesEffect() {
        assertEquals("1.20", new Number("1.2", 2).toPlainString());
        assertEquals("1.200", new Number("1.2").toScaleString(3));
        assertEquals("1.23", new Number("1.235").toScaleString(2, java.math.RoundingMode.DOWN));
        assertEquals("1.24", new Number("1.235").toScaleString(2, java.math.RoundingMode.UP));
    }

    /**
     * 修复前 NIO 版本把含首行的全部内容都过了一遍 apply，首行被回调两次。
     */
    @Test
    public void readLargeFileByLineNIO_doesNotRepeatFirstLine() throws Exception {
        File file = File.createTempFile("jkit-nio-", ".txt");
        try {
            FileUtils.writeFile(file.getAbsolutePath(), "h1\nl1\nl2\n", false);

            final List<String> firstLines = new ArrayList<String>();
            final List<String> bodyLines = new ArrayList<String>();
            FileUtils.readLargeFileByLineNIO(file.getAbsolutePath(), "UTF-8", new Callback() {
                @Override
                public void getFirstLine(String firstLine) {
                    firstLines.add(firstLine);
                }

                @Override
                public void apply(String data) {
                    bodyLines.add(data);
                }
            });

            assertEquals(java.util.Arrays.asList("h1"), firstLines);
            assertEquals(java.util.Arrays.asList("l1", "l2"), bodyLines);
        } finally {
            file.delete();
        }
    }

    /**
     * 修复前配置项不存在时 {@code Boolean.valueOf(null)} 返回 false，defaultValue 分支永远不可达。
     */
    @Test
    public void propertiesUtilGetBoolean_usesDefaultWhenMissing() {
        assertTrue(PropertiesUtil.getBoolean("jkit.absolutely.missing.key", true));
        assertFalse(PropertiesUtil.getBoolean("jkit.absolutely.missing.key", false));
    }

    /**
     * 修复前末尾 writer 既没 flush 也没 close，不足 maxLine 的尾部数据留在缓冲区里被丢弃，
     * 最后一个拆分文件是 0 字节空文件（25 行按 10 行拆分只剩 20 行）。
     */
    @Test
    public void cutFileByLine_doesNotLoseTailLines() throws Exception {
        File source = File.createTempFile("jkit-cut-", ".txt");
        File targetDir = java.nio.file.Files.createTempDirectory("jkit-cut-out-").toFile();
        try {
            StringBuilder content = new StringBuilder();
            for (int i = 1; i <= 25; i++) {
                content.append("line").append(i).append('\n');
            }
            FileUtils.writeFile(source.getAbsolutePath(), content.toString(), false);

            FileUtils.cutFileByLine(source.getAbsolutePath(), "UTF-8", targetDir.getAbsolutePath(), 10);

            File[] parts = targetDir.listFiles();
            assertNotNull(parts);
            // 25 行按每 10 行拆分应得到 3 个文件：10 + 10 + 5
            assertEquals(3, parts.length);
            java.util.Arrays.sort(parts, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            assertEquals(10, java.nio.file.Files.readAllLines(parts[0].toPath()).size());
            assertEquals(10, java.nio.file.Files.readAllLines(parts[1].toPath()).size());
            assertEquals("尾部不足 maxLine 的数据不能丢", 5,
                    java.nio.file.Files.readAllLines(parts[2].toPath()).size());

            // 逐行内容也应与源文件完全一致
            List<String> merged = new ArrayList<String>();
            for (File part : parts) {
                merged.addAll(java.nio.file.Files.readAllLines(part.toPath()));
            }
            assertEquals(25, merged.size());
            assertEquals("line1", merged.get(0));
            assertEquals("line25", merged.get(24));
        } finally {
            source.delete();
            FileUtils.deleteDir(targetDir);
        }
    }

    /**
     * maxLine 不大于 0 时每行都会独占一个文件，属明显误用，应直接拒绝。
     */
    @Test(expected = IllegalArgumentException.class)
    public void cutFileByLine_rejectsNonPositiveMaxLine() {
        FileUtils.cutFileByLine("whatever.txt", "UTF-8", "whatever", 0);
    }

    /**
     * 修复前源文件不存在只是 printStackTrace，方法静默返回，调用方无法区分「拆分成功」与「什么都没做」。
     */
    @Test
    public void cutFileByLine_surfacesIoFailureInsteadOfSwallowing() throws Exception {
        File targetDir = java.nio.file.Files.createTempDirectory("jkit-cut-fail-").toFile();
        try {
            FileUtils.cutFileByLine(new File(targetDir, "not-exist.txt").getAbsolutePath(),
                    "UTF-8", targetDir.getAbsolutePath(), 10);
            fail("源文件不存在时应抛出 UncheckedIOException，而不是静默返回");
        } catch (java.io.UncheckedIOException e) {
            assertNotNull("异常原因不应被丢弃", e.getCause());
        } finally {
            FileUtils.deleteDir(targetDir);
        }
    }

    /**
     * 修复前 subtract(BigDecimal, BigDecimal...) 返回 math.Number 且内部强转，
     * 传入普通 BigDecimal 时必抛 ClassCastException；且与返回 BigDecimal 的 add 不对称。
     */
    @Test
    public void numbersSubtractBigDecimal_returnsBigDecimalWithoutClassCast() {
        // 修复前这一行直接抛 ClassCastException
        BigDecimal tail = com.alianga.jkit.math.Numbers.subtract(new BigDecimal("5"), (BigDecimal[]) null);
        assertEquals(0, new BigDecimal("5").compareTo(tail));

        BigDecimal result = com.alianga.jkit.math.Numbers.subtract(
                new BigDecimal("10"), new BigDecimal("3"), new BigDecimal("2"));
        assertEquals(0, new BigDecimal("5").compareTo(result));

        // null 被减数按 ZERO 处理
        assertEquals(0, BigDecimal.ZERO.compareTo(
                com.alianga.jkit.math.Numbers.subtract(null, (BigDecimal[]) null)));

        // 与 add 的返回类型保持一致
        assertEquals(com.alianga.jkit.math.Numbers.add(new BigDecimal("5"), (BigDecimal[]) null).getClass(),
                tail.getClass());
    }

    /**
     * 修复前 Integer/Long 族不跳过 null 元素，与 BigDecimal 族行为不一致且会抛 NPE。
     */
    @Test
    public void numbersAddAndSubtract_skipNullElements() {
        assertEquals(Integer.valueOf(3),
                com.alianga.jkit.math.Numbers.add(Integer.valueOf(1), null, Integer.valueOf(2)));
        assertEquals(Integer.valueOf(7),
                com.alianga.jkit.math.Numbers.subtract(Integer.valueOf(10), null, Integer.valueOf(3)));
        assertEquals(Long.valueOf(3L),
                com.alianga.jkit.math.Numbers.add(Long.valueOf(1L), null, Long.valueOf(2L)));
        assertEquals(Long.valueOf(7L),
                com.alianga.jkit.math.Numbers.subtract(Long.valueOf(10L), null, Long.valueOf(3L)));
        // BigDecimal 族本来就跳过 null，这里锁定三族行为一致
        assertEquals(0, new BigDecimal("6").compareTo(
                com.alianga.jkit.math.Numbers.subtract(new BigDecimal("10"), null, new BigDecimal("4"))));
    }

    /**
     * 修复前 targets 中出现 null 元素时直接 target.toString() 抛 NPE。
     */
    @Test
    public void orEqualsIgnoreCase_skipsNullTargets() {
        assertTrue(DataUtils.orEqualsIgnoreCase("a", null, "A"));
        assertFalse(DataUtils.orEqualsIgnoreCase("a", null, "b"));
        assertFalse(DataUtils.orEqualsIgnoreCase("a", (Object) null));
        // 原有约定不变
        assertFalse(DataUtils.orEqualsIgnoreCase(null, "a"));
        assertFalse(DataUtils.orEqualsIgnoreCase("a", (Object[]) null));
    }

    /**
     * 修复前 encodeString(null) 直接 NPE，与同类其他方法的空值容错风格不一致。
     */
    @Test
    public void base64EncodeString_toleratesNull() {
        assertNull(Base64Utils.encodeString(null));
        assertEquals("", Base64Utils.encodeString(""));
        assertEquals("5Lit5paH", Base64Utils.encodeString("中文"));
    }

    /**
     * 修复前 getSize 没有 TB 档，负数会落到 B 档输出形如 "-2048B"，
     * 且小数点受运行环境默认区域设置影响。
     */
    @Test
    public void getSize_coversTerabyteAndNegativeValues() {
        long kb = 1024L;
        long mb = kb * 1024;
        long gb = mb * 1024;
        long tb = gb * 1024;

        assertEquals("512B", FileUtils.getSize(512));
        assertEquals("1.0KB", FileUtils.getSize(kb));
        assertEquals("1.00MB", FileUtils.getSize(mb));
        assertEquals("1.00GB", FileUtils.getSize(gb));
        assertEquals("1.00TB", FileUtils.getSize(tb));
        assertEquals("2.00TB", FileUtils.getSize(2 * tb));

        // 负数按绝对值分档并保留负号
        assertEquals("-512B", FileUtils.getSize(-512));
        assertEquals("-1.0KB", FileUtils.getSize(-kb));
        assertEquals("-1.00GB", FileUtils.getSize(-gb));

        // 小数点固定为 '.'，不受默认区域设置影响
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            assertEquals("1.5KB", FileUtils.getSize(1536));
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    /**
     * 修复前默认线程池是非 volatile 的无锁懒加载，并且只能 execute 拿不到执行结果。
     */
    @Test
    public void executorServiceUtil_submitReturnsFuture() throws Exception {
        java.util.concurrent.Future<?> runnableFuture =
                com.alianga.jkit.thread.ExecutorServiceUtil.submit(() -> { });
        assertNull("Runnable 正常结束时结果为 null", runnableFuture.get(5, TimeUnit.SECONDS));

        java.util.concurrent.Future<String> callableFuture =
                com.alianga.jkit.thread.ExecutorServiceUtil.submit(() -> "done");
        assertEquals("done", callableFuture.get(5, TimeUnit.SECONDS));

        assertNotNull(com.alianga.jkit.thread.ExecutorServiceUtil.getDefaultThreadFactory());
    }

    /**
     * 修复前 sleep 被中断后只返回 false，把中断位吞掉了，调用方无从得知已被请求中断。
     */
    @Test
    public void executorServiceUtilSleep_restoresInterruptFlag() throws Exception {
        final boolean[] returned = {true};
        final boolean[] stillInterrupted = {false};
        Thread worker = new Thread(() -> {
            returned[0] = com.alianga.jkit.thread.ExecutorServiceUtil.sleep(5000);
            stillInterrupted[0] = Thread.currentThread().isInterrupted();
        });
        worker.start();
        // 等线程真正进入 sleep 再中断
        Thread.sleep(200);
        worker.interrupt();
        worker.join(5000);

        assertFalse("被中断时应返回 false", returned[0]);
        assertTrue("中断位必须被恢复", stillInterrupted[0]);
    }

    /**
     * 用于反射与 Unsafe 写值的样本类，字段顺序即 getFieldsNames 的返回顺序。
     */
    static class Holder {
        int intValue;
        long longValue;
        String textValue;
    }

    /**
     * 用于校验重载方法查找的样本类。
     */
    public static class Overloaded {
        /**
         * 无参重载。
         *
         * @return 固定返回 {@code "none"}
         */
        public String value() {
            return "none";
        }

        /**
         * int 形参重载。
         *
         * @param v 入参
         * @return {@code "int:" + v}
         */
        public String value(int v) {
            return "int:" + v;
        }

        /**
         * String 形参重载。
         *
         * @param v 入参
         * @return {@code "str:" + v}
         */
        public String value(String v) {
            return "str:" + v;
        }
    }

    /**
     * 表达式求值用的 JavaBean 样本。
     */
    public static class Bean {
        private String name = "tom";
        private int age = 18;

        /**
         * @return 姓名
         */
        public String getName() {
            return name;
        }

        /**
         * @return 年龄
         */
        public int getAge() {
            return age;
        }
    }
}
