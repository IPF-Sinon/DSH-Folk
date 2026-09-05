package me.bmax.apatch.dsh

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import me.bmax.apatch.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音合成（`/native/tts/speak`、`/native/tts/file`、`/native/tts/voices`）：把文字交给
 * **系统自带**的 TTS 引擎读出来。
 *
 * 用系统引擎而不是自带一个：手机上已经有一个（国行多是讯飞 / 小米的，海外是 Google
 * 的），用户对它的音色和语速已经有预期，而打包一个离线合成模型是几十 MB 的事。
 *
 * ## 三个能做的事
 *
 * - [speak]：直接朗读。手机在口袋里也能听见 —— 这是这项能力真正的用处：长任务跑完
 *   了、需要人来做决定了，让 agent 说一声，而不是指望用户盯着屏幕。
 * - [toFile]：合成成 wav 落到容器 `/tmp`。agent 可以把它当素材接着处理。
 * - [voices]：报告有哪些语言/音色可用。**必须有这个**：能不能读中文取决于设备上装了
 *   什么引擎，agent 只能问，猜不出来。
 *
 * ## 为什么每次都新建再关掉
 *
 * `TextToSpeech` 是一个绑定到外部引擎进程的连接。长期持有它意味着：那个引擎进程被我们
 * 吊着不放（有些引擎因此常驻几十 MB），而用户在系统设置里换了引擎或语言之后我们还连着
 * 旧的。这里每次请求建一次、用完 [TextToSpeech.shutdown]，代价是初始化的几百毫秒 ——
 * 对「读一句话」这种量级完全可以接受，换来的是不留任何后台连接。
 *
 * ## 与相机/录音不同：**不**要求前台
 *
 * 朗读在后台是完全正常的（音频播放本来就该能后台跑），也没有任何系统限制。这一项是
 * 少见的「agent 在后台做了也有意义」的能力 —— 恰恰因为用户没看着屏幕，才需要听见。
 */
internal object DshTts {
    private const val TAG = "DshTts"

    /**
     * 初始化超时。
     *
     * 引擎要跨进程 bind，冷启动一两秒是常事；但如果设备上根本没装 TTS 引擎，
     * `OnInitListener` 会回 [TextToSpeech.ERROR] 而不是一直不回，所以这个超时只
     * 兜住「引擎装着但起不来」的情形。
     */
    private const val INIT_TIMEOUT_MS = 8_000L

    /**
     * 朗读的等待上限。
     *
     * 朗读是同步等到读完才回响应的：agent 需要知道「读完了」而不是「已经开始读」——
     * 否则它下一句紧接着又调一次，两句话会互相打断（[TextToSpeech.QUEUE_FLUSH]）或者
     * 排成一条听不懂的长队。
     *
     * 60 秒对应 4000 字符上限下最慢的语速。真正的长文本应该走 [toFile]。
     */
    private const val SPEAK_TIMEOUT_MS = 60_000L

    /** 合成到文件的等待上限。写盘比朗读快，但要留出引擎首次加载音库的时间。 */
    private const val SYNTH_TIMEOUT_MS = 30_000L

    /**
     * 文本长度上限。
     *
     * `TextToSpeech.getMaxSpeechInputLength()` 在 AOSP 上恒返回 4000，但它是**引擎**
     * 的上限：超了 `speak` 直接返回 ERROR，不会截断。这里主动截并在响应里说明截了多少，
     * 比让 agent 收到一个没有细节的 ERROR 有用。
     */
    private const val MAX_TEXT = 3900

    /**
     * 语速与音调的合法区间。
     *
     * `setSpeechRate` 的文档只说 1.0 是正常、值越大越快，没有给范围 —— 实践中低于 0.1
     * 会被引擎当成 0（一个字都不读），高于 3 已经完全听不懂。夹到这个区间是为了不让
     * agent 传个 0 进来然后困惑于「返回 ok 但没声音」。
     */
    private const val RATE_MIN = 0.1f
    private const val RATE_MAX = 3.0f
    private const val PITCH_MIN = 0.5f
    private const val PITCH_MAX = 2.0f

    /** 一次最多报告的音色数：Google 引擎每种语言给好几个，全给出去只会挤爆上下文。 */
    private const val MAX_VOICES = 60

    /**
     * 朗读互斥。
     *
     * 两个并发的 `speak` 会互相打断（后到的用 QUEUE_FLUSH 清掉前一句）或排成长队，
     * 而两边都在等自己的 utterance 完成 —— 结果是一个假成功、一个超时。直接回 409
     * 让 agent 自己排队。
     */
    private val speaking = AtomicBoolean(false)

    // ───────────────────────── 端点 ─────────────────────────

    /** 朗读一段文字，等读完才返回。 */
    fun speak(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val raw = params["text"].orEmpty()
        if (raw.isBlank()) {
            return 400 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_missing_param, "text"),
                "missing_text",
            )
        }
        if (!speaking.compareAndSet(false, true)) {
            return 409 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_tts_busy),
                "already_speaking",
            )
        }
        try {
            return withEngine(ctx) { tts ->
                val text = raw.take(MAX_TEXT)
                val lang = applyVoice(tts, params)
                val id = "dsh-${System.currentTimeMillis()}"
                val done = CountDownLatch(1)
                // 用 UtteranceProgressListener 而不是「speak 返回 SUCCESS 就算成功」：
                // 后者只表示「请求已排入引擎队列」，此刻一个字都还没读。
                var failed = -1
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = done.countDown()

                    @Deprecated("由带 errorCode 的重载取代，但基类要求实现它")
                    override fun onError(utteranceId: String?) {
                        failed = TextToSpeech.ERROR
                        done.countDown()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        failed = errorCode
                        done.countDown()
                    }
                })
                val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
                if (queued != TextToSpeech.SUCCESS) {
                    return@withEngine 500 to DshNativeBridge.err(
                        DshNativeBridge.str(ctx, R.string.dsh_native_err_tts_speak),
                        "speak_failed",
                    )
                }
                val finished = runCatching {
                    done.await(SPEAK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }.getOrDefault(false)
                if (!finished) {
                    // 超时就停掉：留着它继续读，用户会在几十秒后听到一段莫名其妙的话
                    runCatching { tts.stop() }
                    return@withEngine 504 to DshNativeBridge.err(
                        DshNativeBridge.str(ctx, R.string.dsh_native_err_tts_timeout),
                        "timeout",
                    )
                }
                if (failed >= 0) {
                    return@withEngine 500 to DshNativeBridge.err(
                        DshNativeBridge.str(ctx, R.string.dsh_native_err_tts_synth, errorName(failed)),
                        errorReason(failed),
                    )
                }
                200 to JSONObject()
                    .put("ok", true)
                    .put("spoken", text.length)
                    .put("truncated", raw.length > text.length)
                    .put("language", lang)
                    .put("engine", tts.defaultEngine.orEmpty())
                    .toString()
            }
        } finally {
            speaking.set(false)
        }
    }

    /**
     * 合成到 wav 文件，落在容器 `/tmp/dsh-native/` 下。
     *
     * 与 [speak] 共用 [speaking] 互斥：多数引擎的合成与朗读走同一条管道，并发时表现和
     * 两个 speak 一样糟。
     */
    fun toFile(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val raw = params["text"].orEmpty()
        if (raw.isBlank()) {
            return 400 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_missing_param, "text"),
                "missing_text",
            )
        }
        if (!speaking.compareAndSet(false, true)) {
            return 409 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_tts_busy),
                "already_speaking",
            )
        }
        try {
            return withEngine(ctx) { tts ->
                val text = raw.take(MAX_TEXT)
                val lang = applyVoice(tts, params)
                val dir = DshNativeBridge.stageDir(ctx)
                val out = File(dir, "tts_${System.currentTimeMillis()}.wav")
                val id = "dsh-file-${System.currentTimeMillis()}"
                val done = CountDownLatch(1)
                var failed = -1
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = done.countDown()

                    @Deprecated("由带 errorCode 的重载取代，但基类要求实现它")
                    override fun onError(utteranceId: String?) {
                        failed = TextToSpeech.ERROR
                        done.countDown()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        failed = errorCode
                        done.countDown()
                    }
                })
                val queued = runCatching {
                    tts.synthesizeToFile(text, Bundle(), out, id)
                }.getOrDefault(TextToSpeech.ERROR)
                if (queued != TextToSpeech.SUCCESS) {
                    out.delete()
                    return@withEngine 500 to DshNativeBridge.err(
                        DshNativeBridge.str(ctx, R.string.dsh_native_err_tts_speak),
                        "speak_failed",
                    )
                }
                val finished = runCatching {
                    done.await(SYNTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }.getOrDefault(false)
                if (!finished || failed >= 0) {
                    // 半成品 wav 比没有文件更坏：agent 会把它当成功的产物接着用
                    out.delete()
                    return@withEngine if (!finished) {
                        504 to DshNativeBridge.err(
                            DshNativeBridge.str(ctx, R.string.dsh_native_err_tts_timeout),
                            "timeout",
                        )
                    } else {
                        500 to DshNativeBridge.err(
                            DshNativeBridge.str(ctx, R.string.dsh_native_err_tts_synth, errorName(failed)),
                            errorReason(failed),
                        )
                    }
                }
                // 有引擎会「成功」地写出一个 0 字节文件（音库缺失时），那不是成功
                if (!out.isFile || out.length() == 0L) {
                    out.delete()
                    return@withEngine 500 to DshNativeBridge.err(
                        DshNativeBridge.str(ctx, R.string.dsh_native_err_tts_empty),
                        "empty_output",
                    )
                }
                DshNativeBridge.trimStage(dir)
                200 to JSONObject()
                    .put("ok", true)
                    .put("path", DshNativeBridge.stageGuestPath(out.name))
                    .put("bytes", out.length())
                    .put("spoken", text.length)
                    .put("truncated", raw.length > text.length)
                    .put("language", lang)
                    .toString()
            }
        } finally {
            speaking.set(false)
        }
    }

    /**
     * 报告可用的语言与音色。
     *
     * agent 必须能问到这个：设备上装了什么引擎决定了能读什么语言，而中文支持在海外
     * ROM 上经常是缺的。返回里 `installed` 区分「引擎声称支持」与「音库真的在本机」——
     * 后者才是能直接读出来的。
     */
    fun voices(ctx: Context): Pair<Int, String> = withEngine(ctx) { tts ->
        val langs = JSONArray()
        val seen = HashSet<String>()
        runCatching {
            for (loc in tts.availableLanguages.orEmpty().sortedBy { it.toLanguageTag() }) {
                val tag = loc.toLanguageTag()
                if (!seen.add(tag)) continue
                langs.put(tag)
            }
        }
        val voices = JSONArray()
        runCatching {
            // 音色可能有几十上百个（Google 引擎每种语言给好几个），全给出去只会挤爆
            // agent 的上下文。按语言标签排序后截断，并在 truncated 里说明。
            val all = tts.voices.orEmpty()
                .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
            for (v in all.take(MAX_VOICES)) voices.put(voiceJson(v))
        }
        val total = runCatching { tts.voices.orEmpty().size }.getOrDefault(0)
        200 to JSONObject()
            .put("ok", true)
            .put("engine", tts.defaultEngine.orEmpty())
            .put("default", runCatching { tts.defaultVoice?.locale?.toLanguageTag() }.getOrNull().orEmpty())
            .put("languages", langs)
            .put("voices", voices)
            .put("voiceCount", total)
            .put("truncated", total > MAX_VOICES)
            .toString()
    }

    private fun voiceJson(v: Voice): JSONObject = JSONObject()
        .put("name", v.name)
        .put("locale", v.locale.toLanguageTag())
        // 音库在不在本机：needsNetwork 为 true 的音色断网就读不出来
        .put("network", v.isNetworkConnectionRequired)
        .put("quality", v.quality)
        .put("latency", v.latency)

    // ───────────────────────── 引擎生命周期 ─────────────────────────

    /**
     * 建一个 TTS 连接、跑 [block]、无论成败都关掉。
     *
     * `TextToSpeech` 的构造函数**立刻返回**，真正可用要等 `OnInitListener`；在那之前
     * 调任何方法都返回 ERROR。所以这里用闭锁等初始化，而不是构造完就用。
     */
    private fun withEngine(
        ctx: Context,
        block: (TextToSpeech) -> Pair<Int, String>,
    ): Pair<Int, String> {
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts = runCatching {
            TextToSpeech(ctx.applicationContext) { s ->
                status = s
                ready.countDown()
            }
        }.getOrElse { e ->
            Log.w(TAG, "TTS 构造失败: ${e.message}")
            return 500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_tts_init),
                "tts_init_failed",
            )
        }
        val inited = runCatching {
            ready.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!inited || status != TextToSpeech.SUCCESS) {
            runCatching { tts.shutdown() }
            // 分开两种：没装引擎（status=ERROR，立刻回）与引擎起不来（超时）。
            // 前者要提示用户去装一个，后者重试可能就好了。
            return if (!inited) {
                504 to DshNativeBridge.err(
                    DshNativeBridge.str(ctx, R.string.dsh_native_err_tts_timeout),
                    "timeout",
                )
            } else {
                500 to DshNativeBridge.err(
                    DshNativeBridge.str(ctx, R.string.dsh_native_err_tts_no_engine),
                    "no_engine",
                )
            }
        }
        return try {
            block(tts)
        } catch (e: Throwable) {
            Log.w(TAG, "TTS 处理失败: ${e.message}")
            500 to DshNativeBridge.err(
                DshNativeBridge.str(ctx, R.string.dsh_native_err_tts_synth, e.message ?: ""),
                "tts_failed",
            )
        } finally {
            // shutdown 一定要走到：不然那个引擎进程被我们吊着不放
            runCatching { tts.shutdown() }
        }
    }

    /**
     * 应用语言、语速、音调，返回最终生效的语言标签。
     *
     * 返回**实际**语言而不是请求的那个：请求 zh-CN 但设备只有 en 时，引擎会退回到默认
     * 语言照读（读出来是一串拼音式的怪音）。让 agent 看到实际值，它才能判断这次朗读
     * 到底有没有意义。
     */
    private fun applyVoice(tts: TextToSpeech, params: Map<String, String>): String {
        params["rate"]?.toFloatOrNull()?.let {
            runCatching { tts.setSpeechRate(it.coerceIn(RATE_MIN, RATE_MAX)) }
        }
        params["pitch"]?.toFloatOrNull()?.let {
            runCatching { tts.setPitch(it.coerceIn(PITCH_MIN, PITCH_MAX)) }
        }
        val want = params["lang"]?.takeIf { it.isNotBlank() }
        if (want != null) {
            runCatching {
                val loc = Locale.forLanguageTag(want)
                // 只在真的支持时才切：setLanguage 对不支持的语言会退回默认，
                // 而那意味着「用英语引擎念中文」
                val avail = tts.isLanguageAvailable(loc)
                if (avail == TextToSpeech.LANG_AVAILABLE ||
                    avail == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    avail == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                ) {
                    tts.language = loc
                }
            }
        }
        return runCatching { tts.voice?.locale?.toLanguageTag() }.getOrNull().orEmpty()
    }

    /** 引擎是否可用：设备上有没有装 TTS 引擎。用于 `availability`。 */
    fun hasEngine(ctx: Context): Boolean = runCatching {
        // 不去 bind 引擎（那要几百毫秒，而 availability 会被 /native/capabilities
        // 频繁调用）；查系统的默认引擎设置就够：没有引擎时它是空的。
        !Settings.Secure.getString(ctx.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
            .isNullOrBlank()
    }.getOrElse { true }

    /** 错误码 → 可读名字，进给用户看的报错。 */
    private fun errorName(code: Int): String = when (code) {
        TextToSpeech.ERROR_SYNTHESIS -> "synthesis"
        TextToSpeech.ERROR_SERVICE -> "service"
        TextToSpeech.ERROR_OUTPUT -> "output"
        TextToSpeech.ERROR_NETWORK -> "network"
        TextToSpeech.ERROR_NETWORK_TIMEOUT -> "network_timeout"
        TextToSpeech.ERROR_INVALID_REQUEST -> "invalid_request"
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "not_installed_yet"
        else -> "error_$code"
    }

    /**
     * 错误码 → 稳定的机器可读 reason。
     *
     * 与 [errorName] 分开是因为 reason 是协议的一部分（agent 按它分支），
     * 而 errorName 只是拼进本地化文案里的一个词。
     */
    private fun errorReason(code: Int): String = when (code) {
        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> "tts_network"
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "tts_voice_not_installed"
        TextToSpeech.ERROR_INVALID_REQUEST -> "tts_invalid_request"
        else -> "tts_failed"
    }
}
