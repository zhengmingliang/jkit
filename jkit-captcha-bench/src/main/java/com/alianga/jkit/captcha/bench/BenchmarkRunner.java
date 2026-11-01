package com.alianga.jkit.captcha.bench;

import com.alianga.jkit.captcha.CaptchaRecognizer;
import com.alianga.jkit.captcha.RecognizeOptions;
import com.alianga.jkit.captcha.RecognizeResult;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在数据集上跑 jkit-captcha 全量识别，统计各类别与风格的准确率。
 *
 * <p>识别器本身线程安全，这里按片并行。算术题按"表达式与结果都对"判定，字符题按整串判定，
 * 同时记一份忽略大小写的整串准确率，因为部分字体本身不区分大小写。
 */
public final class BenchmarkRunner {
    private static final int BATCH = 200;
    /** 置信度低于此值单独记为低置信样本。 */
    public static final double LOW_CONFIDENCE = 0.60;

    private final int threads;
    private final double lowConfidence;

    /**
     * @param threads 并发线程数
     * @param lowConfidence 低置信阈值
     */
    public BenchmarkRunner(int threads, double lowConfidence) {
        this.threads = Math.max(1, threads);
        this.lowConfidence = lowConfidence;
    }

    /**
     * 在数据集上跑一遍识别。
     *
     * @param samples 样本
     * @param limit   最多识别多少张，小于 0 表示全部
     * @param progress 进度回调，可为 {@code null}
     * @return 统计结果
     * @throws Exception 识别或读图出错时
     */
    public Outcome run(List<Sample> samples, int limit, Progress progress) throws Exception {
        List<Sample> work = samples;
        if (limit >= 0 && limit < samples.size()) {
            work = new ArrayList<Sample>(samples.subList(0, limit));
        }
        // 每个线程一个识别器：模板库与 ONNX 会话各自持有，避免互相等待。
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<Verdict>>> futures = new ArrayList<Future<List<Verdict>>>();
            for (int from = 0; from < work.size(); from += BATCH) {
                final List<Sample> slice =
                        new ArrayList<Sample>(work.subList(from, Math.min(work.size(), from + BATCH)));
                futures.add(pool.submit(new Callable<List<Verdict>>() {
                    @Override
                    public List<Verdict> call() {
                        return runSlice(slice);
                    }
                }));
            }
            List<Verdict> verdicts = new ArrayList<Verdict>(work.size());
            AtomicInteger done = new AtomicInteger();
            for (int i = 0; i < futures.size(); i++) {
                List<Verdict> slice = futures.get(i).get();
                verdicts.addAll(slice);
                if (progress != null) {
                    progress.onProgress(done.addAndGet(slice.size()), work.size());
                }
            }
            return summarize(verdicts);
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private List<Verdict> runSlice(List<Sample> slice) {
        CaptchaRecognizer recognizer = CaptchaRecognizer.createDefault();
        List<Verdict> verdicts = new ArrayList<Verdict>(slice.size());
        try {
            for (int i = 0; i < slice.size(); i++) {
                verdicts.add(judge(recognizer, slice.get(i)));
            }
        } finally {
            recognizer.close();
        }
        return verdicts;
    }

    private Verdict judge(CaptchaRecognizer recognizer, Sample sample) {
        BufferedImage image;
        try {
            image = ImageIO.read(sample.getFile());
        } catch (IOException ex) {
            return new Verdict(sample, "", null, 0d, -1L, Kind.READ_ERROR);
        }
        if (image == null) {
            return new Verdict(sample, "", null, 0d, -1L, Kind.READ_ERROR);
        }
        long started = System.nanoTime();
        RecognizeResult result;
        try {
            result = recognizer.recognize(image, RecognizeOptions.auto());
        } catch (RuntimeException ex) {
            long failed = (System.nanoTime() - started) / 1000000L;
            return new Verdict(sample, "", null, 0d, failed, Kind.ERROR);
        }
        long elapsed = (System.nanoTime() - started) / 1000000L;
        String actual = result.getText();
        double confidence = result.getConfidence();
        Kind kind;
        if (sample.getCategory() == Sample.Category.ARITHMETIC) {
            boolean ok = result.getValue() != null
                    && result.getValue().intValue() == sample.getValue().intValue()
                    && sample.getExpected().equals(result.getExpression());
            kind = ok ? Kind.CORRECT : Kind.WRONG;
        } else {
            kind = sample.getExpected().equals(actual) ? Kind.CORRECT : Kind.WRONG;
        }
        return new Verdict(sample, actual,
                result.getValue(), confidence, elapsed, kind);
    }

    private Outcome summarize(List<Verdict> verdicts) {
        Map<String, Bucket> byCategory = new ConcurrentHashMap<String, Bucket>();
        Map<String, Bucket> byStyle = new ConcurrentHashMap<String, Bucket>();
        List<Verdict> wrong = new ArrayList<Verdict>();
        List<Verdict> failures = new ArrayList<Verdict>();
        List<Verdict> lowConfidenceHits = new ArrayList<Verdict>();
        long totalMillis = 0;
        int readable = 0;
        for (int i = 0; i < verdicts.size(); i++) {
            Verdict verdict = verdicts.get(i);
            Bucket category = bucket(byCategory, verdict.getSample().getCategory().name());
            Bucket style = bucket(byStyle, verdict.getSample().getStyleTag());
            category.add(verdict);
            style.add(verdict);
            if (verdict.getKind() == Kind.READ_ERROR || verdict.getKind() == Kind.ERROR) {
                failures.add(verdict);
            } else {
                readable++;
                totalMillis += verdict.getElapsedMillis();
                if (verdict.getConfidence() < lowConfidence) {
                    lowConfidenceHits.add(verdict);
                }
            }
            if (verdict.getKind() == Kind.WRONG) {
                wrong.add(verdict);
            }
        }
        List<String> names = new ArrayList<String>(byCategory.keySet());
        Collections.sort(names);
        List<Bucket> categories = new ArrayList<Bucket>();
        for (int i = 0; i < names.size(); i++) {
            categories.add(byCategory.get(names.get(i)));
        }
        List<String> styleNames = new ArrayList<String>(byStyle.keySet());
        Collections.sort(styleNames);
        List<Bucket> styles = new ArrayList<Bucket>();
        for (int i = 0; i < styleNames.size(); i++) {
            styles.add(byStyle.get(styleNames.get(i)));
        }
        double meanMillis = readable == 0 ? 0d : (double) totalMillis / readable;
        return new Outcome(verdicts.size(), categories, styles, wrong, failures, lowConfidenceHits, meanMillis);
    }

    private static Bucket bucket(Map<String, Bucket> map, String key) {
        Bucket found = map.get(key);
        if (found == null) {
            found = new Bucket(key);
            map.put(key, found);
        }
        return found;
    }

    /** 单张判定的结果分类。 */
    public enum Kind {
        /** 整串（算术题含结果）正确。 */
        CORRECT,
        /** 识别出错。 */
        WRONG,
        /** 识别过程抛异常。 */
        ERROR,
        /** 图片读不出来。 */
        READ_ERROR
    }

    /** 一张图的判定。 */
    public static final class Verdict {
        private final Sample sample;
        private final String actual;
        private final Integer value;
        private final double confidence;
        private final long elapsedMillis;
        private final Kind kind;

        Verdict(Sample sample, String actual, Integer value, double confidence, long elapsedMillis,
                Kind kind) {
            this.sample = sample;
            this.actual = actual;
            this.value = value;
            this.confidence = confidence;
            this.elapsedMillis = elapsedMillis;
            this.kind = kind;
        }

        /** @return 样本 */
        public Sample getSample() {
            return sample;
        }

        /** @return 识别出的文本 */
        public String getActual() {
            return actual;
        }

        /** @return 识别出的算术结果 */
        public Integer getValue() {
            return value;
        }

        /** @return 置信度 */
        public double getConfidence() {
            return confidence;
        }

        /** @return 耗时毫秒 */
        public long getElapsedMillis() {
            return elapsedMillis;
        }

        /** @return 判定分类 */
        public Kind getKind() {
            return kind;
        }
    }

    /** 一个分组的累计统计。 */
    public static final class Bucket {
        private final String name;
        private int total;
        private int correct;
        private int wrong;
        private int errors;
        private int caseInsensitive;
        private long millis;

        Bucket(String name) {
            this.name = name;
        }

        void absorb(Bucket other) {
            total += other.total;
            correct += other.correct;
            wrong += other.wrong;
            errors += other.errors;
            caseInsensitive += other.caseInsensitive;
            millis += other.millis;
        }

        void add(Verdict verdict) {
            total++;
            if (verdict.getKind() == Kind.CORRECT) {
                correct++;
                caseInsensitive++;
            } else if (verdict.getKind() == Kind.WRONG) {
                wrong++;
                if (verdict.getSample().getExpected().equalsIgnoreCase(verdict.getActual())) {
                    caseInsensitive++;
                }
            } else {
                errors++;
            }
            millis += Math.max(0L, verdict.getElapsedMillis());
        }

        /** @return 分组名 */
        public String getName() {
            return name;
        }

        /** @return 样本数 */
        public int getTotal() {
            return total;
        }

        /** @return 整串正确数 */
        public int getCorrect() {
            return correct;
        }

        /** @return 识别错误数 */
        public int getWrong() {
            return wrong;
        }

        /** @return 异常数 */
        public int getErrors() {
            return errors;
        }

        /** @return 忽略大小写后的整串正确数 */
        public int getCaseInsensitive() {
            return caseInsensitive;
        }

        /** @return 整串准确率，0 到 1 */
        public double accuracy() {
            return total == 0 ? 0d : (double) correct / total;
        }

        /** @return 忽略大小写准确率，0 到 1 */
        public double caseInsensitiveAccuracy() {
            return total == 0 ? 0d : (double) caseInsensitive / total;
        }

        /** @return 平均耗时毫秒 */
        public double meanMillis() {
            return total == 0 ? 0d : (double) millis / total;
        }
    }

    /** 一次全量识别的汇总。 */
    public static final class Outcome {
        private final int total;
        private final List<Bucket> categories;
        private final List<Bucket> styles;
        private final List<Verdict> wrong;
        private final List<Verdict> failures;
        private final List<Verdict> lowConfidence;
        private final double meanMillis;

        Outcome(int total, List<Bucket> categories, List<Bucket> styles, List<Verdict> wrong,
                List<Verdict> failures, List<Verdict> lowConfidence, double meanMillis) {
            this.total = total;
            this.categories = categories;
            this.styles = styles;
            this.wrong = wrong;
            this.failures = failures;
            this.lowConfidence = lowConfidence;
            this.meanMillis = meanMillis;
        }

        /** @return 样本总数 */
        public int getTotal() {
            return total;
        }

        /** @return 按类别分组的统计 */
        public List<Bucket> getCategories() {
            return categories;
        }

        /** @return 按风格分组的统计 */
        public List<Bucket> getStyles() {
            return styles;
        }

        /** @return 识别错误的样本，文本或算术结果和标签不一致 */
        public List<Verdict> getWrong() {
            return wrong;
        }

        /** @return 识别失败的样本，读图失败或识别过程抛异常 */
        public List<Verdict> getFailures() {
            return failures;
        }

        /** @return 置信度低于阈值、且已经给出文本的样本。可以和识别错误重叠 */
        public List<Verdict> getLowConfidence() {
            return lowConfidence;
        }

        /** @return 全量平均耗时毫秒 */
        public double getMeanMillis() {
            return meanMillis;
        }

        /** @return 整体整串准确率，0 到 1 */
        public double accuracy() {
            return total == 0 ? 0d : (double) getCorrect() / total;
        }

        /** @return 整串正确数 */
        public int getCorrect() {
            int correct = 0;
            for (int i = 0; i < categories.size(); i++) {
                correct += categories.get(i).getCorrect();
            }
            return correct;
        }

        /** @return 整体忽略大小写准确率，0 到 1 */
        public double caseInsensitiveAccuracy() {
            int hit = 0;
            for (int i = 0; i < categories.size(); i++) {
                hit += categories.get(i).getCaseInsensitive();
            }
            return total == 0 ? 0d : (double) hit / total;
        }
    }

    /** 跑批过程中的进度回调。 */
    public interface Progress {
        /**
         * @param done  已完成张数
         * @param total 总张数
         */
        void onProgress(int done, int total);
    }
}
