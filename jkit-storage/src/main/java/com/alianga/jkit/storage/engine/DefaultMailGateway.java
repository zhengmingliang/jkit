package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageException;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生产网关：委托给 {@link ImapMailSession} / {@link ImapAppendStore}。
 * 支持 {@code channelPool}（默认 1、上限 4）：池内多会话按需创建；
 * 单会话时所有操作串行；多会话时借还通道，避免交叉 SELECT。
 *
 * @author 郑明亮
 */
final class DefaultMailGateway implements MailGateway {
    private static final int MAX_POOL = 4;
    private static final long BORROW_TIMEOUT_MS = 30_000L;

    private final Object lock = new Object();
    private final LinkedBlockingQueue<ImapMailSession> idle = new LinkedBlockingQueue<ImapMailSession>();
    private final AtomicInteger created = new AtomicInteger(0);
    private final List<ImapMailSession> all = new ArrayList<ImapMailSession>();

    private Map<String, String> cfg;
    private int poolSize = 1;
    private boolean closed;

    DefaultMailGateway() {
        // pool filled on connect
    }

    /** 兼容旧构造：外部传入单会话时退化为池大小 1。 */
    DefaultMailGateway(ImapMailSession session, ImapAppendStore appendStore) {
        if (session == null) {
            throw StorageException.invalidConfig("ImapMailSession 不能为 null");
        }
        idle.offer(session);
        all.add(session);
        created.set(1);
        poolSize = 1;
        // appendStore 与 session 绑定；池化路径不再使用外部 appendStore
    }

    @Override
    public void connect(Map<String, String> cfg) {
        if (cfg == null) {
            throw StorageException.invalidConfig("MailGateway 配置不能为 null");
        }
        synchronized (lock) {
            closeUnlocked();
            this.cfg = cfg;
            this.closed = false;
            this.poolSize = clampPool(parseInt(cfg.get("channelPool"), 1));
            ImapMailSession first = newSession();
            first.connect(cfg);
            idle.offer(first);
            all.add(first);
            created.set(1);
        }
    }

    @Override
    public List<MailEntry> list(String path) {
        ImapMailSession session = borrow();
        try {
            return session.list(path);
        } finally {
            release(session);
        }
    }

    @Override
    public void download(String path, OutputStream out) {
        ImapMailSession session = borrow();
        try {
            session.download(path, out);
        } finally {
            release(session);
        }
    }

    @Override
    public void delete(String path) {
        ImapMailSession session = borrow();
        try {
            session.delete(path);
        } finally {
            release(session);
        }
    }

    @Override
    public boolean exists(String path) {
        ImapMailSession session = borrow();
        try {
            return session.exists(path);
        } finally {
            release(session);
        }
    }

    @Override
    public MailEntry stat(String path) {
        ImapMailSession session = borrow();
        try {
            return session.stat(path);
        } finally {
            release(session);
        }
    }

    @Override
    public void mkdir(String dir) {
        ImapMailSession session = borrow();
        try {
            session.mkdir(dir);
        } finally {
            release(session);
        }
    }

    @Override
    public void rmdir(String dir) {
        ImapMailSession session = borrow();
        try {
            session.rmdir(dir);
        } finally {
            release(session);
        }
    }

    @Override
    public void append(String folder, InputStream mimeMessage, long sizeBytes) {
        ImapMailSession session = borrow();
        try {
            new ImapAppendStore(session).append(folder, mimeMessage, sizeBytes);
        } finally {
            release(session);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeUnlocked();
        }
    }

    private ImapMailSession borrow() {
        synchronized (lock) {
            if (closed) {
                throw StorageException.invalidConfig("MailGateway 已关闭");
            }
            if (cfg == null && idle.isEmpty()) {
                throw StorageException.invalidConfig("MailGateway 尚未 connect");
            }
        }
        try {
            ImapMailSession session = idle.poll();
            if (session != null) {
                return session;
            }
            synchronized (lock) {
                if (closed) {
                    throw StorageException.invalidConfig("MailGateway 已关闭");
                }
                if (created.get() < poolSize) {
                    if (created.incrementAndGet() <= poolSize) {
                        ImapMailSession createdSession = newSession();
                        createdSession.connect(cfg);
                        all.add(createdSession);
                        return createdSession;
                    }
                    created.decrementAndGet();
                }
            }
            session = idle.poll(BORROW_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (session == null) {
                throw new StorageException(com.alianga.jkit.storage.StorageErrorCode.TIMEOUT,
                        "IMAP 会话池借取超时 (" + BORROW_TIMEOUT_MS + "ms)", null);
            }
            return session;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw StorageException.io("IMAP 会话池借取被中断", null, e);
        }
    }

    private void release(ImapMailSession session) {
        if (session == null) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                session.close();
                return;
            }
        }
        idle.offer(session);
    }

    private ImapMailSession newSession() {
        return new ImapMailSession();
    }

    private void closeUnlocked() {
        closed = true;
        ImapMailSession s;
        while ((s = idle.poll()) != null) {
            s.close();
        }
        for (ImapMailSession session : all) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // ignore
            }
        }
        all.clear();
        created.set(0);
        cfg = null;
    }

    private static int clampPool(int raw) {
        if (raw < 1) {
            return 1;
        }
        return Math.min(raw, MAX_POOL);
    }

    private static int parseInt(String raw, int defaultValue) {
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
