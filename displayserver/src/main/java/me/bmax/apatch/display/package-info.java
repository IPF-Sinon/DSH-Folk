/**
 * 虚拟屏幕服务端：以 `app_process` 在特权身份（root / shell）下运行，创建一块 Android
 * 虚拟显示屏、把输入事件注入到那块屏、并把画面编码回传给宿主 App。
 *
 * <h2>来源与许可</h2>
 *
 * 本包（`displayserver/`）移植自 <a href="https://github.com/AAswordman/Operit">Operit</a>
 * 的 `tools/shower`（LGPL-3.0），其手法又源自
 * <a href="https://github.com/Genymobile/scrcpy">scrcpy</a>（Apache-2.0）——其中最典型的是
 * {@code shell/Workarounds} 与 {@code shell/FakeContext}：伪造 {@code ActivityThread} 与
 * 系统 Context，好让一个非 App 进程也能实例化 {@code DisplayManager}。
 *
 * <p>LGPLv3 与本项目的 GPLv3 兼容，所以整体仍以 GPL-3.0 分发。移植时做了三件事：
 * 包名 {@code com.ai.assistance.shower} → {@code me.bmax.apatch.display}；
 * 去掉移植源里与本功能无关的 Compose 界面（那是一个 `app_process` 进程用不到的，
 * 却让原始 jar 里多出 408KB 的 resources.arsc 与一堆 res 图片）；
 * 广播 action 与日志路径改为本项目自己的名字。
 *
 * <h2>为什么它必须独立成一个进程</h2>
 *
 * 创建带 {@code SUPPORTS_TOUCH} 的 {@code TRUSTED} 虚拟屏、以及调用隐藏的
 * {@code InputManager.injectInputEvent}，都需要 {@code INJECT_EVENTS} 这类系统权限。
 * 普通 App 拿不到，所以这里走 `app_process`：它以 UID 0（root）或 2000（shell/Shizuku）
 * 运行，再用 Binder 把能力交回宿主 App。
 */
package me.bmax.apatch.display;
