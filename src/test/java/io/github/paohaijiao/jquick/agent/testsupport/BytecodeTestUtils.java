package io.github.paohaijiao.jquick.agent.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class BytecodeTestUtils {

    private BytecodeTestUtils() {
    }

    public static byte[] readClassBytes(Class<?> type) throws IOException {
        String resourceName = type.getName().replace('.', '/') + ".class";
        InputStream inputStream = type.getClassLoader().getResourceAsStream(resourceName);
        if (inputStream == null) {
            throw new IllegalStateException("Class bytes not found: " + resourceName);
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, len);
        }
        return outputStream.toByteArray();
    }
}
