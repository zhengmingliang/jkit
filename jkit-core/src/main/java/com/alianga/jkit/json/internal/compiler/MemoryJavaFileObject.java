package com.alianga.jkit.json.internal.compiler;

import javax.tools.SimpleJavaFileObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;

/**
 * 内存中的 java 文件对象，编译产物（字节码）写入内部的 {@link ByteArrayOutputStream} 而不落盘。
 */
public class MemoryJavaFileObject extends SimpleJavaFileObject {
    private ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    /**
     * 用指定的类型和 URI 构造 SimpleJavaFileObject
     *
     *
     * @param className 类的全限定名，与 {@code kind} 的扩展名拼接后作为文件 URI
     * @param kind the kind of this file object
     */
    protected MemoryJavaFileObject(String className, Kind kind) {
        super(URI.create(className + kind.extension), kind);
    }

    /**
     * 由 JavaSourceObject 创建
     *
     * @param sourceObject java 源码对象，从中取出类的全限定名
     * @return 以该类名创建的内存文件对象，文件类型为 {@link Kind#SOURCE}
     */
    public static MemoryJavaFileObject from(JavaSourceObject sourceObject) {
        return new MemoryJavaFileObject(sourceObject.canonicalName, Kind.SOURCE);
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
        return this.outputStream;
    }

    /**
     * 取出已写入的字节内容，并重置内部缓冲区。
     *
     * @return 已写入的字节内容；未写入过时返回空数组
     */
    public byte[] getBytes() {
        byte[] bytes = this.outputStream.toByteArray();
        outputStream.reset();
        return bytes;
    }
}
