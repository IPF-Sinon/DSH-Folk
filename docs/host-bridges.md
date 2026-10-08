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
dsh-native troubleshoot                              # 权限桥报错时：打印一份排查清单（按当前应用语言生成）
dsh-native notify <标题> [正文] [--id N] [--ongoing]
dsh-native notify-cancel [--id N]
dsh-native notify-list [--limit N]                    # 读活跃系统通知
dsh-native notify-dismiss <key>|--all                 # 清系统通知（需通知完全控制）
dsh-native notify-full-screen <标题> [正文]            # 紧急全屏提醒
dsh-native toast <文本>
dsh-native torch <on|off>                # 把摄像头闪光灯当电筒开/关
dsh-native vibrate [--ms N] [--amplitude 1..255]
dsh-native clip get | clip set <文本> [--label L]
dsh-native share <文本> [--title T]
dsh-native open <https 链接>
dsh-native dial <号码>                    # 把号码填进拨号盘，由用户按通话键
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
dsh-native mic start | mic stop --id ID   # start 立刻返回 id；stop 优雅收尾（把 mp4 的索引写全）
dsh-native location [--maxAge ms] [--wait ms]
dsh-native calendar list [--days N] | calendar add <标题> --start <epochMs> [--minutes N]
dsh-native contacts list [--q 名字或号码] [--limit N]
dsh-native volume | volume set <0..100> [--stream music|ring|alarm|notification|call|system]
dsh-native ringer <normal|vibrate|silent>
dsh-native settings | settings brightness <1..100> [--auto 0|1] | settings timeout <ms>
dsh-native settings rotation <0|1>
dsh-native install                       # 这台机器允不允许安装未知应用
dsh-native usage list [--days N] [--limit N]          # 最近应用前台使用统计
dsh-native sms list [--limit N] | sms send <号码> <文本>   # 读短信 / 发短信
dsh-native shell [--su] [--timeout ms] [--] <命令>   # 走你选的权限通道执行（见下）
dsh-native a11y tree [--depth N] [--max N]        # 读当前屏幕的节点树
dsh-native a11y click <文字或 id> [--class C] [--index N]
dsh-native a11y tap <x> <y> | a11y swipe <x1> <y1> <x2> <y2>
dsh-native a11y text <文字> [--target <文字或 id>] [--class 类名] [--index N]   # 文字为空串 = 清空该输入框
dsh-native a11y global <back|home|recents|notifications|quick_settings|lock_screen|power_dialog>
dsh-native a11y screenshot               # 截当前屏幕，PNG 落在 /tmp、JSON 里回路径
dsh-native display status                # 服务起没起、当前会话是哪块屏、走的哪条通道
dsh-native display session [--width N --height N --dpi N --bitrate K]   # 建虚拟屏；同尺寸会复用已有那块
dsh-native display shot [--display N]    # 截屏，PNG 落在 /tmp、JSON 里回路径与尺寸
dsh-native display tap <x> <y> [--display N]
dsh-native display swipe <x1> <y1> <x2> <y2> [--ms N] [--display N]
dsh-native display key <home|back|enter|...> [--display N]
dsh-native display launch <包名> [--display N]   # 把 App 起在那块虚拟屏上，而不是真实屏
dsh-native display stop                  # 立刻关掉服务端（虚拟屏跟着进程一起回收）
dsh-native caps                          # 查当前哪些能力开着、能不能用
dsh-native elevate <能力> <read|write|read_write|control> --reason <理由> [--command <命令>]
```

### 虚拟屏（`display`）

在一块**虚拟显示器**上启动目标 App 并代为操作 —— 截屏、点击、滑动、按键 —— 而用户自己的屏幕不受打扰。
服务端以 root/shell 身份用 `app_process` 起来，通过 Binder 把能力交回应用（同 /native/ 下其它能力一样，
需要一条就绪的提权通道）。`display 0` 是**真实屏幕**：`shot`/`tap`/`swipe`/`key` 对它也有效，
`launch` 才是只为虚拟屏准备的。

坐标一律从刚截的那张图里算：`shot` 回的是 PNG 路径与宽高，**先看图再算点**，别凭上一次的坐标猜。
每做一步就重新截一张，比「猜界面变成什么样了」可靠得多。

几个容易误解的地方：

- **同尺寸同 dpi 的 `session` 会复用已有那块屏**，不是每次都新建。想换尺寸就按新尺寸调用，那时才真的新建。
  这一条以前不是这样的（移植时把复用检查删掉了），后果是真机上攒下一串同尺寸的 `DshDisplay-*`
  虚拟屏、每块都占着一个硬件编码器，而只有 `display stop` 才会回收。
  **改尺寸新建之后旧的不会自己消失**，所以「现在到底有几块屏」得看得见：设置 → 权限管理 → 虚拟屏
  那张卡片上有「管理虚拟屏」，列出服务端进程里所有活着的屏（id / 尺寸 / dpi / 有没有画面在传 /
  上面跑的是哪个 App），能逐个预览、逐个终止，也能「全部终止」（=停掉服务端）。
  这一页**不会为了看列表把服务端拉起来**：打开设置顺手起一个 root 进程是纯粹的副作用，
  服务端没在跑它就如实显示"没有活着的虚拟屏"。
- **我们与移植源的参数对照**（同一块屏、同一个编码器前提下）：分辨率与 dpi 都取**设备真实值**
  （有的实现会减掉状态栏高度并按固定 dpi 建屏，那会让画面比例与真实设备不一致，按坐标点击就更容易偏）；
  默认码率我们 4 Mbps、移植源 3 Mbps（它另有 1.5/3/5/10/20 Mbps 四档预设，我们目前只有内部默认值）；
  帧率都用 30 fps、关键帧间隔 1 秒。这些数值可以在 `session --bitrate` 上按需覆盖 ——
  卡的时候先看小窗里那一行数字（入队/解码 fps、丢帧、队列峰值、平均等待），再决定调哪一个：
  解码 fps 低而丢帧少 = 编码端（码率/分辨率）；丢帧多或等待高 = 客户端解码跟不上。
- **用户能看着它操作**：agent 一开始用虚拟屏（**任何**一条 display 命令成功，包括显式带
  `--display N` 直接用已有屏那条路），屏幕边缘就会出现一个**折叠把手**，里面是 agent 正在操作的
  那个 App 的图标（`display launch` 起过谁就显示谁）；**点一下**展开成小窗，能看实时画面，还能拖、
  能切全屏；展开态**点一下画面**唤出控制条（折叠 / 全屏 / 终止，3 秒后自己隐去），控制条下面会
  带一行视频链路的数字（入队/解码 fps、丢帧、队列峰值、平均等待）—— 用户报「卡」时这行就是判据。
  **全屏时手指是直接的**：单击与滑动按预览页那套换算（`x * 虚拟屏宽 / 视频区宽`，位移小于 24px
  算单击）发给虚拟屏 —— 全屏就是「我要亲手点它」的场景；因为单指触摸都转发走了、覆盖层收不到，
  全屏态的控制条是右上角一个**常驻**小胶囊（同样是折叠 / 退出全屏 / 终止），随时都退得出来。
  转发只在这里发生（小窗态仍是只看不动）。
  **把手尽量不挡事，但不再"看不见"**：默认只在屏幕边缘占 40dp，出现时淡入、agent 每用一次屏就
  亮一下，**第一次**出现时还会撑开一条提示（之后不再打扰，记在 `display_float_hint_shown`）。
  把手上那个 ✕ 是**终止这块虚拟屏**（先弹确认框，因为它会让 agent 下一步失败）—— agent 之后调用
  同一个 id 会拿到 `display_terminated_by_user`，要问用户是否重建。想"别挡着我"而不是终止，请用
  折叠那个按钮。另外：不写 `--display` 时**只**认当前会话，没有会话回 `no_session`，**不会**悄悄
  落到真实屏幕（那是 `--display 0`，要显式写）。默认开；需要「显示在其他应用上层」这项特殊权限，
  在 设置 → 权限管理 → 虚拟屏 那一行里开。
  用户主动打开预览页时小窗让位 —— 服务端一块屏只有一个视频出口，两边同时挂会互相顶掉。
- **服务端的存活跟随 App**：它带看门狗（没有视频 sink、又没有客户端活动就退出），但 App 在运行期间
  每 10 秒 `ping` 一次，而 `ping` 也算客户端活动 —— 这是**刻意**的，否则 agent 在「截图 → 思考 → 点击」
  的十几秒间隔里就会丢掉整块虚拟屏。所以准确的描述是：**App 一死，服务端约 15 秒后自己退出**；
  只要 App 还活着，就只能靠 `display stop`（或预览页的停止按钮）立刻回收。
- **预览页看的就是 agent 那块屏**：它优先挂到已有会话上（`setVideoSink`），不会另开一块。
  用户点开预览时看到的画面，与 agent 正在操作的是同一个。

### 特权命令（`shell`）

这一项让容器里的 AI 真正用上你选的通道：App 代它执行，它自己不获得任何特权。三条通道的差别只在「谁执行」：

| 通道 | 身份 | 实现 |
| --- | --- | --- |
| root | uid 0 | 常驻 su shell |
| Shizuku | uid 0（Sui/root 模式）或 2000（adb 模式） | 送到 Shizuku 进程里的用户服务执行（`newProcess` 的返回类型是库内部可见的，应用侧编译不过） |
| 无线 ADB | 2000，`--su` 才到 0 | 转发给容器内那条脚本，于是它的两把锁照样生效 |

档位只有两档有意义：**读**只放行诊断类命令（与容器内脚本共用**同一张白名单**，`tools/check-native-logic.js`
会断言两边逐字一致），**读写**才能改设备状态。要不要问由「限制模式 + 两张清单」决定（见前文），每一次调用
都写进审计，记录里带上走的哪条通道、拿到的身份、当时的限制模式，以及这次是用户点过头还是自动放行的
（自动放行还分「开关关着」与「这条能力不在清单里」两种，事后复盘含义不同）。
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

**往输入框里写字的三种定位方式**：`--target`（文字/描述/view id）、**只给 `--class`**、以及都不给
（写当前焦点）。中间那一路是给 **WebView 里的编辑框**留的：网页元素在无障碍树里常常既没有 text 也没有
view id（`viewIdResourceName` 通常是 null），`class`（如 `EditText`）是唯一稳的抓手。三种方式失败的
原因不同：定位失败是 `not_found`，焦点失败是 `no_input_focus`（两者以前都叫 `no_input_focus`，
"没找到"因此会被读成"没焦点"）。

**我们自己的 Web UI 会给输入框补名字**：网页元素在无障碍树里没有 text / view id，所以
`DshWebUiActivity` 在文档开头注入一段垫片（`A11Y_SHIM`），把页面上**本来就显示给用户**的
`placeholder` 抄成 `aria-label`（已有 aria-label/labelledby/title 的不动，没有名字也不硬造）。
于是 agent 读树时能看到这些输入框的名字、也能按名字 `--target` 定位 —— 只对回环 origin 生效，
别的站点不被改语义。

**`a11y tree` 的判因字段**：`focused`（哪个节点拿着键盘焦点）、`input`（`FOCUS_INPUT` 解析到的那个
节点；解析不到时 `found:false`）、`a11y`（`FOCUS_ACCESSIBILITY`，即**读屏光标** —— 与输入焦点不是
一回事，常常还不在同一个窗里；两者都带 `window` = 回答者所在的窗 id）、`own`（活动窗口本来是我们
自己的悬浮窗，于是这棵树读的是别的窗）、`rootWindow`/`rootChildren`（这棵树从哪个窗读的、根上有
几个孩子 —— "怎么才 37 个节点"靠这两个字段分辨是"树被裁了"还是"读错了窗"），以及 `hideOwn`
（用户那个「无障碍别看本应用」开关当前是 `off`/`agent`/`all`）。
`no_input_focus` / `not_found` 的返回值里还会带 `window`、`windowReadable` 与 `windows[]`
（每窗 `package`/`own`/`id`/`type`/`system`/`active`/`focused`/`rootAvailable`）。`windowReadable` 的存在
是因为 `window`/`package` 都是从"根"上读的：**根没拿到与包名为空是两件事**，混在一起会把
"窗口读不到"误读成"包名为空"。

**「别看本应用」（`hideOwn` 不是 `off` 时）**：用户把设置里的开关拨到 `agent`（默认）或 `all` 之后，
**本应用自己的窗口**（含我们自己的悬浮窗）不进候选、也不进搜索表 —— 于是 `tree` 可能回 `no_window`
（"只剩自家窗"的 note 会说明是策略、不是锁屏），`--target`/`--class` 定位本应用界面里的节点一律
`not_found`（`searchedWindows` 里不会出现自家窗 id），`text` 的全局输入焦点落在自家窗里时也按
"没有焦点"处理。这是**策略**，不是 bug：不要重试、不要绕开；任务真要求驱动本应用自己的界面时，
请用户把那一档改成"都不拦"。`all` 档还会在视图层隐藏本应用（连 TalkBack 也读不到），但那只是
best effort —— 截屏（`a11y screenshot`）、`shell` 里的 `uiautomator`、WebView 的虚拟子树仍可能碰到。

**往输入框写字有两条路，返回值里的 `by` 说明走了哪条**：先 `ACTION_SET_TEXT`（uiautomator 的
`setText` 也是这一路，WebView 的输入框认它），被宿主拒绝时退到"聚焦 + 系统剪贴板 +
`ACTION_PASTE`"（自绘/接管输入的框常拒 SET_TEXT 但认粘贴；**粘完会把用户的剪贴板还原**）。
两路都不行才是 `set_text_rejected`。注意无障碍服务**拿不到**目标应用的 `InputConnection`：
AOSP 的 `AccessibilityNodeInfo` 里没有 `getInputConnection`/`commitText`，所以"改用
InputConnection"这条路在 Android 上不存在。

**`a11y text ""` 清空输入框**：`ACTION_SET_TEXT` 的语义就是把内容设成给定串，空串即清空 ——
不用再去找到并点中那个清除按钮（它往往连节点名都没有）。清空**不走粘贴退路**：把空剪贴板粘进
去对多数宿主是个无操作，而 `performAction` 照样回 true，那会变成"报告 ok、框里没清掉"；清不掉
就如实回 `set_text_rejected`。

**显式寻址（`--target` / `--class`）搜的是"这块屏幕上的所有可读窗"，不止活动窗**：真机上
"当前窗"有三个且可以互不相同 —— 活动窗（`tree` 读的那个）、输入焦点窗（`text` 的焦点路径写进去的那个）、
读屏焦点窗。现场就出现过 `input.window=29546` 与 `rootWindow=1` 并存的一轮：那时"服务自己刚写进去的字，
拿 `--target` 却查不到"，因为查找只在活动窗里翻。现在按固定优先级遍历（活动窗 → 输入焦点窗 → 读屏焦点窗 →
其余，自家悬浮窗一律跳过），`not_found` 会带 `searchedWindows`（实际搜过哪些窗）与 `matches`（命中几个），
`tree` 也**总是**带 `windows[]`。命中多个时用 `--index N` 挑第 N 个（`click` 与 `text` 同义；不写 `--index`
时 `text` 仍在同批命中里优先挑可编辑的那个）。

**焦点为什么可能"明明有输入框却报没有"**：`AccessibilityService.findFocus(FOCUS_INPUT)` 走
`ANY_WINDOW_ID`，服务端按 `getFocusedWindowId(FOCUS_INPUT)` 解析，并且**当那个窗不属于调用者的
display 类型时整个查询作废**（AOSP `resolveAccessibilityWindowIdForFindFocusLocked` →
`windowIdBelongsToDisplayType`）。镜像虚拟屏（proxy display）上的窗正好会踩这一条。所以焦点
路径是三层：系统解析 → 我们自己选中的那棵树里再问一次 → 那棵树里第一个可见可编辑节点。

**`a11y screenshot`（Android 11+）依赖一个"只在 bind 时读一次"的能力位**：服务必须在自己的 meta-data
里声明 `android:canTakeScreenshot="true"`（本仓在 `res/xml/dsh_a11y.xml`）。缺了它不是"降级"而是**硬失败**
—— 系统在服务端直接抛 `SecurityException`（AOSP：`canTakeScreenshotLocked` 失败），而且**运行时补不上**：
`setServiceInfo` 只同步事件类型/包名/flags 这类"可动态配置"的属性，capabilities 不在其中。所以
`no_screenshot_capability` 的含义是"这台机器在能力位生效之前就把服务 bind 上了"，处理办法是让用户把那个
无障碍开关**关一次再开**。另外两点也是系统给的：系统限制**最快 333ms 一张**（调用落在窗口里时我们等
≥350ms 自动重试一次，仍失败就回 `capture_failed_too_soon`），以及安全窗口/私有虚拟屏会让它回
`capture_failed_no_access` / `capture_failed_bad_display`。Android 11 以下是 `unsupported_os`。

**页面可能被用户脚本改过**：App 的插件页里有一处「用户脚本」—— 导入一个 `.user.js`（粘贴或选文件），
它会在页面脚本之前跑到我们自己的页面上（GM_getValue/setValue/deleteValue、GM_addStyle、GM_log、
GM_notification 可用）。所以同一版 App 的界面**可能不是出厂样子**：诊断"这个按钮怎么不见了"时，先看一眼
插件 → 用户脚本；那里关掉即可恢复（管理页是原生的，坏脚本弄白页面时它照样能开）。

那一页上半部分是**「应用内置」**：App 自己也往页面里注入五段脚本（旧内核 JS 兼容垫片、系统栏内边距、
手机回车换行、网页输入框的无障碍名字、blob:/data: 下载落地），其中兼容垫片与回车换行有开关（与设置里
同一处状态），其余三条恒开。**内置不受「用户脚本」总开关影响** —— 关掉总开关只停导入的脚本（脚本把
页面弄白时，内边距与无障碍名字还得在）。所以"界面和出厂不一样"既可能来自用户脚本，也可能来自这些内置
补丁：前者在该页关掉，后者看该页内置那几行的开关与说明。

**权限不够时，能力调用自己就是申请**：桥不会立刻回 403，而是**把这次调用挂住**，同时在 App 里弹窗；
用户答应就地执行这条命令、把真实结果还给 agent，用户拒绝（或 60 秒不处理）这次调用就以失败结束。
agent 因此不需要「先申请、再调一次」，也不会出现「申请成功了但调用还是失败」这种半途状态。

弹窗给用户**三个**选择 —— **允许**（级别落盘、长期生效）、**仅本次**（只放行这**一次**调用，用完自动收回，
设置里的开关不动）、**拒绝**（关掉弹窗等同拒绝）。档位本来就够、只是按「限制模式」或危险清单要用户点头的
那种弹窗没有「允许（长期）」（长期档位授权与「下次还要问」直接冲突），只有 **允许本次 / 拒绝**。弹窗正文里
**原文照显这次要执行的命令**（等宽、可选中复制）：用户要判断的从来不是「camera=write 要不要给」，而是
「它接下来到底要做什么」。命令由桥从这次调用本身重建，agent 不需要额外带 —— 显式的 `dsh-native elevate`
才需要 `--command` 来告诉用户「我打算做什么」。**想免掉后续确认，入口只有一个**：设置 → 权限管理 →
限制模式；弹窗里只写一句"想免掉去哪儿"，不给第二个入口（挂在弹窗上时用户看不到自己一共免掉了哪几项）。

**要不要问，只由两件事决定**（`PrivPolicy.needsConfirm`，一行判定，`check-native-logic.js` 逐格对拍）：

1. **危险操作清单**：命中就一直问，与开关无关。默认表来自 `PrivilegedShell` 里那两张写死的表
   （`rm` / `dd` / `mkfs` / `reboot`… 与 `pm uninstall` / `settings put` / `svc power`…），但它在设置里是
   **一份可增删的普通清单**：删掉一条就不再问它（判据取命令名 basename），加一条自定义命令同样立即生效，
   改乱了可以一键恢复默认（删过的内置条目记在"被删"集合里，加过的记在"增补"里，两者分开存，恢复默认
   各自归位）。增补只收「命令」或「命令 子命令」两段、只允许普通字符 —— 一个写错的正则会让某条命令
   **静默免问**，那比"不支持正则"危险得多。
2. **限制模式 + 能力清单**：默认**关**（新装如此，从旧模型迁移过来的老用户也如此）。关着时，能力一旦
   启用，agent 再用它就不问了；打开之后，清单里的能力每次都要你点头 —— 默认清单是 shell、虚拟屏、
   相机、麦克风、短信，同样可增删、可恢复默认。

**为什么把旧模型整个换掉**：旧模型是「全局严格程度（严格/一般/宽松）+ 按能力的「不再逐条确认」名单」，
与档位（允不允许做）叠成三个维度，组合出来的效果解释不清；更要紧的是它的 per-call 闸**只覆盖 shell /
虚拟屏 / 无障碍**三个能力 —— 短信、相机、麦克风、定位在档位启用之后再没被问过，"名单"对它们本来就无事
可做。现在只有一条判据、对所有能力一视同仁：**在不在清单里**决定"问不问"，**档位**决定"允不允许"。

老用户升级时按新模型重建（限制模式一律关、清单取默认值），并在**更新内容弹窗关掉之后**弹一次说明 ——
"默认不再逐条问了"这件事不说，用户下次看到 agent 静默动手会以为是 bug。

**无障碍的动作不再单独分级**：原先 `tree`/`screenshot` 只读、`click`/`swipe` 写、`text`/`global` 危险，
于是一旦进入"要问"的判定，`a11y text` 每次都弹。现在整族都不按动作分级：它在不在能力清单里决定
"问不问"，读写档位决定"允不允许"。

**虚拟屏的风险也不再单独分级**：读/写分档（`isWriteRequest`：查询与截图只读，建会话、点击、滑动、按键、
启动 App、停止是写）仍然决定**档位**，但不再决定"问不问"。以前它被归为危险档，于是**不管用户选哪一档，
每条点击都要弹窗**，「用虚拟屏」在实践中根本走不下去 —— 这也正是旧模型最难解释的地方：同一个端点，
档位按读放行、严格程度按写弹窗。

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
- **按键说话用 `mic start` + `mic stop`**，不要用 `mic record` 猜时长：`start` 立刻回一个 `id`
  （时长由说话的人定，不必按时长切段 —— 段间就没有那截静音），`stop --id` 收尾时
  `MediaRecorder.stop()` 会把 MP4 的索引写全，所以提前停也是好文件。到 `maxMs`（30000）会有
  看门狗自动收尾、文件留在暂存区；客户端慢一拍才 `stop` 同一个 `id` 时，那次结果会再给一遍
  （下一次 start 之前有效）。别人的 `id` 回 `409 bad_session`，早已结束又没结果回 `409 no_session`。
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
