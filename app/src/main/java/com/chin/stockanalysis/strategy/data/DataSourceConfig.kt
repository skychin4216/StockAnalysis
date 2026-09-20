package com.chin.stockanalysis.strategy.data

import com.chin.stockanalysis.config.DataConfig

// 自动生成，请勿手改：python smalltools/_sources.py --sync-apk
// 来源：data/datasources.json（updated=2026-09-19）
// 覆盖优先级：app_config.json 的 "ds.<name>" > 本常量表 > 调用方 fallback

object DataSourceConfig {

    private val URLS = mapOf(
        "bigmodel_api" to "https://open.bigmodel.cn/api/paas/v4/chat/completions",
        "cpc_noaa_oni" to "https://www.cpc.ncep.noaa.gov/data/indices/oni.ascii",
        "dashscope_api" to "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        "deepseek_api" to "https://api.deepseek.com/v1/chat/completions",
        "east_article" to "https://finance.eastmoney.com/a/{code}.html",
        "east_clist" to "https://push2.eastmoney.com/api/qt/clist/get",
        "east_cnotice" to "https://np-cnotice-stock.eastmoney.com/api/content/ann",
        "east_datacenter" to "https://datacenter-web.eastmoney.com/api/data/v1/get",
        "east_datacenter_sec" to "https://datacenter.eastmoney.com/securities/api/data/v1/get",
        "east_delay_clist" to "https://push2delay.eastmoney.com/api/qt/clist/get",
        "east_delay_fflow" to "https://push2delay.eastmoney.com/api/qt/stock/fflow/daykline/get",
        "east_delay_stock" to "https://push2delay.eastmoney.com/api/qt/stock/get",
        "east_delay_stock_flow" to "https://push2delay.eastmoney.com/api/qt/stock/fflow/kline/get",
        "east_delay_ulist" to "https://push2delay.eastmoney.com/api/qt/ulist.np/get",
        "east_emweb" to "https://emweb.securities.eastmoney.com/PC_HSF10/CompanySurvey/PageAjax",
        "east_fast_news" to "https://np-listapi.eastmoney.com/comm/web/getFastNewsList",
        "east_fund_ann" to "https://api.fund.eastmoney.com/f10/JJGG",
        "east_fund_api" to "https://api.fund.eastmoney.com/f10/lsjz",
        "east_fundf10" to "https://fundf10.eastmoney.com/FundArchivesDatas.aspx",
        "east_fundmob" to "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNFInfo",
        "east_kline" to "https://90.push2his.eastmoney.com/api/qt/stock/kline/get",
        "east_news_api" to "https://np-listapi.eastmoney.com/comm/web/getNewsByColumns",
        "east_notice" to "https://np-anotice-stock.eastmoney.com/api/security/ann",
        "east_pdf" to "http://pdf.dfcfw.com/pdf/H2_{code}_1.pdf",
        "east_report_info" to "https://data.eastmoney.com/report/info/{info}.html",
        "east_reportapi" to "https://reportapi.eastmoney.com/report/list",
        "east_search" to "https://search-api-web.eastmoney.com/search/jsonp",
        "fed_press_rss" to "https://www.federalreserve.gov/feeds/press_all.xml",
        "finance_sina" to "https://finance.sina.com.cn/",
        "fred" to "https://fred.stlouisfed.org/graph/fredgraph.csv",
        "gu_qq" to "https://gu.qq.com/",
        "pushplus" to "https://www.pushplus.plus/send",
        "quote_eastmoney" to "https://quote.eastmoney.com/",
        "serverchan" to "https://sctapi.ftqq.com/{sendkey}.send",
        "siliconflow_api" to "https://api.siliconflow.cn/v1/chat/completions",
        "sina_kline" to "https://quotes.sina.cn/cn/api/jsonp_v2.php/=/CN_MarketDataService.getKLineData",
        "sina_m5" to "https://quotes.sina.cn/cn/api/jsonp_v2.php/=/CN_MarketDataService.getKLineData?symbol={codes}&scale={scale}&ma=no&datalen={n}",
        "sina_moneyflow" to "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/MoneyFlow.ssl_qsfx_zjlrqs",
        "sina_quote" to "https://hq.sinajs.cn/list={codes}",
        "sina_zhibo" to "https://zhibo.sina.com.cn/api/zhibo/feed",
        "sse_query" to "https://query.sse.com.cn/commonQuery.do",
        "tencent_kline" to "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get",
        "tencent_proxy_kline" to "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/kline/kline",
        "tencent_proxy_m5" to "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/kline/mkline?param={codes},m5,,{n}",
        "tencent_qt" to "https://qt.gtimg.cn/q={codes}",
        "volces_ark" to "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
        "wallstreetcn" to "https://api-one.wallstcn.com/apiv1/content/lives",
        "wechat_upload_media" to "https://qyapi.weixin.qq.com/cgi-bin/webhook/upload_media",
        "wechat_webhook" to "https://qyapi.weixin.qq.com/cgi-bin/webhook/send",
        "xfyun_spark" to "https://spark-api-open.xf-yun.com/v1/chat/completions",
    )

    private val HOSTS = mapOf(
        "bigmodel_api" to "https://open.bigmodel.cn",
        "cpc_noaa_oni" to "https://www.cpc.ncep.noaa.gov",
        "dashscope_api" to "https://dashscope.aliyuncs.com",
        "deepseek_api" to "https://api.deepseek.com",
        "east_article" to "https://finance.eastmoney.com",
        "east_clist" to "https://push2.eastmoney.com",
        "east_cnotice" to "https://np-cnotice-stock.eastmoney.com",
        "east_datacenter" to "https://datacenter-web.eastmoney.com",
        "east_datacenter_sec" to "https://datacenter.eastmoney.com",
        "east_delay_clist" to "https://push2delay.eastmoney.com",
        "east_delay_fflow" to "https://push2delay.eastmoney.com",
        "east_delay_stock" to "https://push2delay.eastmoney.com",
        "east_delay_stock_flow" to "https://push2delay.eastmoney.com",
        "east_delay_ulist" to "https://push2delay.eastmoney.com",
        "east_emweb" to "https://emweb.securities.eastmoney.com",
        "east_fast_news" to "https://np-listapi.eastmoney.com",
        "east_fund_ann" to "https://api.fund.eastmoney.com",
        "east_fund_api" to "https://api.fund.eastmoney.com",
        "east_fundf10" to "https://fundf10.eastmoney.com",
        "east_fundmob" to "https://fundmobapi.eastmoney.com",
        "east_kline" to "https://90.push2his.eastmoney.com",
        "east_news_api" to "https://np-listapi.eastmoney.com",
        "east_notice" to "https://np-anotice-stock.eastmoney.com",
        "east_pdf" to "http://pdf.dfcfw.com",
        "east_report_info" to "https://data.eastmoney.com",
        "east_reportapi" to "https://reportapi.eastmoney.com",
        "east_search" to "https://search-api-web.eastmoney.com",
        "fed_press_rss" to "https://www.federalreserve.gov",
        "finance_sina" to "https://finance.sina.com.cn",
        "fred" to "https://fred.stlouisfed.org",
        "gu_qq" to "https://gu.qq.com",
        "pushplus" to "https://www.pushplus.plus",
        "quote_eastmoney" to "https://quote.eastmoney.com",
        "serverchan" to "https://sctapi.ftqq.com",
        "siliconflow_api" to "https://api.siliconflow.cn",
        "sina_kline" to "https://quotes.sina.cn",
        "sina_m5" to "https://quotes.sina.cn",
        "sina_moneyflow" to "https://vip.stock.finance.sina.com.cn",
        "sina_quote" to "https://hq.sinajs.cn",
        "sina_zhibo" to "https://zhibo.sina.com.cn",
        "sse_query" to "https://query.sse.com.cn",
        "tencent_kline" to "https://web.ifzq.gtimg.cn",
        "tencent_proxy_kline" to "https://proxy.finance.qq.com",
        "tencent_proxy_m5" to "https://proxy.finance.qq.com",
        "tencent_qt" to "https://qt.gtimg.cn",
        "volces_ark" to "https://ark.cn-beijing.volces.com",
        "wallstreetcn" to "https://api-one.wallstcn.com",
        "wechat_upload_media" to "https://qyapi.weixin.qq.com",
        "wechat_webhook" to "https://qyapi.weixin.qq.com",
        "xfyun_spark" to "https://spark-api-open.xf-yun.com",
    )

    private val ALT_HOSTS = mapOf(
        "east_clist" to listOf("https://90.push2.eastmoney.com", "https://92.push2.eastmoney.com"),
        "east_kline" to listOf("https://push2his.eastmoney.com", "https://17.push2his.eastmoney.com", "https://80.push2his.eastmoney.com"),
    )

    /** 取源 URL；fmt 替换 {占位符}（如 {codes}）。未登记返回 ""。
     *  运行时可用 assets/data/app_config.json 的 "ds.<name>" 覆盖（无需改代码）。 */
    fun url(name: String, vararg fmt: Pair<String, String>): String {
        var u = runCatching { DataConfig.getOrNull("ds.$name") }.getOrNull()
            ?: URLS[name] ?: return ""
        for ((k, v) in fmt) u = u.replace("{$k}", v)
        return u
    }

    /** 未登记时回退 fallback（渐进式迁移用），fmt 同样作用于两者。 */
    fun urlOr(name: String, fallback: String, vararg fmt: Pair<String, String>): String {
        val u = url(name, *fmt)
        if (u.isNotEmpty()) return u
        var f = fallback
        for ((k, v) in fmt) f = f.replace("{$k}", v)
        return f
    }

    /** 协议+主机（如 https://qt.gtimg.cn）。未登记返回 ""。 */
    fun host(name: String): String = HOSTS[name] ?: ""

    /** 多主机备选列表；未登记返回空表。 */
    fun altHosts(name: String): List<String> = ALT_HOSTS[name] ?: emptyList()
}
