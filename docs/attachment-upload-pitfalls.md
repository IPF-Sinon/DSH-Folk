# 附件上传：Android / proroot 下的落盘踩坑

这份文档记录 **dsh 网页端「上传文件」在 DSH-Folk 容器里失败** 这次排查的全过程：
现象、怎么拿到证据、根因在哪一行源码、以及为什么「有的文件能传、有的不能」。
它写给以后维护运行时/桥接层的人 —— 这类问题的报错信息**会被上游吞成一句通用文案**，
不掌握取证方法就只能猜。

> 授权：本文档以 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.zh)
> （知识共享 署名 4.0 国际）授权。转载、改编请保留出处与本说明。
> 代码本身仍是 GPL-3.0（见项目 LICENSE）。

---

## 1. 现象

- 聊天输入框里选文件后，卡片显示「上传失败，点击重试」；
- 同一台设备上 **有的文件能传、有的不能**，而且是「以前传过的能传、新文件不能」；
- 页面与 `dsh.log` 里都没有原因 —— 只有一句 `{"ok":false,"error":{"code":"session/attachment-invalid","reason":"ATTACHMENT_WRITE_FAILED"}}`；
- 换外部浏览器也一样（所以**不是 WebView 的问题**）；
- 本机运行方式为 `proroot`。

## 2. 先让它开口：三个观测点

上游把上传失败压在 HTTP 200 的响应体里，前端只渲染通用文案。必须先自己取证：

1. **页面侧钩子**：上游 `@deepseek-ai/dsh-client-file-upload` 支持启动前钩子
   `window.__DSH_FILE_UPLOAD__ = { fetch }`。装上它就能在失败当下把
   `status` / 文件体积 / 响应体前 300 字打进 console，再由 App 的 `onConsoleMessage`
   落进运行日志（前缀 `[DSH-Folk upload]`）。
2. **容器内存储体检**：启动后跑三条命令并把结果写日志（前缀 `[attach]`）——
   `df`、`ls -ld $DSH_HOME/attachments`、以及一次小文件写入。
3. **直接探系统调用**（最关键）：在容器里用 `node` 复现
   「写临时文件 → link → unlink 源 → chmod 目标」，把每一步的结果打出来。

第 3 步的输出就是这次的决定性证据：

```
[attach] LINK_OK symlink=false
[attach] CHMOD_AFTER_UNLINK_FAIL ENOENT
[attach] FSYNC_DIR_OK
[attach] WRITE_OK
```

读法：`link` **不报错**，但**删掉源名之后，目标名就消失了**（chmod 目标得到 ENOENT）。
磁盘、权限、目录 fsync 全都正常。

## 3. 根因

### 3.1 上游的发布流程依赖「硬链接」

`@deepseek-ai/dsh-attachment-local` 存文件是内容寻址：

- 先把字节写进 `<DSH_HOME>/attachments/v1/tmp/<uuid>`（并 fsync 文件）；
- 再 **`link(staging, target)`** 到 `.../file-objects/<sha2>/<sha>`；
- 然后 **`unlink(staging)`**、`chmod(target, 0o400)`、逐级 fsync 目录；
- 最后再为「带文件名的别名」`.../files/<sha2>/<sha>/<name>` 做一次 `link`。

（上游提交 `65d2015` “feat(attachment): store and project generic files” 引入通用文件存储与 `file-store.ts`，
图片与普通文件共用同一套 `publishStagedObject` / `publishImmutableAlias`。）

### 3.2 proroot 的 `--link2symlink` 不是真硬链接

proroot 的用法说明写得很清楚：`--link2symlink` 是 **“Emulate hardlinks via anchor + symlink groups”**。
也就是说 `link()` 建出来的不是独立目录项，而是「锚点 + 符号链接组」：

- `link(staging, target)` 返回成功；
- `unlink(staging)`（删掉锚点）之后，组里的 `target` **随之失效**；
- 于是下一步 `chmod(target)` 得到 ENOENT，整条链抛出 `ATTACHMENT_WRITE_FAILED`。

而 App 私有目录本身**不允许建真硬链接**（SELinux 拒绝），所以这里没有「退化成真硬链接」的可能。
两个限制叠加，硬链接发布在 Android + proroot 上**必然不成立**。

### 3.3 为什么「有的能传、有的不能」

关键在发布流程里那个 `EEXIST` 分支：

- 目标**已存在**（这个内容以前成功存过）→ `link` 报 `EEXIST` → 上游跳过创建，直接 `unlink(staging)`、`chmod(已有目标)` → **成功**；
- 目标**不存在**（新内容）→ `link` 走「成功」的锚点组路径 → `unlink(staging)` 后目标没了 → **失败**；
- 更糟：失败过的内容会在 `file-objects/` 留下**悬空链接**，之后每次重试都卡在它上面（`digestFile` / `chmod` 一路 ENOENT），
  于是「这个文件永远传不上去」。

这解释了所有看似矛盾的现象：不是「缓存了失败状态」，而是**只有早已存在的对象能走通，新对象必失败**。

## 4. 走过的弯路（值得记住）

| 弯路 | 为什么错 | 教训 |
|---|---|---|
| 以为 `link` 会失败，做「link 失败则 copy」兜底 | `link` **从不失败**（l2s 返回成功），兜底永远不触发 | **不要相信 `link` 的返回值**，要验证「删源后目标还在」 |
| 以为「直接 copyFile」就万无一失 | 复制前一步出现 `copyfile ENOENT`，且历史悬空目标会挡住复制 | 发布前要**先清掉目标名**（悬空链接） |
| 以为是 WebView / 选择器问题 | 外部浏览器同样失败 | 先分清「WebView 内」与「同源浏览器」，再怀疑宿主 |
| 以为是 chroot 引入的 | 该设备无 root、从未用过 chroot；运行方式是 proroot | 先看 `basic.txt` 的 `ContainerRuntime`，别按直觉归因 |

## 5. 修法

把「硬链接发布」换成 **rename 发布**（同目录 `rename` 是原子的，只保留一个真名字，不需要硬链接）：

1. `link(staging, target)` → **`rename(staging, target)`**；
2. 删掉紧随其后的 **`unlink(staging)`**（rename 已消费临时名，再删会把目标一起带走）；
3. 别名 `link(source, target)` → **先 `unlink(target)`（忽略不存在/悬空）再 `copyFile(source, target)`**；
4. `import` 里补上 `copyFile`。

落地方式与 `patchLanHost` 同款：**每次启动幂等重打**（运行时重装后自动回补），
并对每处替换做**计数校验**（不是恰好各 1 次就不写回，避免运行时升级后改错行）。

## 6. 复现/验收的最小探针

在容器里跑（不需要真机 root）：

```bash
node -e 'const fs=require("fs");const d="/root/.dsh/attachments/v1/tmp";fs.mkdirSync(d,{recursive:true,mode:448});
const a=d+"/p1";fs.writeFileSync(a,"x");
try{fs.linkSync(a,d+"/p2");console.log("LINK_OK symlink="+fs.lstatSync(d+"/p2").isSymbolicLink());
 fs.unlinkSync(a);
 try{fs.chmodSync(d+"/p2",256);console.log("CHMOD_AFTER_UNLINK_OK")}catch(e){console.log("CHMOD_AFTER_UNLINK_FAIL "+e.code)}
 fs.unlinkSync(d+"/p2")}catch(e){console.log("LINK_FAIL "+e.code)}'
```

- 输出 `CHMOD_AFTER_UNLINK_FAIL ENOENT` → 这个运行时的 link 不能用于发布，按 §5 修；
- 输出 `CHMOD_AFTER_UNLINK_OK` → 该运行时可以安全使用硬链接发布。

## 7. 通用教训

1. **Android 应用私有目录通常建不了硬链接**（SELinux），任何「硬链接发布」在手机上都先当作不可用。
2. **proot/proroot 的 `--link2symlink` 是模拟**：`link` 成功 ≠ 存在两个独立目录项；删锚点即失效。
3. 目录 `fsync` 在部分内核/文件系统上会失败 —— 但它只是崩溃安全优化，不该阻断业务。
4. 上游把错误压成通用文案时，**先装钩子把响应体拿出来**，再谈修复；不要凭报错名字猜。
5. 幂等补丁要**逐处计数校验**；「片段没匹配上就跳过」比「匹配到半截强行写」安全得多。
6. 线上日志里 `[attach]` / `[DSH-Folk upload]` 这两类前缀是这次排查的主要抓手，保留它们。
