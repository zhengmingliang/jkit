package com.alianga.jkit.storage.svn;

import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import org.junit.Test;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;

import static org.junit.Assert.assertEquals;

/**
 * {@link SvnErrors} 单元测试：SVN 错误码 / 错误链 / HTTP 状态文本到
 * {@link StorageErrorCode} 的映射不触网。
 *
 * @author 郑明亮
 */
public class SvnErrorsTest {

    private static StorageException map(SVNErrorCode code, String message) {
        return SvnErrors.map("/a/b", new SVNException(SVNErrorMessage.create(code, message)));
    }

    @Test
    public void notFoundCodes() {
        assertEquals(StorageErrorCode.NOT_FOUND, map(SVNErrorCode.FS_NOT_FOUND, "missing").code());
        assertEquals(StorageErrorCode.NOT_FOUND,
                map(SVNErrorCode.RA_DAV_PATH_NOT_FOUND, "404").code());
        assertEquals(StorageErrorCode.NOT_FOUND,
                map(SVNErrorCode.ENTRY_NOT_FOUND, "no entry").code());
    }

    @Test
    public void alreadyExistsCodes() {
        assertEquals(StorageErrorCode.ALREADY_EXISTS,
                map(SVNErrorCode.FS_ALREADY_EXISTS, "exists").code());
        assertEquals(StorageErrorCode.ALREADY_EXISTS,
                map(SVNErrorCode.RA_DAV_ALREADY_EXISTS, "405").code());
    }

    @Test
    public void authorizationAndAuthenticationCodes() {
        assertEquals(StorageErrorCode.AUTH_FAILED,
                map(SVNErrorCode.RA_NOT_AUTHORIZED, "forbidden").code());
        assertEquals(StorageErrorCode.AUTH_FAILED,
                map(SVNErrorCode.AUTHZ_ROOT_UNREADABLE, "denied").code());
    }

    @Test
    public void raIoMappedToConnectionFailure() {
        assertEquals(StorageErrorCode.CONNECT_FAILED,
                map(SVNErrorCode.RA_SVN_IO_ERROR, "connection refused").code());
    }

    @Test
    public void cancellationDetected() {
        SVNErrorMessage wrap = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, "aborted");
        wrap.setChildErrorMessage(SVNErrorMessage.create(SVNErrorCode.CANCELLED, "cancel"));
        StorageException result = SvnErrors.map("", new SVNException(wrap));
        assertEquals(StorageErrorCode.CANCELLED, result.code());
    }

    @Test
    public void dav401MessageMappedToAuthFailure() {
        StorageException result = SvnErrors.map("",
                new SVNException(SVNErrorMessage.create(SVNErrorCode.RA_DAV_REQUEST_FAILED,
                        "401 Unauthorized on OPTIONS")));
        assertEquals(StorageErrorCode.AUTH_FAILED, result.code());
    }

    @Test
    public void outOfDateCommitIsConflict() {
        assertEquals(StorageErrorCode.CONFLICT,
                map(SVNErrorCode.FS_TXN_OUT_OF_DATE, "out of date").code());
    }

    @Test
    public void unknownErrorFallsBackToIoError() {
        assertEquals(StorageErrorCode.IO_ERROR,
                map(SVNErrorCode.IO_ERROR, "something else broke").code());
    }

    /**
     * 裸 "eof" 子串匹配必须收窄：错误消息常带路径，文件名里含 eof 子串
     * （如 /beforeof/）会把普通 IO 错误误分类成 CONNECT_FAILED——而
     * Sessions 对 CONNECT_FAILED 有重试语义，误分类会让重试打在真实业务
     * 错误上。真正的连接级 EOF 有完整短语（unexpected end of file 等）。
     */
    @Test
    public void eofSubstringInPathDoesNotBecomeConnectFailure() {
        assertEquals("含 eof 子串的路径是普通 IO 错误，不是连接失败",
                StorageErrorCode.IO_ERROR,
                map(SVNErrorCode.IO_ERROR, "error reading /data/beforeof/file.txt").code());
        assertEquals("真正的连接级 EOF 短语仍应识别为连接失败",
                StorageErrorCode.CONNECT_FAILED,
                map(SVNErrorCode.RA_DAV_REQUEST_FAILED,
                        "unexpected end of file from server").code());
        assertEquals(StorageErrorCode.CONNECT_FAILED,
                map(SVNErrorCode.IO_ERROR, "premature eof while reading response").code());
    }

    @Test
    public void pathPreservedOnMappedException() {
        StorageException result = map(SVNErrorCode.FS_NOT_FOUND, "missing");
        assertEquals("/a/b", result.path());
    }
}
