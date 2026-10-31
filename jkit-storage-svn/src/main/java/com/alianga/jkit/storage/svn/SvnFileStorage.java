package com.alianga.jkit.storage.svn;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.BasicAuthenticationManager;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationProvider;
import org.tmatesoft.svn.core.auth.SVNAuthentication;
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory;
import org.tmatesoft.svn.core.internal.io.svn.SVNRepositoryFactoryImpl;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.io.diff.SVNDeltaGenerator;
import org.tmatesoft.svn.core.wc.SVNClientManager;
import org.tmatesoft.svn.core.wc.SVNCopySource;
import org.tmatesoft.svn.core.wc.SVNRevision;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 基于 SVNKit 的 SVN 文件存储：一套实现同时支持 {@code svn://} 与 {@code http(s)://}
 * 两种服务端连接方式。
 *
 * <p>所有写操作都是一次 SVN 提交（产生新 revision）：上传走提交编辑器 + 文本增量，
 * 直接流式发送，不在本地落临时文件；mkdir / 删除 / 重命名 / 复制走 URL 级 RA 操作，
 * 服务端一次事务完成。读操作（stat / list / download）基于 HEAD 版本。</p>
 *
 * <p>并发：单实例内所有 SVNKit 调用（repository 与 clients）共用一把锁串行化——
 * {@code SVNRepository}（svn:// 是单条 socket 上的多路请求序列）与 {@code SVNClientManager}
 * 都不是线程安全的，而 SyncEngine 的并发同步会让 N 个线程同时对同一实例调
 * stat / download / upload。锁内串行等于把 SVN 端点的并发退化为排队，正确性优先
 * （与 FTP 引擎同一取舍）。{@link #close()} 释放会话连接与客户端管理器。</p>
 *
 * @author 郑明亮
 */
public class SvnFileStorage implements FileStorage {
    static {
        // 注册两种 RA 实现：DAV = http/https，SVN = svn://（内部 setup 幂等）
        DAVRepositoryFactory.setup();
        SVNRepositoryFactoryImpl.setup();
    }

    private final SvnConfig config;
    private final SVNRepository repository;
    private final SVNClientManager clients;
    private final SVNDeltaGenerator deltaGenerator = new SVNDeltaGenerator();
    /**
     * 所有 SVNKit 调用的串行锁：SVNRepository 与 SVNClientManager 均非线程安全，
     * SyncEngine 并发同步（concurrency>1）会多线程共用同一实例。
     */
    private final Object svnLock = new Object();

    private volatile boolean closed;

    /**
     * 引擎工厂入口（由 {@link SvnStorageSupport#register()} 注册到 {@code FileStorages}）。
     *
     * @param storageConfig 连接配置
     * @throws StorageException 配置非法（未知协议 / 缺主机 / 端口错误）
     */
    public SvnFileStorage(StorageConfig storageConfig) {
        this.config = SvnConfig.from(storageConfig);
        try {
            this.repository = SVNRepositoryFactory.create(config.rootUrl());
            this.repository.setAuthenticationManager(authenticationManager(config));
            this.clients = SVNClientManager.newInstance(null, authenticationManager(config));
        } catch (SVNException e) {
            throw SvnErrors.connect(e);
        }
    }

    private static ISVNAuthenticationManager authenticationManager(SvnConfig config) {
        // 直接构造内存态认证器：不走 ~/.subversion 凭据缓存，
        // 避免 A 连接成功后同 realm 的 B 连接静默复用 A 的凭据
        ISVNAuthenticationManager manager = config.anonymous()
                ? new BasicAuthenticationManager(new SVNAuthentication[0])
                : new BasicAuthenticationManager(config.user(), config.password());
        // https 自签名 / 私有 CA 证书：用户勾选「信任 HTTPS 证书」后临时接受全部服务端证书
        if (config.trustServerCert() && manager instanceof BasicAuthenticationManager) {
            ((BasicAuthenticationManager) manager).setAuthenticationProvider(new TrustServerCertProvider());
        }
        return manager;
    }

    @Override
    public String platform() {
        return SvnConfig.PLATFORM_ID;
    }

    @Override
    public boolean exists(String path) {
        synchronized (svnLock) {
            try {
                return repository.checkPath(config.relativePath(path), -1) != SVNNodeKind.NONE;
            } catch (SVNException e) {
                throw SvnErrors.map(path, e);
            }
        }
    }

    @Override
    public StorageEntry stat(String path) {
        String rel = config.relativePath(path);
        synchronized (svnLock) {
            try {
                // info 对精确路径发起 SVN stat 请求，避免每个文件都传输其整个父目录
                SVNDirEntry entry = repository.info(rel, -1);
                if (entry == null) {
                    throw StorageException.notFound(path);
                }
                if (rel.isEmpty()) {
                    // 保留根目录存在性与连接/认证检查，但根目录没有自身最后变更信息
                    return StorageEntry.dir("");
                }
                if (entry.getRevision() >= 0) {
                    // info/stat 不携带提交说明；按最后变更 revision 精确读取 svn:log，保持原目录项信息。
                    org.tmatesoft.svn.core.SVNPropertyValue message =
                            repository.getRevisionPropertyValue(entry.getRevision(),
                                    org.tmatesoft.svn.core.SVNRevisionProperty.LOG);
                    entry.setCommitMessage(org.tmatesoft.svn.core.SVNPropertyValue
                            .getPropertyAsString(message));
                }
                return toEntry(displayPath(path), entry);
            } catch (SVNException e) {
                throw SvnErrors.map(path, e);
            }
        }
    }

    @Override
    public List<StorageEntry> list(String path) {
        String rel = config.relativePath(path);
        synchronized (svnLock) {
            try {
                Collection<SVNDirEntry> entries = listEntries(rel);
                List<StorageEntry> result = new ArrayList<>(entries.size());
                String prefix = displayPath(path);
                for (SVNDirEntry entry : entries) {
                    String childPath = prefix.isEmpty() ? entry.getName() : prefix + "/" + entry.getName();
                    result.add(toEntry(childPath, entry));
                }
                // 目录在前、文件在后，各自按名称排序：和界面其他引擎的浏览顺序一致
                result.sort(Comparator
                        .comparing((StorageEntry e) -> !e.isDirectory())
                        .thenComparing(e -> e.name().toLowerCase(java.util.Locale.ROOT)));
                return result;
            } catch (SVNException e) {
                throw SvnErrors.map(path, e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Collection<SVNDirEntry> listEntries(String rel) throws SVNException {
        return repository.getDir(rel, -1, (org.tmatesoft.svn.core.SVNProperties) null,
                (Collection<SVNDirEntry>) null);
    }

    private StorageEntry toEntry(String path, SVNDirEntry entry) {
        long mtime = entry.getDate() == null ? StorageEntry.MTIME_UNKNOWN : entry.getDate().getTime();
        // 用户信息（最后修改人）与 SVN 专属元数据：界面详情面板按通用键消费
        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
        if (entry.getAuthor() != null && !entry.getAuthor().trim().isEmpty()) {
            attributes.put(SvnStorageSupport.ATTR_OWNER, entry.getAuthor());
        }
        if (entry.getRevision() >= 0) {
            attributes.put(SvnStorageSupport.ATTR_SVN_REVISION, Long.toString(entry.getRevision()));
        }
        if (entry.getCommitMessage() != null && !entry.getCommitMessage().trim().isEmpty()) {
            attributes.put(SvnStorageSupport.ATTR_SVN_COMMIT_MESSAGE, entry.getCommitMessage());
        }
        attributes.put(SvnStorageSupport.ATTR_SVN_URL, config.url(path).toString());

        StorageEntry result;
        if (entry.getKind() == SVNNodeKind.DIR) {
            result = StorageEntry.dir(path, mtime);
        } else {
            // 最后变更版本即强变更标记，作为 etag 供「大小+ETag」对比策略使用
            String etag = entry.getRevision() >= 0 ? "r" + entry.getRevision() : null;
            result = StorageEntry.file(path, entry.getSize(), mtime, etag);
        }
        for (java.util.Map.Entry<String, String> attribute : attributes.entrySet()) {
            result = result.withAttribute(attribute.getKey(), attribute.getValue());
        }
        return result;
    }

    @Override
    public void mkdir(String path) {
        SVNURL url = config.url(path);
        synchronized (svnLock) {
            try {
                // makeParents=true：与本地 / SFTP 引擎一致，缺失的父目录一并创建
                clients.getCommitClient().doMkDir(new SVNURL[]{url},
                        "storage-ui mkdir " + StoragePaths.name(path), null, true);
            } catch (SVNException e) {
                throw SvnErrors.map(path, e);
            }
        }
    }

    @Override
    public void upload(String path, InputStream in, long length) {
        String rel = config.relativePath(path);
        // svn:// 一条物理连接，提交编辑器的多轮请求序列必须独占；
        // 锁覆盖 checkPath + 整个编辑器序列，与其它读写互斥
        synchronized (svnLock) {
            SVNNodeKind existing;
            try {
                existing = repository.checkPath(rel, -1);
            } catch (SVNException e) {
                throw SvnErrors.map(path, e);
            }
            if (existing == SVNNodeKind.DIR) {
                throw StorageException.alreadyExists(path);
            }
            boolean modify = existing == SVNNodeKind.FILE;
            ISVNEditor editor = null;
            boolean committed = false;
            try {
                editor = repository.getCommitEditor("storage-ui upload " + StoragePaths.name(path),
                        null, false, null);
                editor.openRoot(-1);
                if (modify) {
                    editor.openFile(rel, -1);
                } else {
                    editor.addFile(rel, null, -1);
                }
                editor.applyTextDelta(rel, null);
                String checksum = deltaGenerator.sendDelta(rel, in, editor, true);
                editor.closeFile(rel, checksum);
                editor.closeDir();
                editor.closeEdit();
                committed = true;
            } catch (SVNException e) {
                throw SvnErrors.map(path, e);
            } finally {
                if (!committed && editor != null) {
                    abortQuietly(editor);
                }
            }
        }
    }

    @Override
    public void download(String path, OutputStream out) {
        String rel = config.relativePath(path);
        synchronized (svnLock) {
            try {
                repository.getFile(rel, -1, null, out);
            } catch (SVNException e) {
                throw SvnErrors.map(path, e);
            }
        }
    }

    @Override
    public void delete(String path) {
        deleteOne(path);
    }

    @Override
    public void deleteDir(String path) {
        if (config.relativePath(path).isEmpty()) {
            // 与 FileStorage 默认契约一致：绝不允许删存储根
            throw StorageException.invalidConfig("deleteDir 拒绝删除存储根目录，请指定具体路径");
        }
        // SVN 的 deleteEntry 对目录天然递归：一次提交整树删除，不必逐文件 walk
        deleteOne(path);
    }

    private void deleteOne(String path) {
        String rel = config.relativePath(path);
        synchronized (svnLock) {
            ISVNEditor editor = null;
            boolean committed = false;
            try {
                editor = repository.getCommitEditor("storage-ui delete " + StoragePaths.name(path),
                        null, false, null);
                editor.openRoot(-1);
                editor.deleteEntry(rel, -1);
                editor.closeDir();
                editor.closeEdit();
                committed = true;
            } catch (SVNException e) {
                throw SvnErrors.map(path, e);
            } finally {
                if (!committed && editor != null) {
                    abortQuietly(editor);
                }
            }
        }
    }

    @Override
    public void rename(String source, String target) {
        moveOrCopy(source, target, true);
    }

    @Override
    public void copy(String source, String target) {
        moveOrCopy(source, target, false);
    }

    private void moveOrCopy(String source, String target, boolean move) {
        synchronized (svnLock) {
            try {
                SVNURL srcUrl = config.url(source);
                SVNURL dstUrl = config.url(target);
                SVNCopySource copySource = new SVNCopySource(SVNRevision.HEAD, SVNRevision.HEAD, srcUrl);
                // (源, 目标, isMove, makeParents, 目标已存在则失败, 提交说明, 版本属性)
                clients.getCopyClient().doCopy(new SVNCopySource[]{copySource}, dstUrl,
                        move, true, true,
                        (move ? "storage-ui move " : "storage-ui copy ") + StoragePaths.name(target),
                        null);
            } catch (SVNException e) {
                throw SvnErrors.map(target, e);
            }
        }
    }

    private static void abortQuietly(ISVNEditor editor) {
        try {
            editor.abortEdit();
        } catch (SVNException ignored) {
            // 提交已失败，中止事务再失败也无补救空间
        }
    }

    /** 列目录 / stat 使用的逻辑路径：根返回 ""，其余无起首斜杠。 */
    private static String displayPath(String path) {
        return StoragePaths.normalize(path);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        // 与在途操作互斥：不能在别的线程还在 repository 上收发时抽掉会话
        synchronized (svnLock) {
            closed = true;
            try {
                clients.dispose();
            } finally {
                repository.closeSession();
            }
        }
    }

    @Override
    public String toString() {
        return "SvnFileStorage(" + config + ")";
    }

    /** 信任服务端 SSL 证书（自签名 / 私有 CA）；客户端证书仍走认证管理器配置。 */
    private static final class TrustServerCertProvider implements ISVNAuthenticationProvider {
        @Override
        public org.tmatesoft.svn.core.auth.SVNAuthentication requestClientAuthentication(
                String kind, SVNURL url, String realm,
                org.tmatesoft.svn.core.SVNErrorMessage errorMessage,
                org.tmatesoft.svn.core.auth.SVNAuthentication previousAuth, boolean authMayBeStored) {
            return null;
        }

        @Override
        public int acceptServerAuthentication(SVNURL url, String realm, Object certificate,
                                              boolean resultMayBeStored) {
            return ACCEPTED_TEMPORARY;
        }
    }
}
