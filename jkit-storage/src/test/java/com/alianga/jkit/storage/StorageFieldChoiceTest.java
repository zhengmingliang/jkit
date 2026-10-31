package com.alianga.jkit.storage;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 枚举选项字段：选项声明归引擎/预设，界面零硬编码渲染下拉。
 *
 * @author 郑明亮
 */
public class StorageFieldChoiceTest {

    @Test
    public void choiceFieldCarriesOptionsAndEditableFlag() {
        StorageField field = StorageField.choice("commitMode", "提交信息", "auto", "提示", false,
                new StorageField.Option("auto", "自动"),
                new StorageField.Option("manual", "手动"));
        assertTrue(field.isChoice());
        assertFalse(field.isChoiceEditable());
        assertEquals(2, field.options().size());
        assertEquals("auto", field.options().get(0).value());
        assertEquals("自动", field.options().get(0).label());
        assertEquals("manual", field.options().get(1).value());
        assertEquals("auto", field.defaultValue());
    }

    @Test
    public void editableChoiceAllowsFreeInput() {
        StorageField field = StorageField.choice("cdnBase", "CDN 加速域名", "", "提示", true,
                new StorageField.Option("https://cdn.jsdelivr.net", "jsDelivr"),
                new StorageField.Option("", "不启用（留空）"));
        assertTrue(field.isChoice());
        assertTrue(field.isChoiceEditable());
        List<StorageField.Option> options = field.options();
        try {
            options.add(new StorageField.Option("x", "x"));
            assertFalse("选项清单应不可变", options.size() == 2);
        } catch (UnsupportedOperationException expected) {
            // 只读清单
        }
    }

    @Test
    public void legacyFieldsAreNotChoices() {
        assertFalse(StorageField.optional("host", "主机").isChoice());
        assertFalse(StorageField.required("bucket", "桶").isChoice());
        assertFalse(StorageField.secret("password", "密码", true).isChoice());
        assertTrue(StorageField.optional("host", "主机").options().isEmpty());
    }

    @Test
    public void builtinPresetsDeclareChoices() {
        // 多吉云桶类型：非可编辑下拉
        StoragePlatform doge = FileStorages.platform("doge");
        StorageField bucketType = fieldOf(doge, "bucketType");
        assertTrue(bucketType.isChoice());
        assertFalse(bucketType.isChoiceEditable());
        assertEquals(2, bucketType.options().size());
        assertEquals("paid", bucketType.options().get(0).value());

        // Git 提交信息：非可编辑；CDN 域名：可编辑快捷下拉
        StoragePlatform github = FileStorages.platform("git-github");
        assertTrue(fieldOf(github, "commitMode").isChoice());
        assertFalse(fieldOf(github, "commitMode").isChoiceEditable());
        StorageField cdnBase = fieldOf(github, "cdnBase");
        assertTrue(cdnBase.isChoice());
        assertTrue(cdnBase.isChoiceEditable());
        assertEquals(4, cdnBase.options().size());

        // Gitee 没有 CDN 字段（平台差异声明仍在引擎侧）
        for (StorageField field : FileStorages.platform("git-gitee").fields()) {
            assertFalse("Gitee 不应有 CDN 字段", field.key().equals("cdnBase"));
        }
    }

    private static StorageField fieldOf(StoragePlatform platform, String key) {
        for (StorageField field : platform.fields()) {
            if (field.key().equals(key)) {
                return field;
            }
        }
        throw new AssertionError("no field " + key + " on " + platform.id());
    }
}
