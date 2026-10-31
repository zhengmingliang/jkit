package com.alianga.jkit.captcha;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 包内共用的流读取工具，JDK 8 没有 InputStream.readAllBytes。 */
final class Io {
    private Io() {
    }

    static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read = input.read(buffer);
        while (read >= 0) {
            if (read > 0) {
                out.write(buffer, 0, read);
            }
            read = input.read(buffer);
        }
        return out.toByteArray();
    }
}
