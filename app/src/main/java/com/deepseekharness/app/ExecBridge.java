package com.deepseekharness.app;

/**
 * JNI 桥接：通过 memfd_create + fexecve 从内存执行静态 ELF（qemu），
 * 绕过 Android App 目录的 noexec 限制。
 */
public final class ExecBridge {

    // 注：memfd 方案（musl 静态 .so）在部分机型因双 libc 冲突闪退，
    // 已改用外部存储直接 exec qemu。保留此类作为占位，不加载 native 库。
    /*
    static {
        System.loadLibrary("exec");
    }
    public static native String nativeExec(byte[] elfBytes, String[] args);
    */

    private ExecBridge() {}
}
