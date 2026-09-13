package com.chin.stockanalysis.strategy.topology.pipelines

import java.time.LocalTime
import com.chin.stockanalysis.strategy.trade.TTradeType

/**
 * ## A股日内做T的7个关键时间段
 *
 * 根据「大A祖训」和实战经验，不同时间段的做T策略方向完全不同。
 * 本类将全天交易时间划分为7个时段，每个时段对正T/反T信号施加不同的权重调整。
 *
 * ### 核心口诀
 * | 时间段     | 口诀 | 动作           |
 * |-----------|------|----------------|
 * | 9:30-9:40 | 跑   | 高抛为主       |
 * | 9:50-10:10| 跑   | 倒T卖点        |
 * | 10:10-10:40| 看  | 观察去留       |
 * | 11:10-11:30| 防  | 急拉陷阱       |
 * | 13:00-13:30| 防  | 开盘杀         |
 * | 13:30-14:00| 等  | 垃圾时间       |
 * | 14:00-15:00| 盯/决| 方向选择+定调 |
 */
enum class TTimeSlot(
    val label: String,
    val emoji: String,
    val startHour: Int,
    val startMin: Int,
    val endHour: Int,
    val endMin: Int,
    /** 正T（T_BUY）分数调整 */
    val tBuyAdjust: Int,
    /** 反T（RT_SELL）分数调整 */
    val rtSellAdjust: Int,
    /** 整体置信度缩放因子（0.0~1.0），用于「观望时段」降低所有信号置信度 */
    val confidenceScale: Double,
    val actionHint: String
) {
    /** 9:30-9:40 — 早盘冲高/情绪高点，高抛为主，忌追高 */
    EARLY_RUSH(
        "早盘冲高", "🏃", 9, 30, 9, 40,
        tBuyAdjust = -25,    // 严禁追高
        rtSellAdjust = +20,  // 高抛好时机
        confidenceScale = 0.9,
        actionHint = "高抛为主，忌追高！散户跟风最踊跃，主力常利用少量资金拉高诱多"
    ),

    /** 9:50-10:10 — 短期高点，适合倒T卖出 */
    SHORT_TERM_PEAK(
        "短期高点", "🏃", 9, 50, 10, 10,
        tBuyAdjust = -15,
        rtSellAdjust = +15,
        confidenceScale = 0.85,
        actionHint = "第一轮博弈结束，容易形成日内短期高点，见好就收"
    ),

    /** 10:10-10:40 — 主力动向观察期 */
    INST_OBSERVATION(
        "主力动向观察", "👀", 10, 10, 10, 40,
        tBuyAdjust = -5,
        rtSellAdjust = -5,
        confidenceScale = 0.7,  // 降低所有信号置信度，观望为主
        actionHint = "真正的主力决定今天是否「干活」的时间，稳步拉升+资金流入=可持有，无动静=震荡或下跌"
    ),

    /** 10:40-11:10 — 正常交易时段（无特殊偏向） */
    MID_MORNING_NORMAL(
        "上午正常时段", "📊", 10, 40, 11, 10,
        tBuyAdjust = 0,
        rtSellAdjust = 0,
        confidenceScale = 1.0,
        actionHint = "正常交易时段，按机构意图和技术指标正常判断"
    ),

    /** 11:10-11:30 — 午盘收盘/急拉陷阱 */
    LUNCH_TRAP(
        "午盘急拉陷阱", "⚠️", 11, 10, 11, 30,
        tBuyAdjust = -20,    // 急拉别跟
        rtSellAdjust = +10,
        confidenceScale = 0.8,
        actionHint = "临近午盘直线拉升大概率是做图，除非极度强势否则持续性差，追进容易站岗"
    ),

    /** 13:00-13:30 — 午后开盘/警惕开盘杀 */
    AFTERNOON_OPEN(
        "午后开盘杀", "⚠️", 13, 0, 13, 30,
        tBuyAdjust = -15,
        rtSellAdjust = +10,
        confidenceScale = 0.85,
        actionHint = "脉冲式拉升大概率诱多，快速下跌反而是日内相对低点"
    ),

    /** 13:30-14:00 — 垃圾时间/看戏为主 */
    DEAD_ZONE(
        "垃圾时间", "🎭", 13, 30, 14, 0,
        tBuyAdjust = -10,
        rtSellAdjust = -10,
        confidenceScale = 0.6,  // 大幅降低置信度，多空平衡别激动
        actionHint = "多空平衡，主力洗盘或准备大动作，多看少动"
    ),

    /** 14:00-14:30 — 方向选择（关键转折） */
    DIRECTION_PICK(
        "方向选择", "🎯", 14, 0, 14, 30,
        tBuyAdjust = +10,     // 方向确认后可跟
        rtSellAdjust = +10,
        confidenceScale = 1.0,
        actionHint = "全天最关键时段开始：股价回落=全天走弱，资金抢筹=次日还有行情，游资常偷袭涨停"
    ),

    /** 14:30-15:00 — 定调时刻（决定去留） */
    FINAL_CALL(
        "定调时刻", "⚡", 14, 30, 15, 0,
        tBuyAdjust = +15,     // 强势行情可跟
        rtSellAdjust = +15,
        confidenceScale = 1.1,  // 增强强信号置信度
        actionHint = "最重要的转折点：强势=继续拉高为明天出货做准备，弱势=冲高回落诱多，尾盘直线拉升别激动"
    ),

    /** 非交易时段（盘前/午休/盘后） */
    NON_TRADING(
        "非交易时段", "💤", 0, 0, 0, 0,
        tBuyAdjust = 0,
        rtSellAdjust = 0,
        confidenceScale = 0.0,  // 不产生信号
        actionHint = "非交易时段，不产生做T建议"
    );

    companion object {
        /**
         * 根据当前时间返回对应的时间段。
         *
         * @param time 当前时间（可注入测试用）
         * @return 对应的 TTimeSlot
         */
        fun fromTime(time: LocalTime = LocalTime.now()): TTimeSlot {
            // 非交易时段
            if (time.isBefore(LocalTime.of(9, 30)) || time.isAfter(LocalTime.of(15, 0))) {
                return NON_TRADING
            }
            // 午休
            if (time.isAfter(LocalTime.of(11, 30)) && time.isBefore(LocalTime.of(13, 0))) {
                return NON_TRADING
            }

            // 按时间段匹配
            for (slot in values()) {
                if (slot == NON_TRADING) continue
                val start = LocalTime.of(slot.startHour, slot.startMin)
                val end = LocalTime.of(slot.endHour, slot.endMin)
                if (!time.isBefore(start) && time.isBefore(end)) {
                    return slot
                }
            }

            // 15:00 之后归入 FINAL_CALL（收盘前最后几分钟）
            return if (time.isAfter(LocalTime.of(14, 30))) FINAL_CALL else NON_TRADING
        }

        /**
         * 获取当前时间段的做T建议摘要（供 UI 显示）。
         */
        fun currentTimeAdvice(): String {
            val slot = fromTime()
            return if (slot == NON_TRADING) {
                "💤 非交易时段"
            } else {
                "${slot.emoji} ${slot.label}：${slot.actionHint.take(30)}..."
            }
        }
    }
}

/**
 * ## 时段权重调整器
 *
 * 在信号合成时，根据当前时间段对正T/反T分数施加额外调整。
 * 这是做T七个关键时间点的程式化实现。
 */
object TTimeSlotAdjuster {

    data class TimeSlotAdjustment(
        val slot: TTimeSlot,
        val tBuyScoreAdj: Int,
        val rtSellScoreAdj: Int,
        val confidenceScale: Double,
        val hint: String
    )

    /**
     * 计算当前时间段的权重调整。
     *
     * @param signalType 信号类型（T_BUY / RT_SELL）
     * @return 分数调整值和置信度缩放因子
     */
    fun adjust(signalType: TTradeType): TimeSlotAdjustment {
        val slot = TTimeSlot.fromTime()
        return TimeSlotAdjustment(
            slot = slot,
            tBuyScoreAdj = if (signalType == TTradeType.T_BUY) slot.tBuyAdjust else 0,
            rtSellScoreAdj = if (signalType == TTradeType.RT_SELL) slot.rtSellAdjust else 0,
            confidenceScale = slot.confidenceScale,
            hint = "${slot.emoji}${slot.label}: ${slot.actionHint}"
        )
    }

    /**
     * 格式化时段调整信息（供日志/报告输出）。
     */
    fun formatSummary(): String {
        val slot = TTimeSlot.fromTime()
        return if (slot == TTimeSlot.NON_TRADING) {
            "💤 非交易时段，不产生做T建议"
        } else {
            buildString {
                append("${slot.emoji} 当前时段: ${slot.label}")
                append(" | 正T调整: ${if (slot.tBuyAdjust >= 0) "+" else ""}${slot.tBuyAdjust}")
                append(" | 反T调整: ${if (slot.rtSellAdjust >= 0) "+" else ""}${slot.rtSellAdjust}")
                append(" | 置信度缩放: ${"%.0f".format(slot.confidenceScale * 100)}%")
            }
        }
    }
}
