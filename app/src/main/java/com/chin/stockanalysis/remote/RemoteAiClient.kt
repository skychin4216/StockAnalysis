package com.chin.stockanalysis.remote

import android.content.Context
import com.chin.stockanalysis.docs.RemoteDataService_examples
import com.chin.stockanalysis.strategy.predict.AIAnalyzeHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RemoteAiClient: 当 baseUrl == "LOCAL" 时直接调用本地 AIAnalyzeHandler；
 * 否则通过 Retrofit/RemoteDataService_examples 调用远端 API。
 */
class RemoteAiClient(private val context: Context, private val baseUrl: String) {

    private val localHandler: AIAnalyzeHandler? = if (baseUrl == "LOCAL") AIAnalyzeHandler(context) else null
    private val remoteApi: RemoteDataService_examples? = if (baseUrl != "LOCAL") RetrofitFactory.createStrategyApi(baseUrl) else null

    suspend fun analyzeStock(symbol: String, date: String, mode: String? = null): AnalyzeResult {
        return withContext(Dispatchers.IO) {
            if (localHandler != null) {
                val req = AIAnalyzeHandler.AnalyzeRequest(symbol, date, mode)
                val res = localHandler.analyze(req)
                AnalyzeResult(execId = res.execId, status = res.status, raw = res.rawResponse)
            } else if (remoteApi != null) {
                val resp = remoteApi.analyzeStock(RemoteDataService_examples.AnalyzeStockRequest(symbol, date, mode))
                AnalyzeResult(execId = resp.exec_id, status = resp.status, raw = resp.data?.toString())
            } else {
                AnalyzeResult(execId = "", status = "error", raw = "no api configured")
            }
        }
    }

    data class AnalyzeResult(val execId: String, val status: String, val raw: String?)
}
