package com.chin.stockanalysis.remote

import com.chin.stockanalysis.docs.RemoteDataService_examples
import com.squareup.moshi.Moshi
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object RetrofitFactory {
    fun createStrategyApi(baseUrl: String): RemoteDataService_examples.StrategyApi {
        val moshi = Moshi.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        return retrofit.create(RemoteDataService_examples.StrategyApi::class.java)
    }

    typealias StrategyApi = RemoteDataService_examples.StrategyApi
}
