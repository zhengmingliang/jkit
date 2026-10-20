package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.StorageException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * localStorage ticket 解码：+3 移位 base64、外层包装、fingerprinter 尾巴、错误不回显原值。
 *
 * @author 郑明亮
 */
public class QqMailTicketTest {
    private static final String INNER =
            "{\"s_uin\":\"10001\",\"sid\":\"SIDX\",\"pwd_key\":\"PKSECRET\",\"pwd_ticket_key\":\"PTKSECRET\"}";

    private static String outer(String fromType, String enc) {
        return "{\"uin\":\"10001\",\"sid\":\"SIDX\",\"ticketFromType\":\"" + fromType
                + "\",\"encryptTicket\":\"" + jsonEscape(enc) + "\"}";
    }

    /** shift 结果会含 \ 字符：嵌进 JSON 字面量必须按 JSON 规则转义（浏览器原样写盘即如此）。 */
    private static String jsonEscape(String enc) {
        StringBuilder sb = new StringBuilder(enc.length() + 4);
        for (int i = 0; i < enc.length(); i++) {
            char ch = enc.charAt(i);
            if (ch == '\\' || ch == '"') {
                sb.append('\\');
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    @Test
    public void decodesLocalStorageValue() {
        QqMailTicket t = QqMailTicket.parse(outer("qq", QqMailTicket.shift(INNER)));
        assertEquals("PKSECRET", t.pwdKey);
        assertEquals("PTKSECRET", t.pwdTicketKey);
        assertEquals(6, t.authType());
    }

    @Test
    public void wechatTicketUsesAuthType1() {
        assertEquals(1, QqMailTicket.parse(outer("wechat", QqMailTicket.shift(INNER))).authType());
    }

    @Test
    public void stripsFingerprinterTailLikeBrowser() {
        QqMailTicket t = QqMailTicket.parse(outer("qq", QqMailTicket.shift(INNER + "fingerprinter=abc123")));
        assertEquals("PKSECRET", t.pwdKey);
    }

    @Test
    public void acceptsBareEncryptTicketAndDecodedJsonAndQuotedValue() {
        assertEquals("PKSECRET", QqMailTicket.parse(QqMailTicket.shift(INNER)).pwdKey);
        assertEquals("PTKSECRET", QqMailTicket.parse(INNER).pwdTicketKey);
        String quoted = "\"" + outer("qq", QqMailTicket.shift(INNER)).replace("\"", "\\\"") + "\"";
        assertEquals("PKSECRET", QqMailTicket.parse(quoted).pwdKey);
    }

    @Test
    public void blankIsNull() {
        assertNull(QqMailTicket.parse("  "));
        assertNull(QqMailTicket.parse(null));
    }

    @Test
    public void badValuesFailWithoutEchoingSecret() {
        String[] bad = {"NOTATICKETSECRET", "{\"uin\":\"1\",\"sid\":\"SIDSECRET\"}",
                outer("qq", QqMailTicket.shift("{\"pwd_key\":\"ONLYPKSECRET\"}"))};
        for (String b : bad) {
            try {
                QqMailTicket.parse(b);
                fail(b);
            } catch (StorageException e) {
                assertFalse(e.getMessage(), e.getMessage().contains("SECRET"));
                assertTrue(e.getMessage(), e.getMessage().contains("Local Storage"));
            }
        }
        assertFalse(QqMailTicket.parse(INNER).toString().contains("SECRET"));
    }
}
