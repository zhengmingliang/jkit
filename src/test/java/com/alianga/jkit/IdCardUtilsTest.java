package com.alianga.jkit;

import com.alianga.jkit.IdCardUtils.IdCardInfo;
import com.alianga.jkit.json.JSON;
import org.junit.Test;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IdCardUtilsTest {

    @Test
    public void generatedIdShouldBeValidAndParseable() {
        for (int i = 0; i < 20; i++) {
            String id = IdCardUtils.generate(20, 40);
            assertTrue(id, IdCardUtils.isValid(id));
            IdCardInfo info = IdCardUtils.parse(id);
            assertNotNull(id, info);
            assertEquals(id, info.getIdNumber());
            assertEquals(6, info.getAreaCode().length());
            assertNotNull(info.getIssuePlace());
            assertFalse(info.getIssuePlace().isEmpty());
            assertNotNull(info.getBirthday());
            assertNotNull(info.getBirthdayString());
            assertTrue(info.getAge() >= 19 && info.getAge() <= 41);
            assertTrue("男".equals(info.getGender()) || "女".equals(info.getGender()));
            assertEquals("男".equals(info.getGender()), info.isMale());
        }
    }

    @Test
    public void parseShouldExtractBeijingMale() {
        String id = id18("110101", "19900101", "001");
        IdCardInfo info = IdCardUtils.parse(id);
        assertNotNull(info);
        assertEquals("110101", info.getAreaCode());
        assertEquals("北京市", info.getProvince());
        assertEquals("东城区", info.getDistrict());
        assertTrue(info.getIssuePlace().contains("北京"));
        assertTrue(info.getIssuePlace().contains("东城区"));
        assertEquals("1990-01-01", info.getBirthdayString());
        assertEquals("男", info.getGender());
        assertTrue(info.isMale());
        assertEquals(expectedAge(1990, 1, 1), info.getAge());
    }

    @Test
    public void parseShouldExtractFemale() {
        String id = id18("110101", "19920308", "002");
        IdCardInfo info = IdCardUtils.parse(id);
        assertNotNull(info);
        assertEquals("女", info.getGender());
        assertFalse(info.isMale());
        assertEquals("1992-03-08", info.getBirthdayString());
    }

    @Test
    public void parseShouldUpgradeFifteenDigitId() {
        String fifteen = "110101900101001";
        assertTrue(IdCardUtils.isValid(fifteen));
        IdCardInfo info = IdCardUtils.parse(fifteen);
        assertNotNull(info);
        assertEquals(18, info.getIdNumber().length());
        assertEquals("110101", info.getAreaCode());
        assertEquals("1990-01-01", info.getBirthdayString());
        assertEquals("男", info.getGender());
        assertEquals(fifteen.substring(0, 6) + "19" + fifteen.substring(6),
                info.getIdNumber().substring(0, 17));
    }

    @Test
    public void parseShouldAcceptLowercaseCheckDigitX() {
        String withX = idEndingWithX();
        assertNotNull(withX);
        assertTrue(IdCardUtils.isValid(withX));
        assertTrue(IdCardUtils.isValid(withX.substring(0, 17) + "x"));
        IdCardInfo info = IdCardUtils.parse(withX.substring(0, 17) + "x");
        assertNotNull(info);
        assertTrue(info.getIdNumber().endsWith("X"));
    }

    @Test
    public void isValidShouldRejectIllegalValues() {
        assertFalse(IdCardUtils.isValid(null));
        assertFalse(IdCardUtils.isValid(""));
        assertFalse(IdCardUtils.isValid("123"));
        assertFalse(IdCardUtils.isValid("11010119900101123A"));
        String valid = id18("110101", "19900101", "001");
        char wrong = valid.charAt(17) == '0' ? '1' : '0';
        assertFalse(IdCardUtils.isValid(valid.substring(0, 17) + wrong));
        assertNull(IdCardUtils.parse("110101199002311234"));
        assertNull(IdCardUtils.parse("110101180002281234"));
    }

    @Test
    public void ageShouldDecreaseWhenBirthdayNotReached() {
        Calendar today = Calendar.getInstance();
        Calendar birthday = (Calendar) today.clone();
        birthday.add(Calendar.YEAR, -30);
        birthday.add(Calendar.DAY_OF_MONTH, 1);
        String ymd = String.format("%04d%02d%02d",
                birthday.get(Calendar.YEAR),
                birthday.get(Calendar.MONTH) + 1,
                birthday.get(Calendar.DAY_OF_MONTH));
        IdCardInfo info = IdCardUtils.parse(id18("110101", ymd, "001"));
        assertNotNull(info);
        assertEquals(29, info.getAge());
    }

    @Test
    public void parseShouldResolveHistoricalPrefectureAndCounty() {
        IdCardInfo xuanhan = IdCardUtils.parse("513022199705053543");
        assertNotNull(xuanhan);
        assertEquals("四川省", xuanhan.getProvince());
        assertEquals("达川地区", xuanhan.getCity());
        assertEquals("宣汉县", xuanhan.getDistrict());
        assertEquals("四川省达川地区宣汉县", xuanhan.getIssuePlace());

        IdCardInfo shenqiu = IdCardUtils.parse("412728199903024921");
        assertNotNull(shenqiu);
        assertEquals("河南省", shenqiu.getProvince());
        assertEquals("周口地区", shenqiu.getCity());
        assertEquals("沈丘县", shenqiu.getDistrict());
        assertEquals("河南省周口地区沈丘县", shenqiu.getIssuePlace());
    }

    @Test
    public void parseShouldResolveCurrentAndMunicipalityAreas() {
        assertEquals("磐石市", IdCardUtils.parse("220284199803071129").getDistrict());
        assertEquals("吉林市", IdCardUtils.parse("220284199803071129").getCity());
        assertEquals("雨花区", IdCardUtils.parse("430111199806122150").getDistrict());
        assertEquals("长沙市", IdCardUtils.parse("430111199806122150").getCity());
        assertEquals("开县", IdCardUtils.parse("500234199808018099").getDistrict());
        assertEquals("重庆市", IdCardUtils.parse("500234199808018099").getProvince());
        assertEquals("建水县", IdCardUtils.parse("532524199706110928").getDistrict());
        assertEquals("红河哈尼族彝族自治州", IdCardUtils.parse("532524199706110928").getCity());
        assertEquals("吴起县", IdCardUtils.parse("61062619980125044X").getDistrict());
        assertEquals("延安市", IdCardUtils.parse("61062619980125044X").getCity());
        assertEquals("焉耆回族自治县", IdCardUtils.parse("652826199810063224").getDistrict());
        assertEquals("青州市", IdCardUtils.parse("370781199804285365").getDistrict());
        assertEquals("孙吴县", IdCardUtils.parse("231124199808130221").getDistrict());
        assertEquals("松滋市", IdCardUtils.parse("421087199610103725").getDistrict());
        assertEquals("荆州市", IdCardUtils.parse("421087199610103725").getCity());
    }

    @Test
    public void deprecatedGeneratorStillWorks() {
        String id = IdCardGenerator.generate(20, 30);
        assertTrue(IdCardGenerator.isValid(id));
        assertNotNull(IdCardGenerator.parse(id));
        assertEquals("东城区", IdCardUtils.getAreaName(110101));
        assertTrue(IdCardUtils.getAreaNames().size() > 4000);
    }

    private static int expectedAge(int year, int month, int day) {
        Calendar today = Calendar.getInstance();
        int age = today.get(Calendar.YEAR) - year;
        int monthDelta = today.get(Calendar.MONTH) + 1 - month;
        if (monthDelta < 0 || (monthDelta == 0 && today.get(Calendar.DAY_OF_MONTH) < day)) {
            age--;
        }
        return age;
    }

    private static String id18(String area, String yyyyMMdd, String seq) {
        String body = area + yyyyMMdd + seq;
        return body + IdCardUtils.calcTrailingNumber(body.toCharArray());
    }

    private static String idEndingWithX() {
        for (int seq = 0; seq < 1000; seq++) {
            String body = "11010119900101" + String.format("%03d", seq);
            char check = IdCardUtils.calcTrailingNumber(body.toCharArray());
            if (check == 'X') {
                return body + check;
            }
        }
        return null;
    }
    @Test
    public void validTest() throws MalformedURLException {
        String str = "220284199803071129,430111199806122150,513022199705053543,421087199610103725,412728199903024921," +
                "500234199808018099,532524199706110928,61062619980125044X,652826199810063224,370781199804285365,231124199808130221";
        for (String idNo : str.split(",")) {
            System.out.println(JSON.toJsonString(IdCardGenerator.parse(idNo)));
//            System.out.println(JSON.toPrettifyJsonString(IdCardGenerator.parse(idNo)));
        }

        URL url = new URL("https://qq.ip138.com/idsearch/index.asp?userid=532524199706110928&action=idcard");
        String host = url.getHost();
        String path = url.getPath();
        String query = url.getQuery();
        System.out.println("host: " + host);
        System.out.println("path: " + path);
        System.out.println("query: " + query);
    }
}
