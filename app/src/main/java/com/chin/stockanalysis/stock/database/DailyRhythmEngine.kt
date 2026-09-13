package com.chin.stockanalysis.stock.database

import android.content.Context
import android.util.Log
import com.chin.stockanalysis.notification.TradeNotifier
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 每日节奏引擎（APK 侧）。
 *
 * 与 PC 侧 `smalltools/_daily_intel.py` **同口径**：同一套板块规则表、同一套候选池、
 * 同一套六维过滤与追高闸门，保证「同一交易日双端判定一致」。
 *
 * 时段：
 *  - pre8   08:00 盘前：宏观 + 美股收盘 → 利好利空板块 → 候选标的 → 推送 + 存库
 *  - pre9   09:00 盘前：宏观 + 亚太实时（日经/KOSPI/恒生/台湾/新加坡/澳洲 + 韩国权重股）→ 同上
 *  - review 15:20 收盘复盘：a 板块判定对错 / b 当日选股 / c 近5日巡诊 / d 实仓镜像（表格化）
 */
object DailyRhythmEngine {

    private const val TAG = "DailyRhythm"

    /** 判断买入/退出的强度权重 */
    private val RANK = mapOf("强" to 3, "中" to 2, "弱" to 1)

    /** 板块候选池（与 PC 侧 `_daily_intel.BOARD_POOL` 完全一致）。 */
    private val BOARD_POOL: Map<String, List<Pair<String, String>>> = linkedMapOf(
        "油气开采/油服" to listOf(
            "sh601857" to "中国石油", "sh600938" to "中国海油", "sh600583" to "海油工程",
            "sh601808" to "中海油服", "sh603619" to "中曼石油", "sh600968" to "海油发展",
        ),
        "油运/航运" to listOf(
            "sh601872" to "招商轮船", "sh600026" to "中远海能",
            "sh601975" to "招商南油", "sh600798" to "宁波海运",
        ),
        "天然气/LNG" to listOf(
            "sh600256" to "广汇能源", "sh600803" to "新奥股份", "sh603393" to "新天然气",
            "sz002267" to "陕天然气", "sh600617" to "国新能源", "sh600956" to "新天绿能",
        ),
        "煤炭/煤化工" to listOf(
            "sh601088" to "中国神华", "sh601225" to "陕西煤业", "sh601898" to "中煤能源",
            "sh600188" to "兖矿能源", "sz000983" to "山西焦煤", "sh600985" to "淮北矿业",
            "sh600989" to "宝丰能源", "sz000933" to "神火股份", "sh601666" to "平煤股份",
            "sh600971" to "恒源煤电",
        ),
        "有色/资源" to listOf(
            "sh601600" to "中国铝业", "sh600362" to "江西铜业", "sh603993" to "洛阳钼业",
            "sh600111" to "北方稀土", "sh600547" to "山东黄金", "sz002155" to "湖南黄金",
        ),
        "高股息红利" to listOf(
            "sh601398" to "工商银行", "sh601288" to "农业银行", "sh600028" to "中国石化",
            "sh600900" to "长江电力", "sh601088" to "中国神华",
        ),
        "电力/公用" to listOf(
            "sh600900" to "长江电力", "sh600011" to "华能国际",
            "sh601985" to "中国核电", "sh600025" to "华能水电",
        ),
        "农业/食品(CPI)" to listOf(
            "sz000998" to "隆平高科", "sz002041" to "登海种业", "sh600598" to "北大荒",
            "sh601952" to "苏垦农发", "sz000876" to "新希望", "sh600887" to "伊利股份",
        ),
        "造纸(人民币升值)" to listOf(
            "sz002078" to "太阳纸业", "sh600966" to "博汇纸业", "sh600567" to "山鹰国际",
        ),
        "建材/基建" to listOf(
            "sh600801" to "华新建材", "sh600176" to "中国巨石", "sh600019" to "宝钢股份",
        ),
        "航空(利空关注)" to listOf(
            "sh601111" to "中国国航", "sh600029" to "南方航空",
            "sh601021" to "春秋航空", "sh600115" to "中国东航",
        ),
        "成长科技(利空关注)" to listOf(
            "sh688981" to "中芯国际", "sz300502" to "新易盛",
            "sz002371" to "北方华创", "sh688008" to "澜起科技",
        ),
        "化工下游(利空关注)" to listOf(
            "sz002001" to "新和成", "sh600309" to "万华化学",
            "sh600426" to "华鲁恒升", "sz000301" to "东方盛虹",
        ),
    )

    // ─────────────────────────── 数据模型 ───────────────────────────

    data class Verdict(
        val board: String,
        val side: String,      // 利好 / 利空
        val strength: String,  // 强 / 中 / 弱
        val logic: String,
        val trigger: String,
        val falsify: String,
    )

    data class Pick(
        val secid: String,
        val name: String,
        val board: String,
        val price: Double?,
        val pct: Double?,
        val pe: Double?,
        val pb: Double?,
        val mcap: Double?,
        val turn: Double?,
        val chg5: Double?,
        val chg20: Double?,
        val dd60: Double?,
        val shape: String,
        val logic: String,
        val trigger: String,
        val falsify: String,
        val strength: String,
    )

    data class Report(
        val slot: String,
        val title: String,
        val digest: String,
        val content: String,
        val verdicts: List<Verdict>,
        val picks: List<Pick>,
        val snapshot: GlobalMarketCollector.Snapshot,
    )

    // ─────────────────────────── 信号 ───────────────────────────

    private class Signals(
        val oilPrice: Double?, val oilHigh: Boolean, val oilStrong: Boolean,
        val goldPct: Double?, val cny: Double?, val udiPct: Double?,
        val nikkei: Double?, val kospi: Double?, val hsi: Double?, val nasdaq: Double?,
    )

    private fun signals(snap: GlobalMarketCollector.Snapshot): Signals {
        val idx = snap.indices.associateBy { it.key }
        val cmd = snap.commodity.associateBy { it.name }
        val us = snap.usClose.associateBy { it.name }
        val oil = cmd["WTI原油"]?.price
        return Signals(
            oilPrice = oil,
            oilHigh = oil != null && oil >= 100.0,
            oilStrong = oil != null && oil >= 95.0,
            goldPct = cmd["COMEX黄金"]?.pct,
            cny = cmd["在岸人民币"]?.price,
            udiPct = idx["100.UDI"]?.pct,
            nikkei = idx["100.N225"]?.pct,
            kospi = idx["100.KS11"]?.pct,
            hsi = idx["100.HSI"]?.pct,
            nasdaq = us["纳斯达克"]?.pct ?: idx["100.NDX"]?.pct,
        )
    }

    /**
     * 规则化「利好 / 利空」板块判定（与 PC 侧 `_daily_intel.verdicts` 同规则）。
     *
     * 口径：宏观**预期**本身就是催化（美联储加息概率、日央行口风等），不因「未落地」而淡化；
     * 判定按「预期方向 × 已发生事实」双轨给分。
     */
    fun verdicts(snap: GlobalMarketCollector.Snapshot): List<Verdict> {
        val s = signals(snap)
        val out = mutableListOf<Verdict>()

        fun add(board: String, side: String, strength: String,
                logic: String, trigger: String, falsify: String) {
            out += Verdict(board, side, strength, logic, trigger, falsify)
        }

        // ① 油价
        if (s.oilHigh) {
            add("油气开采/油服", "利好", "强",
                "WTI ${fmt(s.oilPrice, 1)} 美元站稳100关口，EIA 上调均价，资本开支扩张预期",
                "布油站稳100以上 / OPEC 继续减产", "布油跌破95 且霍尔木兹流量恢复")
            add("油运/航运", "利好", "中",
                "中东供给扰动 → 运距拉长 + 运价弹性",
                "霍尔木兹流量未恢复 / VLCC 运价上行", "航道恢复常态、运价回落")
            add("煤炭/煤化工", "利好", "中",
                "能源比价替代 + 国内 PPI 煤炭开采环比走强 + 冬储补库",
                "动力煤站上700元/吨 / 10月冬储启动", "动力煤跌破650元/吨")
            add("航空(利空关注)", "利空", "强",
                "航油成本占比高，油价上行直接压缩毛利", "布油维持100以上", "油价快速回落至85以下")
            add("化工下游(利空关注)", "利空", "中",
                "原料成本上行而 CPI 仅0.8% 难以向下游传导", "油价维持高位", "油价回落或化工品提价落地")
        } else if (s.oilStrong) {
            add("油气开采/油服", "利好", "中", "油价90+ 区间震荡，油服订单景气延续",
                "油价站稳95", "油价跌破85")
        }

        // ② 全球加息潮（欧央行已加息 + 日央行/美联储预期升温 → 预期即催化）
        add("高股息红利", "利好", "中",
            "全球利率中枢上移 + 滞胀预期 → 低估值高股息避险资金抱团",
            "10Y美债维持4.5%以上 / 红利成交占比提升", "美债收益率快速下行且成长股放量领涨")
        add("成长科技(利空关注)", "利空", "中",
            "无风险利率上行压制高估值成长，创业板/科创50 已连续走弱",
            "纳指跌破前低 / 创业板失守20日线", "纳指企稳回升、成长放量反包")

        // ③ 美元与汇率
        if (s.udiPct != null && s.udiPct > 0.3) {
            add("有色/资源", "利空", "弱", "美元走强压制以美元计价的商品价格",
                "美元指数上破100", "美元指数回落至98下方")
        }
        if (s.cny != null && s.cny < 6.90) {
            add("造纸(人民币升值)", "利好", "中",
                "人民币走强降低进口木浆成本、增厚汇兑收益", "汇率维持6.9以内", "人民币重回7.0上方")
        }

        // ④ 亚太/美股风险偏好
        val riskOff = listOf("日经225" to s.nikkei, "韩国KOSPI" to s.kospi,
            "恒生指数" to s.hsi, "纳斯达克" to s.nasdaq)
        for ((label, pct) in riskOff) {
            if (pct != null && pct <= -1.5) {
                add("高股息红利", "利好", "弱",
                    "$label 跌 ${fmt(pct)}%，外围避险情绪外溢，A股防御占优",
                    "外围继续走弱", "外围快速修复")
                break
            }
        }

        // ⑤ 黄金
        if (s.goldPct != null && s.goldPct > 1.0) {
            add("有色/资源", "利好", "弱", "黄金单日涨超1%，避险与滞胀交易共振",
                "金价续创新高", "金价回落2%以上")
        }

        // ⑥ 国内 CPI/PPI
        add("农业/食品(CPI)", "利好", "弱",
            "CPI 同比0.8% 回升但食品项仍弱，属预期修复而非需求驱动",
            "食品项同比转正 / 猪价上行", "CPI 回落至0.5%以下")
        add("电力/公用", "利好", "弱", "PPI 电力+1.4%、用电用煤季节性增加，成本可传导",
            "煤价上行且电价联动", "煤价快速回落")

        // 同板块正反冲突 → 保留强者并标注
        val merged = linkedMapOf<String, Verdict>()
        for (v in out) {
            val cur = merged[v.board]
            when {
                cur == null -> merged[v.board] = v
                (RANK[v.strength] ?: 0) > (RANK[cur.strength] ?: 0) -> merged[v.board] = v
                cur.side != v.side -> merged[v.board] = cur.copy(logic = "${cur.logic}（另有反向：${v.logic}）")
            }
        }
        return merged.values.sortedWith(
            compareBy({ RANK[it.strength] ?: 9 }, { if (it.side == "利好") 0 else 1 })
        )
    }

    // ─────────────────────────── 选股 ───────────────────────────

    private class KStats(val chg5: Double?, val chg20: Double?, val dd60: Double?, val shape: String)

    private fun computeKStats(rows: List<Triple<String, Double, Double>>): KStats {
        if (rows.size < 21) return KStats(null, null, null, "数据不足")
        val closes = rows.map { it.second }
        val highs = rows.map { it.third }
        val last = closes.last()
        fun chg(back: Int): Double? {
            val base = closes.getOrNull(closes.size - 1 - back) ?: return null
            return if (base == 0.0) null else (last / base - 1) * 100
        }
        val maxHigh = highs.takeLast(60).maxOrNull()
        val dd60 = if (maxHigh != null && maxHigh != 0.0) (last / maxHigh - 1) * 100 else null
        fun ma(n: Int): Double? =
            if (closes.size < n) null else closes.takeLast(n).average()
        val ma5 = ma(5); val ma10 = ma(10); val ma20 = ma(20)
        val shape = when {
            ma5 == null || ma10 == null || ma20 == null -> "数据不足"
            ma5 > ma10 && ma10 > ma20 -> "多头"
            ma5 < ma10 && ma10 < ma20 -> "空头"
            else -> "纠缠"
        }
        return KStats(chg(5), chg(20), dd60, shape)
    }

    /** 六维过滤 + 追高闸门（与 PC 侧 `_pass_filter` 同口径）。 */
    private fun passFilter(q: GlobalMarketCollector.AQuote?, ks: KStats): Boolean {
        if (q == null) return false
        val mcap = q.mcapYi
        if (mcap != null && (mcap < 40 || mcap > 30000)) return false
        if (q.pb != null && q.pb > 3.2) return false
        val pe = q.peStatic
        if (pe != null && pe > 45) return false
        val c20 = ks.chg20
        if (c20 != null && c20 > 50) return false
        // 追高闸门：20日涨超25%且贴近60日高 → 鱼身中后段，只做回调不追高
        if (c20 != null && c20 > 25 && (ks.dd60 ?: -99.0) > -6) return false
        if (ks.shape == "空头") return false
        if (ks.dd60 != null && ks.dd60 < -20) return false
        return true
    }

    /**
     * 按利好强度排序逐板块取候选（每板块最多 2 只，总量 6 只）。
     *
     * 为控制移动端流量，仅对进入过滤的标的拉日线；命中数量达标即提前结束。
     */
    fun screen(verd: List<Verdict>, perBoard: Int = 2, limit: Int = 6): List<Pick> {
        val picks = mutableListOf<Pick>()
        val used = mutableSetOf<String>()
        for (v in verd.filter { it.side == "利好" }) {
            val pool = BOARD_POOL[v.board] ?: continue
            val quotes = runCatching {
                GlobalMarketCollector.fetchAQuotes(pool.map { it.first })
            }.getOrElse { emptyMap() }
            val rows = mutableListOf<Pair<GlobalMarketCollector.AQuote, KStats>>()
            for ((code, _) in pool) {
                if (code in used) continue
                val q = quotes[code] ?: continue
                val ks = runCatching {
                    computeKStats(GlobalMarketCollector.fetchDailyKline(code, 90))
                }.getOrElse { KStats(null, null, null, "数据不足") }
                if (passFilter(q, ks)) rows += q to ks
                if (rows.size >= perBoard * 3) break
            }
            val take = rows.sortedByDescending { r ->
                val mcap = r.first.mcapYi ?: 0.0
                val elasticity = if (mcap in 1.0..800.0) 1.4 else 1.0
                val trend = if (r.second.shape == "多头") 1.2 else 1.0
                elasticity * trend
            }.take(perBoard)
            take.forEach { (q, ks) ->
                used += q.code
                picks += Pick(q.code, q.name, v.board, q.price, q.pct,
                    q.peStatic, q.pb, q.mcapYi, q.turn,
                    ks.chg5, ks.chg20, ks.dd60, ks.shape,
                    v.logic, v.trigger, v.falsify, v.strength)
            }
            Log.i(TAG, "  · ${v.board} → ${take.joinToString("、") { it.first.name }}")
            if (picks.size >= limit) break
        }
        return picks.take(limit)
    }

    // ─────────────────────────── 渲染 ───────────────────────────

    private fun fmt(v: Double?, digits: Int = 2): String =
        if (v == null) "—" else String.format(Locale.CHINA, "%.${digits}f", v)

    private fun fmtPct(v: Double?): String =
        if (v == null) "—" else String.format(Locale.CHINA, "%+.2f%%", v)

    /** 中日韩字符按 2 宽度计算，用于文本表格对齐。 */
    private fun dispWidth(s: String): Int = s.fold(0) { acc, c ->
        val w = when {
            c.code in 0x1100..0x115F || c.code in 0x2E80..0xA4CF ||
                c.code in 0xAC00..0xD7A3 || c.code in 0xF900..0xFAFF ||
                c.code in 0xFE30..0xFE6F || c.code in 0xFF00..0xFF60 ||
                c.code in 0xFFE0..0xFFE6 -> 2
            else -> 1
        }
        acc + w
    }

    private fun pad(s: String, width: Int): String {
        val gap = width - dispWidth(s)
        return if (gap <= 0) s else s + " ".repeat(gap)
    }

    /** 渲染文本表格（列宽自适应）。 */
    fun table(header: List<String>, rows: List<List<String>>): String {
        if (rows.isEmpty()) return "（无数据）"
        val widths = header.indices.map { i ->
            maxOf(dispWidth(header[i]), rows.maxOfOrNull { dispWidth(it.getOrElse(i) { "" }) } ?: 0)
        }
        val sb = StringBuilder()
        sb.append(header.mapIndexed { i, h -> pad(h, widths[i]) }.joinToString("│"))
        sb.append('\n').append(widths.joinToString("┼") { "─".repeat(it) }).append('\n')
        rows.forEach { r ->
            sb.append(r.mapIndexed { i, c -> pad(c, widths[i]) }.joinToString("│")).append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** 情报正文（推送文本）。 */
    fun renderText(snap: GlobalMarketCollector.Snapshot, verd: List<Verdict>,
                   picks: List<Pick>): String {
        val sb = StringBuilder()
        val slotCn = if (snap.slot == "pre8") "08:00盘前" else "09:00亚太"
        sb.append("【每日节奏·$slotCn】${snap.ts}\n")

        sb.append("\n一、全球市场快照\n")
        for (region in listOf("美股", "亚太", "欧洲")) {
            val toks = snap.indices.filter { it.region == region }
                .map { "${it.name} ${fmtPct(it.pct)}" }
            if (toks.isNotEmpty()) sb.append("  ").append(toks.joinToString(" | ")).append('\n')
        }
        if (snap.usClose.isNotEmpty()) {
            sb.append("  美股收盘: ").append(
                snap.usClose.joinToString(" | ") { "${it.name} ${fmtPct(it.pct)}(${fmt(it.price)})" }
            ).append('\n')
        }
        if (snap.commodity.isNotEmpty()) {
            sb.append("  商品汇率: ").append(snap.commodity.joinToString(" | ") {
                "${it.name} ${fmt(it.price)}" + if (it.pct != null) "(${fmtPct(it.pct)})" else ""
            }).append('\n')
        }
        if (snap.koreaHeavy.isNotEmpty()) {
            sb.append("  韩国权重股: ").append(
                snap.koreaHeavy.take(4).joinToString(" | ") { "${it.name} ${fmtPct(it.pct)}" }
            ).append('\n')
        }

        sb.append("\n二、利好 / 利空板块判定\n")
        verd.forEach { v ->
            val mark = if (v.side == "利空") "🔴" else "🟢"
            sb.append("  $mark[${v.strength}]${v.board} ${v.logic}\n")
            sb.append("      触发: ${v.trigger} ｜ 证伪: ${v.falsify}\n")
        }

        sb.append("\n三、候选标的（${picks.size} 只，六维过滤后）\n")
        if (picks.isEmpty()) {
            sb.append("  今日无同时满足估值/形态/涨幅的标的，宁缺毋滥。\n")
        } else {
            val header = listOf("名称", "板块", "现价", "PE", "PB", "市值(亿)", "5日%", "20日%", "距60高%", "形态")
            val rows = picks.map {
                listOf(it.name, it.board, fmt(it.price), fmt(it.pe, 1), fmt(it.pb),
                    fmt(it.mcap, 0), fmtPct(it.chg5), fmtPct(it.chg20), fmtPct(it.dd60), it.shape)
            }
            sb.append(table(header, rows)).append('\n')
        }

        val news = snap.news.take(6)
        if (news.isNotEmpty()) {
            sb.append("\n四、盘前快讯\n")
            news.forEach { sb.append("  · ${it.title}\n") }
        }
        return sb.toString()
    }

    // ─────────────────────────── 执行 ───────────────────────────

    /** 采集 → 判定 → 选股 → 渲染（不推送、不落库，便于测试与复用）。 */
    fun analyze(slot: String): Report {
        val snap = GlobalMarketCollector.snapshot(slot)
        val verd = verdicts(snap)
        val picks = screen(verd, limit = 6)
        val content = renderText(snap, verd, picks)
        val slotCn = if (slot == "pre8") "盘前08:00" else "盘前09:00亚太"
        val title = "📡 ${slotCn}情报 ${snap.ts.substring(5, 16)}"
        val digest = "利好${verd.count { it.side == "利好"}}/利空${verd.count { it.side == "利空"}}，" +
            "候选${picks.size}只"
        return Report(slot, title, digest, content, verd, picks, snap)
    }

    /** 执行情报时段：分析 → 推送（系统通知 + 微信）→ 落库。 */
    suspend fun runSlot(ctx: Context, slot: String): Report {
        val report = analyze(slot)
        var ok = false
        var err = ""
        try {
            TradeNotifier.send(ctx, report.title, report.content, "intel_${slot}_${System.currentTimeMillis()}")
            ok = true
        } catch (e: Exception) {
            err = e.message ?: e.toString()
            Log.w(TAG, "情报推送失败: $err")
        }
        store(ctx, report, ok, err)
        Log.i(TAG, "$slot 完成：${report.digest}，推送${if (ok) "成功" else "失败"}")
        return report
    }

    private suspend fun store(ctx: Context, report: Report, ok: Boolean, err: String) {
        runCatching {
            val db = StockDatabase.getInstance(ctx)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            val now = Date()
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(now)
            val snap = report.snapshot
            db.dailyIntelDao().insert(
                DailyIntelEntity(
                    tradeDate = date, slot = report.slot, createdAt = sdf.format(now),
                    title = report.title, digest = report.digest,
                    macroJson = snapshotJson(snap),
                    sectorsJson = verdictsJson(report.verdicts),
                    picksJson = picksJson(report.picks),
                    newsJson = newsJson(snap),
                    content = report.content, pushed = ok,
                )
            )
            db.pushRecordDao().insert(
                PushRecordEntity(
                    tradeDate = date, slot = report.slot, createdAt = sdf.format(now),
                    kind = "intel", title = report.title,
                    codes = report.picks.joinToString(",") { it.secid },
                    ok = ok, err = err, content = report.content,
                )
            )
        }.onFailure { Log.w(TAG, "落库失败: ${it.message}") }
    }

    private fun snapshotJson(snap: GlobalMarketCollector.Snapshot): String {
        val o = JSONObject()
        o.put("ts", snap.ts)
        o.put("slot", snap.slot)
        o.put("indices", JSONArray(snap.indices.map {
            JSONObject().put("key", it.key).put("name", it.name)
                .put("region", it.region).put("price", it.price ?: JSONObject.NULL)
                .put("pct", it.pct ?: JSONObject.NULL)
        }))
        o.put("us_close", JSONArray(snap.usClose.map {
            JSONObject().put("name", it.name).put("price", it.price ?: JSONObject.NULL)
                .put("pct", it.pct ?: JSONObject.NULL)
        }))
        o.put("commodity", JSONArray(snap.commodity.map {
            JSONObject().put("name", it.name).put("price", it.price ?: JSONObject.NULL)
                .put("pct", it.pct ?: JSONObject.NULL)
        }))
        o.put("korea_heavy", JSONArray(snap.koreaHeavy.map {
            JSONObject().put("name", it.name).put("pct", it.pct ?: JSONObject.NULL)
        }))
        return o.toString()
    }

    private fun verdictsJson(verd: List<Verdict>): String = JSONArray(verd.map {
        JSONObject().put("board", it.board).put("side", it.side).put("strength", it.strength)
            .put("logic", it.logic).put("trigger", it.trigger).put("falsify", it.falsify)
    }).toString()

    private fun picksJson(picks: List<Pick>): String = JSONArray(picks.map {
        JSONObject().put("secid", it.secid).put("name", it.name).put("board", it.board)
            .put("price", it.price ?: JSONObject.NULL).put("pct", it.pct ?: JSONObject.NULL)
    }).toString()

    private fun newsJson(snap: GlobalMarketCollector.Snapshot): String =
        JSONArray(snap.news.map { JSONObject().put("title", it.title).put("time", it.time) }).toString()

    // ─────────────────────────── 15:20 复盘 ───────────────────────────

    /**
     * 构建复盘四张表（表格化文本）。
     *
     * - a 板块判定对错：情报判定 vs 板块内权重股当日均涨
     * - b 当日选股：情报候选 + 当日推送记录（主线/工具/ETF 均由推送账本留痕）
     * - c 近5个交易日信号票巡诊：入选价 → 现价
     * - d 实仓镜像：持仓盈亏 + 应对提示
     */
    suspend fun reviewTables(ctx: Context): List<Pair<String, Pair<List<String>, List<List<String>>>>> {
        val db = StockDatabase.getInstance(ctx)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val today = sdf.format(Date())
        val sections = mutableListOf<Pair<String, Pair<List<String>, List<List<String>>>>>()

        // a. 板块判定对错
        val intel = db.dailyIntelDao().ofDate(today)
        val rowsA = mutableListOf<List<String>>()
        val checked = mutableSetOf<String>()
        for (rec in intel) {
            val arr = runCatching { JSONArray(rec.sectorsJson) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val board = o.optString("board")
                val side = o.optString("side")
                val key = "$board|$side"
                if (key in checked || rowsA.size >= 12) continue
                checked += key
                val pool = BOARD_POOL[board] ?: continue
                val qs = runCatching {
                    GlobalMarketCollector.fetchAQuotes(pool.map { it.first })
                }.getOrElse { emptyMap() }
                val pcts = qs.values.mapNotNull { it.pct }
                if (pcts.isEmpty()) continue
                val avg = pcts.average()
                val hit = (side == "利好" && avg > 0) || (side == "利空" && avg < 0)
                val hhmm = if (rec.createdAt.length >= 16) rec.createdAt.substring(11, 16) else rec.createdAt
                rowsA += listOf(side, o.optString("strength"), board,
                    fmtPct(avg), if (hit) "√ 命中" else "× 未兑现", hhmm)
            }
        }
        if (rowsA.isNotEmpty()) {
            sections += "a. 板块判定对错核对" to
                (listOf("方向", "强度", "板块", "板块均涨", "结论", "判定时间") to rowsA)
        }

        // b. 当日选股
        val rowsB = mutableListOf<List<String>>()
        for (rec in intel) {
            val picks = runCatching { JSONArray(rec.picksJson) }.getOrNull() ?: continue
            if (picks.length() == 0) continue
            val names = (0 until picks.length()).mapNotNull { picks.optJSONObject(it)?.optString("name") }
            rowsB += listOf("情报候选", rec.slot, picks.length().toString(), names.joinToString("、"))
        }
        db.pushRecordDao().ofDate(today).filter { it.kind != "intel" }.forEach { r ->
            rowsB += listOf("推送/${r.slot}", r.kind, r.codes.split(",").filter { it.isNotBlank() }.size.toString(),
                r.title.take(30))
        }
        if (rowsB.isNotEmpty()) {
            sections += "b. 当日选股复盘（情报 / 主线 / 工具 / ETF）" to
                (listOf("来源", "周期/类型", "数量", "标的") to rowsB)
        }

        // c. 近5个交易日信号票巡诊
        val rowsC = mutableListOf<List<String>>()
        val recent = db.pushRecordDao().recent(200)
        val sdf2 = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -8) }
        val since = sdf2.format(cal.time)
        val latestByCode = linkedMapOf<String, Pair<String, Double?>>()
        for (r in recent.filter { it.tradeDate >= since }) {
            for (code in r.codes.split(",").filter { it.isNotBlank() }) {
                if (code !in latestByCode) latestByCode[code] = r.tradeDate to null
            }
        }
        if (latestByCode.isNotEmpty()) {
            val qs = runCatching {
                GlobalMarketCollector.fetchAQuotes(latestByCode.keys.toList())
            }.getOrElse { emptyMap() }
            // 入选价：从情报 picksJson 里回溯（现价字段）
            val entryPrice = mutableMapOf<String, Double>()
            for (rec in db.dailyIntelDao().recent(20)) {
                runCatching { JSONArray(rec.picksJson) }.getOrNull()?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val code = o.optString("secid")
                        val p = o.optDouble("price", Double.NaN)
                        if (code.isNotBlank() && !p.isNaN() && code !in entryPrice) entryPrice[code] = p
                    }
                }
            }
            for ((code, meta) in latestByCode) {
                val q = qs[code] ?: continue
                val base = entryPrice[code]
                val cur = q.price
                val ret = if (base != null && base != 0.0 && cur != null) (cur / base - 1) * 100 else null
                rowsC += listOf(q.name, code.removePrefix("sh").removePrefix("sz"),
                    meta.first, fmt(base), fmt(cur), fmtPct(ret), fmtPct(q.pct))
            }
        }
        if (rowsC.isNotEmpty()) {
            sections += "c. 近5日信号票巡诊（持有中标的今日表现）" to
                (listOf("名称", "代码", "入选日", "入选价", "现价", "至今涨跌", "今日涨跌") to rowsC)
        }

        // d. 实仓镜像
        val rowsD = mutableListOf<List<String>>()
        runCatching {
            val pos = db.realPositionDao().getAllActive()
            val qs = GlobalMarketCollector.fetchAQuotes(pos.map { it.stockCode })
            for (p in pos) {
                val cur = qs[p.stockCode]?.price ?: p.currentPrice
                val pnl = if (p.avgBuyPrice > 0) (cur / p.avgBuyPrice - 1) * 100 else null
                val advice = when {
                    pnl == null -> "数据不足"
                    pnl <= -12 -> "破位风险，优先减仓"
                    pnl <= -8 -> "减仓警戒，反弹减"
                    pnl <= -5 -> "回调应对，观察20日线"
                    pnl >= 12 -> "浮盈较厚，可分批止盈"
                    else -> "持有观察"
                }
                rowsD += listOf(p.stockName, p.stockCode.removePrefix("sh").removePrefix("sz"),
                    fmt(cur), fmt(p.avgBuyPrice), fmtPct(pnl), advice)
            }
            if (pos.isNotEmpty()) {
                val totalPnl = pos.mapNotNull { p ->
                    val cur = qs[p.stockCode]?.price ?: p.currentPrice
                    if (p.avgBuyPrice > 0) (cur / p.avgBuyPrice - 1) * 100 else null
                }.average()
                rowsD += listOf("组合整体", "—", "—", "—", fmtPct(totalPnl), "按纪律执行，勿情绪化")
            }
        }.onFailure { Log.w(TAG, "实仓镜像失败: ${it.message}") }
        sections += "d. 实仓镜像复盘与应对" to
            (listOf("名称", "代码", "现价", "成本", "盈亏", "应对/提示") to
                (rowsD.ifEmpty { listOf(listOf("—", "—", "—", "—", "—", "今日无实仓记录")) }))

        return sections
    }

    /** 15:20 表格化复盘：渲染表格 → 推送 → 存库。 */
    suspend fun runReview(ctx: Context): String {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val title = "📋 收盘复盘 $today"
        val sections = reviewTables(ctx)
        val sb = StringBuilder()
        sections.forEach { (name, hdrRows) ->
            val (header, rows) = hdrRows
            sb.append("\n■ ").append(name).append('\n')
            sb.append(table(header, rows))
            sb.append("\n\n")
        }
        sb.append("口径：a 用板块内权重股当日均涨核对情报判定；b 汇总情报候选与当日各类推送；")
        sb.append("c 近5日信号票按入选价对现价算涨跌；d 实仓镜像含盈亏与应对提示。\n")
        sb.append("💬 复盘是为了下一次更准，不是为了后悔。按纪律走，把仓位留给确定性 👊")
        val content = sb.toString().trim()

        var ok = false
        var err = ""
        try {
            TradeNotifier.send(ctx, title, content, "review_${System.currentTimeMillis()}")
            ok = true
        } catch (e: Exception) {
            err = e.message ?: e.toString()
            Log.w(TAG, "复盘推送失败: $err")
        }
        runCatching {
            val db = StockDatabase.getInstance(ctx)
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            db.dailyIntelDao().insert(
                DailyIntelEntity(
                    tradeDate = today, slot = "review", createdAt = now,
                    title = title, digest = "${sections.size} 段表格",
                    content = content, pushed = ok,
                )
            )
            db.pushRecordDao().insert(
                PushRecordEntity(
                    tradeDate = today, slot = "eod", createdAt = now,
                    kind = "review", title = title, ok = ok, err = err, content = content,
                )
            )
        }.onFailure { Log.w(TAG, "复盘落库失败: ${it.message}") }
        Log.i(TAG, "复盘完成（${sections.size} 段），推送${if (ok) "成功" else "失败"}")
        return content
    }
}
