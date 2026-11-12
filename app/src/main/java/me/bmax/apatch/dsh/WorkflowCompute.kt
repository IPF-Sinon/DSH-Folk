package me.bmax.apatch.dsh

import me.bmax.apatch.R
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 纯计算动作：在引擎里本地求值，**不调端点、不需要系统能力**。
 *
 * ## 为什么要单独一册
 *
 * [WorkflowActionCatalog] 里的每条 `Action` 都强制绑一个系统能力与一个真实端点，
 * 门禁会逐条核对那个端点在 `DshNativeBridge` 的 dispatch 表里存在。
 * 数值加减、文本拼接这类动作没有端点 —— 硬塞进去就得编一个假端点，
 * 那等于让"动作列表里有一条永远跑不通"。
 *
 * 所以计算类单独成册：它们的"能力"只是算力，执行器直接算。
 * 好处是这些动作**在任何权限状态下都能用**，也就没有任何批准弹窗噪音。
 *
 * ## 与点击助手的对应关系
 *
 * 参考实现把这些放在 `bean/action/number`、`bean/action/string`、`bean/action/list`、
 * `bean/action/map`、`bean/action/bool` 下，同样不需要系统权限。
 * 这里按"用户想干什么"分组，与目录其余部分保持一致的两级结构。
 */
internal object WorkflowCompute {

    /** 参数的控件类型。与 [WorkflowActionCatalog.ParamKind] 同构，但这里不依赖它。 */
    enum class Kind { TEXT, NUMBER, MULTILINE }

    /**
     * 一个计算动作。
     *
     * [params] 只描述**输入**；输出统一是 `result`（见 [eval] 的返回值）。
     * 参数键不带前缀（不像原生动作的 `p_`）：计算动作没有跨册重名的可能，
     * 而且这些键会直接进 config，前缀只会让流程文件更难读。
     */
    data class Compute(
        val id: String,
        val groupTitleRes: Int,
        val titleRes: Int,
        val descRes: Int,
        val params: List<Param> = emptyList(),
    )

    data class Param(
        val key: String,
        val labelRes: Int,
        val kind: Kind = Kind.TEXT,
        val default: String = "",
    )

    private fun p(key: String, labelRes: Int, kind: Kind = Kind.TEXT, default: String = "") =
        Param(key, labelRes, kind, default)

    // ── 分组标题（复用执行类已有的一组文案）──
    private val G_NUMBER = R.string.dsh_wf_cat_number
    private val G_TEXT = R.string.dsh_wf_cat_text
    private val G_DATA = R.string.dsh_wf_cat_data_ops
    private val G_BOOL = R.string.dsh_wf_cat_bool

    val ALL: List<Compute> = listOf(
        // ── 数值 ──
        Compute("num_add", G_NUMBER, R.string.dsh_wf_compute_add, R.string.dsh_wf_compute_add_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a, Kind.NUMBER, "0"), p("b", R.string.dsh_wf_field_compute_b, Kind.NUMBER, "0"))),
        Compute("num_sub", G_NUMBER, R.string.dsh_wf_compute_sub, R.string.dsh_wf_compute_sub_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a, Kind.NUMBER, "0"), p("b", R.string.dsh_wf_field_compute_b, Kind.NUMBER, "0"))),
        Compute("num_mul", G_NUMBER, R.string.dsh_wf_compute_mul, R.string.dsh_wf_compute_mul_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a, Kind.NUMBER, "0"), p("b", R.string.dsh_wf_field_compute_b, Kind.NUMBER, "0"))),
        Compute("num_div", G_NUMBER, R.string.dsh_wf_compute_div, R.string.dsh_wf_compute_div_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a, Kind.NUMBER, "0"), p("b", R.string.dsh_wf_field_compute_b, Kind.NUMBER, "1"))),
        Compute("num_mod", G_NUMBER, R.string.dsh_wf_compute_mod, R.string.dsh_wf_compute_mod_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a, Kind.NUMBER, "0"), p("b", R.string.dsh_wf_field_compute_b, Kind.NUMBER, "2"))),
        Compute("num_abs", G_NUMBER, R.string.dsh_wf_compute_abs, R.string.dsh_wf_compute_abs_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a, Kind.NUMBER, "0"))),
        Compute("num_round", G_NUMBER, R.string.dsh_wf_compute_round, R.string.dsh_wf_compute_round_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a, Kind.NUMBER, "0"))),
        Compute("num_max", G_NUMBER, R.string.dsh_wf_compute_max, R.string.dsh_wf_compute_max_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a, Kind.NUMBER, "0"), p("b", R.string.dsh_wf_field_compute_b, Kind.NUMBER, "0"))),
        Compute("num_min", G_NUMBER, R.string.dsh_wf_compute_min, R.string.dsh_wf_compute_min_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a, Kind.NUMBER, "0"), p("b", R.string.dsh_wf_field_compute_b, Kind.NUMBER, "0"))),
        Compute("num_random", G_NUMBER, R.string.dsh_wf_compute_random, R.string.dsh_wf_compute_random_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_min, Kind.NUMBER, "0"), p("b", R.string.dsh_wf_field_compute_max, Kind.NUMBER, "100"))),

        // ── 比较（输出 1 / 0，可直接喂 condition 的 source）──
        Compute("num_eq", G_NUMBER, R.string.dsh_wf_compute_num_eq, R.string.dsh_wf_compute_num_eq_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a, Kind.NUMBER, "0"), p("b", R.string.dsh_wf_field_compute_b, Kind.NUMBER, "0"))),
        Compute("num_lt", G_NUMBER, R.string.dsh_wf_compute_num_lt, R.string.dsh_wf_compute_num_lt_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a, Kind.NUMBER, "0"), p("b", R.string.dsh_wf_field_compute_b, Kind.NUMBER, "0"))),
        Compute("num_gt", G_NUMBER, R.string.dsh_wf_compute_num_gt, R.string.dsh_wf_compute_num_gt_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a, Kind.NUMBER, "0"), p("b", R.string.dsh_wf_field_compute_b, Kind.NUMBER, "0"))),

        // ── 文本 ──
        Compute("str_append", G_TEXT, R.string.dsh_wf_compute_str_append, R.string.dsh_wf_compute_str_append_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a), p("b", R.string.dsh_wf_field_compute_b))),
        Compute("str_substring", G_TEXT, R.string.dsh_wf_compute_str_substring, R.string.dsh_wf_compute_str_substring_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a), p("start", R.string.dsh_wf_field_compute_start, Kind.NUMBER, "0"), p("len", R.string.dsh_wf_field_compute_len, Kind.NUMBER, "0"))),
        Compute("str_replace", G_TEXT, R.string.dsh_wf_compute_str_replace, R.string.dsh_wf_compute_str_replace_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a), p("from", R.string.dsh_wf_field_compute_from), p("to", R.string.dsh_wf_field_compute_to))),
        Compute("str_split", G_TEXT, R.string.dsh_wf_compute_str_split, R.string.dsh_wf_compute_str_split_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a), p("sep", R.string.dsh_wf_field_compute_sep, default = ","))),
        Compute("str_regex", G_TEXT, R.string.dsh_wf_compute_str_regex, R.string.dsh_wf_compute_str_regex_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a), p("pattern", R.string.dsh_wf_field_compute_pattern))),
        Compute("str_trim", G_TEXT, R.string.dsh_wf_compute_str_trim, R.string.dsh_wf_compute_str_trim_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a))),
        Compute("str_len", G_TEXT, R.string.dsh_wf_compute_str_len, R.string.dsh_wf_compute_str_len_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a))),
        Compute("str_upper", G_TEXT, R.string.dsh_wf_compute_str_upper, R.string.dsh_wf_compute_str_upper_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a))),
        Compute("str_lower", G_TEXT, R.string.dsh_wf_compute_str_lower, R.string.dsh_wf_compute_str_lower_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a))),
        Compute("str_equal", G_TEXT, R.string.dsh_wf_compute_str_equal, R.string.dsh_wf_compute_str_equal_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a), p("b", R.string.dsh_wf_field_compute_b))),
        Compute("str_contains", G_TEXT, R.string.dsh_wf_compute_str_contains, R.string.dsh_wf_compute_str_contains_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a), p("b", R.string.dsh_wf_field_compute_b))),
        Compute("str_encode", G_TEXT, R.string.dsh_wf_compute_str_encode, R.string.dsh_wf_compute_str_encode_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a))),
        Compute("str_decode", G_TEXT, R.string.dsh_wf_compute_str_decode, R.string.dsh_wf_compute_str_decode_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a))),

        // ── 布尔 ──
        Compute("bool_and", G_BOOL, R.string.dsh_wf_compute_bool_and, R.string.dsh_wf_compute_bool_and_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a), p("b", R.string.dsh_wf_field_compute_b))),
        Compute("bool_or", G_BOOL, R.string.dsh_wf_compute_bool_or, R.string.dsh_wf_compute_bool_or_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a), p("b", R.string.dsh_wf_field_compute_b))),
        Compute("bool_not", G_BOOL, R.string.dsh_wf_compute_bool_not, R.string.dsh_wf_compute_bool_not_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a))),

        // ── JSON ──
        Compute("json_get", G_DATA, R.string.dsh_wf_compute_json_get, R.string.dsh_wf_compute_json_get_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_json, Kind.MULTILINE), p("path", R.string.dsh_wf_field_compute_json_path, default = ""))),
        Compute("json_keys", G_DATA, R.string.dsh_wf_compute_json_keys, R.string.dsh_wf_compute_json_keys_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_json, Kind.MULTILINE))),

        // ── 列表（序列化形式：JSON 数组字符串）──
        Compute("list_make", G_DATA, R.string.dsh_wf_compute_list_make, R.string.dsh_wf_compute_list_make_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_a), p("b", R.string.dsh_wf_field_compute_b))),
        Compute("list_size", G_DATA, R.string.dsh_wf_compute_list_size, R.string.dsh_wf_compute_list_size_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_list))),
        Compute("list_get", G_DATA, R.string.dsh_wf_compute_list_get, R.string.dsh_wf_compute_list_get_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_list), p("index", R.string.dsh_wf_field_compute_index, Kind.NUMBER, "0"))),
        Compute("list_add", G_DATA, R.string.dsh_wf_compute_list_add, R.string.dsh_wf_compute_list_add_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_list), p("b", R.string.dsh_wf_field_compute_a))),
        Compute("list_contains", G_DATA, R.string.dsh_wf_compute_list_contains, R.string.dsh_wf_compute_list_contains_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_list), p("b", R.string.dsh_wf_field_compute_a))),
        Compute("list_join", G_DATA, R.string.dsh_wf_compute_list_join, R.string.dsh_wf_compute_list_join_desc,
            listOf(p("a", R.string.dsh_wf_field_compute_list), p("sep", R.string.dsh_wf_field_compute_sep, default = ","))),
    )

    fun byId(id: String): Compute? = ALL.firstOrNull { it.id == id }

    /** 按一级分组标题归类（面板左栏用）。保持 [ALL] 的原始顺序。 */
    fun byGroup(): List<Pair<Int, List<Compute>>> =
        ALL.groupBy { it.groupTitleRes }.map { (title, items) -> title to items }

    /**
     * 求值。返回 (值, 失败原因)。
     *
     * 失败一律返回明确原因而不是"猜一个默认值"：一个算错的数比一个失败的步骤更难查，
     * 因为它会一路流到下游，最后在别处表现成奇怪的行为。
     */
    fun evaluate(action: Compute, cfg: (String) -> String): Pair<String, String?> {
        fun raw(key: String) = cfg(key)
        fun num(key: String): Double? = raw(key).trim().toDoubleOrNull()
        fun bool(key: String): Boolean = when (raw(key).trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            else -> false
        }
        fun listOf(key: String): JSONArray? = runCatching { JSONArray(raw(key)) }.getOrNull()

        return when (action.id) {
            // ── 数值 ──
            "num_add", "num_sub", "num_mul", "num_div", "num_mod", "num_max", "num_min" -> {
                val a = num("a")
                val b = num("b")
                if (a == null || b == null) return Pair("", "bad_number")
                val v = when (action.id) {
                    "num_add" -> a + b
                    "num_sub" -> a - b
                    "num_mul" -> a * b
                    // 除零单独判：IEEE 的 Infinity 会被下游当成一个可用的数字。
                    "num_div" -> if (b == 0.0) return Pair("", "div_by_zero") else a / b
                    "num_mod" -> if (b == 0.0) return Pair("", "div_by_zero") else a % b
                    "num_max" -> maxOf(a, b)
                    else -> minOf(a, b)
                }
                Pair(fmt(v), null)
            }
            "num_abs" -> Pair(fmt(abs(num("a") ?: return Pair("", "bad_number"))), null)
            // 取整按"四舍五入到整数"：用户说"取整"时想的多半不是截断。
            "num_round" -> Pair((num("a") ?: return Pair("", "bad_number")).roundToLong().toString(), null)
            "num_random" -> {
                val lo = num("a")?.roundToLong() ?: 0L
                val hi = num("b")?.roundToLong() ?: 100L
                if (hi < lo) return Pair("", "bad_range")
                Pair((lo + (Math.random() * (hi - lo + 1)).toLong()).toString(), null)
            }
            "num_eq", "num_lt", "num_gt" -> {
                val a = num("a")
                val b = num("b")
                if (a == null || b == null) return Pair("", "bad_number")
                val ok = when (action.id) {
                    "num_eq" -> a == b
                    "num_lt" -> a < b
                    else -> a > b
                }
                Pair(if (ok) "1" else "0", null)
            }

            // ── 文本 ──
            "str_append" -> Pair(raw("a") + raw("b"), null)
            "str_substring" -> {
                val s = raw("a")
                val start = (num("start")?.toInt() ?: 0).coerceIn(0, s.length)
                val len = num("len")?.toInt() ?: 0
                // len <= 0 表示"取到末尾"：让用户不必先知道长度。
                val end = if (len <= 0) s.length else (start + len).coerceIn(start, s.length)
                Pair(s.substring(start, end), null)
            }
            "str_replace" -> Pair(raw("a").replace(raw("from"), raw("to")), null)
            "str_split" -> {
                val sep = raw("sep").ifEmpty { "," }
                val arr = JSONArray()
                for (part in raw("a").split(sep)) arr.put(part)
                Pair(arr.toString(), null)
            }
            "str_regex" -> Pair(
                runCatching {
                    Regex(raw("pattern")).find(raw("a"))?.groupValues?.lastOrNull() ?: ""
                }.getOrElse { return Pair("", "bad_regex") },
                null,
            )
            "str_trim" -> Pair(raw("a").trim(), null)
            "str_len" -> Pair(raw("a").length.toString(), null)
            "str_upper" -> Pair(raw("a").uppercase(), null)
            "str_lower" -> Pair(raw("a").lowercase(), null)
            "str_equal" -> Pair((if (raw("a") == raw("b")) "1" else "0"), null)
            "str_contains" -> Pair((if (raw("a").contains(raw("b"))) "1" else "0"), null)
            "str_encode" -> Pair(runCatching { URLEncoder.encode(raw("a"), "UTF-8") }.getOrElse { return Pair("", "bad_encode") }, null)
            "str_decode" -> Pair(runCatching { URLDecoder.decode(raw("a"), "UTF-8") }.getOrElse { return Pair("", "bad_decode") }, null)

            // ── 布尔 ──
            "bool_and" -> Pair((if (bool("a") && bool("b")) "1" else "0"), null)
            "bool_or" -> Pair((if (bool("a") || bool("b")) "1" else "0"), null)
            "bool_not" -> Pair((if (!bool("a")) "1" else "0"), null)

            // ── JSON ──
            "json_get" -> {
                val obj = runCatching { JSONObject(raw("a")) }.getOrNull()
                    ?: return Pair("", "bad_json")
                var cur: Any = obj
                for (seg in raw("path").split('.').filter { it.isNotEmpty() }) {
                    cur = when (val c = cur) {
                        is JSONObject -> c.opt(seg) ?: return Pair("", "json_path_missing")
                        else -> return Pair("", "json_path_missing")
                    }
                }
                Pair(cur.toString(), null)
            }
            "json_keys" -> {
                val obj = runCatching { JSONObject(raw("a")) }.getOrNull()
                    ?: return Pair("", "bad_json")
                val arr = JSONArray()
                val it = obj.keys()
                while (it.hasNext()) arr.put(it.next())
                Pair(arr.toString(), null)
            }

            // ── 列表（JSON 数组字符串）──
            "list_make" -> Pair(JSONArray().put(raw("a")).put(raw("b")).toString(), null)
            "list_size" -> Pair((listOf("a")?.length() ?: return Pair("", "bad_list")).toString(), null)
            "list_get" -> {
                val arr = listOf("a") ?: return Pair("", "bad_list")
                val i = num("index")?.toInt() ?: 0
                if (i < 0 || i >= arr.length()) return Pair("", "index_out_of_range")
                Pair(arr.optString(i), null)
            }
            "list_add" -> {
                val arr = listOf("a") ?: return Pair("", "bad_list")
                arr.put(raw("b"))
                Pair(arr.toString(), null)
            }
            "list_contains" -> {
                val arr = listOf("a") ?: return Pair("", "bad_list")
                var found = false
                for (i in 0 until arr.length()) {
                    if (arr.optString(i) == raw("b")) { found = true; break }
                }
                Pair((if (found) "1" else "0"), null)
            }
            "list_join" -> {
                val arr = listOf("a") ?: return Pair("", "bad_list")
                val sep = raw("sep").ifEmpty { "," }
                Pair((0 until arr.length()).joinToString(sep) { arr.optString(it) }, null)
            }

            else -> Pair("", "unknown_compute")
        }
    }

    /**
     * 数字落成字符串：整数不带小数点。
     *
     * `3.0` 这种形态会一路流到下游做字符串比较（`str_equal`）和日志里，
     * 而用户心里的"3"就是 3。
     */
    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble() && !v.isInfinite()) v.toLong().toString() else v.toString()
}