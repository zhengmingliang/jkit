package com.alianga.jkit.notify;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ChannelConfig 地址拆分与 SMTP 附属字段。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class ChannelConfigTest {

    /**
     * {@code to("a,b")} / 分号分隔会拆成多个地址；多次调用累加。
     */
    @Test
    public void toSplitsCommaAndSemicolon() {
        ChannelConfig config = ChannelConfig.smtp("smtp.example.com", 465)
                .to("a@x.com, b@x.com")
                .to("c@x.com;d@x.com");
        assertEquals(Arrays.asList("a@x.com", "b@x.com", "c@x.com", "d@x.com"), config.to());
    }

    /**
     * cc / bcc / replyTo 能设置；列表整体覆盖。
     */
    @Test
    public void ccBccReplyTo() {
        ChannelConfig config = ChannelConfig.smtp("h", 25)
                .cc("cc1@x.com,cc2@x.com")
                .bcc(Arrays.asList("bcc@x.com"))
                .replyTo("reply@x.com")
                .fromName("机器人");
        assertEquals(Arrays.asList("cc1@x.com", "cc2@x.com"), config.cc());
        assertEquals(Arrays.asList("bcc@x.com"), config.bcc());
        assertEquals("reply@x.com", config.replyTo());
        assertEquals("机器人", config.fromName());
        assertFalse(config.autoSplit());
        config.autoSplit(true).maxAttachmentSize(1024).splitChunkSize(512);
        assertTrue(config.autoSplit());
        assertEquals(1024, config.resolvedMaxAttachmentSize());
        assertEquals(512, config.resolvedSplitChunkSize());

        ChannelConfig sized = ChannelConfig.smtp("h", 25)
                .maxAttachmentSize("10MB")
                .splitChunkSize("512KB");
        assertEquals(10L * 1024 * 1024, sized.resolvedMaxAttachmentSize());
        assertEquals(512L * 1024, sized.resolvedSplitChunkSize());
    }

    /**
     * {@code 10MB} / {@code 1.5K} / 纯数字都能解析。
     */
    @Test
    public void parseDataSizeUnits() {
        assertEquals(10L * 1024 * 1024, NotifyUtils.parseDataSize("10MB"));
        assertEquals(512L * 1024, NotifyUtils.parseDataSize("512 kb"));
        assertEquals(1536L, NotifyUtils.parseDataSize("1.5K"));
        assertEquals(42L, NotifyUtils.parseDataSize("42"));
    }
}
