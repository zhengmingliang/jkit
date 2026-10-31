package com.alianga.jkit.captcha.bench;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Random;

/**
 * 生成器与基准的冒烟测试。跑小样本，验证数据集可生成、可判定、清单可回读。
 *
 * <p>全量 10 万张由 {@code BenchMain} 跑，不放在 surefire 里，避免测试阶段过长。
 * 需要本机跑一遍时用 {@code mvn -pl jkit-captcha-bench test -Dtest=BenchmarkSmokeTest}。
 */
public class BenchmarkSmokeTest {

    @Test
    public void generatorProducesReadableManifest() throws Exception {
        File output = new File(System.getProperty("bench.output", "target/captcha-dataset"), "smoke");
        int perCategory = 5;
        List<Sample> samples = DatasetGenerator.generate(output, perCategory, 1L, null);
        Assert.assertEquals(perCategory * Sample.Category.values().length, samples.size());
        for (int i = 0; i < samples.size(); i++) {
            Assert.assertTrue(samples.get(i).getFile().isFile());
            Assert.assertFalse(samples.get(i).getExpected().isEmpty());
        }
        List<Sample> reloaded = DatasetGenerator.load(output);
        Assert.assertEquals(samples.size(), reloaded.size());
        for (int i = 0; i < reloaded.size(); i++) {
            Assert.assertEquals("清单回读应保留期望文本",
                    samples.get(i).getExpected(), reloaded.get(i).getExpected());
            Assert.assertEquals("清单回读应保留类别",
                    samples.get(i).getCategory(), reloaded.get(i).getCategory());
            Assert.assertEquals("清单回读应保留结果",
                    samples.get(i).getValue(), reloaded.get(i).getValue());
            Assert.assertTrue("图片路径应可解析", reloaded.get(i).getFile().isFile());
        }
    }

    @Test
    public void arithmeticLabelsMatchEvaluatorRules() {
        Random random = new Random(7L);
        for (int i = 0; i < 500; i++) {
            TextGen.Expression expression = TextGen.arithmetic(random, i % 4 == 0);
            Assert.assertFalse("减法结果不应为负：" + expression.getText(),
                    expression.getText().contains("-")
                            && expression.getValue() < 0);
            Assert.assertTrue(expression.getText(), expression.getValue() >= 0);
        }
    }

    @Test
    public void runnerJudgesSmallSample() throws Exception {
        File output = new File(System.getProperty("bench.output", "target/captcha-dataset"), "smoke-run");
        List<Sample> samples = DatasetGenerator.generate(output, 3, 5L, null);
        BenchmarkRunner runner = new BenchmarkRunner(2, BenchmarkRunner.LOW_CONFIDENCE);
        BenchmarkRunner.Outcome outcome = runner.run(samples, -1, null);
        Assert.assertEquals(samples.size(), outcome.getTotal());
        Assert.assertEquals("每类一组", Sample.Category.values().length,
                outcome.getCategories().size());
        int bucketTotal = 0;
        for (int i = 0; i < outcome.getCategories().size(); i++) {
            bucketTotal += outcome.getCategories().get(i).getTotal();
        }
        Assert.assertEquals("各分组样本数之和应等于总数", samples.size(), bucketTotal);
    }
}
