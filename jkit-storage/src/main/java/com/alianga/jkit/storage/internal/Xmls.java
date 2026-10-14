package com.alianga.jkit.storage.internal;

import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * XML 解析小工具：S3 / OSS / Azure / WebDAV 的响应都是 XML。
 * 统一按「本地名」匹配（自动剥离 D: / ns: 前缀），禁用外部实体防 XXE。
 *
 * @author 郑明亮
 */
public final class Xmls {
    private Xmls() {
    }

    /**
     * 解析 XML 文档。
     *
     * @param xml XML 文本
     * @return 根元素
     * @throws StorageException 解析失败抛 {@link StorageErrorCode#IO_ERROR}
     */
    public static Element parse(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            throw StorageException.io("XML 内容为空", "-", null);
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return document.getDocumentElement();
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw StorageException.io("XML 解析失败: " + e.getMessage(), "-", e);
        }
    }

    /**
     * 取元素的全部直接子元素（按本地名过滤，自动忽略命名空间前缀）。
     *
     * @param parent    父元素
     * @param localName 本地名
     * @return 子元素列表
     */
    public static List<Element> children(Element parent, String localName) {
        List<Element> found = new ArrayList<Element>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && localName.equals(localName(node))) {
                found.add((Element) node);
            }
        }
        return found;
    }

    /**
     * 取第一个匹配本地名的直接子元素。
     *
     * @param parent    父元素
     * @param localName 本地名
     * @return 子元素，不存在返回 null
     */
    public static Element first(Element parent, String localName) {
        List<Element> found = children(parent, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 取第一个匹配子元素的文本。
     *
     * @param parent    父元素
     * @param localName 本地名
     * @return 文本（trim 后），不存在或为空返回 null
     */
    public static String text(Element parent, String localName) {
        Element child = first(parent, localName);
        return child == null ? null : child.getTextContent().trim();
    }

    /**
     * 取第一个匹配子元素的文本或默认值。
     *
     * @param parent       父元素
     * @param localName    本地名
     * @param defaultValue 默认值
     * @return 文本或默认值
     */
    public static String textOr(Element parent, String localName, String defaultValue) {
        String value = text(parent, localName);
        return value == null ? defaultValue : value;
    }

    /**
     * 取整棵子树的文本（同名任意层级，getElementsByTagName 语义）。
     *
     * @param parent    父元素
     * @param localName 本地名
     * @return 文本，不存在返回 null
     */
    public static String deepText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && localName.equals(localName(node))) {
                return ((Element) node).getTextContent().trim();
            }
        }
        return null;
    }

    /**
     * 递归查找所有匹配本地名的后代元素。
     *
     * @param parent    父元素
     * @param localName 本地名
     * @return 元素列表
     */
    public static List<Element> deepAll(Element parent, String localName) {
        List<Element> found = new ArrayList<Element>();
        NodeList nodes = parent.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && localName.equals(localName(node))) {
                found.add((Element) node);
            }
        }
        return found;
    }

    /**
     * 元素文本整体读出（保留内部空白），用于文本内容类节点。
     *
     * @param element 元素
     * @return 文本
     */
    public static String fullText(Element element) {
        return element.getTextContent() == null ? "" : element.getTextContent();
    }

    /**
     * 把输入流读为字符串（UTF-8），供调用方在需要重复读 body 时使用。
     *
     * @param in 输入流
     * @return 文本
     */
    public static String readAll(InputStream in) {
        return readAll(in, MAX_RESPONSE_BYTES);
    }

    /**
     * 响应文本读取上限：列表类 XML（WebDAV PROPFIND / S3 ListObjects）几万条目也只有
     * 十几 MB；超过即判定异常响应，抛错而不是把堆打爆。可用系统属性
     * {@code jkit.storage.maxResponseBytes} 调整。
     */
    public static final long MAX_RESPONSE_BYTES =
            Long.getLong("jkit.storage.maxResponseBytes", 64L * 1024L * 1024L);

    /**
     * 把输入流读为字符串（UTF-8），超过 maxBytes 抛 I/O 异常。
     *
     * @param in       输入流
     * @param maxBytes 上限
     * @return 文本
     */
    public static String readAll(InputStream in, long maxBytes) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buffer = new byte[8192];
            int read;
            long total = 0L;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw StorageException.io("响应体超过上限 " + maxBytes + " 字节，已中止读取", "-", null);
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (java.io.IOException e) {
            throw StorageException.io("读取响应失败: " + e.getMessage(), "-", e);
        }
    }

    private static String localName(Node node) {
        String name = node.getNodeName();
        int at = name.indexOf(':');
        return at < 0 ? name : name.substring(at + 1);
    }
}
