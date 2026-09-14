package me.bmax.apatch.dsh

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
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

    /** 把 [node] 及子树转成 JSON；超限就截断并标出来。 */
    fun snapshot(maxDepth: Int = MAX_DEPTH, maxNodes: Int = MAX_NODES): JSONObject {
        val svc = service() ?: return JSONObject().put("ok", false).put("reason", "no_a11y_service")
        val root = svc.rootInActiveWindow
            ?: svc.windows.firstOrNull { it.isActive }?.root
            ?: return JSONObject()
                .put("ok", false)
                .put("reason", "no_window")
                .put("note", "The current window is not readable — a secure or system window (lock screen, password dialog).")
        val counter = intArrayOf(0)
        val truncated = booleanArrayOf(false)
        val tree = walk(root, 0, maxDepth.coerceIn(1, MAX_DEPTH), maxNodes.coerceIn(1, MAX_NODES), counter, truncated)
        return JSONObject()
            .put("ok", true)
            .put("package", root.packageName?.toString().orEmpty())
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

    /** 往可编辑节点里写字（默认写当前聚焦的那个）。 */
    fun setText(text: String, target: String?): JSONObject {
        val svc = service() ?: return fail("no_a11y_service")
        val node = if (target.isNullOrBlank()) {
            svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } else {
            findAll(svc, target, null).firstOrNull { it.isEditable }
                ?: findAll(svc, target, null).firstOrNull()
        }
        if (node == null) return fail("no_input_focus")
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
        "tree" -> PrivRisk.READONLY
        "text", "global" -> PrivRisk.DANGEROUS
        else -> PrivRisk.WRITE
    }

    private fun findAll(root: AccessibilityService, target: String, className: String?): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        root.rootInActiveWindow?.let { queue.add(it) }
            ?: root.windows.firstOrNull { it.isActive }?.root?.let { queue.add(it) }
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

    private fun fail(reason: String): JSONObject = JSONObject().put("ok", false).put("reason", reason)
}
