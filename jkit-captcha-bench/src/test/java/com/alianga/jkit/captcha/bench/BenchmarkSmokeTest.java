package com.alianga.jkit.captcha.bench;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成器契约。全量 10 万张由 {@link BenchMain} 跑，这里用固定种子的小样本卡住标签规则和风格轴。
 */
public class BenchmarkSmokeTest {
    private static final String[] AXES = {"f", "r", "n", "l", "t", "g", "s"};

    @Test
    public void labelsStylesAndManifestFollowTheContract() throws Exception {
        File output = new File(System.getProperty("bench.output", "target/captcha-dataset"), "contract");
        int perCategory = 8;
        long seed = 20261101L;
        List<Sample> samples = DatasetGenerator.generate(output, perCategory, seed, null);
        Assert.assertEquals(perCategory * Sample.Category.values().length, samples.size());
        assertContract(samples);
        List<Sample> reloaded = DatasetGenerator.load(output);
        Assert.assertEquals(samples.size(), reloaded.size());
        for (int i = 0; i < reloaded.size(); i++) {
            Sample original = samples.get(i);
            Sample loaded = reloaded.get(i);
            Assert.assertEquals(original.getExpected(), loaded.getExpected());
            Assert.assertEquals(original.getCategory(), loaded.getCategory());
            Assert.assertEquals(original.getStyleTag(), loaded.getStyleTag());
            Assert.assertEquals(original.getValue(), loaded.getValue());
            Assert.assertEquals(original.getFile().getName(), loaded.getFile().getName());
            Assert.assertTrue(loaded.getFile().isFile());
            Assert.assertTrue(loaded.getFile().length() > 32L);
        }
    }

    @Test
    public void evaluateFollowsIntegerRules() {
        Assert.assertEquals(Integer.valueOf(14), TextGen.evaluate("2+3*4"));
        Assert.assertEquals(Integer.valueOf(2), TextGen.evaluate("8/3"));
        Assert.assertEquals(Integer.valueOf(2), TextGen.evaluate("8-3*2"));
        Assert.assertNull(TextGen.evaluate("8/0"));
        Assert.assertNull(TextGen.evaluate("1-2"));
        Assert.assertNull(TextGen.evaluate("3"));
    }

    @Test
    public void runnerJudgesSmallSample() throws Exception {
        File output = new File(System.getProperty("bench.output", "target/captcha-dataset"), "smoke-run");
        List<Sample> samples = DatasetGenerator.generate(output, 1, 5L, null);
        BenchmarkRunner runner = new BenchmarkRunner(2, BenchmarkRunner.LOW_CONFIDENCE);
        BenchmarkRunner.Outcome outcome = runner.run(samples, -1, null);
        Assert.assertEquals(samples.size(), outcome.getTotal());
        Assert.assertEquals(Sample.Category.values().length, outcome.getCategories().size());
        int bucketTotal = 0;
        for (int i = 0; i < outcome.getCategories().size(); i++) {
            bucketTotal += outcome.getCategories().get(i).getTotal();
        }
        Assert.assertEquals(samples.size(), bucketTotal);
        Assert.assertEquals(outcome.getWrong().size() + outcome.getFailures().size()
                + countCorrect(outcome), outcome.getTotal());
    }

    /**
     * 对一份已经生成的清单再核一遍标签和风格轴。
     * 未设置 {@code bench.audit} 时用固定种子的小样本，设置后改为那份清单。
     */
    @Test
    public void auditManifestWhenPresent() throws Exception {
        String path = System.getProperty("bench.audit", "");
        List<Sample> samples;
        if (path.isEmpty()) {
            File output = new File(System.getProperty("bench.output", "target/captcha-dataset"), "audit-small");
            samples = DatasetGenerator.generate(output, 8, 20261101L, null);
        } else {
            samples = DatasetGenerator.load(new File(path));
        }
        assertContract(samples);
        if (!path.isEmpty()) {
            Assert.assertEquals(100000, samples.size());
            Map<Sample.Category, Integer> counts = new HashMap<Sample.Category, Integer>();
            int slashes = 0;
            for (int i = 0; i < samples.size(); i++) {
                Sample sample = samples.get(i);
                Integer count = counts.get(sample.getCategory());
                counts.put(sample.getCategory(), Integer.valueOf((count == null ? 0 : count.intValue()) + 1));
                if (sample.getCategory() == Sample.Category.ARITHMETIC
                        && sample.getExpected().indexOf('/') >= 0) {
                    slashes++;
                }
            }
            for (int i = 0; i < Sample.Category.values().length; i++) {
                Assert.assertEquals(Integer.valueOf(25000), counts.get(Sample.Category.values()[i]));
            }
            Assert.assertTrue("除法应超过 1000 行，实际 " + slashes, slashes > 1000);
            assertOffModal(samples, 1000);
        }
    }

    private static void assertContract(List<Sample> samples) {
        boolean upper = false;
        boolean lower = false;
        Set<Character> operators = new HashSet<Character>();
        Map<String, Set<String>> axes = new HashMap<String, Set<String>>();
        for (int i = 0; i < AXES.length; i++) {
            axes.put(AXES[i], new HashSet<String>());
        }
        int[] perCategory = new int[Sample.Category.values().length];
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            perCategory[sample.getCategory().ordinal()]++;
            String expected = sample.getExpected();
            Assert.assertFalse(expected.isEmpty());
            if (sample.getCategory() == Sample.Category.LETTERS) {
                Assert.assertEquals(TextGen.LETTER_LENGTH, expected.length());
                for (int c = 0; c < expected.length(); c++) {
                    char ch = expected.charAt(c);
                    Assert.assertTrue(expected, TextGen.LETTERS.indexOf(ch) >= 0);
                    if (ch >= 'A' && ch <= 'Z') {
                        upper = true;
                    }
                    if (ch >= 'a' && ch <= 'z') {
                        lower = true;
                    }
                }
                Assert.assertNull(sample.getValue());
            } else if (sample.getCategory() == Sample.Category.DIGITS) {
                Assert.assertEquals(TextGen.DIGIT_LENGTH, expected.length());
                for (int c = 0; c < expected.length(); c++) {
                    Assert.assertTrue(expected, TextGen.DIGITS.indexOf(expected.charAt(c)) >= 0);
                }
                Assert.assertNull(sample.getValue());
            } else if (sample.getCategory() == Sample.Category.MIXED) {
                Assert.assertEquals(TextGen.MIXED_LENGTH, expected.length());
                boolean letter = false;
                boolean digit = false;
                for (int c = 0; c < expected.length(); c++) {
                    char ch = expected.charAt(c);
                    if (TextGen.LETTERS.indexOf(ch) >= 0) {
                        letter = true;
                    } else if (TextGen.DIGITS.indexOf(ch) >= 0) {
                        digit = true;
                    } else {
                        Assert.fail(expected);
                    }
                }
                Assert.assertTrue(expected, letter && digit);
                Assert.assertNull(sample.getValue());
            } else {
                Assert.assertNotNull(sample.getValue());
                Assert.assertEquals(expected, TextGen.evaluate(expected), sample.getValue());
                Assert.assertTrue(sample.getValue().intValue() >= 0);
                boolean sawOp = false;
                for (int c = 0; c < expected.length(); c++) {
                    char ch = expected.charAt(c);
                    if (ch == '+' || ch == '-' || ch == '*' || ch == '/') {
                        operators.add(Character.valueOf(ch));
                        sawOp = true;
                        if (ch == '/' && c + 1 < expected.length() && expected.charAt(c + 1) == '0') {
                            Assert.fail(expected);
                        }
                    }
                }
                Assert.assertTrue(expected, sawOp);
            }
            for (int a = 0; a < AXES.length; a++) {
                String value = CaptchaStyle.axis(sample.getStyleTag(), AXES[a]);
                Assert.assertFalse(sample.getStyleTag(), value.isEmpty());
                axes.get(AXES[a]).add(value);
            }
            Assert.assertTrue(sample.getFile().isFile());
            Assert.assertTrue(sample.getFile().length() > 32L);
        }
        Assert.assertTrue(upper && lower);
        Assert.assertTrue(operators.contains(Character.valueOf('+')));
        Assert.assertTrue(operators.contains(Character.valueOf('-')));
        Assert.assertTrue(operators.contains(Character.valueOf('*')));
        Assert.assertTrue(operators.contains(Character.valueOf('/')));
        for (int i = 0; i < AXES.length; i++) {
            Assert.assertTrue(AXES[i] + " " + axes.get(AXES[i]), axes.get(AXES[i]).size() > 1);
        }
        for (int i = 0; i < perCategory.length; i++) {
            Assert.assertTrue(perCategory[i] > 0);
        }
    }

    private static void assertOffModal(List<Sample> samples, int minimum) {
        for (int a = 0; a < AXES.length; a++) {
            Map<String, Integer> counts = new HashMap<String, Integer>();
            for (int i = 0; i < samples.size(); i++) {
                String value = CaptchaStyle.axis(samples.get(i).getStyleTag(), AXES[a]);
                Integer count = counts.get(value);
                counts.put(value, Integer.valueOf((count == null ? 0 : count.intValue()) + 1));
            }
            int modal = 0;
            for (Integer count : counts.values()) {
                if (count.intValue() > modal) {
                    modal = count.intValue();
                }
            }
            int off = samples.size() - modal;
            Assert.assertTrue(AXES[a] + " off-modal " + off, off >= minimum);
        }
    }

    private static int countCorrect(BenchmarkRunner.Outcome outcome) {
        int correct = 0;
        for (int i = 0; i < outcome.getCategories().size(); i++) {
            correct += outcome.getCategories().get(i).getCorrect();
        }
        return correct;
    }
}
