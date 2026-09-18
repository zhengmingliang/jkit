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
}
