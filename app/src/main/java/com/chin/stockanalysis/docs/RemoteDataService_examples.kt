package com.chin.stockanalysis.docs

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface RemoteDataService_examples {
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

// DTOs

data class RunResponse(val exec_id: String, val status: String, val message: String?)

data class StrategyResultsResponse(val strategy_id: String, val date: String, val exec_id: String?, val status: String?, val results: List<Map<String, Any>>)

data class ExecutionStatusResponse(val exec_id: String, val strategy_id: String?, val date: String?, val status: String, val meta: Map<String, Any>?)

data class StartSimulationRequest(val date: String, val use_strategies: List<String>, val merge_mode: String = "star", val user_id: String? = null, val capital: Double? = null, val max_positions: Int? = null)

data class StartSimulationResponse(val simulation_id: String, val status: String, val report_url: String?)

data class SimulationReportResponse(val simulation_id: String, val trade_date: String, val ai_picks: List<Map<String,Any>>, val buy_orders: List<Map<String,Any>>, val summary: String, val backtest_metrics: Map<String,Any>?)

data class UiSelectionsResponse(val date: String, val strategies: List<Map<String,Any>>, val merged: List<Map<String,Any>>)

// AI DTOs

data class AnalyzeStockRequest(val symbol: String, val date: String, val mode: String? = null)

data class AnalyzeStockResponse(val exec_id: String, val status: String, val data: Map<String, Any>?)
