package com.chin.stockanalysis.strategy.ssa

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine.Bar
import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine.Params
import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine.RecoIncremental
import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine.RecoSnapshot
import com.chin.stockanalysis.strategy.ssa.SsaHybridEngine.RecoState

/**
 * ## 每日荐股快照存储层（纯 Kotlin，File 注入便于 JVM 单测；App 侧传 filesDir 子目录）
 *
 * 目录结构：
 *  - index.json          池索引（code→票性/参数/最新日/根数），推荐列表骨架免解析全量状态
 *  - state_{code}.json   单票增量引擎全状态（bars 全历史 + 信号链 + 票性聚合 + 账户快照）
 *  - reco_latest.json    最近一次构建/更新后的推荐卡片缓存（UI 直接读）
 *
 * 等价性约定：restore→step 与不中断连续 step 逐位一致（单测守护）；
 * 票性 label 迁移导致 [SsaHybridEngine.recommendedParams] 变化时，用存储全历史重新 seed（罕见事件）。
 *
 * ⚠️ 日期格式须为可字典序比较的 yyyy-MM-dd（step 依赖严格递增判断）。
 */
class SsaRecoStore(private val dir: File) {

    init { dir.mkdirs() }

    /** 推荐卡片：code + 引擎快照（name 由 UI 层按池数据补） */
    data class RecoCard(val code: String, val snap: RecoSnapshot)

    /** 索引条目（免解析全量状态的轻量骨架） */
    data class IndexEntry(
        val code: String, val label: String,
        val ts: Int, val tl: Int, val cy: Int, val em: Int, val basePct: Int,
        val lastDate: String, val barCount: Int
    )

    private val indexFile = File(dir, "index.json")
    private val latestFile = File(dir, "reco_latest.json")
    private fun stateFile(code: String) = File(dir, "state_$code.json")

    /** seed/step 前最少 K 线数（覆盖全部推荐参数档 max(L,30)+30 = 90） */
    private val minBars = 90

    // ═══════════════════════════════ 索引 ═══════════════════════════════

    @Synchronized
    fun loadIndex(): Map<String, IndexEntry> {
        if (!indexFile.exists()) return emptyMap()
        return try {
            val arr = JSONObject(indexFile.readText()).optJSONArray("stocks") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                IndexEntry(
                    o.getString("code"), o.optString("label", "MIXED"),
                    o.optInt("ts", 10), o.optInt("tl", 20), o.optInt("cy", 60),
                    o.optInt("em", 2), o.optInt("basePct", 0),
                    o.optString("lastDate", ""), o.optInt("barCount", 0))
            }.associateBy { it.code }
        } catch (e: Exception) { emptyMap() }
    }

    private fun flushIndex(map: Map<String, IndexEntry>) {
        val arr = JSONArray()
        map.values.sortedBy { it.code }.forEach { e ->
            arr.put(JSONObject().apply {
                put("code", e.code); put("label", e.label)
                put("ts", e.ts); put("tl", e.tl); put("cy", e.cy)
                put("em", e.em); put("basePct", e.basePct)
                put("lastDate", e.lastDate); put("barCount", e.barCount)
            })
        }
        writeSafe(indexFile, JSONObject().put("stocks", arr).toString())
    }

    private fun indexEntryOf(code: String, s: RecoState, label: String) = IndexEntry(
        code, label, s.params.trendShort, s.params.trendLong, s.params.cycleL,
        s.params.smoothSpan, Math.round(s.params.baseFraction * 100).toInt(),
        s.bars.last().date, s.bars.size
    )

    // ═══════════════════════════ 单票状态 ═══════════════════════════

    @Synchronized
    fun loadState(code: String): RecoState? {
        val f = stateFile(code)
        if (!f.exists()) return null
        return try { decodeState(code, f.readText()) } catch (e: Exception) { null }
    }

    /** 单票落盘：状态文件 + 索引同步更新（card 可选，用于单独刷新某票时同步卡片缓存） */
    @Synchronized
    fun saveState(code: String, state: RecoState, label: String, card: RecoCard? = null) {
        writeSafe(stateFile(code), encodeState(code, state))
        val idx = loadIndex().toMutableMap()
        idx[code] = indexEntryOf(code, state, label)
        flushIndex(idx)
        if (card != null) {
            val m = latestMap().toMutableMap(); m[code] = card; flushLatest(m.values.toList())
        }
    }

    @Synchronized
    fun removeState(code: String) {
        stateFile(code).delete()
        flushIndex(loadIndex() - code)
        val m = latestMap().toMutableMap(); m.remove(code); flushLatest(m.values.toList())
    }

    @Synchronized
    fun stateCount(): Int = loadIndex().size

    fun encodeState(code: String, s: RecoState): String {
        val bars = JSONArray()
        s.bars.forEach { b -> bars.put(JSONArray().put(b.date).put(b.open).put(b.high).put(b.low).put(b.close)) }
        val adv = JSONArray(); s.adviceHistory.forEach { adv.put(it) }
        val chain = JSONObject().apply {
            put("tsPrev", s.tsPrev); put("tsLast", s.tsLast)
            put("tlPrev", s.tlPrev); put("tlLast", s.tlLast)
            put("cy2", s.cyPrev2); put("cy1", s.cyPrev1); put("cy0", s.cyLast)
            put("tr30", s.tr30Last); put("cy30", s.cy30Last)
        }
        val noise = JSONArray(); s.noiseRecent.forEach { noise.put(it) }
        val pz = JSONArray()
        s.pendingZ.forEach { p -> pz.put(JSONArray().put(p.idx).put(p.isHi).put(p.isLo)) }
        val cyh = JSONArray(); s.cy30Hist.forEach { cyh.put(it) }
        val ticket = JSONObject().apply {
            put("noiseRecent", noise); put("pendingZ", pz)
            put("hiCnt", s.hiCnt); put("hiSum3", s.hiSum3); put("hiSum5", s.hiSum5)
            put("loCnt", s.loCnt); put("loSum5", s.loSum5)
            put("cy30Hist", cyh); put("closeSum", s.closeSum)
        }
        val sim = JSONObject().apply {
            put("cap0", s.sim.cap0); put("capT", s.sim.capT); put("capB", s.sim.capB)
            put("cashT", s.sim.cashT); put("shT", s.sim.shT); put("biT", s.sim.biT)
            put("biDate", s.sim.biDate); put("bpT", s.sim.bpT); put("bwhy", s.sim.bwhy)
            put("cashB", s.sim.cashB); put("shB", s.sim.shB); put("biDateB", s.sim.biDateB)
            put("pendT", s.sim.pendT); put("pendB", s.sim.pendB)
            put("whyT", s.sim.whyT); put("inBull", s.sim.inBull)
        }
        val params = JSONObject().apply {
            put("ts", s.params.trendShort); put("tl", s.params.trendLong); put("cy", s.params.cycleL)
            put("em", s.params.smoothSpan); put("basePct", Math.round(s.params.baseFraction * 100))
            put("comm", s.params.commissionRate); put("stamp", s.params.stampDutyRate)
        }
        return JSONObject().apply {
            put("ver", 1); put("code", code); put("params", params)
            put("chain", chain); put("sim", sim); put("ticket", ticket)
            put("bars", bars); put("adviceHistory", adv)
        }.toString()
    }

    fun decodeState(code: String, json: String): RecoState {
        val o = JSONObject(json)
        val p = o.getJSONObject("params")
        val params = Params(
            p.optInt("ts", 10), p.optInt("tl", 20), p.optInt("cy", 60),
            p.optInt("em", 2), p.optDouble("basePct", 0.0) / 100.0,
            p.optDouble("comm", 0.00025), p.optDouble("stamp", 0.0005))
        val c = o.getJSONObject("chain")
        val sim = o.getJSONObject("sim")
        val tk = o.getJSONObject("ticket")
        val ba = o.getJSONArray("bars")
        val bars = ArrayList<Bar>(ba.length())
        for (i in 0 until ba.length()) {
            val b = ba.getJSONArray(i)
            bars.add(Bar(b.getString(0), b.getDouble(1), b.getDouble(2), b.getDouble(3), b.getDouble(4)))
        }
        val aa = o.getJSONArray("adviceHistory")
        val adv = ArrayList<Int>(aa.length())
        for (i in 0 until aa.length()) adv.add(aa.getInt(i))
        val na = tk.optJSONArray("noiseRecent") ?: JSONArray()
        val noise = ArrayList<Double>(na.length())
        for (i in 0 until na.length()) noise.add(na.getDouble(i))
        val pa = tk.optJSONArray("pendingZ") ?: JSONArray()
        val pz = ArrayList<SsaHybridEngine.PendingZEntry>(pa.length())
        for (i in 0 until pa.length()) {
            val e = pa.getJSONArray(i)
            pz.add(SsaHybridEngine.PendingZEntry(e.getInt(0), e.getBoolean(1), e.getBoolean(2)))
        }
        val ca = tk.optJSONArray("cy30Hist") ?: JSONArray()
        val cyh = ArrayList<Double>(ca.length())
        for (i in 0 until ca.length()) cyh.add(ca.getDouble(i))
        return RecoState(
            params = params, bars = bars, adviceHistory = adv,
            tsPrev = c.getDouble("tsPrev"), tsLast = c.getDouble("tsLast"),
            tlPrev = c.getDouble("tlPrev"), tlLast = c.getDouble("tlLast"),
            cyPrev2 = c.getDouble("cy2"), cyPrev1 = c.getDouble("cy1"), cyLast = c.getDouble("cy0"),
            tr30Last = c.getDouble("tr30"), cy30Last = c.getDouble("cy30"),
            sim = SsaHybridEngine.SimSnapshot(
                sim.getDouble("cap0"), sim.getDouble("capT"), sim.getDouble("capB"),
                sim.getDouble("cashT"), sim.getLong("shT"), sim.getInt("biT"),
                sim.getString("biDate"), sim.getDouble("bpT"), sim.getString("bwhy"),
                sim.getDouble("cashB"), sim.getLong("shB"),
                sim.getInt("pendT"), sim.getInt("pendB"), sim.getString("whyT"), sim.getBoolean("inBull"),
                sim.optString("biDateB", "")
            ),
            noiseRecent = noise, pendingZ = pz,
            hiCnt = tk.optInt("hiCnt", 0), hiSum3 = tk.optDouble("hiSum3", 0.0),
            hiSum5 = tk.optDouble("hiSum5", 0.0),
            loCnt = tk.optInt("loCnt", 0), loSum5 = tk.optDouble("loSum5", 0.0),
            cy30Hist = cyh, closeSum = tk.optDouble("closeSum", 0.0)
        )
    }

    // ═══════════════════════ 构建与每日更新 ═══════════════════════

    /**
     * 首次全量分批构建：对缺状态的票逐票 classifyOnly→recommendedParams→seed→落盘。
     * 幂等可续跑（已有状态的跳过）；isCancelled 随时可中断，下次调用续跑。
     * @return 本次完成（成功 seed）票数
     */
    @Synchronized
    fun seedMissing(
        codes: List<String>,
        barsProvider: (code: String) -> List<Bar>?,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int, code: String) -> Unit = { _, _, _ -> }
    ): Int {
        val idx = loadIndex()
        val todo = codes.filter { it !in idx }
        var done = 0
        for (code in todo) {
            if (isCancelled()) break
            onProgress(done, todo.size, code)
            val bars = try { barsProvider(code) } catch (e: Exception) { null }
            if (bars == null || bars.size < minBars) continue   // 数据不足，留待下次
            try {
                val prof = SsaHybridEngine.classifyOnly(bars)
                val params = SsaHybridEngine.recommendedParams(prof.label)
                val inc = RecoIncremental.seed(bars, params)
                val state = inc.exportState()
                writeSafe(stateFile(code), encodeState(code, state))
                flushIndex(loadIndex().toMutableMap().apply {
                    put(code, indexEntryOf(code, state, prof.label))
                })
                done++
            } catch (e: Exception) { /* 单票失败不影响整体，留待下次 */ }
        }
        return done
    }

    /**
     * 每日增量更新：对索引内每票用新K step（新K日期须晚于已存最新日，重复数据自动去重）。
     * label 迁移→recommendedParams 变化→用全史重新 seed。单票异常跳过不影响整体。
     * @return 处理后的全部卡片（写入 reco_latest.json）
     */
    @Synchronized
    fun dailyUpdate(
        barsByCode: Map<String, List<Bar>>,
        onProgress: (done: Int, total: Int, code: String) -> Unit = { _, _, _ -> }
    ): List<RecoCard> {
        val idx = loadIndex()
        val cards = latestMap().toMutableMap()
        val newIndex = idx.toMutableMap()
        var done = 0
        for (e in idx.values) {
            onProgress(done, idx.size, e.code)
            val all = barsByCode[e.code]
            if (all != null) try {
                val st = loadState(e.code)
                if (st != null) {
                    val fresh = all.filter { it.date > st.bars.last().date }
                    if (fresh.isNotEmpty()) {
                        val inc = RecoIncremental.restore(st)
                        inc.step(fresh)
                        val snap = inc.snapshot()
                        val label = snap.profile.label
                        val rec = SsaHybridEngine.recommendedParams(label)
                        val finalInc = if (rec == st.params) inc
                            else RecoIncremental.seed(inc.barsCopy(), rec)   // 参数迁移：全史重 seed（罕见）
                        val state = finalInc.exportState()
                        writeSafe(stateFile(e.code), encodeState(e.code, state))
                        newIndex[e.code] = indexEntryOf(e.code, state, label)
                        cards[e.code] = RecoCard(e.code, finalInc.snapshot())
                    } else if (!cards.containsKey(e.code)) {
                        // 无新K：状态不动盘；卡片缓存缺失时才重建（O(n) 拷贝，平凡）
                        cards[e.code] = RecoCard(e.code, RecoIncremental.restore(st).snapshot())
                    }
                }
            } catch (e2: Exception) { /* 单票异常跳过 */ }
            done++
        }
        flushIndex(newIndex)
        flushLatest(cards.values.toList())
        return cards.values.toList()
    }

    // ═════════════════════════ 卡片缓存 ═════════════════════════

    /** 最近一次构建/更新后的推荐卡片（UI 直接读，免解析全量状态） */
    @Synchronized
    fun loadLatestCards(): List<RecoCard> = latestMap().values.toList()

    private fun latestMap(): Map<String, RecoCard> {
        if (!latestFile.exists()) return emptyMap()
        return try {
            val arr = JSONObject(latestFile.readText()).optJSONArray("cards") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> decodeCard(arr.getJSONObject(i)) }
                .associateBy { it.code }
        } catch (e: Exception) { emptyMap() }
    }

    private fun flushLatest(cards: List<RecoCard>) {
        val arr = JSONArray()
        cards.sortedBy { it.code }.forEach { arr.put(encodeCard(it)) }
        writeSafe(latestFile, JSONObject().put("cards", arr).toString())
    }

    private fun encodeCard(c: RecoCard): JSONObject {
        val s = c.snap
        val prof = JSONObject().apply {
            put("label", s.profile.label); put("hi3", s.profile.hi3); put("hi5", s.profile.hi5)
            put("lo5", s.profile.lo5); put("cycAmp", s.profile.cycAmp)
            put("hiCount", s.profile.hiCount); put("loCount", s.profile.loCount)
        }
        val rec = JSONArray()
        s.recentAdvices.forEach { r ->
            rec.put(JSONObject().apply {
                put("date", r.date); put("advice", r.advice); put("close", r.close)
                put("r1", r.r1 ?: JSONObject.NULL); put("r3", r.r3 ?: JSONObject.NULL)
                put("r5", r.r5 ?: JSONObject.NULL)
            })
        }
        fun stat(a: SsaHybridEngine.AdviceStat) = JSONObject().apply {
            put("count", a.count); put("w1", a.win1Pct); put("w3", a.win3Pct); put("a5", a.avgRet5Pct)
        }
        return JSONObject().apply {
            put("code", c.code)
            put("advice", s.advice); put("date", s.date); put("close", s.close)
            put("ts", s.params.trendShort); put("tl", s.params.trendLong); put("cy", s.params.cycleL)
            put("em", s.params.smoothSpan); put("basePct", Math.round(s.params.baseFraction * 100))
            put("profile", prof); put("recent", rec)
            put("buyStat", stat(s.buyStat)); put("sellStat", stat(s.sellStat))
            put("hpStat", stat(s.holdPosStat)); put("heStat", stat(s.holdEmptyStat))
        }
    }

    private fun decodeCard(o: JSONObject): RecoCard? = try {
        val p = o.getJSONObject("profile")
        val rec = mutableListOf<SsaHybridEngine.RecentAdvice>()
        val ra = o.optJSONArray("recent") ?: JSONArray()
        for (i in 0 until ra.length()) {
            val r = ra.getJSONObject(i)
            rec.add(SsaHybridEngine.RecentAdvice(
                r.getString("date"), r.getInt("advice"), r.getDouble("close"),
                r.optDouble("r1", Double.NaN).takeUnless { it.isNaN() },
                r.optDouble("r3", Double.NaN).takeUnless { it.isNaN() },
                r.optDouble("r5", Double.NaN).takeUnless { it.isNaN() }))
        }
        fun stat(key: String): SsaHybridEngine.AdviceStat {
            val s = o.optJSONObject(key) ?: JSONObject()
            return SsaHybridEngine.AdviceStat(
                s.optInt("count", 0), s.optDouble("w1", 0.0), s.optDouble("w3", 0.0), s.optDouble("a5", 0.0))
        }
        val snap = RecoSnapshot(
            params = Params(
                o.optInt("ts", 10), o.optInt("tl", 20), o.optInt("cy", 60),
                o.optInt("em", 2), o.optInt("basePct", 0) / 100.0),
            profile = SsaHybridEngine.TicketProfile(
                p.optString("label", "MIXED"), p.optDouble("hi3", 0.0), p.optDouble("hi5", 0.0),
                p.optDouble("lo5", 0.0), p.optDouble("cycAmp", 0.0),
                p.optInt("hiCount", 0), p.optInt("loCount", 0)),
            advice = o.getInt("advice"), date = o.getString("date"), close = o.getDouble("close"),
            recentAdvices = rec,
            buyStat = stat("buyStat"), sellStat = stat("sellStat"),
            holdPosStat = stat("hpStat"), holdEmptyStat = stat("heStat")
        )
        RecoCard(o.getString("code"), snap)
    } catch (e: Exception) { null }

    // ═════════════════════════ 工具 ═════════════════════════

    /** 原子写：先写临时文件再替换，防中断产生半截状态文件 */
    private fun writeSafe(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            Files.copy(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            tmp.delete()
        }
    }

    // ═════════════════════════ 备份 / 恢复 ═════════════════════════

    /**
     * 把整个荐股目录（index + 全部 state + 卡片缓存）打包为 zip 写入 [out]。
     * @return 打包的文件数（目录为空返回 0）
     */
    @Synchronized
    fun exportZip(out: OutputStream): Int {
        val files = dir.listFiles()?.filter { it.isFile && it.extension == "json" }?.sortedBy { it.name }
            ?: emptyList()
        if (files.isEmpty()) return 0
        var n = 0
        ZipOutputStream(out.buffered()).use { zip ->
            files.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                n++
            }
        }
        return n
    }

    /**
     * 从 [input] 解压恢复：先解到临时目录校验（须含 index.json），全部成功后整体替换当前目录
     * （中断不破坏现有状态）。
     * @return 恢复的文件数
     * @throws IllegalArgumentException 缺 index.json（不是有效备份）或包内无文件
     */
    @Synchronized
    fun importZip(input: InputStream): Int {
        val tmp = File(dir.parentFile, "${dir.name}_restore_tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        var n = 0
        try {
            ZipInputStream(input.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        // 只取末级文件名，防路径穿越
                        File(tmp, File(e.name).name).outputStream().use { zip.copyTo(it) }
                        n++
                    }
                    e = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw IllegalArgumentException("压缩包读取失败：${e.message}")
        }
        if (n == 0 || !File(tmp, "index.json").exists()) {
            tmp.deleteRecursively()
            throw IllegalArgumentException("缺少 index.json，不是有效的荐股备份")
        }
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) {
            dir.mkdirs()
            tmp.listFiles()?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            tmp.deleteRecursively()
        }
        return n
    }
}
