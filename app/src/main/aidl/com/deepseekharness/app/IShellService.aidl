package com.deepseekharness.app;

interface IShellService {
    void destroy() = 16777114;
    String exec(String cmd) = 1;
}
