// Shizuku 用户服务的接口。
//
// Shizuku 只把「以它的身份执行」这一件事交给应用，而这件事必须跑在**它自己的进程**里 ——
// 应用不能直接拿到那个 uid，只能通过一条 binder 调用过去。所以这里定义的是「把一条命令
// 送过去执行」这一个方法，而不是别的什么。
package me.bmax.apatch.dsh;

interface IDshShellService {
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
