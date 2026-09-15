package com.alianga.jkit.notify;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link SendResult#aggregate} 的失败类别聚合：本地抑制（SUPPRESSED）不应盖过平台错误。
 */
public class SendResultTest {

    @Test
    public void suppressedDoesNotOutrankRealFailures() {
        SendResult suppressed = SendResult.fail("a", "quiet hours", FailureType.SUPPRESSED);
        SendResult throttled = SendResult.fail("b", "rate limited", FailureType.THROTTLED);
        SendResult merged = SendResult.aggregate("all", Arrays.asList(suppressed, throttled));
        assertEquals(FailureType.THROTTLED, merged.failureType());

        SendResult retryable = SendResult.fail("c", "io error", FailureType.RETRYABLE);
        merged = SendResult.aggregate("all", Arrays.asList(suppressed, retryable));
        assertEquals(FailureType.RETRYABLE, merged.failureType());
    }

    @Test
    public void suppressedAloneKeepsSuppressedType() {
        SendResult suppressed = SendResult.fail("a", "quiet hours", FailureType.SUPPRESSED);
        SendResult merged = SendResult.aggregate("all",
                java.util.Collections.singletonList(suppressed));
        assertTrue(merged.isFailed());
        assertEquals(FailureType.SUPPRESSED, merged.failureType());
    }

    @Test
    public void permanentStillOutranksOthers() {
        SendResult permanent = SendResult.fail("a", "bad request", FailureType.PERMANENT);
        SendResult config = SendResult.fail("b", "bad key", FailureType.CONFIG_ERROR);
        SendResult merged = SendResult.aggregate("all", Arrays.asList(permanent, config));
        assertEquals(FailureType.PERMANENT, merged.failureType());
    }
}
