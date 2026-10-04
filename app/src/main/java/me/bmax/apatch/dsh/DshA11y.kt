package me.bmax.apatch.dsh

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无障碍能力的实现：把当前屏幕读成一份节点树，或对它做一次操作。
 *
 * 与其它能力的区别在于**目标不是本应用**：它操作的是用户此刻正在看的那个界面（可能是
 * 微信、可能是系统设置）。所以这一项的档位比别的能力更要紧（关 / 只看 / 还能操作），
 * 而且每一次动作都要进审计。
 *
 * 三条限制是系统给的、不是我们加的，反过来必须写进返回值里（否则 agent 会以为是我们坏了）：
 * - **安全窗口**：锁屏、密码输入框所在的窗口，系统不给节点（`rootInActiveWindow` 为空
 *   或 `FLAG_SECURE`），读不到也点不了；
 * - **坐标**：手势是按屏幕绝对坐标发的，跨设备、跨分辨率都不通用，所以按文字/ id 定位
 *   优先于坐标，读出来的 bounds 也一并返回；
 * - **输入框**：`ACTION_SET_TEXT` 只对真正可编辑的节点有效，点在普通文本上返回 false。
 */
internal object DshA11y {
    private const val TAG = "DshA11y"

    /** 节点树的两个上限：深度与节点总数。屏幕再乱也不至于让一次读屏吃掉几十万字符。 */
    const val MAX_DEPTH = 12
    const val MAX_NODES = 400

    /** 手势的总时长上限（毫秒）。一次点击 40ms 足够，长按/滑动由调用方给 duration。 */
    private const val MAX_GESTURE_MS = 3_000L

    /** 服务在不在（用户在系统设置 → 无障碍里打开了没有）。 */
    fun connected(): Boolean = DshA11yService.service() != null

    private fun service(): AccessibilityService? = DshA11yService.service()

    /**
     * 把 [node] 及子树转成 JSON；超限就截断并标出来。
     *
     * 除了树本身，还回三样**判因**用的东西（都是踩出来的坑，见各自说明）：
     * - `own`：活动窗口本来是我们**自己的悬浮窗**、这一棵读的是别的窗；
     * - `input`：当前"键盘输入焦点"落在哪（`FOCUS_INPUT` 解析不到时说 `found:false`）；
     * - `package`：读的到底是哪个包（`home` 之后仍报自己包名就是这么看出来的）。
     */
    fun snapshot(maxDepth: Int = MAX_DEPTH, maxNodes: Int = MAX_NODES): JSONObject {
        val svc = service() ?: return JSONObject().put("ok", false).put("reason", "no_a11y_service")
        val (root, skippedOwn) = pickRoot(svc)
        if (root == null) {
            return windowDiag(
                svc,
                JSONObject()
                    .put("ok", false)
                    .put("reason", "no_window")
                    .put("note", "The current window is not readable — a secure or system window (lock screen, password dialog)."),
            )
        }
        val counter = intArrayOf(0)
        val truncated = booleanArrayOf(false)
        val tree = walk(root, 0, maxDepth.coerceIn(1, MAX_DEPTH), maxNodes.coerceIn(1, MAX_NODES), counter, truncated)
        return JSONObject()
            .put("ok", true)
            .put("package", root.packageName?.toString().orEmpty())
            // 活动窗口本来是我们自己的悬浮窗（于是这棵树读的是别的窗）—— 不写出来，
            // 「package 怎么是自己」就会变成下一个要靠猜的谜
            .put("own", skippedOwn)
            .put("input", inputDiag(svc))
            .put("nodes", counter[0])
            .put("truncated", truncated[0])
            .put("tree", tree)
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        depth: Int,
        maxDepth: Int,
        maxNodes: Int,
        counter: IntArray,
        truncated: BooleanArray,
    ): JSONObject? {
        if (node == null) return null
        if (counter[0] >= maxNodes) {
            truncated[0] = true
            return null
        }
        counter[0]++
        val obj = JSONObject()
        node.text?.toString()?.takeIf { it.isNotEmpty() }?.let { obj.put("text", it) }
        node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { obj.put("desc", it) }
        node.viewIdResourceName?.let { obj.put("id", it.substringAfterLast('/')) }
        node.className?.toString()?.substringAfterLast('.')?.let { obj.put("class", it) }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        obj.put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
        // 键盘输入焦点落在谁身上：agent 找输入框时这一位比什么都直接（以前树里根本没有它，
        // 于是"节点没报 focused"其实是"我们从没问过"）
        if (node.isFocused) obj.put("focused", true)
        if (node.isClickable) obj.put("clickable", true)
        if (node.isEditable) obj.put("editable", true)
        if (node.isScrollable) obj.put("scrollable", true)
        if (node.isCheckable) obj.put("checked", node.isChecked)
        if (depth >= maxDepth) {
            if (node.childCount > 0) truncated[0] = true
            return obj
        }
        val children = JSONArray()
        for (i in 0 until node.childCount) {
            val child = walk(node.getChild(i), depth + 1, maxDepth, maxNodes, counter, truncated) ?: continue
            children.put(child)
        }
        if (children.length() > 0) obj.put("children", children)
        return obj
    }

    /**
     * 按文字或 view id（可再带 class 过滤）找节点并点击。
     *
     * 优先点「能点的那个祖先」：屏幕上的文案节点自己往往 `isClickable=false`，真正接点击的
     * 是它的父容器 —— 直接对文案节点 `ACTION_CLICK` 会返回 true 却什么也不发生，那是最难查
     * 的一类「点了没用」。
     */
    fun click(target: String, className: String?, index: Int): JSONObject {
        val svc = service() ?: return fail("no_a11y_service")
        val matches = findAll(svc, target, className)
        if (matches.isEmpty()) return fail("not_found").put("target", target)
        val picked = matches.getOrNull(index) ?: matches[0]
        val clickable = generateSequence(picked) { it.parent }.firstOrNull { it.isClickable }
        if (clickable == null) {
            // 找不到可点祖先：退回按中心坐标点一次（很多自绘控件只认触摸）
            val rect = Rect().also { picked.getBoundsInScreen(it) }
            val tapped = tap((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f, 60L)
            return if (tapped.optBoolean("ok")) {
                JSONObject().put("ok", true).put("by", "gesture").put("target", target)
            } else {
                fail("not_clickable").put("target", target)
            }
        }
        val done = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (done) {
            JSONObject().put("ok", true).put("by", "node").put("target", target)
        } else {
            fail("click_rejected").put("target", target)
        }
    }

    /**
     * 往可编辑节点里写字。
     *
     * 三条定位方式，按语义从窄到宽：
     * - 给了 `target`（可再带 `class` 过滤）：按文字/描述/view id 找（与 [click] 同一套匹配）；
     * - **只给了 `class`**：WebView 里的原生编辑框经常既没有 text 也没有 view id
     *   （`viewIdResourceName` 对网页元素通常是 null），`class` 是唯一稳的抓手 ——
     *   `findAll` 的匹配是 `text.contains(target)`，target 传空串即命中全部，再被 class 滤掉；
     * - 两个都没给：写**当前聚焦**的那个（`FOCUS_INPUT`）。
     *
     * 三种方式失败的 reason 不同：定位失败是 `not_found`（附上找的是什么），
     * 焦点失败是 `no_input_focus`（附上是哪个窗口）—— 否则"没找到"会被读成"没焦点"。
     */
    fun setText(text: String, target: String?, className: String? = null): JSONObject {
        val svc = service() ?: return fail("no_a11y_service")
        val byFocus = target.isNullOrBlank() && className.isNullOrBlank()
        if (byFocus) {
            val focused = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused == null) return windowDiag(svc, fail("no_input_focus"))
            return writeInto(focused, text)
        }
        val matches = findAll(svc, target.orEmpty(), className)
        val node = matches.firstOrNull { it.isEditable } ?: matches.firstOrNull()
            ?: return windowDiag(
                svc,
                fail("not_found")
                    .put("target", target.orEmpty())
                    .put("class", className.orEmpty()),
            )
        return writeInto(node, text)
    }

    /** 真正落字：不可编辑也不可点 = 这个节点现在还不是输入框（状态不符，不是找不到）。 */
    private fun writeInto(node: AccessibilityNodeInfo, text: String): JSONObject {
        if (!node.isEditable && !node.isClickable) return fail("not_editable")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val done = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (done) JSONObject().put("ok", true) else fail("set_text_rejected")
    }

    /** 按屏幕坐标点一下。坐标来自 [snapshot] 里读到的 bounds。 */
    fun tap(x: Float, y: Float, durationMs: Long): JSONObject =
        gesture(x, y, x, y, durationMs)

    /** 从 (x1,y1) 滑到 (x2,y2)。 */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): JSONObject =
        gesture(x1, y1, x2, y2, durationMs)

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): JSONObject {
        val svc = service() ?: return fail("no_a11y_service")
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val duration = durationMs.coerceIn(20L, MAX_GESTURE_MS)
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val dispatched = svc.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    ok.set(true)
                    latch.countDown()
                }

                override fun onCancelled(description: GestureDescription?) {
                    latch.countDown()
                }
            },
            null,
        )
        if (!dispatched) return fail("gesture_rejected")
        // 等回调：手势是异步的，不等的话「执行成功」只是「排队成功」
        latch.await(duration + 1_500L, TimeUnit.MILLISECONDS)
        return if (ok.get()) JSONObject().put("ok", true) else fail("gesture_cancelled")
    }

    /** 系统级动作：back / home / recents / notifications / quick_settings / lock_screen。 */
    fun global(action: String): JSONObject {
        val svc = service() ?: return fail("no_a11y_service")
        val code = when (action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "recent" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "notification" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings", "quicksettings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen", "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "power_dialog", "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            else -> return fail("unknown_action").put("action", action)
        }
        val done = svc.performGlobalAction(code)
        return if (done) JSONObject().put("ok", true).put("action", action)
        else fail("action_rejected").put("action", action)
    }

    /**
     * 这次无障碍动作的风险等级。
     *
     * 「看」是只读；点按与滑动会改界面状态，算写；**往输入框打字**与**系统级动作**算危险 ——
     * 前者可能把消息发出去、把密码填进别的应用，后者能直接锁屏或返回桌面，都不该在宽松档
     * 里悄悄发生。
     */
    fun riskOf(action: String): PrivRisk = when (action.lowercase()) {
        "tree", "screenshot" -> PrivRisk.READONLY
        "text", "global" -> PrivRisk.DANGEROUS
        else -> PrivRisk.WRITE
    }

    /** 截屏等待上限：takeScreenshot 是异步回调，别让桥接线程无限期挂着。 */
    private const val SCREENSHOT_TIMEOUT_MS = 4000L

    /**
     * 两次 a11y 截屏之间的最小间隔。
     *
     * 系统侧的硬限制（`ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS = 333`，
     * 见 AOSP AbstractAccessibilityServiceConnection.takeScreenshot）：太密就直接回
     * `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`。而这个数字在返回码里是看不到的 ——
     * 调用方（agent）看到"截屏失败"只会以为坏了，所以这里**自己**等一小会儿再试一次。
     */
    private const val SCREENSHOT_MIN_GAP_MS = 350L

    /**
     * 系统返回的截屏错误码 → 能行动的原因。
     *
     * 直接用 `capture_failed_3` 这种写法（以前的实现）虽然诚实，但对 agent 完全不可读：
     * 3 是"间隔太短"、2 是"无障碍访问没给"、6 是"安全窗口" —— 三者的处理方式完全不同
     * （等一下 / 让用户开开关 / 换个界面）。
     */
    private fun screenshotReason(errorCode: Int): String = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "capture_failed_internal"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "capture_failed_no_access"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "capture_failed_too_soon"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "capture_failed_bad_display"
        else -> "capture_failed_$errorCode"
    }

    /**
     * 截取当前屏幕（[AccessibilityService.takeScreenshot]，Android 11+）。
     *
     * 与 media/camera 一样：字节落进容器 `/tmp` 暂存区，回**容器内路径**而不是把二进制塞回
     * JSON。安全窗口（锁屏 / 标了 FLAG_SECURE 的界面）系统会拒绝，回 capture_failed_* ——
     * 那是系统在挡，不是 bug。回调在主执行器上跑，用闩锁等它、超时按失败处理。
     *
     * ## 一个**只在 bind 时读一次**的能力位
     *
     * 这一条是踩过的坑，写在这里省得再踩：`takeScreenshot` 要求服务在它的 meta-data 里
     * 声明 `android:canTakeScreenshot="true"`（见 res/xml/dsh_a11y.xml）。缺了它，**系统
     * 在服务端直接抛 SecurityException**（AOSP：canTakeScreenshotLocked 失败），客户端拿到的
     * 只是一次普通的失败 —— 以前这里就把它归成 `capture_failed`，于是"截屏永远失败"看起来
     * 像是设备或权限的问题，其实是配置里少了一位。
     *
     * 更麻烦的是：capabilities 属于"只在 bind 时从 XML 读"的那一类
     * （`setServiceInfo` 只同步 updateDynamicallyConfigurableProperties，不含它），所以
     * **运行时补不上**，App 升级后老连接也可能还带着旧能力位。所以这里先自查一次能力位：
     * 缺了就回一个**指名道姓**的原因，用户去"无障碍"里关一次再开即可。
     */
    fun screenshot(ctx: Context): JSONObject {
        val svc = service() ?: return fail("no_a11y_service")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return fail("unsupported_os")
        if (!canTakeScreenshot(svc)) return fail("no_screenshot_capability")

        // 间隔太短时自己补一次：350ms 之后重试，最多两回。
        var attempt = 0
        while (true) {
            val shot = captureOnce(ctx, svc)
            val bmp = shot.first
            val reason = shot.second
            if (bmp != null) return saveResult(ctx, bmp)
            // too_soon 是**暂时性**的（系统有 333ms 的下限），值得再试一次；其余按失败返回
            if (reason == "capture_failed_too_soon" && attempt == 0) {
                attempt++
                runCatching { Thread.sleep(SCREENSHOT_MIN_GAP_MS) }
                continue
            }
            return fail(reason ?: "capture_failed")
        }
    }

    /** 这个服务现在有没有截屏能力位（见 [screenshot] 的说明）。 */
    private fun canTakeScreenshot(svc: AccessibilityService): Boolean = runCatching {
        val info = svc.serviceInfo ?: return@runCatching true
        (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
    }.getOrDefault(true)

    /** 试一次；回调在主执行器上跑，这里用闩锁等它（超时按失败）。 */
    private fun captureOnce(
        ctx: Context,
        svc: AccessibilityService,
    ): Pair<Bitmap?, String?> {
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var reason: String? = null
        runCatching {
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ctx.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = result.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            // 复制成软件位图再压缩：硬件缓冲不能直接 PNG 编码，且随后要 close
                            bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped?.recycle()
                            hb.close()
                            if (bitmap == null) reason = "decode_failed"
                        } catch (e: Throwable) {
                            reason = "decode_failed"
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        reason = screenshotReason(errorCode)
                        latch.countDown()
                    }
                },
            )
        }.onFailure {
            // 能力位缺失时，系统的 SecurityException 就走这条路（见 KDoc）：别把它缩成
            // 一个笼统的 capture_failed，那会让"配置少一位"看起来像"设备不支持"。
            reason = if (it is SecurityException) "no_screenshot_capability" else "capture_failed"
            latch.countDown()
        }
        if (!latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null to "timeout"
        return bitmap to reason
    }

    /** 落盘 + 收尾（原 screenshot 的后半段，拆出来是为了让重试那段读得下去）。 */
    private fun saveResult(ctx: Context, bmp: Bitmap): JSONObject {
        val width = bmp.width
        val height = bmp.height
        val dir = DshNativeBridge.stageDir(ctx)
        val out = File(dir, "shot_${System.currentTimeMillis()}.png")
        val written = runCatching {
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        }.getOrDefault(false)
        bmp.recycle()
        if (!written) {
            runCatching { out.delete() }
            return fail("write_failed")
        }
        DshNativeBridge.trimStage(dir)
        return JSONObject()
            .put("ok", true)
            .put("path", DshNativeBridge.stageGuestPath(out.name))
            .put("width", width)
            .put("height", height)
    }

    private fun findAll(root: AccessibilityService, target: String, className: String?): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        pickRoot(root).first?.let { queue.add(it) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES * 8) {
            val node = queue.removeFirst()
            visited++
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
            val cls = node.className?.toString().orEmpty()
            val classOk = className.isNullOrBlank() || cls.endsWith(className)
            if (classOk && (text == target || desc == target || id == target ||
                    text.contains(target) || desc.contains(target))
            ) {
                out.add(node)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    /**
     * 读哪一棵树、往哪个窗口里找节点。
     *
     * 优先"活动窗口"，但**跳过我们自己的悬浮窗**：它是常驻特性（TYPE_APPLICATION_OVERLAY），
     * `FLAG_NOT_FOCUSABLE` 保证它不抢输入焦点，可它照样在无障碍的窗口列表里、也可能被当成
     * 活动窗口 —— 那时 agent 是对着一个把手读屏、在错误的树里找目标，表现成"节点找不到"，
     * 而真正的原因是我们自己的窗冒充了前台。
     *
     * 只跳"系统窗口"那一类：**本应用自己的界面**（TYPE_APPLICATION）常常正是 agent 要驱动的
     * 目标（本仓的聊天/搜索框就在自己的 WebView 里），跳掉它等于把能力从自己身上拿掉。
     *
     * @return 选中的 root，以及"是否因为活动窗口是自己的悬浮窗而改选了别的"
     */
    private fun pickRoot(svc: AccessibilityService): Pair<AccessibilityNodeInfo?, Boolean> {
        val active = svc.rootInActiveWindow
        val ownOverlayActive = svc.windows.firstOrNull { it.isActive }?.let { isOwnOverlay(svc, it) } ?: false
        if (active != null && !ownOverlayActive) return active to false
        val other = svc.windows.asSequence()
            .filter { !isOwnOverlay(svc, it) }
            .mapNotNull { it.root }
            .firstOrNull()
        // 没有别的可读窗口就退回活动窗口：读我们自己的树，也好过读不到
        return (other ?: active ?: svc.windows.firstOrNull { it.isActive }?.root) to ownOverlayActive
    }

    /** 这个窗口是本应用自己的悬浮窗吗（系统窗口 + 自家包名）。 */
    private fun isOwnOverlay(svc: AccessibilityService, window: AccessibilityWindowInfo): Boolean =
        window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
            window.root?.packageName?.toString() == svc.packageName

    /** 当前"键盘输入焦点"落在哪个节点上（解析不到就是"没人有焦点"，不是异常）。 */
    private fun inputDiag(svc: AccessibilityService): JSONObject {
        val n = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return JSONObject().put("found", false)
        return JSONObject()
            .put("found", true)
            .put("class", n.className?.toString()?.substringAfterLast('.').orEmpty())
            .put("text", n.text?.toString().orEmpty())
            .put("id", n.viewIdResourceName?.substringAfterLast('/').orEmpty())
            .put("editable", n.isEditable)
    }

    /**
     * 失败时附上"问的是哪个窗口"。
     *
     * 一次真机就能分开两类 `no_input_focus`：解析到的是**目标 App** 的窗（那问题在 WebView
     * 的节点契约），还是**我们自己的悬浮窗**（见 [pickRoot]）。没有这两个字段时，两种成因
     * 在返回值里一模一样。
     */
    private fun windowDiag(svc: AccessibilityService, out: JSONObject): JSONObject {
        out.put("window", svc.rootInActiveWindow?.packageName?.toString().orEmpty())
        val ws = JSONArray()
        for (w in svc.windows) {
            ws.put(
                JSONObject()
                    .put("package", w.root?.packageName?.toString().orEmpty())
                    .put("system", w.type == AccessibilityWindowInfo.TYPE_SYSTEM)
                    .put("active", w.isActive)
                    .put("focused", w.isFocused),
            )
        }
        return out.put("windows", ws)
    }

    private fun fail(reason: String): JSONObject = JSONObject().put("ok", false).put("reason", reason)
}
