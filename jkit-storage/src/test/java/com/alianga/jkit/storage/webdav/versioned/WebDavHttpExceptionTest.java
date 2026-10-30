package com.alianga.jkit.storage.webdav.versioned;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * WebDavHttpException 暴露 HTTP 状态；RemoteStore 在 4xx/5xx 时抛出。
 *
 * @author 郑明亮
 */
public class WebDavHttpExceptionTest {

    private FakeWebDavServer fake;

    @Before
    public void start() throws Exception {
        fake = FakeWebDavServer.startDefault();
        fake.setMode(FakeWebDavServer.Mode.STRICT_412);
    }

    @After
    public void stop() {
        if (fake != null) {
            fake.close();
        }
    }

    @Test
    public void statusAccessorPreserved() {
        WebDavHttpException ex = new WebDavHttpException(401, "PROPFIND failed: HTTP 401", "deny");
        assertEquals(401, ex.status());
        assertEquals("deny", ex.responseBody());
        assertTrue(ex.getMessage().contains("401"));
    }

    @Test
    public void listThrowsHttpExceptionOn401() throws Exception {
        WebDavEndpoint bad = new WebDavEndpoint(
                "http://127.0.0.1:" + fake.port() + "/", "alice", "wrong", "backups");
        WebDavRemoteStore store = new WebDavRemoteStore(bad);
        try {
            store.list();
            fail("expected WebDavHttpException");
        } catch (WebDavHttpException e) {
            assertEquals(401, e.status());
            assertTrue(e.getMessage().contains("401"));
        }
    }
}
