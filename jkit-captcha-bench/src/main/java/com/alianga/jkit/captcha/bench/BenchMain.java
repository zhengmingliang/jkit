package com.alianga.jkit.captcha.bench;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * 命令行入口：生成数据集、跑全量识别、写报告。
 *
 * <pre>
 * mvn -pl jkit-captcha-bench exec:java -Dexec.args="--out target/dataset --count 25000 --threads 16"
 * mvn -pl jkit-captcha-bench exec:java -Dexec.args="--out target/dataset --only-benchmark"
 * </pre>
 */
public final class BenchMain {
    private BenchMain() {
    }

    /**
     * @param args 命令行参数
     * @throws Exception 生成或识别失败时
     */
    public static void main(String[] args) throws Exception {
        File output = new File(arg(args, "--out", "target/captcha-dataset"));
        int perCategory = Integer.parseInt(arg(args, "--count", "25000"));
        int threads = Integer.parseInt(arg(args, "--threads", "16"));
        long seed = Long.parseLong(arg(args, "--seed", "20261101"));
        int limit = Integer.parseInt(arg(args, "--limit", "-1"));
        boolean onlyBenchmark = has(args, "--only-benchmark");
        boolean onlyGenerate = has(args, "--only-generate");
        String tag = arg(args, "--tag", "run");

        if (!onlyBenchmark) {
            System.out.println("生成数据集 -> " + output.getAbsolutePath());
            long started = System.currentTimeMillis();
            List<Sample> samples = DatasetGenerator.generate(output, perCategory, seed,
                    new DatasetGenerator.Progress() {
                        @Override
                        public void onProgress(String category, int done, int total) {
                            if (done % 10000 == 0) {
                                System.out.println("  " + category + " " + done + "/" + total);
                            }
                        }
                    });
            System.out.printf(Locale.ROOT, "生成 %d 张，用时 %.1f s%n",
                    Integer.valueOf(samples.size()),
                    Double.valueOf((System.currentTimeMillis() - started) / 1000d));
            if (onlyGenerate) {
                return;
            }
        }

        System.out.println("加载清单 -> " + new File(output, "manifest.tsv").getAbsolutePath());
        List<Sample> samples = DatasetGenerator.load(output);
        if (limit >= 0 && limit < samples.size()) {
            samples = samples.subList(0, limit);
        }
        System.out.println("开始识别 " + samples.size() + " 张，线程数 " + threads);
        long started = System.currentTimeMillis();
        BenchmarkRunner runner = new BenchmarkRunner(threads, BenchmarkRunner.LOW_CONFIDENCE);
        BenchmarkRunner.Outcome outcome = runner.run(samples, -1, new BenchmarkRunner.Progress() {
            @Override
            public void onProgress(int done, int total) {
                if (done % 10000 < 200) {
                    System.out.printf(Locale.ROOT, "  %d/%d%n", Integer.valueOf(done),
                            Integer.valueOf(total));
                }
            }
        });
        double seconds = (System.currentTimeMillis() - started) / 1000d;
        System.out.printf(Locale.ROOT, "识别完成，用时 %.1f s，整串准确率 %.2f%%%n",
                Double.valueOf(seconds), Double.valueOf(outcome.accuracy() * 100d));

        File report = new File(output, "report-" + tag + ".md");
        ReportWriter.write(report, outcome, "jkit-captcha 全量识别基准（" + tag + "）",
                "数据集由 jkit-captcha-bench 生成，共 " + outcome.getTotal() + " 张。");
        ReportWriter.writeFailures(new File(output, "failures-" + tag + ".tsv"), outcome);
        System.out.println("报告 -> " + report.getAbsolutePath());

        for (int i = 0; i < outcome.getCategories().size(); i++) {
            BenchmarkRunner.Bucket bucket = outcome.getCategories().get(i);
            System.out.printf(Locale.ROOT, "  %-12s %5d 张  %.2f%%%n", bucket.getName(),
                    Integer.valueOf(bucket.getTotal()),
                    Double.valueOf(bucket.accuracy() * 100d));
        }
    }

    private static String arg(String[] args, String key, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equals(args[i])) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static boolean has(String[] args, String key) {
        for (int i = 0; i < args.length; i++) {
            if (key.equals(args[i])) {
                return true;
            }
        }
        return false;
    }
}
