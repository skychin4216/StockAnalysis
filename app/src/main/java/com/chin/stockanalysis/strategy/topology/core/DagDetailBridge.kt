package com.chin.stockanalysis.strategy.topology.core

/**
 * ## DAG 节点详细日志桥
 *
 * 引擎层（DagPipeline.executeNode）在每个节点「进入前 / 退出后」通过本桥通知
 * UI 层的 [hook]，用于打印按板块分组的详细调试日志（输入/输出/被过滤股票）。
 *
 * 设计为可挂接的全局单点：
 * - hook == null 时引擎零开销（一次可空判断），不影响其它流程；
 * - hook 由一键建仓日志面板（QuantFragmentBase → DagDetailLogger）挂接，
 *   内部自行捕获异常，绝不影响节点执行结果。
 */
object DagDetailBridge {

    enum class Phase {
        /** 进入节点前：input 为该节点将要消费的输入 */
        ENTER,

        /** 退出节点后：input 为该节点输入、output 为该节点输出（失败时为 null） */
        EXIT
    }

    @Volatile
    var hook: (suspend (pipeline: String, nodeName: String, phase: Phase,
                        input: Any?, output: Any?) -> Unit)? = null

    /** 是否已有消费者挂接（供日志层幂等挂载判断） */
    val isAttached: Boolean get() = hook != null
}
