// Shizuku 用户服务的接口。
//
// Shizuku 只把「以它的身份执行」这一件事交给应用，而这件事必须跑在**它自己的进程**里 ——
// 应用不能直接拿到那个 uid，只能通过一条 binder 调用过去。所以这里定义的是「把一条命令
// 送过去执行」这一个方法，而不是别的什么。
package me.bmax.apatch.dsh;

interface IDshShellService {
    /**
     * Shizuku 服务器定义的「销毁」方法，事务号是它写死的 16777115（aidl 里写 16777114）。
     *
     * **必须原样声明**：Shizuku 停止用户服务时会调它做清理。少了它，那条事务没人应答，
     * 而用户服务的进程又不会被自动杀掉（Shizuku 的文档明确写了这一点），于是每次换通道
     * 都会留下一个以 shell/root 身份活着的进程。
     */
    void destroy() = 16777114;

    /**
     * 在 Shizuku 的进程里执行一条命令并等它结束。
     *
     * 返回一个紧凑 JSON：{"exit":n,"stdout":"…","stderr":"…","timedOut":bool}。
     * 输出在**服务侧**就截断：binder 事务有 1MB 上限，超了会抛
     * TransactionTooLargeException，而那条异常在应用侧看起来只是「通道坏了」。
     *
     * 超时也由服务侧执行（destroyForcibly）：binder 调用是同步阻塞的，应用侧要么等到底，
     * 要么放弃等待 —— 放弃等待不会让那条命令停下来。
     */
    String exec(String command, int timeoutMs);
}
