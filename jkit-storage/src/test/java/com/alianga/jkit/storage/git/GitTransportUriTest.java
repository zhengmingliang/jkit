package com.alianga.jkit.storage.git;

import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;

import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Git 传输层的 URL 解析不得把令牌泄进异常消息。
 *
 * <p>历史 bug：URI.create 遇未编码字符抛未受检 IAE，消息内嵌完整 URL——Gitee 的
 * access_token 在查询参数里（平台限制），文件名含空格即触发，令牌随异常一路上抛
 * 进 UI 弹窗与任务日志。回归锁：含空格 + access_token 的 URL 必须翻译成
 * INVALID_CONFIG 的 StorageException，消息不含令牌、不含完整 URL。</p>
 *
 * @author 郑明亮
 */
public class GitTransportUriTest {
    @Test
    public void validUrlParses() {
        URI uri = GitTransport.uri("https://gitee.com/o/r/raw/main/a.txt?access_token=tok", "a.txt");
        assertEquals("https", uri.getScheme());
        assertEquals("gitee.com", uri.getHost());
    }

    @Test
    public void illegalCharacterUrlBecomesStorageExceptionWithoutToken() {
        // 文件名含空格：URI.create 会抛 IAE，消息里带完整 URL（含 access_token）
        String url = "https://gitee.com/o/r/raw/main/a b.txt?access_token=SUPERSECRET";
        StorageException e = assertThrows(StorageException.class,
                () -> GitTransport.uri(url, "a b.txt"));
        assertEquals("非法 URL 归 INVALID_CONFIG", StorageErrorCode.INVALID_CONFIG, e.code());
        String message = e.getMessage();
        assertFalse("令牌绝不能进异常消息：" + message, message.contains("SUPERSECRET"));
        assertFalse("access_token 参数名也不该出现：" + message, message.contains("access_token"));
        assertFalse("不能回显完整 URL：" + message, message.contains("a b.txt?"));
        assertTrue("要告诉用户是非法字符问题", message.contains("非法字符"));
        assertEquals("逻辑路径保留", "a b.txt", e.path());
        assertTrue("原始 IAE 作为 cause 保留供排障", e.getCause() instanceof IllegalArgumentException);
    }

    @Test
    public void skeletonStripsQueryAndFileName() {
        assertEquals("https://gitee.com/o/r/raw/main/…",
                GitTransport.skeleton("https://gitee.com/o/r/raw/main/a b.txt?access_token=tok"));
        assertEquals("https://gitee.com/o/r/raw/main/…",
                GitTransport.skeleton("https://gitee.com/o/r/raw/main/a b.txt"));
        assertEquals("https://gitee.com", GitTransport.skeleton("https://gitee.com"));
        assertEquals("", GitTransport.skeleton(null));
    }

    @Test
    public void skeletonNeverContainsToken() {
        String skeleton = GitTransport.skeleton("https://x.com/a/b/c.txt?access_token=S3CRET-T0KEN");
        assertFalse(skeleton.contains("S3CRET"));
        assertFalse(skeleton.contains("?"));
    }
}
