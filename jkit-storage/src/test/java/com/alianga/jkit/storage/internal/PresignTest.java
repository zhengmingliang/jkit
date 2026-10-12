package com.alianga.jkit.storage.internal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 预签名查询串：SigV4 必含要素齐全且 credential 已编码、SigV2 三段参数齐全。
 *
 * @author 郑明亮
 */
public class PresignTest {

    @Test
    public void sigV4QueryContainsRequiredParts() {
        String query = AwsSigners.sigV4PresignQuery("GET", "bucket.s3.us-east-1.amazonaws.com",
                "/a/b.txt", "AKID", "secret", "us-east-1", 3600, "20261012T120000Z");
        assertTrue(query.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
        assertTrue(query.contains("X-Amz-Credential=AKID%2F20261012%2Fus-east-1%2Fs3%2Faws4_request"));
        assertTrue(query.contains("X-Amz-Date=20261012T120000Z"));
        assertTrue(query.contains("X-Amz-Expires=3600"));
        assertTrue(query.contains("X-Amz-SignedHeaders=host"));
        assertTrue(query.contains("X-Amz-Signature="));
    }

    @Test
    public void sigV2QueryShape() {
        String query = AwsSigners.sigV2PresignQuery("AKID", "secret", "GET",
                1780000000L, "bucket", "a/b.txt");
        assertTrue(query.startsWith("AWSAccessKeyId=AKID&Expires=1780000000&Signature="));
    }

    @Test
    public void sigV4SignatureIsStable() {
        String first = AwsSigners.sigV4PresignQuery("GET", "h", "/k", "AK", "SK",
                "r", 100, "20261012T000000Z");
        String second = AwsSigners.sigV4PresignQuery("GET", "h", "/k", "AK", "SK",
                "r", 100, "20261012T000000Z");
        assertEquals(first, second);
    }
}
