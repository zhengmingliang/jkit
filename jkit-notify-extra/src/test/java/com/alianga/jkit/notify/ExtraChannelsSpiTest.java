package com.alianga.jkit.notify;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 确认 jkit-notify-extra 的 SPI 渠道会在依赖本模块时自动注册。
 */
public class ExtraChannelsSpiTest {

    @Test
    public void extraChannelsRegisteredViaSpi() {
        NotificationManager manager = NotificationManager.get();
        for (String id : new String[]{
                "slack", "telegram", "ntfy",
                "sms-aliyun", "sms-tencent", "sms-yunpian", "sms-huawei"}) {
            assertNotNull("missing channel via SPI: " + id + " in " + manager.list(),
                    manager.get(id));
        }
        assertTrue(manager.list().size() >= 7);
    }
}
