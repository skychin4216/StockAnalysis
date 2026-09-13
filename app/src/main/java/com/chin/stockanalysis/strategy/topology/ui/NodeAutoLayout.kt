package com.chin.stockanalysis.strategy.topology.ui

/**
 * ## NodeAutoLayout — 节点自动布局
 *
 * 根据连线拓扑结构，将节点排列为分层（Sugiyama 风格简化版）布局：
 * - 按拓扑顺序分层（Kahn 算法），同层节点垂直排列
 * - 层间水平间距固定，层内垂直间距固定
 * - 无依赖的根节点放在第 0 层
 *
 * 布局结果直接写回 [VisualNode] 的 `x` / `y` 字段。
 */
object NodeAutoLayout {

    /** 层间水平间距（像素） */
    private const val LAYER_GAP = 320f

    /** 层内节点垂直间距（像素） */
    private const val NODE_GAP = 130f

    /** 起始座标偏移（像素） */
    private const val ORIGIN_X = 60f
    private const val ORIGIN_Y = 60f

    /**
     * 对节点列表执行自动布局，原地修改节点的 (x, y) 座标。
     *
     * @param nodes 待布局的节点列表（可变，座标会被原地更新）
     * @param links 连线列表（用于推导拓扑依赖关系）
     */
    fun layout(nodes: MutableList<VisualNode>, links: MutableList<VisualLink>) {
        if (nodes.isEmpty()) return

        val nodeIds = nodes.map { it.id }.toSet()

        // 只考虑两端都存在的连线
        val validLinks = links.filter { it.fromId in nodeIds && it.toId in nodeIds }

        // 构建邻接表与入度表
        val outgoing = validLinks.groupBy { it.fromId }
            .mapValues { (_, ls) -> ls.map { it.toId }.distinct() }
        val inDegree = nodes.associate { it.id to 0 }.toMutableMap()
        for (link in validLinks) {
            inDegree[link.toId] = (inDegree[link.toId] ?: 0) + 1
        }

        // Kahn 拓扑排序 → 分层
        val remaining = inDegree.toMutableMap()
        val layers = mutableListOf<MutableList<String>>()

        while (remaining.isNotEmpty()) {
            // 当前层：所有入度为 0 的节点
            val ready = remaining.filter { it.value == 0 }.keys.toList()
            if (ready.isEmpty()) {
                // 存在环：将剩余节点放入新一层以避免死循环
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

        // 计算每层节点数量，用于垂直居中偏移
        val nodeMap = nodes.associateBy { it.id }

        for ((layerIndex, layer) in layers.withIndex()) {
            val layerHeight = layer.size * NODE_GAP
            val startY = ORIGIN_Y + (maxOf(0, 4 - layer.size) * NODE_GAP / 2f)
            for ((posInLayer, nodeId) in layer.withIndex()) {
                val node = nodeMap[nodeId] ?: continue
                node.x = ORIGIN_X + layerIndex * LAYER_GAP
                node.y = startY + posInLayer * NODE_GAP
            }
            // 防止层过高时与下一层重叠：不额外处理，NODE_GAP 已足够
            if (layerHeight > 4 * NODE_GAP) {
                // 层较高时保留实际高度，下一层自然右移
            }
        }
    }
}
