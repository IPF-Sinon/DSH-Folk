# 它是怎么跑起来的

[← 返回 README](../README.md)

## 它是怎么跑起来的

```
DSH-Folk (Android app)                      ← 按 ABI 拆包：arm64-v8a / x86_64
  └─ proot / proroot                        ← 打包在 APK 里的可执行 .so
       └─ Ubuntu 24.04 rootfs               ← 首次启动时在线下载（arm64 或 x86_64）
            ├─ python3                       ← 无线 ADB 配对用，已预装
            ├─ git                            ← git 源插件用，已预装
            └─ Node.js 24 + @deepseek-ai/dsh
                 └─ dsh web --port 3080      ← 默认只监听 127.0.0.1
                      └─ 手机浏览器 / 应用内打开
```

几个不得不这么做的地方：

- Android 的 `app_data_file` 带 **noexec**，只有 `nativeLibraryDir` 里的 `.so` 可执行，所以 proot / proroot 以 `.so` 形式打包进 APK。
- proroot 只有 arm64 版本（[上游](https://github.com/coderredlab/proroot) 只发布 arm64-v8a），所以 x86_64 设备上运行方式固定为 proot，
  设置里那一项会禁选并说明原因。
- 部分设备的私有目录禁止 `link(2)`（真机实测报 `AccessDeniedException`），应用会先探测硬链接是否可用，不可用时给 proot 加 `--link2symlink`；
  proroot 则无条件启用它。而 pnpm 正是用 `link()` 从内容存储装包 —— 链接一旦被改写成符号链接，
  Node 的 `require.resolve` 做 realpath 就会解析进内容存储的扁平哈希目录，插件声明的 `./lib/client.cjs` 再也拼不出来
  （表现是装完插件 `dsh web` 报 `MissingClientBundleError`）。所以这种环境下会给 profile 的 `pnpm-workspace.yaml`
  写上 `packageImportMethod: copy`，让 pnpm 复制真实文件。代价是内容存储的去重失效，容器体积会大一些。
- `dsh plugin` 只负责调 pnpm，PATH 上没有 pnpm 就直接 exit 127。运行时因此固定带自包含的
  `pnpm@10.34.5`（不跟 `latest`：pnpm 12 的 npm 包改成了依赖 postinstall 下载原生二进制的启动器，
  与异架构构建必须使用的 `--ignore-scripts` 冲突），按 `package.json.bin` 重建 `/usr/local/bin`
  下的链接，并在打包前用 `node bin/pnpm.cjs --version` 自检 —— 这一层只由运行时负责，App 不再
  在设备上给 rootfs 打补丁（那只会把「运行时是坏的」藏起来）。修复版以 r3 重新发布在**两个**通道上，
  所以存量用户（含仍停在 0.1.2-r2 的 stable 用户）能直接更新运行时脱困，不必重装。
- 插件目录里超过一半的条目是 `github:owner/name` 安装规格，pnpm 解析它要 `git ls-remote`，所以 git 也预装进了 rootfs。
  注意 rootfs 是用 `dpkg-deb -x` 纯解包装出来的（不跑 maintainer script —— 它们要在目标架构上执行），
  **dpkg 的依赖关系没人替我们解** —— 包列表写漏一个传递依赖，构建期一切正常，到设备上 exec 那一刻才报
  `cannot find libxxx.so.N`。所以构建末尾有一步 `check-elf-closure.js`：从 git-core / perl 扩展 / python3
  出发递归解析 ELF 的 `DT_NEEDED`，任何 SONAME 找不到提供者就让构建失败（并断言入口是目标架构）。
- `dsh web` 默认只绑定回环地址；配置备份走的也是同一个回环 HTTP 接口。局域网访问是设置里一个默认关闭的开关。
- **1.9.0 把 dsh 0.1.5 推上正式通道**（`runtime-latest` = `0.1.5-rc.1-ubuntunoble-r4`，要求 App ≥ 1.9.0）。
  测试通道此前已经在同一份 rootfs 上跑了一整轮 beta，但**测试人数不足**：升级 dsh 会改写会话与插件数据，
  而 App 侧保留的那几条路径（`root/.dsh`、`root/.local`、`.l2s`）只保得住文件、保不住上游格式变化。
  所以更新运行时前请先备份 —— 更新说明弹窗的第一句就是这句警示，用错误色显示。
