package com.alianga.jkit.mock;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

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
    public void testCountLimit() {
        MockDataProducer producer = new MockDataProducer();
        producer.addField(MockFieldType.NAME);
        producer.setCount(0);
        Assert.assertEquals(1, producer.generate().size());

        producer.setCount(20000);
        Assert.assertEquals(10000, producer.generate().size());
    }
}
