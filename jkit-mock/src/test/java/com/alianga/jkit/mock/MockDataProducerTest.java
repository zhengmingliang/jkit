package com.alianga.jkit.mock;

import com.alianga.jkit.IdCardUtils;
import com.alianga.jkit.IdCardUtils.IdCardInfo;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MockDataProducer 单元测试。
 *
 * @author 郑明亮
 */
public class MockDataProducerTest {

    @Test
    public void testGenerateUserTemplate() {
        MockDataProducer producer = new MockDataProducer();
        producer.addFields(MockTemplate.USER.getFields());
        producer.setCount(5);
        List<Map<String, Object>> records = producer.generate();
        Assert.assertEquals(5, records.size());
        for (Map<String, Object> record : records) {
            Assert.assertTrue(record.containsKey("name"));
            Assert.assertTrue(record.containsKey("email"));
            Assert.assertTrue(record.containsKey("phone"));
            Assert.assertTrue(record.containsKey("gender"));
            Assert.assertTrue(record.containsKey("age"));
            Assert.assertTrue(record.containsKey("address"));
        }
    }

    @Test
    public void testFormatJson() {
        MockDataProducer producer = new MockDataProducer();
        producer.addField(MockFieldType.NAME);
        producer.addField(MockFieldType.AGE);
        producer.setCount(2);
        String json = producer.format(MockOutputFormat.JSON);
        Assert.assertNotNull(json);
        Assert.assertTrue(json.contains("["));
        Assert.assertTrue(json.contains("]"));
        Assert.assertTrue(json.contains("\"name\""));
        Assert.assertTrue(json.contains("\"age\""));
    }

    @Test
    public void testFormatSql() {
        MockDataProducer producer = new MockDataProducer();
        producer.addField(MockFieldType.NAME);
        producer.setCount(2);
        String sql = producer.format(MockOutputFormat.SQL);
        Assert.assertNotNull(sql);
        Assert.assertTrue(sql.contains("CREATE TABLE fake_data"));
        Assert.assertTrue(sql.contains("INSERT INTO fake_data"));
    }

    @Test
    public void testFormatCsv() {
        MockDataProducer producer = new MockDataProducer();
        producer.addField(MockFieldType.NAME);
        producer.setCount(3);
        String csv = producer.format(MockOutputFormat.CSV);
        Assert.assertNotNull(csv);
        String[] lines = csv.split("\n");
        Assert.assertEquals(4, lines.length);
        Assert.assertEquals("name", lines[0]);
    }

    @Test
    public void testCustomField() {
        MockDataProducer producer = new MockDataProducer();
        producer.addCustomField(new MockCustomField("score", MockCustomFieldType.NUMBER, ""));
        producer.setCount(2);
        List<Map<String, Object>> records = producer.generate();
        Assert.assertEquals(2, records.size());
        for (Map<String, Object> record : records) {
            Object score = record.get("score");
            Assert.assertTrue(score instanceof Number);
        }
    }

    @Test
    public void identityFieldsStayConsistent() {
        MockDataProducer producer = new MockDataProducer();
        producer.addField(MockFieldType.GENDER);
        producer.addField(MockFieldType.BIRTHDAY);
        producer.addField(MockFieldType.AGE);
        producer.addField(MockFieldType.ID_CARD);
        producer.addField(MockFieldType.ADDRESS);
        producer.setCount(40);
        List<Map<String, Object>> records = producer.generate();
        Set<String> ids = new HashSet<String>();
        for (Map<String, Object> record : records) {
            String id = (String) record.get("idCard");
            ids.add(id);
            IdCardInfo info = IdCardUtils.parse(id);
            Assert.assertNotNull(id, info);
            Assert.assertEquals(info.getGender(), record.get("gender"));
            Assert.assertEquals(info.getBirthdayString(), record.get("birthday"));
            Assert.assertEquals(info.getAge(), ((Number) record.get("age")).intValue());
            int digit = id.charAt(16) - '0';
            Assert.assertEquals("男".equals(info.getGender()) ? 1 : 0, digit % 2);
            String address = (String) record.get("address");
            if (info.getProvince() != null) {
                Assert.assertTrue(address, address.contains(info.getProvince()));
            }
            if (info.getCity() != null) {
                Assert.assertTrue(address, address.contains(info.getCity()));
            }
            if (info.getDistrict() != null) {
                Assert.assertTrue(address, address.contains(info.getDistrict()));
            }
            Assert.assertFalse(address, address.contains("黑龙江省天津"));
            Assert.assertFalse(address, address.contains("香港特别行政区南昌"));
        }
        Assert.assertTrue(ids.size() > 1);
    }

    @Test
    public void jsonObjectsUseTwoSpaceIndent() {
        MockDataProducer producer = new MockDataProducer();
        producer.addField(MockFieldType.NAME);
        producer.setCount(2);
        String json = producer.format(MockOutputFormat.JSON);
        Assert.assertTrue(json, json.startsWith("[\n  {"));
        Assert.assertTrue(json, json.contains("\n    \"name\": "));
        Assert.assertTrue(json, json.contains("\n  },\n  {"));
        Assert.assertTrue(json, json.endsWith("\n  }\n]"));
        Assert.assertFalse(json, json.contains("\n{"));
    }

    @Test
    public void testCountLimit() {
        MockDataProducer producer = new MockDataProducer();
        producer.addField(MockFieldType.NAME);
        producer.setCount(0);
        Assert.assertEquals(1, producer.generate().size());

        producer.setCount(20000);
        Assert.assertEquals(10000, producer.generate().size());
    }
}
