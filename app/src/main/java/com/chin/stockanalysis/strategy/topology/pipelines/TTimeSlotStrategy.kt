package com.chin.stockanalysis.strategy.topology.pipelines

import java.time.LocalTime
import com.chin.stockanalysis.strategy.trade.TTradeType

/**
 * ## A股日內做T的7個關鍵時間段
 *
 * 根據「大A祖訓」和實戰經驗，不同時間段的做T策略方向完全不同。
 * 本類將全天交易時間劃分為7個時段，每個時段對正T/反T信號施加不同的權重調整。
 *
 * ### 核心口訣
 * | 時間段     | 口訣 | 動作           |
 * |-----------|------|----------------|
 * | 9:30-9:40 | 跑   | 高拋為主       |
 * | 9:50-10:10| 跑   | 倒T賣點        |
 * | 10:10-10:40| 看  | 觀察去留       |
 * | 11:10-11:30| 防  | 急拉陷阱       |
 * | 13:00-13:30| 防  | 開盤殺         |
 * | 13:30-14:00| 等  | 垃圾時間       |
 * | 14:00-15:00| 盯/決| 方向選擇+定調 |
 */
enum class TTimeSlot(
    val label: String,
    val emoji: String,
    val startHour: Int,
    val startMin: Int,
    val endHour: Int,
    val endMin: Int,
    /** 正T（T_BUY）分數調整 */
    val tBuyAdjust: Int,
    /** 反T（RT_SELL）分數調整 */
    val rtSellAdjust: Int,
    /** 整體置信度縮放因子（0.0~1.0），用於「觀望時段」降低所有信號置信度 */
    val confidenceScale: Double,
    val actionHint: String
) {
    /** 9:30-9:40 — 早盤衝高/情緒高點，高拋為主，忌追高 */
    EARLY_RUSH(
        "早盤衝高", "🏃", 9, 30, 9, 40,
        tBuyAdjust = -25,    // 嚴禁追高
        rtSellAdjust = +20,  // 高拋好時機
        confidenceScale = 0.9,
        actionHint = "高拋為主，忌追高！散戶跟風最踴躍，主力常利用少量資金拉高誘多"
    ),

    /** 9:50-10:10 — 短期高點，適合倒T賣出 */
    SHORT_TERM_PEAK(
        "短期高點", "🏃", 9, 50, 10, 10,
        tBuyAdjust = -15,
        rtSellAdjust = +15,
        confidenceScale = 0.85,
        actionHint = "第一輪博弈結束，容易形成日內短期高點，見好就收"
    ),

    /** 10:10-10:40 — 主力動向觀察期 */
    INST_OBSERVATION(
        "主力動向觀察", "👀", 10, 10, 10, 40,
        tBuyAdjust = -5,
        rtSellAdjust = -5,
        confidenceScale = 0.7,  // 降低所有信號置信度，觀望為主
        actionHint = "真正的主力決定今天是否「幹活」的時間，穩步拉升+資金流入=可持有，無動靜=震盪或下跌"
    ),

    /** 10:40-11:10 — 正常交易時段（無特殊偏向） */
    MID_MORNING_NORMAL(
        "上午正常時段", "📊", 10, 40, 11, 10,
        tBuyAdjust = 0,
        rtSellAdjust = 0,
        confidenceScale = 1.0,
        actionHint = "正常交易時段，按機構意圖和技術指標正常判斷"
    ),

    /** 11:10-11:30 — 午盤收盤/急拉陷阱 */
    LUNCH_TRAP(
        "午盤急拉陷阱", "⚠️", 11, 10, 11, 30,
        tBuyAdjust = -20,    // 急拉別跟
        rtSellAdjust = +10,
        confidenceScale = 0.8,
        actionHint = "臨近午盤直線拉升大概率是做圖，除非極度強勢否則持续性差，追進容易站崗"
    ),

    /** 13:00-13:30 — 午後開盤/警惕開盤殺 */
    AFTERNOON_OPEN(
        "午後開盤殺", "⚠️", 13, 0, 13, 30,
        tBuyAdjust = -15,
        rtSellAdjust = +10,
        confidenceScale = 0.85,
        actionHint = "脈衝式拉升大概率誘多，快速下跌反而是日內相對低點"
    ),

    /** 13:30-14:00 — 垃圾時間/看戲為主 */
    DEAD_ZONE(
        "垃圾時間", "🎭", 13, 30, 14, 0,
        tBuyAdjust = -10,
        rtSellAdjust = -10,
        confidenceScale = 0.6,  // 大幅降低置信度，多空平衡別激動
        actionHint = "多空平衡，主力洗盤或準備大動作，多看少動"
    ),

    /** 14:00-14:30 — 方向選擇（關鍵轉折） */
    DIRECTION_PICK(
        "方向選擇", "🎯", 14, 0, 14, 30,
        tBuyAdjust = +10,     // 方向確認後可跟
        rtSellAdjust = +10,
        confidenceScale = 1.0,
        actionHint = "全天最關鍵時段開始：股價回落=全天走弱，資金搶籌=次日還有行情，游資常偷襲漲停"
    ),

    /** 14:30-15:00 — 定調時刻（決定去留） */
    FINAL_CALL(
        "定調時刻", "⚡", 14, 30, 15, 0,
        tBuyAdjust = +15,     // 強勢行情可跟
        rtSellAdjust = +15,
        confidenceScale = 1.1,  // 增強強信號置信度
        actionHint = "最重要的轉折點：強勢=繼續拉高為明天出貨做準備，弱勢=沖高回落誘多，尾盤直線拉升別激動"
    ),

    /** 非交易時段（盤前/午休/盤後） */
    NON_TRADING(
        "非交易時段", "💤", 0, 0, 0, 0,
        tBuyAdjust = 0,
        rtSellAdjust = 0,
        confidenceScale = 0.0,  // 不產生信號
        actionHint = "非交易時段，不產生做T建議"
    );

    companion object {
        /**
         * 根據當前時間返回對應的時間段。
         *
         * @param time 當前時間（可注入測試用）
         * @return 對應的 TTimeSlot
         */
        fun fromTime(time: LocalTime = LocalTime.now()): TTimeSlot {
            // 非交易時段
            if (time.isBefore(LocalTime.of(9, 30)) || time.isAfter(LocalTime.of(15, 0))) {
                return NON_TRADING
            }
            // 午休
            if (time.isAfter(LocalTime.of(11, 30)) && time.isBefore(LocalTime.of(13, 0))) {
                return NON_TRADING
            }

            // 按時間段匹配
            for (slot in values()) {
                if (slot == NON_TRADING) continue
                val start = LocalTime.of(slot.startHour, slot.startMin)
                val end = LocalTime.of(slot.endHour, slot.endMin)
                if (!time.isBefore(start) && time.isBefore(end)) {
                    return slot
                }
            }

            // 15:00 之後歸入 FINAL_CALL（收盤前最後幾分鐘）
            return if (time.isAfter(LocalTime.of(14, 30))) FINAL_CALL else NON_TRADING
        }

        /**
         * 獲取當前時間段的做T建議摘要（供 UI 顯示）。
         */
        fun currentTimeAdvice(): String {
            val slot = fromTime()
            return if (slot == NON_TRADING) {
                "💤 非交易時段"
            } else {
                "${slot.emoji} ${slot.label}：${slot.actionHint.take(30)}..."
            }
        }
    }
}

/**
 * ## 時段權重調整器
 *
 * 在信號合成時，根據當前時間段對正T/反T分數施加額外調整。
 * 這是做T七個關鍵時間點的程式化實現。
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
     * 計算當前時間段的權重調整。
     *
     * @param signalType 信號類型（T_BUY / RT_SELL）
     * @return 分數調整值和置信度縮放因子
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
     * 格式化時段調整信息（供日誌/報告輸出）。
     */
    fun formatSummary(): String {
        val slot = TTimeSlot.fromTime()
        return if (slot == TTimeSlot.NON_TRADING) {
            "💤 非交易時段，不產生做T建議"
        } else {
            buildString {
                append("${slot.emoji} 當前時段: ${slot.label}")
                append(" | 正T調整: ${if (slot.tBuyAdjust >= 0) "+" else ""}${slot.tBuyAdjust}")
                append(" | 反T調整: ${if (slot.rtSellAdjust >= 0) "+" else ""}${slot.rtSellAdjust}")
                append(" | 置信度縮放: ${"%.0f".format(slot.confidenceScale * 100)}%")
            }
        }
    }
}
