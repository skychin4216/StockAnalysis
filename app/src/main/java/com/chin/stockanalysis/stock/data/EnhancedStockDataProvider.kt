/**
 * ### EnhancedStockDataProvider 已拆分为独立文件
 *
 * 为保持架构清晰，原 EnhancedStockDataProvider.kt 中的 5 个组件
 * 已拆分为各自独立的文件，使用相同包名和类名，调用方无需任何修改：
 *
 * | 组件 | 新文件位置 |
 * |------|-----------|
 * | SmartStockCache | SmartStockCache.kt |
 * | MultiSourceStockRepository | MultiSourceStockRepository.kt |
 * | StockDataSourceFactory | StockDataSourceFactory.kt |
 * | AKShareSource | sources/AKShareSource.kt |
 * | JoinQuantsSource | sources/JoinQuantsSource.kt |
 *
 * 更新内容：
 * - 所有数据源统一使用 HttpClientProvider 共享连接池
 * - 新增指数退避重试
 * - 新增 User-Agent 反爬Headers
 * - 代码结构更清晰，方便后续维护
 *
 * 此文件不再包含任何实现，保留仅用于编译兼容性参考。
 */
package com.chin.stockanalysis.stock.data