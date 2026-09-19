package com.alianga.jkit.json;

import com.alianga.jkit.json.exceptions.JSONPatchException;
import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * JSON Patch（RFC 6902）回归测试。
 */
public class JSONPatchTest {

    private void assertPatch(String documentJson, String patchJson, String expectedJson) {
        String actual = JSONPatch.apply(documentJson, patchJson);
        assertEquals(JSON.toJsonString(JSON.parse(expectedJson)), actual);
    }

    private void assertThrows(String documentJson, String patchJson) {
        Assert.assertThrows(JSONPatchException.class, () -> JSONPatch.apply(documentJson, patchJson));
    }

    @Test
    public void addNewKey() {
        assertPatch("{\"a\":1}", "[{\"op\":\"add\",\"path\":\"/b\",\"value\":2}]", "{\"a\":1,\"b\":2}");
    }

    @Test
    public void addNestedKey() {
        assertPatch("{\"a\":{\"b\":1}}", "[{\"op\":\"add\",\"path\":\"/a/c\",\"value\":3}]",
                "{\"a\":{\"b\":1,\"c\":3}}");
    }

    @Test
    public void addIntoArrayMiddle() {
        assertPatch("{\"a\":[1,2,3]}", "[{\"op\":\"add\",\"path\":\"/a/1\",\"value\":9}]",
                "{\"a\":[1,9,2,3]}");
    }

    @Test
    public void addArrayEndToken() {
        assertPatch("{\"a\":[1,2,3]}", "[{\"op\":\"add\",\"path\":\"/a/-\",\"value\":4}]",
                "{\"a\":[1,2,3,4]}");
    }

    @Test
    public void addArrayAtIndexEqualToSize() {
        assertPatch("{\"a\":[1,2,3]}", "[{\"op\":\"add\",\"path\":\"/a/3\",\"value\":4}]",
                "{\"a\":[1,2,3,4]}");
    }

    @Test
    public void addReplacesRoot() {
        assertPatch("{\"a\":1}", "[{\"op\":\"add\",\"path\":\"\",\"value\":{\"x\":9}}]", "{\"x\":9}");
    }

    @Test
    public void removeKey() {
        assertPatch("{\"a\":1,\"b\":2}", "[{\"op\":\"remove\",\"path\":\"/a\"}]", "{\"b\":2}");
    }

    @Test
    public void removeArrayIndexShifts() {
        assertPatch("[1,2,3]", "[{\"op\":\"remove\",\"path\":\"/0\"}]", "[2,3]");
    }

    @Test
    public void replaceKey() {
        assertPatch("{\"a\":1}", "[{\"op\":\"replace\",\"path\":\"/a\",\"value\":10}]", "{\"a\":10}");
    }

    @Test
    public void replaceRoot() {
        assertPatch("{\"a\":1}", "[{\"op\":\"replace\",\"path\":\"\",\"value\":[1,2]}]", "[1,2]");
    }

    @Test
    public void replaceMissingKeyFails() {
        assertThrows("{\"a\":1}", "[{\"op\":\"replace\",\"path\":\"/z\",\"value\":1}]");
    }

    @Test
    public void removeRootFails() {
        assertThrows("{\"a\":1}", "[{\"op\":\"remove\",\"path\":\"\"}]");
    }

    @Test
    public void addToScalarFails() {
        assertThrows("{\"a\":1}", "[{\"op\":\"add\",\"path\":\"/a/b\",\"value\":1}]");
    }

    @Test
    public void moveObject() {
        assertPatch("{\"a\":{\"x\":1},\"b\":{}}",
                "[{\"op\":\"move\",\"from\":\"/a\",\"path\":\"/b/c\"}]", "{\"b\":{\"c\":{\"x\":1}}}");
    }

    @Test
    public void moveArrayElement() {
        assertPatch("[1,2,3]", "[{\"op\":\"move\",\"from\":\"/0\",\"path\":\"/2\"}]", "[2,3,1]");
    }

    @Test
    public void copyObject() {
        assertPatch("{\"a\":1}", "[{\"op\":\"copy\",\"from\":\"/a\",\"path\":\"/b\"}]",
                "{\"a\":1,\"b\":1}");
    }

    @Test
    public void copyArrayIsIndependent() {
        String result = JSONPatch.apply("{\"a\":[1,2]}",
                "[{\"op\":\"copy\",\"from\":\"/a\",\"path\":\"/b\"}]");
        assertEquals(JSON.toJsonString(JSON.parse("{\"a\":[1,2],\"b\":[1,2]}")), result);
    }

    @Test
    public void testSuccess() {
        assertPatch("{\"a\":1}", "[{\"op\":\"test\",\"path\":\"/a\",\"value\":1}]", "{\"a\":1}");
    }

    @Test
    public void testFailureThrows() {
        assertThrows("{\"a\":1}", "[{\"op\":\"test\",\"path\":\"/a\",\"value\":2}]");
    }

    @Test
    public void testNullValue() {
        assertPatch("{\"a\":null}", "[{\"op\":\"test\",\"path\":\"/a\",\"value\":null}]", "{\"a\":null}");
    }

    @Test
    public void testNumericEqualityIntVsDouble() {
        assertPatch("{\"a\":1}", "[{\"op\":\"test\",\"path\":\"/a\",\"value\":1.0}]", "{\"a\":1}");
    }

    @Test
    public void pointerEscapeSlash() {
        assertPatch("{\"a/b\":1}", "[{\"op\":\"test\",\"path\":\"/a~1b\",\"value\":1}]", "{\"a/b\":1}");
    }

    @Test
    public void pointerEscapeTilde() {
        assertPatch("{\"a~b\":1}", "[{\"op\":\"test\",\"path\":\"/a~0b\",\"value\":1}]", "{\"a~b\":1}");
    }

    @Test
    public void addEscapedKey() {
        assertPatch("{\"a/b\":{\"x\":1}}", "[{\"op\":\"add\",\"path\":\"/a~1b/y\",\"value\":2}]",
                "{\"a/b\":{\"x\":1,\"y\":2}}");
    }

    @Test
    public void invalidArrayIndexOnArrayFails() {
        assertThrows("[1,2]", "[{\"op\":\"add\",\"path\":\"/x\",\"value\":1}]");
    }

    @Test
    public void arrayIndexOutOfRangeFails() {
        assertThrows("[1,2]", "[{\"op\":\"add\",\"path\":\"/5\",\"value\":1}]");
    }

    @Test
    public void missingOpFails() {
        assertThrows("{\"a\":1}", "[{\"path\":\"/b\",\"value\":2}]");
    }

    @Test
    public void unknownOpFails() {
        assertThrows("{\"a\":1}", "[{\"op\":\"frobnicate\",\"path\":\"/b\",\"value\":2}]");
    }

    @Test
    public void nonArrayPatchFails() {
        assertThrows("{\"a\":1}", "{\"op\":\"add\",\"path\":\"/b\",\"value\":2}");
    }

    @Test
    public void missingValueFails() {
        assertThrows("{\"a\":1}", "[{\"op\":\"add\",\"path\":\"/b\"}]");
    }

    @Test
    public void moveWithoutFromFails() {
        assertThrows("{\"a\":1}", "[{\"op\":\"move\",\"path\":\"/b\"}]");
    }

    @Test
    public void multipleOperationsInOrder() {
        assertPatch("{\"a\":1,\"list\":[1,2]}",
                "[{\"op\":\"add\",\"path\":\"/b\",\"value\":2},"
                        + "{\"op\":\"remove\",\"path\":\"/a\"},"
                        + "{\"op\":\"add\",\"path\":\"/list/-\",\"value\":3}]",
                "{\"list\":[1,2,3],\"b\":2}");
    }

    // ------------------------------------------------------------------
    // 修复回归：test 操作的数值比较此前走 doubleValue()，>2^53 的整数会截断产生假相等，
    // 乐观锁场景会在前置条件不成立时误放行
    // ------------------------------------------------------------------

    @Test
    public void testOpComparesLargeLongExactly() {
        assertPatch("{\"a\":9007199254740993}",
                "[{\"op\":\"test\",\"path\":\"/a\",\"value\":9007199254740993}]",
                "{\"a\":9007199254740993}");
        assertThrows("{\"a\":9007199254740993}",
                "[{\"op\":\"test\",\"path\":\"/a\",\"value\":9007199254740992}]");
    }

    @Test
    public void testOpComparesBigIntegerExactly() {
        String big = "123456789012345678901234567890";
        String bigPlusOne = "123456789012345678901234567891";
        assertPatch("{\"a\":" + big + "}",
                "[{\"op\":\"test\",\"path\":\"/a\",\"value\":" + big + "}]",
                "{\"a\":" + big + "}");
        assertThrows("{\"a\":" + big + "}",
                "[{\"op\":\"test\",\"path\":\"/a\",\"value\":" + bigPlusOne + "}]");
    }

    @Test
    public void testOpStillTreatsIntAndDoubleAsEqual() {
        // 数值语义相等仍应通过：1 与 1.0
        assertPatch("{\"a\":1}",
                "[{\"op\":\"test\",\"path\":\"/a\",\"value\":1.0}]",
                "{\"a\":1}");
        assertPatch("{\"a\":0.5}",
                "[{\"op\":\"test\",\"path\":\"/a\",\"value\":0.5}]",
                "{\"a\":0.5}");
    }

    @Test
    public void arrayIndexWithLeadingZeroOrSignRejected() {
        // RFC 6901 的 array-index 不允许前导零（"0" 除外）与 '+'，此前 Integer.parseInt 宽松放行
        assertThrows("{\"a\":[1,2]}", "[{\"op\":\"remove\",\"path\":\"/a/01\"}]");
        assertThrows("{\"a\":[1,2]}", "[{\"op\":\"remove\",\"path\":\"/a/+1\"}]");
        assertThrows("{\"a\":[1,2]}", "[{\"op\":\"add\",\"path\":\"/a/01\",\"value\":3}]");
        // 合法形式不受影响
        assertPatch("{\"a\":[1,2]}", "[{\"op\":\"remove\",\"path\":\"/a/0\"}]", "{\"a\":[2]}");
    }
}
