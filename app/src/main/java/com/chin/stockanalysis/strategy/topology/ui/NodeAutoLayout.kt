package com.chin.stockanalysis.strategy.topology.ui

/**
 * ## NodeAutoLayout — 節點自動佈局
 *
 * 根據連線拓撲結構，將節點排列為分層（Sugiyama 風格簡化版）佈局：
 * - 按拓撲順序分層（Kahn 算法），同層節點垂直排列
 * - 層間水平間距固定，層內垂直間距固定
 * - 無依賴的根節點放在第 0 層
 *
 * 佈局結果直接寫回 [VisualNode] 的 `x` / `y` 字段。
 */
object NodeAutoLayout {

    /** 層間水平間距（像素） */
    private const val LAYER_GAP = 320f

    /** 層內節點垂直間距（像素） */
    private const val NODE_GAP = 130f

    /** 起始座標偏移（像素） */
    private const val ORIGIN_X = 60f
    private const val ORIGIN_Y = 60f

    /**
     * 對節點列表執行自動佈局，原地修改節點的 (x, y) 座標。
     *
     * @param nodes 待佈局的節點列表（可變，座標會被原地更新）
     * @param links 連線列表（用於推導拓撲依賴關係）
     */
    fun layout(nodes: MutableList<VisualNode>, links: MutableList<VisualLink>) {
        if (nodes.isEmpty()) return

        val nodeIds = nodes.map { it.id }.toSet()

        // 只考慮兩端都存在的連線
        val validLinks = links.filter { it.fromId in nodeIds && it.toId in nodeIds }

        // 構建鄰接表與入度表
        val outgoing = validLinks.groupBy { it.fromId }
            .mapValues { (_, ls) -> ls.map { it.toId }.distinct() }
        val inDegree = nodes.associate { it.id to 0 }.toMutableMap()
        for (link in validLinks) {
            inDegree[link.toId] = (inDegree[link.toId] ?: 0) + 1
        }

        // Kahn 拓撲排序 → 分層
        val remaining = inDegree.toMutableMap()
        val layers = mutableListOf<MutableList<String>>()

        while (remaining.isNotEmpty()) {
            // 當前層：所有入度為 0 的節點
            val ready = remaining.filter { it.value == 0 }.keys.toList()
            if (ready.isEmpty()) {
                // 存在環：將剩餘節點放入新一層以避免死循環
                layers.add(remaining.keys.toMutableList())
                break
            }
            layers.add(ready.toMutableList())
            for (id in ready) {
                remaining.remove(id)
                for (target in outgoing[id] ?: emptyList()) {
                    remaining[target] = (remaining[target] ?: 1) - 1
                }
            }
        }

        // 計算每層節點數量，用於垂直居中偏移
        val nodeMap = nodes.associateBy { it.id }

        for ((layerIndex, layer) in layers.withIndex()) {
            val layerHeight = layer.size * NODE_GAP
            val startY = ORIGIN_Y + (maxOf(0, 4 - layer.size) * NODE_GAP / 2f)
            for ((posInLayer, nodeId) in layer.withIndex()) {
                val node = nodeMap[nodeId] ?: continue
                node.x = ORIGIN_X + layerIndex * LAYER_GAP
                node.y = startY + posInLayer * NODE_GAP
            }
            // 防止層過高時與下一層重疊：不額外處理，NODE_GAP 已足夠
            if (layerHeight > 4 * NODE_GAP) {
                // 層較高時保留實際高度，下一層自然右移
            }
        }
    }
}
