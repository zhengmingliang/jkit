package com.alianga.jkit.mock;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邮箱生成用例：格式合法、指定域名生效、用户名形态覆盖、域名占比落在预期区间。
 *
 * @author 郑明亮
 * @since 2.0.4
 */
public class MockEmailTest {

    /** 邮箱字符集：用户名与域名都只含小写字母、数字、点与中划线。 */
    private static final Pattern MAIL = Pattern.compile("^[a-z0-9._-]+@[a-z0-9.-]+\\.[a-z]{2,}$");

    private static final int TIMES = 10000;

    @Test
    public void emailShouldBeWellFormed() {
        MockRandom random = new MockRandom();
        for (int i = 0; i < TIMES; i++) {
            String mail = random.email();
            Assert.assertTrue("非法邮箱：" + mail, MAIL.matcher(mail).matches());
            Assert.assertTrue("长度越界：" + mail, mail.length() <= 48);
        }
    }

    @Test
    public void emailShouldUseGivenDomain() {
        MockRandom random = new MockRandom();
        for (int i = 0; i < 200; i++) {
            String mail = random.email("alianga.com");
            Assert.assertTrue(mail, mail.endsWith("@alianga.com"));
        }
    }

    @Test
    public void emailShouldUseInvokeEntry() {
        MockRandom random = new MockRandom();
        for (int i = 0; i < 500; i++) {
            String mail = String.valueOf(random.invoke("email"));
            Assert.assertTrue("非法邮箱：" + mail, MAIL.matcher(mail).matches());
        }
    }

    @Test
    public void emailShouldCoverAllLocalStyles() {
        MockRandom random = new MockRandom();
        boolean dotted = false;
        boolean withYear = false;
        boolean withShortNumber = false;
        boolean pureLetter = false;
        for (int i = 0; i < TIMES; i++) {
            String local = localOf(random.email());
            if (local.contains(".")) {
                dotted = true;
            } else if (matchesYear(local)) {
                withYear = true;
            } else if (Character.isDigit(local.charAt(local.length() - 1))) {
                withShortNumber = true;
            } else {
                pureLetter = true;
            }
        }
        Assert.assertTrue("未出现带点的用户名", dotted);
        Assert.assertTrue("未出现带年份的用户名", withYear);
        Assert.assertTrue("未出现带短数字的用户名", withShortNumber);
        Assert.assertTrue("未出现纯字母用户名", pureLetter);
    }

    @Test
    public void emailDomainShouldMostlyBeProviders() {
        MockRandom random = new MockRandom();
        List<String> providers = new ArrayList<String>();
        int hitProvider = 0;
        int hitGmail = 0;
        for (String domain : MockDict.EMAIL_DOMAINS) {
            providers.add(domain);
        }
        for (int i = 0; i < TIMES; i++) {
            String domain = domainOf(random.email());
            if (providers.contains(domain)) {
                hitProvider++;
            }
            if ("gmail.com".equals(domain)) {
                hitGmail++;
            }
        }
        double providerRate = hitProvider * 1.0 / TIMES;
        double gmailRate = hitGmail * 1.0 / TIMES;
        Assert.assertTrue("服务商域名占比过低：" + providerRate, providerRate > 0.80);
        Assert.assertTrue("gmail 占比超出预期：" + gmailRate, gmailRate > 0.15 && gmailRate < 0.25);
    }

    @Test
    public void emailShouldComplyInTemplateMode() {
        MockJs js = new MockJs();
        String json = js.mockJson("{'list|50': [{'email': '@email'}]}");
        Matcher mailbox = Pattern.compile("\"email\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        int hit = 0;
        while (mailbox.find()) {
            String mail = mailbox.group(1);
            Assert.assertTrue("模板模式非法邮箱：" + mail, MAIL.matcher(mail).matches());
            hit++;
        }
        Assert.assertEquals("模板模式邮箱条数不符：" + json, 50, hit);
    }

    @Test
    public void fieldModeEmailShouldShareSameRule() {
        MockDataGenerator generator = new MockDataGenerator();
        for (int i = 0; i < TIMES; i++) {
            String mail = generator.generateEmail();
            Assert.assertTrue("非法邮箱：" + mail, MAIL.matcher(mail).matches());
        }
        List<Map<String, Object>> rows = new MockDataProducer()
                .addField(MockFieldType.EMAIL)
                .setCount(200)
                .generate();
        Assert.assertEquals(200, rows.size());
        for (Map<String, Object> row : rows) {
            String mail = String.valueOf(row.get("email"));
            Assert.assertTrue("非法邮箱：" + mail, MAIL.matcher(mail).matches());
        }
    }

    private static String localOf(String mail) {
        return mail.substring(0, mail.indexOf('@'));
    }

    private static String domainOf(String mail) {
        return mail.substring(mail.indexOf('@') + 1);
    }

    private static boolean matchesYear(String local) {
        if (local.length() < 4) {
            return false;
        }
        String tail = local.substring(local.length() - 4);
        if (!tail.matches("\\d{4}")) {
            return false;
        }
        int year = Integer.parseInt(tail);
        return year >= 1970 && year <= 2014;
    }
}
