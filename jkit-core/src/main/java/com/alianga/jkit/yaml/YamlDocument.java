/*
 * Copyright [2020-2026] [wangyunchao]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.alianga.jkit.yaml;

import com.alianga.jkit.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * YAML 文档的读取、解析、转换与写回入口。
 *
 * <p>支持从字符串、字符数组、文件、输入流和远程 URL 读取 YAML，
 * 并可将文档转换为节点、Map、Properties 或 Java 对象。</p>
 */
public final class YamlDocument extends YamlParser {
    /** 是否存在以 {@code ---} 分隔的多文档内容。 */
    private boolean multiple;

    /** 虚拟根节点。 */
    private YamlNode root;

    /** 多文档模式下各文档的根节点列表。 */
    private List<YamlNode> yamlNodeList;

    private YamlDocument() {
    }

    /**
     * 解析 YAML 字符串。
     * @param yaml YAML 文本
     * @return 解析后的文档
     */
    public static YamlDocument parse(String yaml) {
        return parse(getChars(yaml));
    }

    /**
     * 解析 YAML 字符数组。
     * @param source YAML 源字符数组
     * @return 解析后的文档
     */
    public static YamlDocument parse(char[] source) {
        int length = source.length;
        char lastChar = source[source.length - 1];
        // 最后以换行符结束，可以避免越界检查
        // todo 性能优化点
        if (lastChar != '\n') {
            source = Arrays.copyOf(source, length + 1);
            source[length] = '\n';
        }

        YamlDocument yamlDocument = new YamlDocument();
        yamlDocument.source = source;
        yamlDocument.parseYamlRoot(0);
        return yamlDocument;
    }

    /**
     * 读取输入流中的完整 YAML 内容并解析。
     *
     * @param is 输入流
     * @return 解析后的文档
     * @throws IOException 读取失败时抛出
     */
    public static YamlDocument read(InputStream is) throws IOException {
        return parse(IOUtils.readAsChars(is));
    }

    /**
     * In yaml scenarios, large files will not appear. Read the complete stream directly and then parse it
     *
     * @param is      the input stream to read from
     * @param charset the charset used to decode the stream
     * @return the parsed yaml document
     * @throws IOException if reading the stream fails
     */
    public static YamlDocument read(InputStream is, Charset charset) throws IOException {
        return parse(IOUtils.readAsChars(is, charset));
    }

    /** 读取并解析 YAML 文件。
     * @param yamlFile YAML 文件
     * @return 解析后的文档
     * @throws IOException 读取失败时抛出
     */
    public static YamlDocument read(File yamlFile) throws IOException {
        return read(new FileInputStream(yamlFile));
    }

    /** 读取并解析远程 YAML 资源。
     * @param url 资源 URL
     * @return 解析后的文档
     * @throws IOException 网络读取失败时抛出
     */
    public static YamlDocument read(URL url) throws IOException {
        return read(url, YamlDocument.class);
    }

    /** 从指定偏移量开始解析一个 YAML 文档根节点。
     * @param offset 源文本起始下标
     */
    protected void parseYamlRoot(int offset) {
        YamlNode yamlRoot = new YamlNode();
        if (root == null) {
            root = yamlRoot;
        } else {
            this.multiple = true;
            if (yamlNodeList == null) {
                yamlNodeList = new ArrayList<YamlNode>();
                yamlNodeList.add(root);
            }
            yamlNodeList.add(yamlRoot);
        }
        List<YamlNode> yamlNodes = new ArrayList<YamlNode>();
        parseNodes(yamlNodes, offset, source.length);
        // 构建yaml树结构
        yamlRoot.buildYamlTree(yamlNodes);
    }

    /**
     * 解析字符串转化为指定类型
     *
     * @param yamlStr    YAML 文本
     * @param actualType 目标实体类型
     * @param <T>        目标实体类型
     * @return 由根节点绑定得到的实体对象
     */
    public static <T> T parse(String yamlStr, Class<T> actualType) {
        YamlDocument yamlDocument = parse(yamlStr);
        return yamlDocument.getRoot().toEntity(actualType);
    }

    /***
     * 解析字符数组转化为指定类型
     *
     * @param buf        YAML 源字符数组
     * @param actualType 目标实体类型
     * @param <T>        目标实体类型
     * @return 由根节点绑定得到的实体对象
     */
    public static <T> T parse(char[] buf, Class<T> actualType) {
        YamlDocument yamlDocument = parse(buf);
        return yamlDocument.getRoot().toEntity(actualType);
    }

    /**
     * 读取字节数组转化为指定class的实例
     *
     * @param bytes      字节流
     * @param actualType 实体类型
     * @param <T>        实体类型
     * @return T对象，读取过程发生 IO 异常时返回 {@code null}
     */
    public static <T> T read(byte[] bytes, Class<T> actualType) {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        try {
            return read(bais, actualType);
        } catch (IOException e) {
        }
        return null;
    }

    /**
     * 读取yaml文件转化为指定class的实例
     *
     * @param file       文件
     * @param actualType 实体类型
     * @param <T>        实体类型
     * @return T对象
     * @throws IOException 文件不存在或读取失败时抛出
     */
    public static <T> T read(File file, Class<T> actualType) throws IOException {
        return read(new FileInputStream(file), actualType);
    }

    /**
     * 读取远程资源yaml转化为指定class的实例
     *
     * @param url        URL资源
     * @param actualType 实体类型
     * @param <T>        实体类型
     * @return T对象
     * @throws IOException 网络连接或读取失败时抛出
     */
    public static <T> T read(URL url, Class<T> actualType) throws IOException {
        return read(url, actualType, -1);
    }

    /**
     * 读取远程资源yaml转化为指定class的实例
     *
     * @param url        URL资源
     * @param actualType 实体类型
     * @param timeout    超时时间，单位毫秒，大于 0 时同时作用于连接与读取超时
     * @param <T>        实体类型
     * @return T对象
     * @throws IOException 网络连接或读取失败时抛出
     */
    public static <T> T read(URL url, Class<T> actualType, int timeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        if (timeout > 0) {
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
        }
        conn.connect();
        return read(conn.getInputStream(), actualType);
    }

    /**
     * 将输入流转化为指定class的实例
     *
     * @param is         输入流
     * @param actualType 实体类型
     * @return T对象
     */
    private static <T> T read(InputStream is, Class<T> actualType) throws IOException {
        return read(is).toEntity(actualType);
    }

    /**
     * 读取属性列表
     *
     * @param is 输入流
     * @return 由文档根节点扁平化得到的 Properties
     * @throws IOException 读取失败时抛出
     */
    public static Properties loadProperties(InputStream is) throws IOException {
        return read(is).toProperties();
    }

    /**
     * 判断是否为以 {@code ---} 分隔的多文档内容。
     *
     * @return 包含多个文档时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isMultiple() {
        return multiple;
    }

    /**
     * 获取文档的虚拟根节点。
     *
     * @return 当前文档的根节点，多文档模式下为第一个文档的根节点
     */
    public YamlNode getRoot() {
        return root;
    }

    /**
     * 获取多文档模式下各文档的根节点列表。
     *
     * @return 各文档的根节点列表，单文档时为 {@code null}
     */
    public List<YamlNode> getYamlNodeList() {
        return yamlNodeList;
    }

    /***
     * 将文档(根节点)转化为指定的实体bean
     *
     * @param actualType 目标类型
     * @param <T>        目标类型
     * @return 绑定后的对象；目标类型为 {@code YamlDocument} 时返回自身，为 {@code YamlNode} 时返回根节点
     */
    public <T> T toEntity(Class<T> actualType) {
        if (actualType == YamlDocument.class) {
            return (T) this;
        }
        if (actualType == YamlNode.class) {
            return (T) getRoot();
        }
        return getRoot().toEntity(actualType);
    }

    /**
     * 将嵌套 Map 绑定为指定类型，供配置前缀映射等场景复用节点树转换。
     *
     * @param map 嵌套 Map（非扁平 properties）
     * @param actualType 目标类型
     * @param <T> 目标类型
     * @return 绑定后的对象；map 为 null 时返回 null
     */
    @SuppressWarnings("unchecked")
    public static <T> T toEntity(Map<?, ?> map, Class<T> actualType) {
        if (map == null) {
            return null;
        }
        if (actualType == null || actualType == Map.class || actualType == LinkedHashMap.class) {
            return (T) map;
        }
        YamlNode node = YamlNode.fromValue(map);
        YamlNode.markRoot(node, node);
        return node.toEntity(actualType);
    }

    /***
     * 将文档(根节点)转化为map
     *
     * @return 由根节点转换出的嵌套 Map
     */
    public Map toMap() {
        return getRoot().toMap();
    }

    /***
     * 将文档(根节点)转化为properties
     *
     * @return 由根节点扁平化得到的 Properties
     */
    public Properties toProperties() {
        return getRoot().toProperties();
    }

    /**
     * 写入文件
     *
     * @param file 目标文件，不存在时自动创建
     * @throws IOException 创建文件或写入失败时抛出
     */
    public void writeTo(File file) throws IOException {
        if (!file.exists()) {
            file.createNewFile();
        }
        writeTo(new FileWriter(file));
    }

    /**
     * 写入指定的输出流
     *
     * @param os 目标输出流，以平台默认字符集编码，写完后关闭
     * @throws IOException 写入失败时抛出
     */
    public void writeTo(OutputStream os) throws IOException {
        writeTo(new OutputStreamWriter(os));
    }

    /**
     * 写入指定的writer
     *
     * <p>简单模式下的yaml回写转化(如果包含&amp;引用和*关联等语法时缩进会混乱，暂时不支持)</p>
     *
     * @param writer 目标字符流，写完后无论成功与否都会被关闭
     * @throws IOException 写入失败时抛出
     */
    public void writeTo(Writer writer) throws IOException {
        try {
            if (this.multiple) {
                for (YamlNode rootNode : yamlNodeList) {
                    rootNode.writeTo(writer);
                    writer.write("---");
                }
            } else {
                this.root.writeTo(writer);
            }
            writer.flush();
        } finally {
            writer.close();
        }
    }

    /**
     * 将文档写回为 YAML 字符串。
     *
     * @return YAML 文本，写出过程发生 IO 异常时返回已写入的部分内容
     */
    public String toYamlString() {
        StringWriter writer = new StringWriter();
        try {
            writeTo(writer);
        } catch (IOException e) {
        }
        return writer.toString();
    }

//    /***
//     * 对象转化为yaml字符串
//     * todo
//     * @param obj
//     * @return
//     */
//    public static String toYamlString(Object obj) {
//        return null;
//    }
}
