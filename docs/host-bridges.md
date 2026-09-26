# 容器里能调宿主的什么（dsh-fs / dsh-native）

[← 返回 README](../README.md)

## 容器里能调宿主的什么

容器内除了 dsh 本体，还有两个由 App 落盘的命令，都走同一个只绑 `127.0.0.1` 的回环桥（带随机 token，
其它 App 读不到本应用私有目录，也就拿不到 token）：

`dsh-fs` —— 受控访问共享存储（根目录固定 `/sdcard`，路径逐段校验 + canonical 二次确认，防符号链接逃逸）：

```
dsh-fs list [路径] [--recursive] [--maxDepth N] [--limit N]
dsh-fs stat <路径>
dsh-fs read <路径> [--offset N] [--length N]     # 二进制写到 stdout
dsh-fs write <本地文件> [远端路径] [--append]
dsh-fs rm <路径> [-r]
dsh-fs mv <源> <目标>
dsh-fs cp <源> <目标> [--overwrite]
dsh-fs mkdir <路径>
dsh-fs find <路径> --glob '*.log' [--maxDepth N] [--limit N]
dsh-fs space [路径]
dsh-fs health
```

Android 规定读写整个共享存储要「所有文件访问」，而这一项**只能**在系统设置页里授予（它的
protectionLevel 是 `signature|appop`，应用申请不到）。没授予时上面每条命令都回
`403 no_storage`，`dsh-fs health` 会如实报 `storageGranted: false`；去
**设置 → 安全 → 原生能力 → 共享存储** 点一下就能跳到那个系统页面。
顺带说明：`/storage/emulated/0` 本来就 bind mount 进了容器，普通 `read`/`write`/`glob` 常常够用，
这个桥的价值是**窄而可审计**的那条路径，不是访问本身。

同一个回环桥上还有一组**云备份补包端点**（`/cloud/appdata/status`、`/cloud/appdata/export`、
`/cloud/appdata/restore`），供 `dsh-folk-cloud` 插件在做**含软件数据**的备份/恢复时反向请 App 帮忙：
软件数据（Android `SharedPreferences` 与外观资源）住在 App 私有目录、容器内的插件够不到，只能由 App
出/收整包（复用备份页那条导出/导入通路）。它们与 `dsh-fs`/`dsh-native` 共用同一 token 与回环守卫 ——
能调到这里就等于容器内可信代码；旧版 App 没有这组端点时，插件会自动回退到不含软件数据的档位。

`dsh-native` —— 借 App 之手调原生能力，共 24 项，**默认整体关闭**：要在 **设置 → 安全 → 原生能力**
里打开总开关，再逐项勾选。界面按「这项能力动的是什么」分四组，越往下越该慎重。
**总开关关着的时候，这一页只显示总开关与一行说明**（并告诉你还有多少项档位留在那里）：分项、
共享存储与 CLI 提示整块收起 —— 关着还摊一屏开关墙，既读不出「现在什么都不通」，也读不出
「档位是保留而不是清空」。档位本身留在 prefs 里，重新打开开关即全部恢复。

```
与设备交互   notify / full_screen_notify / toast / vibrate / clipboard / intent（分享与打开链接）/ tts（语音合成）
读设备状态   device / network / phone / sensors
个人数据     media / camera / mic / location / calendar / contacts / sms / a11y（无障碍）
更改系统状态 volume / settings / install / usage / shell（特权命令）
```

命令：

```
dsh-native notify <标题> [正文] [--id N] [--ongoing]
dsh-native notify-cancel [--id N]
dsh-native toast <文本>
dsh-native vibrate [--ms N] [--amplitude 1..255]
dsh-native clip get | clip set <文本> [--label L]
dsh-native share <文本> [--title T]
dsh-native open <https 链接>
dsh-native device
dsh-native network                       # 连接类型 / 是否真能上网 / 是否计费 / WiFi 信号
dsh-native phone                         # 运营商 / 制式 / SIM / 通话状态
dsh-native sensors list | sensors read <id>
dsh-native media list [--type image|video|audio] [--q 名字] [--limit N]
dsh-native media get <id> [--type image|video|audio]
dsh-native camera photo [--facing back|front] [--max N]
dsh-native tts say <文本> [--lang zh-CN] [--rate 0.1..3] [--pitch 0.5..2]
dsh-native tts file <文本> [--lang L]    # 合成成 wav 落在 /tmp
dsh-native tts voices                    # 这台设备能读哪些语言
dsh-native mic record [--ms N]
dsh-native location [--maxAge ms] [--wait ms]
dsh-native calendar list [--days N] | calendar add <标题> --start <epochMs> [--minutes N]
dsh-native contacts list [--q 名字或号码] [--limit N]
dsh-native volume | volume set <0..100> [--stream music|ring|alarm|notification|call|system]
dsh-native ringer <normal|vibrate|silent>
dsh-native settings | settings brightness <1..100> [--auto 0|1] | settings timeout <ms>
dsh-native settings rotation <0|1>
dsh-native install                       # 这台机器允不允许安装未知应用
dsh-native shell [--su] [--timeout ms] [--] <命令>   # 走你选的权限通道执行（见下）
dsh-native a11y tree [--depth N] [--max N]        # 读当前屏幕的节点树
dsh-native a11y click <文字或 id> [--class C] [--index N]
dsh-native a11y tap <x> <y> | a11y swipe <x1> <y1> <x2> <y2>
dsh-native a11y text <文字> [--target <文字或 id>]
dsh-native a11y global <back|home|recents|notifications|quick_settings|lock_screen>
dsh-native caps                          # 查当前哪些能力开着、能不能用
dsh-native elevate <能力> <read|write|read_write|control> --reason <理由> [--command <命令>]
```

### 特权命令（`shell`）

这一项让容器里的 AI 真正用上你选的通道：App 代它执行，它自己不获得任何特权。三条通道的差别只在「谁执行」：

| 通道 | 身份 | 实现 |
| --- | --- | --- |
| root | uid 0 | 常驻 su shell |
| Shizuku | uid 0（Sui/root 模式）或 2000（adb 模式） | 送到 Shizuku 进程里的用户服务执行（`newProcess` 的返回类型是库内部可见的，应用侧编译不过） |
| 无线 ADB | 2000，`--su` 才到 0 | 转发给容器内那条脚本，于是它的两把锁照样生效 |

档位只有两档有意义：**读**只放行诊断类命令（与容器内脚本共用**同一张白名单**，`tools/check-native-logic.js`
会断言两边逐字一致），**读写**才能改设备状态。严格程度决定要不要问（见前文），每一次调用都写进审计，
记录里带上走的哪条通道、拿到的身份、当时的严格程度，以及这次是用户点过头还是自动放行的。
**「选了通道」和「通道能用」是两件事**，提示词把两件都告诉 agent：选了 root 但还没点过「刷新权限」时
它是「已选择，还差一步」（`root_unverified`），而不是「这台设备没有特权」—— 后者会让它连试都不试。
root 的「已验证」不再需要用户手动点一次：应用启动时会自己验（已经授权过的就是静默的，不弹框），
只有真没授权过的人才会看到那一次系统框，被拒之后一天内也不会再自动试 —— 免得每次开 App 都弹。
真正拦住的只有三种可以提前判定的情况：root 没验过（还允许试，调用那一刻才弹 su 授权框）、
Shizuku 没授权、无线 ADB 没配对。用户把通道设成 root、点「刷新权限」、或者刚给 Shizuku 授权，
这三个时刻都会立刻重写容器侧的宿主事实 —— 少写一处，用户就会遇到「我明明开了 root，它好像不知道」，
而那段提示词是按事实渲染的。`dsh-native caps` 里带**只读命令清单**与通道是否就绪：严格档下
猜错一次就要用户多点一下，清单只有一份（与宿主判定同源），所以不会出现「提示词说只读、宿主说不是」。


返回值里带 `exit` 与 `stdout`/`stderr`（超 64 KB 截断）；跑不成的情况用状态码分开：
`403` 通道不允许（`no_channel` / `adb_write_disabled` / `root_unavailable` …）、`504` 超时被丢弃、
`429` 已有一条在执行。这些全是**状态**而不是暂时性错误，提示词里写明不要重试。

### 无障碍（`a11y`）

读当前屏幕的节点树，或对它点按、滑动、输入、返回桌面。目标是**用户此刻正在看的界面**，不是本应用 ——
所以「读」与「写」的差别比别的能力大得多，而且需要用户在系统设置里单独打开那个无障碍开关
（未打开时 `caps` 报 `available:false` + `no_a11y_service`）。

读屏优先按文字或 view id 定位再点，而不是记坐标：坐标跨设备跨分辨率都不通用，读树时把 `bounds` 一并返回。
节点自己常常 `clickable=false`（真正接点击的是父容器），所以点击会往上找可点祖先；找不到才退回按中心坐标
点一次。安全窗口（锁屏、密码框）系统不给节点，这时明确回 `no_window`，而不是让人以为是自己写错了。

**权限不够时，能力调用自己就是申请**：桥不会立刻回 403，而是**把这次调用挂住**，同时在 App 里弹窗；
用户答应就地执行这条命令、把真实结果还给 agent，用户拒绝（或 60 秒不处理）这次调用就以失败结束。
agent 因此不需要「先申请、再调一次」，也不会出现「申请成功了但调用还是失败」这种半途状态。

弹窗给用户三个选择 —— **允许**（级别落盘、长期生效）、**仅本次**（只放行这**一次**调用，用完自动收回，
设置里的开关不动）、**拒绝**（关掉弹窗等同拒绝）。档位本来就够、只是按严格程度要用户点头的那种弹窗
（特权命令与无障碍动作）只有 **允许本次** 与 **拒绝** 两个按钮：长期授权与「下次还要问」直接冲突。弹窗正文里**原文照显这次要执行的命令**（等宽、可选中
复制）：用户要判断的从来不是「camera=write 要不要给」，而是「它接下来到底要做什么」。命令由桥从这次调用
本身重建，agent 不需要额外带 —— 显式的 `dsh-native elevate` 才需要 `--command` 来告诉用户「我打算做什么」。

几个刻意的约束：

- **60 秒不处理按拒绝算**。弹窗挂着不答会永久占住「同时只允许一份待处理申请」那个名额，后面的申请只剩
  409；有时限之后，最坏情况退化成「这次没成」，而不是「这条通道从此废了」。
- 因为有时限，弹窗必须真的能被看见：它同时挂在主界面与 **WebUI 的 Activity** 上。只挂主界面的话，用户
  正看着 WebUI，申请被压在下面 —— 表现是「AI 申请完毫无反应」，然后静默超时算他拒绝。
- **第二段弹窗：Android 层还差权限。** 用户点了「允许」只解决 App 层那一半；相机、麦克风、通知、
  「修改系统设置」这些是 Android 自己的权限，缺了照样执行不了。这时弹第二段，说清缺哪一项，需要跳系统
  设置页的就给一个「去系统设置」按钮，用户切回应用时**自动复查**；用户点「我知道了」或这一段超时，这次
  调用就以 `no_android_permission` 结束（而不是假装成功）。这一段给足 5 分钟 —— 用户正在系统页里找开关。
- 「仅本次」只买一次调用，且三分钟后自动失效；只有真的执行到设备的那次调用才会花掉它 —— 因为缺系统权限
  而失败的那次不花（否则用户要为同一件事回答两次）。
- 申请状态仍是可查的（`dsh-native caps` 的 `pending` / `once` / `lastElevation`）：阻塞期间 agent 正等着，
  这些字段主要用于插件与排查。提示词里写清了「被拒绝 / 超时 / Android 层缺权限就不要重问」。

```
```

勾上一项就会立刻申请它缺的权限；被永久拒绝之后不再弹空窗，而是直接跳系统设置页 —— 那种情况下
`launch` 会立即回调、界面毫无反应，用户只会以为按钮坏了。三项走的是**特殊权限**（`settings` 要
「修改系统设置」、`volume` 的静音与勿扰下调音量要「勿扰访问」、`install` 是「安装未知应用」），
它们 `requestPermissions()` 永远拿不到，只能跳系统页，所以那三行提示的措辞也不同：说的是
「点这里打开系统页」而不是「点这里授权」。

`tts` 是这批能力里唯一**刻意不要求前台**的一项。相机在后台只能拿到黑帧、剪贴板在后台恒返回 null，
所以那些能力后台一律回 `409 not_foreground`；而朗读恰恰相反 —— 手机在口袋里、用户没看屏幕的时候，
「让 agent 说一声」才有意义。它调的是系统自带的引擎（国行多是讯飞或小米的，海外是 Google 的），
不打包任何合成模型；设备上没装引擎时 `caps` 会如实报 `available:false` + `no_tts_engine`。
`tts voices` 存在的理由是**能不能读中文取决于设备**：海外精简 ROM 经常没有中文音库，agent 只能问，
猜不出来。朗读是同步等到读完才返回的 —— 否则 agent 紧接着再调一次，两句话会互相打断。

`media get` / `camera photo` / `mic record` / `tts file` **都不回二进制**：字节落进容器的 `/tmp/dsh-native/`，
回一个容器内路径，agent 用普通文件工具读，只保留最新 32 个。容器 rootfs 是本应用私有目录，写它
不需要任何存储权限，也少一次 base64 膨胀。

几处只有真机上才会发现的取舍：

- **相机**无预览直接出图（拉起系统相机等于让用户自己按快门，那不是「agent 拍一张」）。要丢掉前
  5 帧等自动曝光收敛 —— 单发一张 `STILL_CAPTURE` 在多数机型上就是一张黑图。
- **录音与拍照**固定要求前台：Android 后台录音只给**静音**、后台开相机只给**黑帧**，两者都不报错。
  与其交一份废数据，不如直接 `409 not_foreground`。
- **位置**先用缓存点位（响应里 `fresh: false`），只有过期了才唤醒 GNSS —— 室内主动定位可能几十秒
  无果。Android 12 起用户可以只给「大致位置」，那时坐标被系统模糊到公里级，响应里 `precise: false`
  说明这一点，界面上也单独一行提示，而不是当成缺权限反复索要。
- **亮度与音量**收的是百分比：不同机型的原始量程差别很大（媒体常见 15 档、通话 5 档），让 agent
  先查一次 max 再算是多余的往返。亮度不接受 0（全黑屏幕用户没法自己调回来），音量接受。
  自动亮度开着时写入会在几秒内被系统覆盖，所以响应里带 `autoBrightness` 提醒。
- **每个写操作都返回改动前后的值**：改完不会有人替用户恢复，agent 至少要能说清自己改了什么。
- **传感器**这一项不因缺权限而不可用：加速度、光、气压等都不需要权限，只有心率（`BODY_SENSORS`）
  与计步（`ACTIVITY_RECOGNITION`）要，缺了就从列表里消失并在 `needPermission` 里列出。
- **通讯录只读**，也只返回姓名与号码。**电话**只给网络环境，没有拨号、短信、IMEI —— 拨号真要做，
  正确形式是 `ACTION_DIAL`（号码填进拨号盘、由用户按下通话键），那属于已有的 `intent` 能力。
- **网络**的带宽是系统**估值**不是实测，字段名里带 `estimated` 就是为了别被当测速结果；
  `validated: false` + `connected: true` 是门户认证那种「连上了但上不了网」。

分项而不是一个总开关，是因为容器里同时跑着用户自己装的第三方插件，它们共享同一个 token —— 「能调这个接口」
等价于「容器内任何代码都能调」。读剪贴板、拉起分享/链接、录音、拍照都受 Android 的后台限制约束，
应用不在前台时会返回 `409 not_foreground` 而不是假装成功。

两个桥的报错都是**双份**的：`error` 是跟随应用语言的人话（给用户看），`reason` 是稳定的机器码（给 agent 判断）。
用户把手机切成英文不会改变程序行为。

agent 默认**不知道**这些东西存在（dsh 上游没有 Android 宿主的概念）。App 会往容器里装一个单文件
cordis 插件，往 dsh 的系统提示词里加一段说明：宿主是什么机型/系统、`/sdcard` 已经挂进来了、有
`dsh-fs` / `dsh-native` 这两个命令、此刻**哪些**能力真的开着、缺哪些系统权限、设备语言是什么、以及提权是不是关的。
勾掉哪一项，下一轮对话里那一项就从提示词里消失，agent 不会再去调一个注定 403 的接口。
每项还附一句最容易踩错的地方 —— 日历的时间戳是毫秒、位置可能被模糊到公里级、带宽是估值不是测速、
自动亮度会覆盖刚写入的亮度。
那段本身是英文的（与 dsh 自带的各段一致，避免给模型的输出语言添偏置），设备语言只作为一条**事实**告诉它。
提示词里还写清了「自助提权」这套流程：怎么申请、弹窗有哪三个选项、同一时刻只能有一份待处理申请、
多久不答算拒绝、以及去 `dsh-native caps` 的哪个字段看申请的下文（`pending` / `once` / `lastElevation`）。
少了这些，模型只会拿到一个 403 的 `reason`，然后靠猜决定「该等」还是「该换个办法」。
不想让它知道就在 **插件 → 安卓原生权限桥提示词** 关闭这个置顶的“内置”插件；它不可卸载，关闭后提示词段落会渲染为空。
