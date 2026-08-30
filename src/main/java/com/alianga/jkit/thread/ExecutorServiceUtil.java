/**
 * Created by 郑明亮 on 2022/1/25 10:17.
 */
package com.alianga.jkit.thread;

import com.alianga.jkit.common.DefaultValues;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p> 线程池工具类</p>
 *
 * @author 郑明亮
 * @time 2022/1/24 22:17
 * @since 1.3.3
 */
public class ExecutorServiceUtil {
    private static final String THREAD_NAME_PREFIX = "alianga-";

    private static final ThreadFactory DEFAULT_THREAD_FACTORY = Executors.defaultThreadFactory();

    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger(1);

    private static final ThreadFactory THREAD_FACTORY = r -> {
        Thread thread = DEFAULT_THREAD_FACTORY.newThread(r);
        if (!thread.isDaemon()) {
            thread.setDaemon(true);
        }
        thread.setName(THREAD_NAME_PREFIX + THREAD_NUMBER.getAndIncrement());
        return thread;
    };

    /**
     * 创建一个定时调度线程池
     *
     * @return 新创建的定时调度线程池，线程数为 {@link DefaultValues.Thread#SCHEDULED_EXECUTOR_POOL_SIZE}，
     *         使用守护线程工厂
     */
    public static ScheduledExecutorService newScheduledExecutorService() {
        return new ScheduledThreadPoolExecutor(DefaultValues.Thread.SCHEDULED_EXECUTOR_POOL_SIZE, THREAD_FACTORY);
    }

    /**
     * 创建只有一个线程的调度线程池
     *
     * @return 新创建的单线程定时调度线程池，使用守护线程工厂
     */
    public static ScheduledExecutorService newSingleScheduledExecutorService() {
        return new ScheduledThreadPoolExecutor(1, THREAD_FACTORY);
    }

    /**
     * 创建单例线程池
     *
     * @return 新创建的单线程线程池，使用守护线程工厂
     */
    public static ExecutorService newSingleExecutorService() {
        return Executors.newSingleThreadExecutor(THREAD_FACTORY);
    }

    /**
     * 创建一个通用连接池
     *
     * @return executor service
     */
    public static ExecutorService newExecutorService() {
        return new ThreadPoolExecutor(DefaultValues.Thread.CORE_POOL_SIZE, DefaultValues.Thread.MAX_POOL_SIZE, 60L,
                TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
                THREAD_FACTORY);
    }

    /**
     * 关闭一个线程连接池
     *
     * @param executorService 要关闭的线程池
     */
    public static void shutdown(ExecutorService executorService) {
        executorService.shutdownNow();
    }

    /**
     * 获取默认的线程工厂
     *
     * @return 内置线程工厂，创建的线程均为守护线程，名称形如 {@code alianga-1}
     */
    public static ThreadFactory getDefaultThreadFactory() {
        return THREAD_FACTORY;
    }

    /**
     * 使用内部默认线程池异步执行任务
     *
     * <p>默认线程池在首次使用时创建，全进程共享，其线程均为守护线程，因此不会阻止 JVM 退出。</p>
     *
     * @param runnable 待执行的任务
     */
    public static void execute(Runnable runnable) {
        DefaultPoolHolder.POOL.execute(runnable);
    }

    /**
     * 使用内部默认线程池提交任务，并返回可用于获取结果或取消的凭据
     *
     * @param runnable 待执行的任务
     * @return 该任务的 {@link Future}，正常结束时 {@link Future#get()} 返回 {@code null}
     */
    public static Future<?> submit(Runnable runnable) {
        return DefaultPoolHolder.POOL.submit(runnable);
    }

    /**
     * 使用内部默认线程池提交有返回值的任务
     *
     * @param callable 待执行的任务
     * @param <T> 任务返回值类型
     * @return 该任务的 {@link Future}
     */
    public static <T> Future<T> submit(Callable<T> callable) {
        return DefaultPoolHolder.POOL.submit(callable);
    }

    /**
     * 挂起当前线程
     *
     * @param millis 挂起的毫秒数
     * @return 被中断返回false，否则true
     * @since 1.4.5
     */
    public static boolean sleep(long millis) {
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                // 恢复中断位，否则调用方无从得知本线程已被请求中断
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * 默认线程池的持有者。
     *
     * <p>借类初始化的线程安全语义完成懒加载，既避免了加锁开销，
     * 也避免了无同步的双重检查导致并发下创建出多个线程池（被丢弃的那个将永不关闭）。</p>
     */
    private static final class DefaultPoolHolder {
        private static final ExecutorService POOL = newExecutorService();
    }
}
