# TouchTool 点击助手

[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)

TouchTool 是一款 Android 自动化点击工具，通过无障碍服务实现自动点击、屏幕录制、OCR 识别等功能，帮助用户实现手机操作的自动化。

## 核心特色

### 蓝图式任务编辑

TouchTool 采用直观的**蓝图式任务编辑器**，通过可视化节点连接方式创建自动化流程。用户可以像搭建积木一样，将各种自动化动作以节点形式拖拽到画布上，并通过连接线定义执行顺序和逻辑关系，无需编写代码即可完成复杂的自动化任务设计。

### 多种多样的自动化动作

应用提供了**160+ 种自动化动作**，覆盖系统操作、界面交互、数据处理、逻辑控制等多个维度，满足各种自动化需求。

## 界面截图

| 截图 1                                                                        | 截图 2                                                                        | 截图 3                                                                        |
|-----------------------------------------------------------------------------|-----------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| ![截图1](docs/assets/images/screenshot1-77134f80bf7d86f7a4978e8c3b692ac0.jpg) | ![截图2](docs/assets/images/screenshot2-a52cd76986ccb347ad07ddc5f628c6b3.jpg) | ![截图3](docs/assets/images/screenshot3-f30f7abb0b0c05543869b2cb2aea22d2.jpg) |

| 截图 4                                                                        | 截图 5                                                                        | 截图 6                                                                        |
|-----------------------------------------------------------------------------|-----------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| ![截图4](docs/assets/images/screenshot7-d5e5302485c85d5a1384961ab1dfb7e5.jpg) | ![截图5](docs/assets/images/screenshot5-09dd0fa2d306e088cc2dca8b4a916b51.jpg) | ![截图6](docs/assets/images/screenshot6-f840352c9c9e6c4220ccbcea0dad2575.jpg) |

## 自动化动作分类

### 触发器类动作

| 动作类型                                                                                                  | 功能说明                         |
|-------------------------------------------------------------------------------------------------------|------------------------------|
| [`手动执行`](app/src/main/java/top/bogey/touch_tool/bean/action/start/ManualStartAction.java)             | 在选中应用内显示执行悬浮窗                |
| [`进应用或界面时执行`](app/src/main/java/top/bogey/touch_tool/bean/action/start/ApplicationStartAction.java)   | 进入对应应用或界面时自动执行               |
| [`退出应用时执行`](app/src/main/java/top/bogey/touch_tool/bean/action/start/ApplicationQuitStartAction.java) | 退出对应应用时自动执行                  |
| [`定时执行`](app/src/main/java/top/bogey/touch_tool/bean/action/start/TimeStartAction.java)               | 在设定的时间执行                     |
| [`收到新通知时执行`](app/src/main/java/top/bogey/touch_tool/bean/action/start/NotificationStartAction.java)   | 收到新通知或Toast时执行               |
| [`网络状态变更时执行`](app/src/main/java/top/bogey/touch_tool/bean/action/start/NetworkStartAction.java)       | WIFI、数据、VPN变更时执行             |
| [`电池状态变更时执行`](app/src/main/java/top/bogey/touch_tool/bean/action/start/BatteryStartAction.java)       | 电量变更或充电变更时执行                 |
| [`屏幕状态变更时执行`](app/src/main/java/top/bogey/touch_tool/bean/action/start/ScreenStartAction.java)        | 屏幕亮起关闭解锁时执行                  |
| [`蓝牙连接变更时执行`](app/src/main/java/top/bogey/touch_tool/bean/action/start/BluetoothStartAction.java)     | 蓝牙设备连接或断开时执行                 |
| [`收到分享时执行`](app/src/main/java/top/bogey/touch_tool/bean/action/start/ReceivedShareStartAction.java)   | 接受分享行为，分享的内容将自动转换为对应参数       |
| [`收到广播时执行`](app/src/main/java/top/bogey/touch_tool/bean/action/start/BroadcastStartAction.java)       | 收到指定广播时自动执行                  |
| [`外部调用`](app/src/main/java/top/bogey/touch_tool/bean/action/start/OutCallStartAction.java)            | 外部调用任务，能自动将传入的参数转换到任务内同名变量中去 |

### 逻辑控制类动作

| 动作类型                                                                                          | 功能说明                    |
|-----------------------------------------------------------------------------------------------|-------------------------|
| [`如果…就…`](app/src/main/java/top/bogey/touch_tool/bean/action/logic/IfConditionAction.java)    | 满足条件时执行，否则执行未达成节点       |
| [`等待条件成立`](app/src/main/java/top/bogey/touch_tool/bean/action/logic/WaitConditionAction.java) | 在一定时间内满足条件就执行，否则执行未达成节点 |
| [`选择执行`](app/src/main/java/top/bogey/touch_tool/bean/action/logic/SwitchAction.java)          | 根据文本值执行对应针脚             |
| [`手动选择执行`](app/src/main/java/top/bogey/touch_tool/bean/action/logic/ChoiceExecuteAction.java) | 弹出选择窗口供使用者手动选择          |
| [`循环执行…次`](app/src/main/java/top/bogey/touch_tool/bean/action/logic/ForLoopAction.java)       | 循环多次执行节点                |
| [`条件循环执行`](app/src/main/java/top/bogey/touch_tool/bean/action/logic/WhileLoopAction.java)     | 满足条件时执行，否则执行完成节点        |
| [`顺序执行`](app/src/main/java/top/bogey/touch_tool/bean/action/logic/SequenceExecuteAction.java) | 执行针脚从左到右顺序执行            |
| [`随机执行`](app/src/main/java/top/bogey/touch_tool/bean/action/logic/RandomExecuteAction.java)   | 从所有执行针脚中随机一个执行          |
| [`并行执行`](app/src/main/java/top/bogey/touch_tool/bean/action/logic/ParallelExecuteAction.java) | 多条执行并行执行，直到超时或者达成指定完成数量 |

### 通用动作

| 动作类型                                                                                           | 功能说明          |
|------------------------------------------------------------------------------------------------|---------------|
| [`延时`](app/src/main/java/top/bogey/touch_tool/bean/action/normal/DelayAction.java)             | 等待一段时间后再继续执行  |
| [`输出日志`](app/src/main/java/top/bogey/touch_tool/bean/action/normal/LoggerAction.java)          | 输出日志到日志界面     |
| [`贴图到屏幕上`](app/src/main/java/top/bogey/touch_tool/bean/action/normal/StickScreenAction.java)   | 将针脚内容贴到屏幕上    |
| [`关闭贴图`](app/src/main/java/top/bogey/touch_tool/bean/action/normal/StickCloseAction.java)      | 关闭对应贴图        |
| [`关闭所有贴图`](app/src/main/java/top/bogey/touch_tool/bean/action/normal/StickCloseAllAction.java) | 关闭屏幕所有贴图      |
| [`配置面板`](app/src/main/java/top/bogey/touch_tool/bean/action/normal/InputConfigAction.java)     | 手动输入任务运行需要的配置 |
| [`区域备注`](app/src/main/java/top/bogey/touch_tool/bean/action/normal/ShowTextAction.java)        | 对一片区域内的卡片进行备注 |

### 应用管理类动作

| 动作类型                                                                                        | 功能说明           |
|---------------------------------------------------------------------------------------------|----------------|
| [`打开应用`](app/src/main/java/top/bogey/touch_tool/bean/action/app/OpenAppAction.java)         | 打开选定的应用        |
| [`打开链接`](app/src/main/java/top/bogey/touch_tool/bean/action/app/OpenUriSchemeAction.java)   | 打开Uri Scheme链接 |
| [`打开快捷方式`](app/src/main/java/top/bogey/touch_tool/bean/action/app/OpenShortcutAction.java)  | 打开应用的快捷方式      |
| [`获取当前应用`](app/src/main/java/top/bogey/touch_tool/bean/action/app/GetCurrentAppAction.java) | 获取当前运行的应用与界面   |
| [`是否在应用内`](app/src/main/java/top/bogey/touch_tool/bean/action/app/CheckInAppAction.java)    | 判断待测应用或界面是否被包含 |
| [`文本转应用或界面`](app/src/main/java/top/bogey/touch_tool/bean/action/app/StringToAppAction.java) | 将文本转为应用或界面值    |
| [`等待在应用内`](app/src/main/java/top/bogey/touch_tool/bean/action/app/WaitInAppAction.java)     | 等待当前处于某个应用或界面内 |

### 系统操作类动作

| 动作类型                                                                                                 | 功能说明           |
|------------------------------------------------------------------------------------------------------|----------------|
| [`执行Shell命令`](app/src/main/java/top/bogey/touch_tool/bean/action/system/ExecuteShellAction.java)     | 执行Shell命令      |
| [`模拟按键`](app/src/main/java/top/bogey/touch_tool/bean/action/system/SystemKeyAction.java)             | 模拟系统按键功能       |
| [`播放铃声`](app/src/main/java/top/bogey/touch_tool/bean/action/system/PlayRingtoneAction.java)          | 播放选定的铃声        |
| [`停止铃声`](app/src/main/java/top/bogey/touch_tool/bean/action/system/StopRingtoneAction.java)          | 停止播放铃声         |
| [`文本转语音`](app/src/main/java/top/bogey/touch_tool/bean/action/system/TextToSpeechAction.java)         | 将文本转为语音并播放     |
| [`震动`](app/src/main/java/top/bogey/touch_tool/bean/action/system/VibrateAction.java)                 | 震动手机           |
| [`获取音量`](app/src/main/java/top/bogey/touch_tool/bean/action/system/GetVolumeAction.java)             | 获取音量大小         |
| [`设置音量`](app/src/main/java/top/bogey/touch_tool/bean/action/system/SetVolumeAction.java)             | 设置音量大小         |
| [`发送通知`](app/src/main/java/top/bogey/touch_tool/bean/action/system/SendNotificationAction.java)      | 发送自定义通知到通知栏    |
| [`发送广播`](app/src/main/java/top/bogey/touch_tool/bean/action/system/SendBroadcastAction.java)         | 发送自定义广播        |
| [`弹出提示`](app/src/main/java/top/bogey/touch_tool/bean/action/system/SendToastAction.java)             | 弹出自定义提示文本      |
| [`复制到剪贴板`](app/src/main/java/top/bogey/touch_tool/bean/action/system/WriteToClipboardAction.java)    | 将针脚值复制到剪贴板     |
| [`从剪贴板读取`](app/src/main/java/top/bogey/touch_tool/bean/action/system/ReadFromClipboardAction.java)   | 将剪贴板中的内容复制到针脚  |
| [`分享到`](app/src/main/java/top/bogey/touch_tool/bean/action/system/ShareToAction.java)                | 将针脚值分享到选定的应用   |
| [`屏幕录制开关`](app/src/main/java/top/bogey/touch_tool/bean/action/system/SwitchCaptureAction.java)       | 打开或关闭屏幕录制      |
| [`屏幕录制状态`](app/src/main/java/top/bogey/touch_tool/bean/action/system/CheckCaptureReadyAction.java)   | 判断屏幕录制是否开启     |
| [`开关屏幕`](app/src/main/java/top/bogey/touch_tool/bean/action/system/SwitchScreenAction.java)          | 打开或关闭屏幕        |
| [`获取屏幕状态`](app/src/main/java/top/bogey/touch_tool/bean/action/system/GetScreenStatusAction.java)     | 获取屏幕当前开关或锁定状态  |
| [`获取电池状态`](app/src/main/java/top/bogey/touch_tool/bean/action/system/GetBatteryStatusAction.java)    | 获取电池电量与充放电状态   |
| [`获取网络状态`](app/src/main/java/top/bogey/touch_tool/bean/action/system/GetNetworkStatusAction.java)    | 获取当前网络状态       |
| [`获取WiFi信息`](app/src/main/java/top/bogey/touch_tool/bean/action/system/GetWifiInfoAction.java)       | 获取当前连接的WiFi信息  |
| [`获取蓝牙设备`](app/src/main/java/top/bogey/touch_tool/bean/action/system/GetBluetoothDevicesAction.java) | 获取当前已连接的蓝牙设备列表 |
| [`获取日期`](app/src/main/java/top/bogey/touch_tool/bean/action/system/GetDateAction.java)               | 获取当前日期         |
| [`获取时间`](app/src/main/java/top/bogey/touch_tool/bean/action/system/GetTimeAction.java)               | 获取当前时间         |

### 数值计算类动作

| 动作类型                                                                                              | 功能说明             |
|---------------------------------------------------------------------------------------------------|------------------|
| [`数学公式`](app/src/main/java/top/bogey/touch_tool/bean/action/number/MathExpressionAction.java)     | 支持复杂的公式计算        |
| [`数值相加`](app/src/main/java/top/bogey/touch_tool/bean/action/number/NumberAddAction.java)          | 将多个数值相加          |
| [`数值相减`](app/src/main/java/top/bogey/touch_tool/bean/action/number/NumberSubAction.java)          | 两个数值相减           |
| [`数值相乘`](app/src/main/java/top/bogey/touch_tool/bean/action/number/NumberMulAction.java)          | 将多个数值相乘          |
| [`数值相除`](app/src/main/java/top/bogey/touch_tool/bean/action/number/NumberDivAction.java)          | 两个数值相除           |
| [`取余数`](app/src/main/java/top/bogey/touch_tool/bean/action/number/NumberModAction.java)           | 取两个数值的余数         |
| [`取绝对值`](app/src/main/java/top/bogey/touch_tool/bean/action/number/NumberAbsAction.java)          | 取一个数值的绝对值        |
| [`数值比较 =`](app/src/main/java/top/bogey/touch_tool/bean/action/number/NumberEqualAction.java)      | 判断两个数值是否相等       |
| [`数值比较 <`](app/src/main/java/top/bogey/touch_tool/bean/action/number/NumberLessAction.java)       | 判断第一个数值是否小于第二个数值 |
| [`数值比较 >`](app/src/main/java/top/bogey/touch_tool/bean/action/number/NumberGreaterAction.java)    | 判断第一个数值是否大于第二个数值 |
| [`随机数`](app/src/main/java/top/bogey/touch_tool/bean/action/number/NumberRandomAction.java)        | 生成一个随机数值         |
| [`取整`](app/src/main/java/top/bogey/touch_tool/bean/action/number/NumberToIntegerAction.java)      | 将数值按规则取整         |
| [`数值范围判断`](app/src/main/java/top/bogey/touch_tool/bean/action/number/CheckNumberInValueArea.java) | 判断数值是否在指定范围内     |
| [`组合为数值范围`](app/src/main/java/top/bogey/touch_tool/bean/action/number/NumberToValueArea.java)     | 将两个数值组合为一个数值范围   |

### 文本处理类动作

| 动作类型                                                                                                | 功能说明              |
|-----------------------------------------------------------------------------------------------------|-------------------|
| [`转换为文本`](app/src/main/java/top/bogey/touch_tool/bean/action/string/StringFromObjectAction.java)    | 将任意对象转换为文本        |
| [`文本转数值`](app/src/main/java/top/bogey/touch_tool/bean/action/string/StringToNumberAction.java)      | 提取文本中的第一个数值       |
| [`文本拼接`](app/src/main/java/top/bogey/touch_tool/bean/action/string/StringAppendAction.java)         | 将多个文本拼接成一个文本      |
| [`文本截取`](app/src/main/java/top/bogey/touch_tool/bean/action/string/StringSubStringAction.java)      | 将文本截取为指定范围的文本     |
| [`文本正则匹配`](app/src/main/java/top/bogey/touch_tool/bean/action/string/StringMatchAction.java)        | 执行正则匹配，判断是否包含特定文本 |
| [`文本分割`](app/src/main/java/top/bogey/touch_tool/bean/action/string/StringSplitAction.java)          | 将文本按特定字符分割为多个文本   |
| [`文本比较`](app/src/main/java/top/bogey/touch_tool/bean/action/string/StringEqualAction.java)          | 判断两个文本是否相等        |
| [`文本替换`](app/src/main/java/top/bogey/touch_tool/bean/action/string/StringReplaceAction.java)        | 将文本中的特定文本替换为另一个文本 |
| [`文本编码`](app/src/main/java/top/bogey/touch_tool/bean/action/string/StringEncodeAction.java)         | 将文本编码为指定编码        |
| [`文本解码`](app/src/main/java/top/bogey/touch_tool/bean/action/string/StringDecodeAction.java)         | 将文本解码为指定编码        |
| [`文本加密`](app/src/main/java/top/bogey/touch_tool/bean/action/string/StringEncryptAction.java)        | 将文本加密为指定编码        |
| [`OCR识别文本`](app/src/main/java/top/bogey/touch_tool/bean/action/string/GetOcrTextAction.java)        | 识别图片中指定区域文字       |
| [`OCR查找文本`](app/src/main/java/top/bogey/touch_tool/bean/action/string/FindOcrTextAction.java)       | 在图片中查找指定文字        |
| [`OCR文本是否存在`](app/src/main/java/top/bogey/touch_tool/bean/action/string/IsOcrTextExistAction.java)  | 判断图片中是否存在指定文字     |
| [`JSON解析`](app/src/main/java/top/bogey/touch_tool/bean/action/string/ParseJsonAction.java)          | 解析JSON字符串         |
| [`新建单选`](app/src/main/java/top/bogey/touch_tool/bean/action/string/StringToSingleSelectAction.java) | 创建一个自定义单选         |

### 条件判断类动作

| 动作类型                                                                                                    | 功能说明                       |
|---------------------------------------------------------------------------------------------------------|----------------------------|
| [`所有条件达成`](app/src/main/java/top/bogey/touch_tool/bean/action/bool/BooleanAndAction.java)               | 所有条件达成结果才是达成               |
| [`任意条件达成`](app/src/main/java/top/bogey/touch_tool/bean/action/bool/BooleanOrAction.java)                | 任意条件达成结果就能达成               |
| [`反转条件`](app/src/main/java/top/bogey/touch_tool/bean/action/bool/BooleanNotAction.java)                 | 取与条件相反的结果                  |
| [`短路所有条件达成`](app/src/main/java/top/bogey/touch_tool/bean/action/bool/BooleanAndShortCircuitAction.java) | 短路所有条件达成结果才是达成（遇到false则停止） |
| [`短路任意条件达成`](app/src/main/java/top/bogey/touch_tool/bean/action/bool/BooleanOrShortCircuitAction.java)  | 短路任意条件达成结果就能达成（遇到true则停止）  |

### 控件操作类动作

| 动作类型                                                                                           | 功能说明         |
|------------------------------------------------------------------------------------------------|--------------|
| [`查找控件`](app/src/main/java/top/bogey/touch_tool/bean/action/node/FindNodeAction.java)          | 根据条件获取控件     |
| [`控件是否存在`](app/src/main/java/top/bogey/touch_tool/bean/action/node/IsNodeExistAction.java)     | 判断控件是否存在     |
| [`获取区域内控件`](app/src/main/java/top/bogey/touch_tool/bean/action/node/GetNodesInAreaAction.java) | 获取与区域相交的所有控件 |
| [`获取控件信息`](app/src/main/java/top/bogey/touch_tool/bean/action/node/GetNodeInfoAction.java)     | 获取控件详细信息     |
| [`获取子控件`](app/src/main/java/top/bogey/touch_tool/bean/action/node/GetNodeChildrenAction.java)  | 获取控件所有子控件    |
| [`获取父控件`](app/src/main/java/top/bogey/touch_tool/bean/action/node/GetNodeParentAction.java)    | 获取控件的父控件     |
| [`获取所有窗口`](app/src/main/java/top/bogey/touch_tool/bean/action/node/GetWindowsAction.java)      | 获取界面上所有窗口    |
| [`是有效控件?`](app/src/main/java/top/bogey/touch_tool/bean/action/node/CheckNodeValidAction.java)  | 检查控件是否有效     |
| [`点击控件`](app/src/main/java/top/bogey/touch_tool/bean/action/node/NodeTouchAction.java)         | 点击传入的控件      |
| [`输入框输入文本`](app/src/main/java/top/bogey/touch_tool/bean/action/node/EditTextInputAction.java)  | 对文本输入框输入文本   |
| [`输入框粘贴`](app/src/main/java/top/bogey/touch_tool/bean/action/node/EditTextPasteAction.java)    | 对文本输入框粘贴文本   |
| [`手动选择控件`](app/src/main/java/top/bogey/touch_tool/bean/action/node/PickNodeAction.java)        | 选择一个控件       |

### 图像处理类动作

| 动作类型                                                                                         | 功能说明            |
|----------------------------------------------------------------------------------------------|-----------------|
| [`获取屏幕界面`](app/src/main/java/top/bogey/touch_tool/bean/action/image/GetImageAction.java)     | 获取当前屏幕指定区域图片    |
| [`加载图片`](app/src/main/java/top/bogey/touch_tool/bean/action/image/LoadImageAction.java)      | 加载图片到任务         |
| [`裁剪图片`](app/src/main/java/top/bogey/touch_tool/bean/action/image/CropImageAction.java)      | 裁剪图片            |
| [`缩放图片`](app/src/main/java/top/bogey/touch_tool/bean/action/image/ResizeImageAction.java)    | 缩放图片            |
| [`保存图片`](app/src/main/java/top/bogey/touch_tool/bean/action/image/SaveImageAction.java)      | 静默保存图片到图片文件夹    |
| [`识别图片`](app/src/main/java/top/bogey/touch_tool/bean/action/image/FindImageAction.java)      | 识别目标图片在图片中的位置   |
| [`识别所有匹配图片`](app/src/main/java/top/bogey/touch_tool/bean/action/image/FindImagesAction.java) | 识别目标图片在图片中的所有区域 |
| [`图片是否存在`](app/src/main/java/top/bogey/touch_tool/bean/action/image/IsImageExistAction.java) | 判断目标图片是否存在      |
| [`点击图片`](app/src/main/java/top/bogey/touch_tool/bean/action/image/TouchImageAction.java)     | 点击目标图片          |
| [`生成二维码`](app/src/main/java/top/bogey/touch_tool/bean/action/image/CreateQRCodeAction.java)  | 生成二维码           |
| [`解析二维码`](app/src/main/java/top/bogey/touch_tool/bean/action/image/ParseQRCodeAction.java)   | 解析二维码           |
| [`目标识别`](app/src/main/java/top/bogey/touch_tool/bean/action/image/YoloDetectAction.java)     | 识别图片中的目标（YOLO）  |

### 颜色处理类动作

| 动作类型                                                                                         | 功能说明            |
|----------------------------------------------------------------------------------------------|-----------------|
| [`获取颜色`](app/src/main/java/top/bogey/touch_tool/bean/action/image/GetColorAction.java)       | 获取图片指定位置颜色      |
| [`识别颜色`](app/src/main/java/top/bogey/touch_tool/bean/action/image/FindColorsAction.java)     | 识别指定颜色在图片中的所有区域 |
| [`颜色是否存在`](app/src/main/java/top/bogey/touch_tool/bean/action/image/IsColorExistAction.java) | 判断指定颜色是否存在      |
| [`颜色比较`](app/src/main/java/top/bogey/touch_tool/bean/action/image/ColorEqualAction.java)     | 判断两个颜色是否相似      |
| [`点击颜色`](app/src/main/java/top/bogey/touch_tool/bean/action/image/TouchColorAction.java)     | 点击指定颜色          |

### 区域操作类动作

| 动作类型                                                                                                 | 功能说明              |
|------------------------------------------------------------------------------------------------------|-------------------|
| [`区域转数值`](app/src/main/java/top/bogey/touch_tool/bean/action/area/AreaToIntegerAction.java)          | 提取区域的上下左右数值       |
| [`数值转区域`](app/src/main/java/top/bogey/touch_tool/bean/action/area/AreaFromIntegerAction.java)        | 将数值转换为区域          |
| [`区域偏移`](app/src/main/java/top/bogey/touch_tool/bean/action/area/AreaOffsetAction.java)              | 将区域进行偏移           |
| [`位置是否在区域里`](app/src/main/java/top/bogey/touch_tool/bean/action/area/CheckAreaContainPosAction.java) | 判断一个位置是否在指定区域内    |
| [`区域关系`](app/src/main/java/top/bogey/touch_tool/bean/action/area/CheckAreaRelationAction.java)       | 判断两个区域是否包含、相交、不相交 |
| [`取区域交集`](app/src/main/java/top/bogey/touch_tool/bean/action/area/GetAreaIntersectionAction.java)    | 取两个区域相交的区域        |
| [`取区域中心`](app/src/main/java/top/bogey/touch_tool/bean/action/area/GetAreaCenterAction.java)          | 取指定区域的中心位置        |
| [`取区域随机位置`](app/src/main/java/top/bogey/touch_tool/bean/action/area/GetAreaRandomAction.java)        | 取指定区域的随机位置        |
| [`手动选择区域`](app/src/main/java/top/bogey/touch_tool/bean/action/area/PickAreaAction.java)              | 选择一个区域            |

### 位置操作类动作

| 动作类型                                                                                            | 功能说明       |
|-------------------------------------------------------------------------------------------------|------------|
| [`位置转数值`](app/src/main/java/top/bogey/touch_tool/bean/action/point/PointToIntegerAction.java)   | 提取位置的横纵坐标  |
| [`数值转位置`](app/src/main/java/top/bogey/touch_tool/bean/action/point/PointFromIntegerAction.java) | 将数值转换为位置   |
| [`位置偏移`](app/src/main/java/top/bogey/touch_tool/bean/action/point/PointOffsetAction.java)       | 将位置进行偏移    |
| [`位置转为手势`](app/src/main/java/top/bogey/touch_tool/bean/action/point/PointToTouchAction.java)    | 将位置转换为手势   |
| [`位置列表转为手势`](app/src/main/java/top/bogey/touch_tool/bean/action/point/PointsToTouchAction.java) | 将位置列表转换为手势 |
| [`执行手势`](app/src/main/java/top/bogey/touch_tool/bean/action/point/TouchAction.java)             | 执行指定手势     |
| [`点击位置`](app/src/main/java/top/bogey/touch_tool/bean/action/point/TouchPointAction.java)        | 点击指定位置     |

### 列表操作类动作

| 动作类型                                                                                            | 功能说明              |
|-------------------------------------------------------------------------------------------------|-------------------|
| [`新建列表`](app/src/main/java/top/bogey/touch_tool/bean/action/list/MakeListAction.java)           | 新建一个列表            |
| [`列表长度`](app/src/main/java/top/bogey/touch_tool/bean/action/list/ListSizeAction.java)           | 获取列表长度            |
| [`列表是否为空`](app/src/main/java/top/bogey/touch_tool/bean/action/list/ListIsEmptyAction.java)      | 判断列表是否为空          |
| [`是否包含元素`](app/src/main/java/top/bogey/touch_tool/bean/action/list/ListContainAction.java)      | 判断列表是否包含元素        |
| [`添加元素`](app/src/main/java/top/bogey/touch_tool/bean/action/list/ListAddAction.java)            | 向列表中添加元素          |
| [`移除索引元素`](app/src/main/java/top/bogey/touch_tool/bean/action/list/ListRemoveAction.java)       | 按索引从列表中移除元素       |
| [`移除元素对象`](app/src/main/java/top/bogey/touch_tool/bean/action/list/ListRemoveObjectAction.java) | 按对象从列表中移除元素       |
| [`列表拼接`](app/src/main/java/top/bogey/touch_tool/bean/action/list/ListAppendAction.java)         | 将两个列表拼接           |
| [`清空列表`](app/src/main/java/top/bogey/touch_tool/bean/action/list/ListClearAction.java)          | 清空列表              |
| [`获取元素`](app/src/main/java/top/bogey/touch_tool/bean/action/list/ListGetAction.java)            | 从列表中获取元素          |
| [`手动选择元素`](app/src/main/java/top/bogey/touch_tool/bean/action/list/ListChoiceAction.java)       | 弹出选择窗口供使用者选择列表的一项 |
| [`设置元素`](app/src/main/java/top/bogey/touch_tool/bean/action/list/ListSetAction.java)            | 向列表中设置元素          |
| [`元素索引`](app/src/main/java/top/bogey/touch_tool/bean/action/list/ListIndexOfAction.java)        | 获取元素在列表中的索引       |
| [`获取子列表`](app/src/main/java/top/bogey/touch_tool/bean/action/list/ListSubListAction.java)       | 获取列表中指定范围的子列表     |
| [`遍历列表`](app/src/main/java/top/bogey/touch_tool/bean/action/list/ListForeachAction.java)        | 遍历列表中的每个元素        |

### 字典操作类动作

| 动作类型                                                                                           | 功能说明        |
|------------------------------------------------------------------------------------------------|-------------|
| [`新建字典`](app/src/main/java/top/bogey/touch_tool/bean/action/map/MakeMapAction.java)            | 新建一个字典      |
| [`字典长度`](app/src/main/java/top/bogey/touch_tool/bean/action/map/MapSizeAction.java)            | 获取字典长度      |
| [`字典是否为空`](app/src/main/java/top/bogey/touch_tool/bean/action/map/MapIsEmptyAction.java)       | 判断字典是否为空    |
| [`字典是否包含键`](app/src/main/java/top/bogey/touch_tool/bean/action/map/MapContainKeyAction.java)   | 判断字典是否包含键   |
| [`字典是否包含值`](app/src/main/java/top/bogey/touch_tool/bean/action/map/MapContainValueAction.java) | 判断字典是否包含值   |
| [`添加或替换值`](app/src/main/java/top/bogey/touch_tool/bean/action/map/MapSetAction.java)           | 向字典中添加或替换值  |
| [`移除键值对`](app/src/main/java/top/bogey/touch_tool/bean/action/map/MapRemoveAction.java)         | 从字典中移除键值对   |
| [`字典拼接`](app/src/main/java/top/bogey/touch_tool/bean/action/map/MapAppendAction.java)          | 将两个字典拼接     |
| [`清空字典`](app/src/main/java/top/bogey/touch_tool/bean/action/map/MapClearAction.java)           | 清空字典        |
| [`获取值`](app/src/main/java/top/bogey/touch_tool/bean/action/map/MapGetAction.java)              | 从字典中获取值     |
| [`获取键`](app/src/main/java/top/bogey/touch_tool/bean/action/map/MapGetKeysAction.java)          | 获取字典中的所有键   |
| [`获取值`](app/src/main/java/top/bogey/touch_tool/bean/action/map/MapGetValuesAction.java)        | 获取字典中的所有值   |
| [`遍历字典`](app/src/main/java/top/bogey/touch_tool/bean/action/map/MapForeachAction.java)         | 遍历字典中的每个键值对 |

## 其他功能特性

- **屏幕录制**：记录屏幕操作并生成自动化脚本
- **OCR 识别**：通过集成 OCR 服务实现文字识别
- **无障碍服务**：基于 Android 无障碍服务实现自动化控制
- **定时任务**：支持定时执行自动化任务
- **手势模拟**：支持复杂手势操作的模拟
- **悬浮窗控制**：提供悬浮窗界面快速控制
- **任务复用**：支持子任务调用，实现任务模块化
- **变量系统**：支持全局和局部变量，实现数据传递
- **标签管理**：支持任务和变量的标签分类

## 许可证

本项目使用 [GNU General Public License v3.0](LICENSE) 许可证。

## 贡献指南

欢迎提交 Issue 和 Pull Request 来改进项目。

## 联系方式

如有问题或建议，请通过以下方式联系：

- 提交 GitHub Issue
- 发送邮件至项目维护者
- 加入 QQ群-529463048 进行反馈

## 致谢

感谢所有为本项目做出贡献的开发者和用户。
