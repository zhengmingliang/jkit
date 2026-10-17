package com.alianga.jkit.storage.engine;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

/**
 * SFTP v3 longname 属主 / 组解析单测：OpenSSH 风格给名字，
 * copyparty / paramiko 风格给数字 id，非 Unix 行不解析。
 *
 * @author 郑明亮
 */
public class SftpLongNameOwnerTest {

    @Test
    public void openSshStyleNames() {
        assertArrayEquals(new String[]{"alice", "staff"},
                SftpFileStorage.parseLongNameOwner(
                        "-rw-r--r-- 1 alice staff 1024 Oct 17 08:53 a.txt"));
    }

    @Test
    public void numericIdsFromCopyparty() {
        assertArrayEquals(new String[]{"1000", "1000"},
                SftpFileStorage.parseLongNameOwner(
                        "-rw-rw-r-- 1 1000 1000 5 17 Oct 16:53 a.txt"));
        assertArrayEquals(new String[]{"0", "0"},
                SftpFileStorage.parseLongNameOwner(
                        "drwxr-xr-x 3 0 0 4096 Oct 17 08:53 docs"));
    }

    @Test
    public void nonUnixLinesRejected() {
        assertNull(SftpFileStorage.parseLongNameOwner(null));
        assertNull(SftpFileStorage.parseLongNameOwner("total 24"));
        assertNull(SftpFileStorage.parseLongNameOwner("-rw-r--r-- 1 alice"));
    }
}
