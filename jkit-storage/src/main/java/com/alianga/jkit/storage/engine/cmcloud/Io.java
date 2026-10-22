package com.alianga.jkit.storage.engine.cmcloud;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 引擎内部的流拷贝工具：jkit-storage 以 Java 8 为目标发布，
 * 不能直接依赖 Java 9 的 {@code InputStream#transferTo}。
 *
 * @author 郑明亮
 */
final class Io {
    private Io() {
    }

    /**
     * 把输入流读完写入输出流（8KB 缓冲，不做流关闭）。
     *
     * @param in  数据源
     * @param out 目标
     * @return 拷贝字节数
     * @throws IOException 读写失败
     */
    static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] chunk = new byte[8192];
        long total = 0L;
        int read;
        while ((read = in.read(chunk)) >= 0) {
            out.write(chunk, 0, read);
            total += read;
        }
        return total;
    }
}
