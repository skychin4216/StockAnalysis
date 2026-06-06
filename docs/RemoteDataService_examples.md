# RemoteDataService 客户端示例（Kotlin + Retrofit）

示例包括 Retrofit 接口和使用示例，供移动端调用后端 Strategy/Simulation API。

依赖（Gradle）:

implementation "com.squareup.retrofit2:retrofit:2.9.0"
implementation "com.squareup.retrofit2:converter-moshi:2.9.0"
implementation "com.squareup.okhttp3:logging-interceptor:4.9.3"
implementation "com.squareup.retrofit2:converter-scalars:2.9.0"

Retrofit 接口定义：

interface StrategyApi {
    @POST("/api/strategies/{strategy_id}/run")
    suspend fun runStrategy(
        @Path("strategy_id") strategyId: String,
        @Query("date") date: String,
        @Body body: Map<String, Any>? = null
    ): RunResponse

    @GET("/api/strategies/{strategy_id}/results")
    suspend fun getStrategyResults(
        @Path("strategy_id") strategyId: String,
        @Query("date") date: String
    ): StrategyResultsResponse

    @GET("/api/executions/{exec_id}")
    suspend fun getExecution(@Path("exec_id") execId: String): ExecutionStatusResponse

    @POST("/api/simulations/start")
    suspend fun startSimulation(@Body req: StartSimulationRequest): StartSimulationResponse

    @GET("/api/simulations/{simulation_id}/report")
    suspend fun getSimulationReport(@Path("simulation_id") simId: String): SimulationReportResponse

    @GET("/api/ui/selections")
    suspend fun getUiSelections(@Query("date") date: String): UiSelectionsResponse

    @POST("/api/ai/analyze_stock")
    suspend fun analyzeStock(@Body req: AnalyzeStockRequest): AnalyzeStockResponse
}

// 新增 DTOs

data class AnalyzeStockRequest(val symbol: String, val date: String, val mode: String? = null)

data class AnalyzeStockResponse(val exec_id: String, val status: String, val data: Map<String, Any>?)


// DTOs (简化版)

data class RunResponse(val exec_id: String, val status: String, val message: String?)

data class StrategyResultsResponse(val strategy_id: String, val date: String, val exec_id: String?, val status: String?, val results: List<Map<String, Any>>)

data class ExecutionStatusResponse(val exec_id: String, val strategy_id: String?, val date: String?, val status: String, val meta: Map<String, Any>?)

data class StartSimulationRequest(val date: String, val use_strategies: List<String>, val merge_mode: String = "star", val user_id: String?, val capital: Double?, val max_positions: Int?)

data class StartSimulationResponse(val simulation_id: String, val status: String, val report_url: String?)

data class SimulationReportResponse(val simulation_id: String, val trade_date: String, val ai_picks: List<Map<String,Any>>, val buy_orders: List<Map<String,Any>>, val summary: String, val backtest_metrics: Map<String,Any>?)

data class UiSelectionsResponse(val date: String, val strategies: List<Map<String,Any>>, val merged: List<Map<String,Any>>)

// 使用示例

fun createStrategyApi(baseUrl: String): StrategyApi {
    val moshi = com.squareup.moshi.Moshi.Builder().build()
    val retrofit = retrofit2.Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create(moshi))
        .build()
    return retrofit.create(StrategyApi::class.java)
}

suspend fun exampleRunAndPoll(api: StrategyApi) {
    val runResp = api.runStrategy("A", "2026-06-07", mapOf("params" to mapOf("threshold" to 0.7)))
    val execId = runResp.exec_id

    // 简单轮询 exec 状态
    repeat(20) {
        val status = api.getExecution(execId)
        if (status.status == "completed") {
            val res = api.getStrategyResults("A", "2026-06-07")
            println("Got results: ${'$'}{res.results.size}")
            return
        }
        kotlinx.coroutines.delay(1500)
    }
}

// Websocket/SSE 建议

/*
建议服务端提供 websocket 或 SSE 推送：当 exec 完成或 simulation 报告可用时推送事件。
客户端使用 OkHttp 的 WebSocket 或 OkSse 等库订阅。
*/
