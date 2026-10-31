package com.alianga.jkit.captcha.bench;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 把基准结果写成 Markdown 报告与失败样本清单。
 */
public final class ReportWriter {
    private static final int MAX_ROWS = 60;
    private static final int MAX_FAILURE_LINES = 400;

    private ReportWriter() {
    }

    /**
     * 写报告。
     *
     * @param file    报告文件
     * @param outcome 汇总结果
     * @param title   报告标题
     * @param note    写在表格前的一段说明
     * @throws IOException 写盘失败时
     */
    public static void write(File file, BenchmarkRunner.Outcome outcome, String title, String note)
            throws IOException {
        if (file.getParentFile() != null && !file.getParentFile().isDirectory()) {
            file.getParentFile().mkdirs();
        }
        BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
        try {
            writer.write("# " + title);
            writer.newLine();
            writer.newLine();
            if (note != null && !note.isEmpty()) {
                writer.write(note);
                writer.newLine();
                writer.newLine();
            }
            writer.write(String.format(Locale.ROOT,
                    "样本 %d 张，整串正确 %d，整串准确率 %.2f%%，忽略大小写 %.2f%%，平均耗时 %.2f ms。",
                    Integer.valueOf(outcome.getTotal()),
                    Integer.valueOf(outcome.getCorrect()),
                    Double.valueOf(outcome.accuracy() * 100d),
                    Double.valueOf(outcome.caseInsensitiveAccuracy() * 100d),
                    Double.valueOf(outcome.getMeanMillis())));
            writer.newLine();
            writer.newLine();
            writeTable(writer, "按类别", outcome.getCategories(), false);
            writeTable(writer, "按风格（最弱的若干组）", weakest(outcome.getStyles()), true);
            writeFailures(writer, outcome);
        } finally {
            writer.close();
        }
    }

    private static void writeTable(BufferedWriter writer, String heading,
                                   List<BenchmarkRunner.Bucket> buckets, boolean sortByAccuracy)
            throws IOException {
        writer.write("## " + heading);
        writer.newLine();
        writer.newLine();
        writer.write("| 分组 | 样本数 | 整串正确 | 整串准确率 | 忽略大小写 | 平均耗时 ms |");
        writer.newLine();
        writer.write("| --- | --- | --- | --- | --- | --- |");
        writer.newLine();
        for (int i = 0; i < buckets.size(); i++) {
            BenchmarkRunner.Bucket bucket = buckets.get(i);
            writer.write(String.format(Locale.ROOT, "| %s | %d | %d | %.1f%% | %.1f%% | %.2f |",
                    bucket.getName(),
                    Integer.valueOf(bucket.getTotal()),
                    Integer.valueOf(bucket.getCorrect()),
                    Double.valueOf(bucket.accuracy() * 100d),
                    Double.valueOf(bucket.caseInsensitiveAccuracy() * 100d),
                    Double.valueOf(bucket.meanMillis())));
            writer.newLine();
        }
        writer.newLine();
        if (sortByAccuracy) {
            writer.write("按准确率升序，只列出最弱的 " + Math.min(MAX_ROWS, buckets.size()) + " 组。");
            writer.newLine();
            writer.newLine();
        }
    }

    private static List<BenchmarkRunner.Bucket> weakest(List<BenchmarkRunner.Bucket> styles) {
        List<BenchmarkRunner.Bucket> copy = new ArrayList<BenchmarkRunner.Bucket>(styles);
        Collections.sort(copy, new Comparator<BenchmarkRunner.Bucket>() {
            @Override
            public int compare(BenchmarkRunner.Bucket left, BenchmarkRunner.Bucket right) {
                int byAccuracy = Double.compare(left.accuracy(), right.accuracy());
                if (byAccuracy != 0) {
                    return byAccuracy;
                }
                return Integer.compare(right.getTotal(), left.getTotal());
            }
        });
        if (copy.size() > MAX_ROWS) {
            return new ArrayList<BenchmarkRunner.Bucket>(copy.subList(0, MAX_ROWS));
        }
        return copy;
    }

    private static void writeFailures(BufferedWriter writer, BenchmarkRunner.Outcome outcome)
            throws IOException {
        writer.write("## 失败样本");
        writer.newLine();
        writer.newLine();
        writer.write(String.format(Locale.ROOT, "识别错误 %d 张，置信度低于 %.2f 的 %d 张。",
                Integer.valueOf(outcome.getWrong().size()),
                Double.valueOf(BenchmarkRunner.LOW_CONFIDENCE),
                Integer.valueOf(outcome.getLowConfidence().size())));
        writer.newLine();
        writer.newLine();
        List<BenchmarkRunner.Verdict> wrong = outcome.getWrong();
        for (int i = 0; i < wrong.size() && i < MAX_FAILURE_LINES; i++) {
            BenchmarkRunner.Verdict verdict = wrong.get(i);
            Sample sample = verdict.getSample();
            writer.write("- `" + sample.getFile().getName() + "` 期望 `" + sample.getExpected()
                    + "` 实际 `" + verdict.getActual() + "` 风格 `" + sample.getStyleTag() + "`");
            writer.newLine();
        }
        if (wrong.size() > MAX_FAILURE_LINES) {
            writer.write("- 其余 " + (wrong.size() - MAX_FAILURE_LINES) + " 张见 failures.tsv");
            writer.newLine();
        }
        writer.newLine();
    }

    /**
     * 把全部失败样本写成 tsv，供后续逐类分析。
     *
     * @param file    输出文件
     * @param outcome 汇总结果
     * @throws IOException 写盘失败时
     */
    public static void writeFailures(File file, BenchmarkRunner.Outcome outcome) throws IOException {
        if (file.getParentFile() != null && !file.getParentFile().isDirectory()) {
            file.getParentFile().mkdirs();
        }
        BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
        try {
            writer.write("# name\texpected\tactual\tcategory\tstyle\tconfidence");
            writer.newLine();
            List<BenchmarkRunner.Verdict> wrong = outcome.getWrong();
            for (int i = 0; i < wrong.size(); i++) {
                BenchmarkRunner.Verdict verdict = wrong.get(i);
                Sample sample = verdict.getSample();
                writer.write(sample.getFile().getName() + '\t' + sample.getExpected() + '\t'
                        + verdict.getActual() + '\t' + sample.getCategory() + '\t'
                        + sample.getStyleTag() + '\t'
                        + String.format(Locale.ROOT, "%.3f", Double.valueOf(verdict.getConfidence())));
                writer.newLine();
            }
        } finally {
            writer.close();
        }
    }
}
