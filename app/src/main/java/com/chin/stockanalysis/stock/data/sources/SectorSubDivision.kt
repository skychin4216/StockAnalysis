package com.chin.stockanalysis.stock.data.sources

/**
 * ## 板块→子板块精选股票库（v2.0 主创分离版）
 *
 * 静态嵌入领域知识。每股标注：
 * - 股票代码/名称
 * - 核心业务描述
 * - 公开订单/产能信息
 * - 近期利好/利空消息摘要
 * - boardType: "主板" / "创业板" / "科创板"
 *
 * 展示规则（由 SectorDetailFragment 控制）：
 * - 每子板块主板最多取 3 只，创/科创合计最多 2 只
 * - 主板和创/科创分别放入独立表格
 */
object SectorSubDivision {

    data class EnrichedStock(
        val code: String,
        val name: String,
        val business: String,
        val orders: String = "",
        val recentNews: String = "",
        val bullishNews: Boolean = true
    ) {
        val boardType: String get() = when {
            code.startsWith("688") -> "科创板"
            code.startsWith("300") || code.startsWith("301") -> "创业板"
            else -> "主板"
        }
        val isMainBoard: Boolean get() = boardType == "主板"
    }

    data class SubSector(
        val name: String,
        val description: String,
        val stocks: List<EnrichedStock>
    ) {
        val mainBoardStocks: List<EnrichedStock> get() = stocks.filter { it.isMainBoard }.take(3)
        val gemKcbStocks: List<EnrichedStock> get() = stocks.filter { !it.isMainBoard }.take(2)
    }

    // ============ CPO 板块（光通信） ============
    val CPO_SECTORS = listOf(
        SubSector("CPO 光模块", "800G/1.6T 光模块 + CPO 硅光共封装，AI 数据中心核心互连器件", listOf(
            EnrichedStock("sh601138", "工业富联", "英伟达 Rubin CPO 全光交换机+AI 服务器机柜代工 | 特斯拉 FSD 算力/SpaceX 太空硬件", "英伟达 Rubin 架构核心代工厂商", "英伟达 Partner 最高级认证；特斯拉 Dojo 主力供应商"),
            EnrichedStock("sz002475", "立讯精密", "CPO 高速互联组件+光引擎 | 特斯拉车载线束+人形机器人精密结构件", "CPO 光引擎批量供应英伟达生态", "英伟达+特斯拉双供应链核心"),
            EnrichedStock("sz300308", "中际旭创", "全球 800G 光模块出货第一，英伟达认证供应商", "2024 800G 出货 500 万只+，1.6T 光模块即将量产", "英伟达 NVLink 光模块核心供应商"),
            EnrichedStock("sz300502", "新易盛", "800G CPO 光模块+光芯片全产业链", "800G CPO 光模块小批量出货北美云厂商"),
            EnrichedStock("sh603083", "剑桥科技", "高端光模块+智能控制器，400G/800G 全系列 CPO", "CPO 光模块产能持续扩张")
        )),
        SubSector("CPO 硅光芯片", "硅光集成芯片 + 光引擎，CPO 最核心技术壁垒", listOf(
            EnrichedStock("sz002281", "光迅科技", "自研 3.2T 硅光 CPO 模块，英伟达认证", "3.2T 硅光 CPO 通过英伟达测试，2025Q3 量产", "国内唯一英伟达 CPO 硅光芯片认证；主力资金大幅流入"),
            EnrichedStock("sh688313", "仕佳光子", "PLC 光分路器芯片全球第一，25G DFB 光芯片量产", "25G/50G DFB 光芯片量产，硅光 AWG 芯片", "光芯片国产替代最受益标的"),
            EnrichedStock("sz300502", "新易盛", "800G CPO 光模块+光芯片全产业链布局"),
            EnrichedStock("sh688195", "腾景科技", "精密光学元件+光通信滤光片+CPO 连接器，华为供应商", "CPO 连接器已送样认证")
        )),
        SubSector("CPO 高速 PCB/覆铜板", "CPO 算力 PCB 和高端覆铜板，CPO 封装基板核心材料", listOf(
            EnrichedStock("sh600183", "生益科技", "CPO 高端覆铜板(算力 PCB 基材)，全球覆铜板前三", "年产高端覆铜板 1亿+平方米，算力 PCB 基材市占率 30%+", "AI 服务器+CPO 拉动高端覆铜板量价齐升"),
            EnrichedStock("sz002938", "鹏鼎控股", "CPO 封装基板+AI 服务器 PCB | 特斯拉车载 PCB/FSD 硬件", "全球最大 PCB 制造商之一，AI 服务器 PCB 出货量翻倍", "英伟达+特斯拉双供应链 PCB 龙头"),
            EnrichedStock("sz002384", "东山精密", "CPO 光模块封装+高速 PCB | 特斯拉车载钣金+FSD 结构件", "CPO 封装基板产能快速扩张", "英伟达+特斯拉双供应链"),
            EnrichedStock("sz300476", "胜宏科技", "AI 算力 PCB 核心供应商，800G CPO PCB 已量产", "算力 PCB 月产能 50 万㎡", "分板后弹性极大")
        )),
        SubSector("CPO 光纤/互连", "CPO 高速光纤 + 硅光传输链路 + 海底光缆", listOf(
            EnrichedStock("sh601869", "长飞光纤", "CPO 高速光纤+硅光传输链路，全球光纤龙头", "全球光纤市占率 15%+", "AI 数据中心光纤需求爆发"),
            EnrichedStock("sh600487", "亨通光电", "CPO 海底高速光传输+算力光纤+光互联设备", "海底光缆国内市占率超 50%，AI 算力光纤出货量激增"),
            EnrichedStock("sh600522", "中天科技", "CPO 光纤+光互联设备+海洋通信线缆", "国内光纤光缆市占率前三")
        )),
        SubSector("CPO 光芯片+光传感", "光芯片设计+光传感器件+光模块制造一体化", listOf(
            EnrichedStock("sz000988", "华工科技", "CPO 光芯片+高速光组件 | 特斯拉车载光传感硬件", "光芯片产能国内前三，特斯拉车载光传感核心供应商", "英伟达+特斯拉双赛道共振"),
            EnrichedStock("sz300394", "天孚通信", "光器件全球龙头，CPO 高速光引擎+FAU 组件", "400G/800G 光引擎批量出货"),
            EnrichedStock("sh688195", "腾景科技", "精密光学元件+光通信滤光片，华为供应商")
        )),
        SubSector("CPO 材料（铌/钽/钨/玻璃基板）", "光芯片衬底和调制器材料，铌酸锂薄膜+玻璃基板是最重要材料", listOf(
            EnrichedStock("sz002149", "西部材料", "铌合金+铌酸锂晶体 | SpaceX 认证铌合金唯一供应商", "铌酸锂薄膜(TFLN)批量化制备", "铌酸锂薄膜需求爆发，光通信+量子计算双驱动"),
            EnrichedStock("sh600456", "宝钛股份", "钛合金+铌合金/钽材，航天+光通信双赛道 | SpaceX 间接供应商"),
            EnrichedStock("sz002182", "云海金属", "镁合金+钨钼材料，光器件精密加工材料 | 华为合作光模块精密结构件")
        ))
    )

    // ============ 半导体板块 ============
    val SEMICONDUCTOR_SECTORS = listOf(
        SubSector("芯片设计", "AI/GPU/CPU 芯片设计，国产替代+AI 算力需求驱动", listOf(
            EnrichedStock("sh603501", "韦尔股份", "全球 CIS 图像传感器第三，汽车+手机双轮驱动", "汽车 CIS 市占率 30%+"),
            EnrichedStock("sz002415", "海康威视", "AI 视觉芯片+安防龙头，自研 AI 芯片"),
            EnrichedStock("sh688981", "中芯国际", "国内最大晶圆代工厂，先进制程突破", "N+2 制程量产，月产能 80 万片", "华为麒麟芯片回归，国产替代加速"),
            EnrichedStock("sh688256", "寒武纪", "国产 AI 芯片龙头，思元 590 对标 A100"),
            EnrichedStock("sh688008", "澜起科技", "内存接口芯片全球第一，DDR5 驱动增长")
        )),
        SubSector("封装测试", "先进封装（Chiplet/3D封装）是 AI 芯片关键路径", listOf(
            EnrichedStock("sh600584", "长电科技", "全球第三大封测企业，Chiplet 技术领先", "先进封装占比 40%+", "AI 芯片需求拉动先进封装订单激增"),
            EnrichedStock("sz002156", "通富微电", "AMD 核心封测合作伙伴，AI GPU 封测受益"),
            EnrichedStock("sh688981", "中芯国际", "晶圆+封测一体化")
        )),
        SubSector("设备材料", "半导体设备国产替代率 < 20%，政策大力支持", listOf(
            EnrichedStock("sz002371", "北方华创", "国产半导体设备龙头，刻蚀/薄膜/清洗全覆盖", "2024 新签订单 300 亿+"),
            EnrichedStock("sh688012", "中微公司", "刻蚀设备龙头，5nm 先进制程进入台积电供应链"),
            EnrichedStock("sh688019", "安集科技", "CMP 抛光液国产唯一，市占率 30%+"),
            EnrichedStock("sh688126", "沪硅产业", "国产大硅片龙头，300mm 硅片量产")
        ))
    )

    // ============ AI 人工智能板块 ============
    val AI_SECTORS = listOf(
        SubSector("AI 算力服务器", "AI 训练/推理服务器需求爆发", listOf(
            EnrichedStock("sz000977", "浪潮信息", "国内最大 AI 服务器厂商，GPU 服务器市占率 50%+"),
            EnrichedStock("sh601138", "工业富联", "苹果+英伟达 AI 服务器代工龙头，英伟达 H100 主力代工厂"),
            EnrichedStock("sz000063", "中兴通讯", "5G+AI 双赛道，算力网络设备"),
            EnrichedStock("sz300502", "新易盛", "800G 光模块，AI 算力网络核心器件"),
            EnrichedStock("sh688256", "寒武纪", "国产 AI 芯片龙头")
        )),
        SubSector("AI 应用", "大模型+AI 应用落地", listOf(
            EnrichedStock("sh601360", "三六零", "360AI 大模型+安全"),
            EnrichedStock("sh600570", "恒生电子", "金融 AI 核心 IT 服务商"),
            EnrichedStock("sz002230", "科大讯飞", "语音 AI+教育 AI，星火大模型")
        ))
    )

    // ============ 有色金属板块 ============
    val METAL_SECTORS = listOf(
        SubSector("铜", "全球铜矿供应偏紧，新能源+电网投资拉动铜需求", listOf(
            EnrichedStock("sh600362", "江西铜业", "国内最大铜冶炼企业", "2024H1 铜精矿产量 10 万吨", "铜价突破 10000 美元/吨"),
            EnrichedStock("sz000630", "铜陵有色", "铜冶炼四强之一，铜箔产品受益新能源"),
            EnrichedStock("sh601899", "紫金矿业", "铜金双主业，全球铜产量前10", "2024 铜产量 100 万吨+", "铜金双涨，业绩弹性极大")
        )),
        SubSector("铝", "电解铝产能天花板 4500 万吨，新能源轻量化拉动铝材需求", listOf(
            EnrichedStock("sh601600", "中国铝业", "全球最大氧化铝生产商"),
            EnrichedStock("sh600219", "南山铝业", "航空铝材国内最大供应商，供货 C919"),
            EnrichedStock("sz000807", "云铝股份", "水电铝龙头，绿电铝溢价明显")
        )),
        SubSector("稀土", "中国掌控全球 60% 稀土产量，新能源/军工/电子核心", listOf(
            EnrichedStock("sz000831", "北方稀土", "全球最大稀土企业，轻稀土绝对龙头"),
            EnrichedStock("sz000970", "中科三环", "钕铁硼永磁材料龙头，供货特斯拉/比亚迪")
        )),
        SubSector("黄金", "全球央行持续增持黄金，金价突破历史高位", listOf(
            EnrichedStock("sh600547", "山东黄金", "国内第二大黄金企业", "矿产金 40 吨+/年", "金价持续历史高位"),
            EnrichedStock("sh601899", "紫金矿业", "国内最大黄金矿企（铜金双主业）"),
            EnrichedStock("sh600489", "中金黄金", "央企黄金平台，资源注入预期")
        )),
        SubSector("锂", "锂价触底反弹，新能源汽车+储能拉动长期需求", listOf(
            EnrichedStock("sz002460", "赣锋锂业", "全球锂业龙头，锂资源+锂化合物+固态电池"),
            EnrichedStock("sz002466", "天齐锂业", "控股全球最大锂矿 Greenbushes", "锂价触底反弹"),
            EnrichedStock("sz002240", "盛新锂能", "国内锂盐加工龙头，产能快速扩张")
        ))
    )

    // ============ 商业航天 ============
    val SPACE_SECTORS = listOf(
        SubSector("火箭发动机及材料", "商业火箭发射频率激增，高温合金/钛合金/铌合金是核心", listOf(
            EnrichedStock("sh600343", "航天动力", "液体火箭发动机唯一上市企业"),
            EnrichedStock("sh600399", "抚顺特钢", "高温合金/航天锻件核心供应商"),
            EnrichedStock("sh600456", "宝钛股份", "钛合金航天结构件，供货航天一院/四院")
        )),
        SubSector("低轨卫星制造", "星链/千帆星座等低轨卫星批量发射", listOf(
            EnrichedStock("sh600118", "中国卫星", "小卫星整星研制龙头"),
            EnrichedStock("sh600879", "航天电子", "星载电子设备+测控通信"),
            EnrichedStock("sh601698", "中国卫通", "卫星运营商")
        ))
    )

    // ============ 新能源 ============
    val ENERGY_SECTORS = listOf(
        SubSector("光伏", "光伏产业链价格触底，N型技术替代P型加速", listOf(
            EnrichedStock("sh601012", "隆基绿能", "全球最大光伏组件企业"),
            EnrichedStock("sz002459", "晶澳科技", "光伏组件全球前三，N型产能领先"),
            EnrichedStock("sh688599", "天合光能", "210mm 组件龙头")
        )),
        SubSector("储能", "新能源配储政策推动，大储+户储双增长", listOf(
            EnrichedStock("sz300750", "宁德时代", "储能电池全球第一"),
            EnrichedStock("sz300274", "阳光电源", "储能逆变器+储能系统全球龙头"),
            EnrichedStock("sz002594", "比亚迪", "储能电池+系统集成全球前三")
        ))
    )

    // ============ 电网设备 ============
    val GRID_SECTORS = listOf(
        SubSector("特高压", "特高压直流输电+柔性直流，新能源外送关键通道", listOf(
            EnrichedStock("sh601179", "中国西电", "特高压变压器/GIS 龙头，国网特高压核心供应商"),
            EnrichedStock("sh600406", "国电南瑞", "电网自动化+特高压控制保护，国网系核心子公司"),
            EnrichedStock("sh600312", "平高电气", "特高压 GIS 组合电器龙头，特高压建设直接受益"),
            EnrichedStock("sh688248", "南网科技", "南方电网科技平台，储能+智能电网"),
            EnrichedStock("sz300001", "特锐德", "箱式变电站+充电桩龙头，配网升级受益")
        )),
        SubSector("配电网", "分布式新能源接入+智能配电网改造", listOf(
            EnrichedStock("sh600517", "置信电气", "非晶合金变压器龙头，配网节能设备"),
            EnrichedStock("sz002452", "长高电新", "高压隔离开关+GIS，配网设备核心供应商"),
            EnrichedStock("sz002350", "北京科锐", "配电自动化终端+故障指示器")
        ))
    )

    // ============ 风电设备 ============
    val WIND_SECTORS = listOf(
        SubSector("海上风电", "深远海风电是未来方向，2025-2030 装机 CAGR 30%+", listOf(
            EnrichedStock("sh601615", "明阳智能", "海上风机龙头，16MW 全球最大海上风机已下线"),
            EnrichedStock("sh688660", "电气风电", "海上风机市占率国内前三，上海电气旗下"),
            EnrichedStock("sz002531", "天顺风能", "风电塔筒全球龙头，海上风电桩基核心供应商"),
            EnrichedStock("sz300185", "通裕重工", "风电主轴+铸件龙头，海上风电大型化受益")
        )),
        SubSector("风电零部件", "齿轮箱/轴承/叶片等关键零部件国产替代", listOf(
            EnrichedStock("sh601016", "节能风电", "风电运营龙头"),
            EnrichedStock("sz002202", "金风科技", "国内风电整机龙头"),
            EnrichedStock("sh603218", "日月股份", "风电铸件全球龙头，海上风电大型化核心受益")
        ))
    )

    // ============ 固态电池 ============
    val SOLID_BATTERY_SECTORS = listOf(
        SubSector("固态电池", "固态电池是下一代锂电池核心技术，2027 年量产预期", listOf(
            EnrichedStock("sz300750", "宁德时代", "凝聚态电池+硫化物固态电池，2027 年小批量量产", "全固态电池能量密度 500Wh/kg", "固态电池产业化绝对龙头"),
            EnrichedStock("sz002460", "赣锋锂业", "氧化物固态电池+固态电解质，全产业链布局"),
            EnrichedStock("sh688116", "天奈科技", "碳纳米管导电剂，固态电池导电剂核心供应商"),
            EnrichedStock("sz300438", "鹏辉能源", "固态电池+钠离子电池双赛道"),
            EnrichedStock("sz002074", "国轩高科", "大众入股，固态电池 2025 年装车验证")
        ))
    )

    // ============ 化工 / 有机硅 / 电子布 ============
    val CHEMICAL_SECTORS = listOf(
        SubSector("有机硅", "有机硅下游光伏/新能源/电子需求拉动，供给端产能出清", listOf(
            EnrichedStock("sh600596", "新安股份", "有机硅+草甘膦双龙头，有机硅单体产能 50 万吨"),
            EnrichedStock("sh603260", "合盛硅业", "有机硅+工业硅一体化龙头，成本优势显著"),
            EnrichedStock("sz002211", "宏达新材", "高温硅橡胶龙头"),
            EnrichedStock("sh688363", "华熙生物", "有机硅+玻尿酸双赛道")
        )),
        SubSector("电子布/电子纱", "PCB 用电子级玻璃纤维布和电子纱，AI 算力拉动高端电子布需求", listOf(
            EnrichedStock("sh600176", "中国巨石", "全球玻纤龙头，电子布产能全球第一", "电子布年产能 20 亿米+", "AI 服务器 PCB 拉动低介电电子布需求爆发"),
            EnrichedStock("sh600183", "生益科技", "电子布+覆铜板一体化，全球覆铜板前三"),
            EnrichedStock("sz002080", "中材科技", "玻纤+风电叶片+锂膜三主业，电子布产能快速扩张")
        )),
        SubSector("氟化工", "制冷剂+含氟聚合物，半导体/新能源需求拉动", listOf(
            EnrichedStock("sh600160", "巨化股份", "氟化工龙头，制冷剂+含氟聚合物全产业链"),
            EnrichedStock("sh600309", "万华化学", "MDI 全球龙头，聚氨酯+新材料双轮驱动"),
            EnrichedStock("sz002407", "多氟多", "六氟磷酸锂+电子级氢氟酸，半导体+新能源双驱动")
        ))
    )

    // ============ 芯片/集成电路 ============
    val CHIP_ALLIANCE_SECTORS = listOf(
        SubSector("英伟达供应链", "英伟达 GPU/算力芯片配套产业链", listOf(
            EnrichedStock("sh601138", "工业富联", "英伟达 Rubin CPO 交换机+AI 服务器核心代工"),
            EnrichedStock("sz300308", "中际旭创", "英伟达 800G 光模块核心供应商"),
            EnrichedStock("sz002916", "深南电路", "英伟达 GPU 封装基板核心供应商"),
            EnrichedStock("sz300476", "胜宏科技", "英伟达 AI 算力 PCB 核心供应商"),
            EnrichedStock("sz002475", "立讯精密", "英伟达高速互联组件供应商")
        )),
        SubSector("谷歌供应链", "Google TPU/云计算配套产业链", listOf(
            EnrichedStock("sz300308", "中际旭创", "Google 800G 光模块第一大供应商"),
            EnrichedStock("sz002475", "立讯精密", "Google 数据中心高速互联供应商"),
            EnrichedStock("sz300394", "天孚通信", "Google 光器件核心供应商")
        )),
        SubSector("国产芯片", "国产 GPU/NPU/CPU/FPGA 芯片，自主可控核心", listOf(
            EnrichedStock("sh688256", "寒武纪", "国产 AI 芯片龙头，思元 590 对标 A100"),
            EnrichedStock("sh688981", "中芯国际", "国内最大晶圆代工厂，N+2 先进制程"),
            EnrichedStock("sh688041", "海光信息", "国产 x86 CPU+DCU 协处理器，信创核心"),
            EnrichedStock("sh688008", "澜起科技", "内存接口芯片全球第一"),
            EnrichedStock("sz002371", "北方华创", "国产半导体设备龙头")
        ))
    )

    // ============ 马斯克相关供应链 ============
    val MUSK_SECTORS = listOf(
        SubSector("特斯拉供应链", "特斯拉汽车+FSD+Dojo算力硬件供应链", listOf(
            EnrichedStock("sh601138", "工业富联", "特斯拉 Dojo 算力硬件+FSD 服务器代工"),
            EnrichedStock("sz002475", "立讯精密", "特斯拉车载线束+人形机器人精密结构件一级供应商"),
            EnrichedStock("sz002384", "东山精密", "特斯拉车载精密钣金+FSD 硬件结构件核心供应商"),
            EnrichedStock("sz002938", "鹏鼎控股", "特斯拉车载 PCB+FSD 硬件电路板"),
            EnrichedStock("sz000988", "华工科技", "特斯拉车载光传感硬件供应商")
        )),
        SubSector("SpaceX 产业链", "星链/星舰 A 股核心供应商", listOf(
            EnrichedStock("sz002149", "西部材料", "SpaceX 认证铌合金唯一供应商，猛禽发动机燃烧室材料"),
            EnrichedStock("sh600456", "宝钛股份", "钛合金棒材，SpaceX 星舰/猎鹰9 同类钛材国内替代"),
            EnrichedStock("sh600399", "抚顺特钢", "高温合金，火箭发动机涡轮盘/叶片关键材料"),
            EnrichedStock("sh600118", "中国卫星", "低轨卫星整星研制，千帆/鸿鹄星座"),
            EnrichedStock("sh600879", "航天电子", "星载电子设备+卫星测控通信")
        ))
    )

    // ============ 液冷 ============
    val LIQUID_COOLING_SECTORS = listOf(
        SubSector("液冷服务器", "AI 数据中心液冷散热，替代风冷是确定性趋势", listOf(
            EnrichedStock("sh601138", "工业富联", "液冷 AI 服务器出货量全球第一，英伟达主力代工厂"),
            EnrichedStock("sz000977", "浪潮信息", "液冷服务器国内市占率第一，全液冷冷板技术业界领先"),
            EnrichedStock("sz300499", "高澜股份", "电力电子液冷龙头，服务器液冷+储能液冷双驱动"),
            EnrichedStock("sz300602", "飞荣达", "液冷板+散热模组，华为液冷服务器核心供应商"),
            EnrichedStock("sz002837", "英维克", "数据中心精密温控龙头，液冷全链条方案供应商")
        ))
    )

    // ============ 超算节点 ============
    val SUPERCOMPUTER_SECTORS = listOf(
        SubSector("超算/算力网络", "超算中心+算力网络+算力调度", listOf(
            EnrichedStock("sz000977", "浪潮信息", "国内超算服务器市占率第一"),
            EnrichedStock("sh601138", "工业富联", "超算服务器+液冷方案供应商"),
            EnrichedStock("sz000063", "中兴通讯", "算力网络设备+AI 服务器"),
            EnrichedStock("sh600850", "电科数字", "超算中心建设+算力云平台运营"),
            EnrichedStock("sh603019", "中科曙光", "国产超算龙头，曙光 6000 超算系统")
        ))
    )

    // ============ 稀有小金属（完整版） ============
    val RARE_METAL_SECTORS = listOf(
        SubSector("钨", "钨是熔点最高金属，硬质合金+军工穿甲弹+半导体靶材核心材料，中国占全球 80% 产量", listOf(
            EnrichedStock("sh600549", "厦门钨业", "全球钨业龙头，钨精矿→APT→硬质合金全产业链，稀土+锂电正极三主业", "钨精矿年产量 1.2 万吨，全球第一", "钨价突破 20 万元/吨，公司业绩弹性极大"),
            EnrichedStock("sz002378", "章源钨业", "国内钨矿资源储量前五，硬质合金+钨粉+碳化钨粉全产业链", "钨精矿年产量 4000 吨"),
            EnrichedStock("sh603993", "洛阳钼业", "全球最大钼钨矿企业之一，三道庄钼钨矿世界级", "钼钨伴生矿年产钨精矿 5000+ 吨")
        )),
        SubSector("钼", "钼是钢铁合金化元素+光电子材料+催化剂，中国产量全球第一", listOf(
            EnrichedStock("sh603993", "洛阳钼业", "全球最大钼矿企业之一，TFM 铜钴矿+三道庄钼钨矿", "钼精矿年产量 2.5 万吨，全球前三", "钼价高位运行，钼矿利润占比超 40%"),
            EnrichedStock("sh601958", "金钼股份", "亚洲最大钼矿企业，金堆城钼矿是全球最大单体钼矿", "钼精矿年产量 3.5 万吨，全球第一"),
            EnrichedStock("sz002738", "中矿资源", "钼矿+铯铷盐+锂矿多主业，海外钼矿资源丰富")
        )),
        SubSector("钽/铌", "钽电容+铌酸锂薄膜+高温合金，光通信/半导体/航天/核工业核心材料", listOf(
            EnrichedStock("sz002149", "西部材料", "铌合金+铌酸锂晶体，光通信+量子计算+SpaceX 三重驱动", "国内唯一铌酸锂薄膜(TFLN)量产企业", "铌酸锂薄膜是光通信最核心材料，需求爆发式增长"),
            EnrichedStock("sh600456", "宝钛股份", "铌合金+钽材+钛合金+锆材，航天+光通信+核工业多赛道", "国内唯一全系列铌/钽/锆材加工企业"),
            EnrichedStock("sz000969", "安泰科技", "钽电容器+铌合金靶材+非晶纳米晶，半导体+军工双驱动"),
            EnrichedStock("sh600206", "有研新材", "高纯钽靶材+铌靶材，半导体溅射靶材核心供应商", "12英寸钽靶材通过台积电认证"),
            EnrichedStock("sh688122", "西部超导", "铌钛超导线材+Nb3Sn 超导线材，全球超导材料龙头")
        )),
        SubSector("锗", "锗是红外光学+光纤通信+半导体核心材料，中国占全球 70% 产量", listOf(
            EnrichedStock("sz000519", "云南锗业", "全球锗业龙头，锗矿→锗锭→锗单晶全产业链", "锗金属年产量 50 吨，全球第一", "锗价突破 15000 元/kg，红外+光纤双需求拉动"),
            EnrichedStock("sh600497", "驰宏锌锗", "铅锌锗多金属，锗金属年产 40 吨+，全国第二", "伴生锗资源储量 2000 吨"),
            EnrichedStock("sz002428", "云南锗业", "（同上）深市代码")
        )),
        SubSector("铂族金属（铂/钯/铑/铱/锇）", "铂族金属是汽车催化剂+氢能电解水+燃料电池核心材料", listOf(
            EnrichedStock("sh600459", "贵研铂业", "国内唯一铂族金属综合利用企业，汽车催化剂+氢能双驱动", "铂族金属年回收量 10 吨+", "氢能电解水催化剂需求爆发"),
            EnrichedStock("sh601899", "紫金矿业", "铜金钼铂多金属，海外铂族资源储量丰富")
        )),
        SubSector("锡", "锡是焊料（电子+光伏）核心材料，全球锡矿供给持续偏紧", listOf(
            EnrichedStock("sz000960", "锡业股份", "全球锡业龙头，锡矿→锡锭→锡化工全产业链", "锡金属年产量 8 万吨，全球第一", "AI 服务器+光伏焊带拉动锡需求增长"),
            EnrichedStock("sz002428", "兴业矿业", "锡矿+锌矿，银漫矿业锡精矿产量全国前三")
        )),
        SubSector("锑", "锑是阻燃剂+光伏玻璃澄清剂核心材料，中国占全球 80% 产量", listOf(
            EnrichedStock("sh600103", "青山纸业", "（锑矿概念，实际主营纸业）"),
            EnrichedStock("sz002155", "湖南黄金", "锑矿+金矿+钨矿多金属，锑金属年产量 3 万吨，全球第一", "锑价突破 15 万元/吨，锑矿利润大幅增长"),
            EnrichedStock("sh600497", "驰宏锌锗", "铅锌锗锑多金属伴生")
        )),
        SubSector("铟/镓", "铟是 ITO 靶材（显示屏）核心，镓是氮化镓(GaN)半导体核心，中国占全球 90%+", listOf(
            EnrichedStock("sz000960", "锡业股份", "铟金属年产量 80 吨，全球第一，ITO 靶材全产业链"),
            EnrichedStock("sz002155", "湖南黄金", "铟金属伴生产量全国前三"),
            EnrichedStock("sh600549", "厦门钨业", "镓金属回收+砷化镓半导体衬底，国产替代核心")
        )),
        SubSector("锆", "锆是核级锆材+陶瓷+耐火材料核心，核电站建设拉动需求", listOf(
            EnrichedStock("sh600456", "宝钛股份", "国内唯一核级锆材合格供应商，AP1000/CAP1400 核电锆材", "核电用锆材年产能 2000 吨", "核电重启+核级锆材国产替代双重利好"),
            EnrichedStock("sz002167", "东方锆业", "全球锆制品龙头，锆英砂→氧氯化锆→核级海绵锆全产业链", "锆英砂年加工量 30 万吨"),
            EnrichedStock("sz002182", "云海金属", "锆合金+镁合金，氢能储运+汽车轻量化双驱动")
        )),
        SubSector("钛", "钛合金是航空航天+海洋工程+医疗器械核心材料", listOf(
            EnrichedStock("sh600456", "宝钛股份", "国内钛合金绝对龙头，航空钛材市占率 60%+，军工钛材市占率 80%+", "钛材年产量 3 万吨，国内第一", "C919 量产+军机放量+船舶钛材需求爆发"),
            EnrichedStock("sz002149", "西部材料", "钛合金板材+管材+复合材料，航空+航天+舰船多领域", "钛合金板材国内市占率 30%"),
            EnrichedStock("sh688122", "西部超导", "航空钛合金+超导线材，高端钛合金国内第二")
        )),
        SubSector("镁", "镁合金是汽车轻量化+3C电子+氢能储运核心材料，中国占全球 85% 产量", listOf(
            EnrichedStock("sz002182", "云海金属", "全球镁合金龙头，镁合金产能 25 万吨，国内第一", "镁合金产能 25 万吨/年，国内第一", "汽车轻量化+氢能储运拉动镁需求翻倍"),
            EnrichedStock("sh600549", "厦门钨业", "（主营钨，镁合金业务较小）"),
            EnrichedStock("sz002182", "云海金属", "（同上）深市代码")
        ))
    )

    // ============ 银行 ============
    val BANK_SECTORS = listOf(
        SubSector("国有大行", "高股息+低估值+资产质量改善", listOf(
            EnrichedStock("sh601398", "工商银行", "全球最大银行，A股市值最高"),
            EnrichedStock("sh601939", "建设银行", "四大行之一，住房贷款龙头"),
            EnrichedStock("sh601288", "农业银行", "农村金融+乡村振兴核心渠道")
        )),
        SubSector("股份行", "零售银行+财富管理转型", listOf(
            EnrichedStock("sh600036", "招商银行", "零售银行王者，ROE 持续最高"),
            EnrichedStock("sz000001", "平安银行", "零售转型+科技银行"),
            EnrichedStock("sh601166", "兴业银行", "绿色金融+同业业务龙头")
        ))
    )

    // ============ ETF ============
    val ETF_SECTORS = listOf(
        SubSector("A 股 ETF", "沪深300/上证50/中证500/创业板/科创板等 A 股宽基+行业 ETF", listOf(
            EnrichedStock("sh510050", "上证50ETF", "跟踪上证50指数，A股大盘蓝筹核心ETF"),
            EnrichedStock("sh510300", "沪深300ETF", "跟踪沪深300指数，A股核心资产代表"),
            EnrichedStock("sh159915", "创业板ETF", "跟踪创业板指，A股成长股核心ETF"),
            EnrichedStock("sh588000", "科创50ETF", "跟踪科创50指数，硬科技核心ETF"),
            EnrichedStock("sh510500", "中证500ETF", "跟踪中证500指数，中盘成长核心ETF")
        )),
        SubSector("美股关联 ETF", "纳斯达克/标普/道琼斯等美股相关 ETF", listOf(
            EnrichedStock("sh513100", "纳指ETF", "跟踪纳斯达克100指数，全球科技龙头ETF"),
            EnrichedStock("sh513500", "标普500ETF", "跟踪标普500指数，美股核心资产ETF"),
            EnrichedStock("sz159941", "纳指ETF(深)", "跟踪纳斯达克100指数，深市T+0交易"),
            EnrichedStock("sh513050", "中概互联网ETF", "跟踪中证海外中国互联网指数，阿里/腾讯/拼多多等"),
            EnrichedStock("sz159869", "游戏ETF", "跟踪中证动漫游戏指数，AI+游戏双驱动")
        )),
        SubSector("韩国股市关联 ETF", "韩国KOSPI/韩国半导体相关 ETF", listOf(
            EnrichedStock("sh513310", "中韩半导体ETF", "跟踪中证韩交所中韩半导体指数，三星/SK海力士+中芯国际"),
            EnrichedStock("sz159869", "游戏ETF(韩)", "含韩国游戏公司标的"),
            EnrichedStock("sh510050", "上证50ETF", "（韩国投资者可通过沪港通配置）")
        )),
        SubSector("港股关联 ETF", "恒生指数/恒生科技/港股通相关 ETF", listOf(
            EnrichedStock("sh513600", "恒生ETF", "跟踪恒生指数，港股蓝筹核心ETF"),
            EnrichedStock("sh513180", "恒生科技ETF", "跟踪恒生科技指数，腾讯/阿里/美团/小米等"),
            EnrichedStock("sh159954", "H股ETF", "跟踪恒生中国企业指数，港股中资企业"),
            EnrichedStock("sh513050", "中概互联ETF", "海外中国互联网龙头"),
            EnrichedStock("sz159605", "中概互联网ETF(深)", "深市跨境ETF，T+0交易")
        ))
    )

    // ============ 绿电 ============
    val GREEN_POWER_SECTORS = listOf(
        SubSector("水电/核电", "清洁基荷能源，绿电溢价+碳交易受益", listOf(
            EnrichedStock("sh600900", "长江电力", "全球最大水电运营商，乌白电站注入完成"),
            EnrichedStock("sh601985", "中国核电", "核电运营龙头，三代核电批量建设"),
            EnrichedStock("sh600025", "华能水电", "西南水电龙头，澜沧江全流域开发"),
            EnrichedStock("sh003816", "中国广核", "国内核电运营双寡头之一")
        )),
        SubSector("绿电运营", "风电+光伏运营，绿电+碳交易双收益", listOf(
            EnrichedStock("sh601016", "节能风电", "风电运营龙头"),
            EnrichedStock("sh600905", "三峡能源", "新能源发电龙头，风光+抽蓄全布局"),
            EnrichedStock("sz000591", "太阳能", "光伏运营龙头")
        ))
    )

    /** 全部板块名 → 子板块列表 映射 */
    val ALL_SECTORS: Map<String, List<SubSector>> = mapOf(
        // 光通信/CPO
        "CPO" to CPO_SECTORS,
        "光通信" to CPO_SECTORS,
        "光通信七大" to CPO_SECTORS,
        // 半导体
        "半导体" to SEMICONDUCTOR_SECTORS,
        "半导体四大板块" to SEMICONDUCTOR_SECTORS,
        "PCB" to SEMICONDUCTOR_SECTORS.filter { it.name.contains("封") },
        // AI/算力
        "人工智能" to AI_SECTORS,
        "AI" to AI_SECTORS,
        "算力" to AI_SECTORS + SUPERCOMPUTER_SECTORS,
        "超算节点" to SUPERCOMPUTER_SECTORS,
        "液冷" to LIQUID_COOLING_SECTORS,
        // 芯片
        "芯片" to CHIP_ALLIANCE_SECTORS,
        "国产芯片" to CHIP_ALLIANCE_SECTORS.filter { it.name == "国产芯片" },
        "集成电路" to CHIP_ALLIANCE_SECTORS,
        "英伟达供应链" to CHIP_ALLIANCE_SECTORS.filter { it.name == "英伟达供应链" },
        "谷歌供应链" to CHIP_ALLIANCE_SECTORS.filter { it.name == "谷歌供应链" },
        // 马斯克
        "马斯克" to MUSK_SECTORS,
        "马斯克相关供应链" to MUSK_SECTORS,
        "特斯拉" to MUSK_SECTORS.filter { it.name.contains("特斯拉") },
        "SpaceX" to MUSK_SECTORS.filter { it.name.contains("SpaceX") },
        // 新能源
        "新能源" to ENERGY_SECTORS + WIND_SECTORS + SOLID_BATTERY_SECTORS,
        "光伏" to ENERGY_SECTORS.filter { it.name == "光伏" },
        "储能" to ENERGY_SECTORS.filter { it.name == "储能" },
        "风电" to WIND_SECTORS,
        "风电设备" to WIND_SECTORS,
        "固态电池" to SOLID_BATTERY_SECTORS,
        "绿电" to GREEN_POWER_SECTORS,
        "电网" to GRID_SECTORS,
        "电网设备" to GRID_SECTORS,
        "商业航天" to SPACE_SECTORS,
        // 化工/材料
        "化工" to CHEMICAL_SECTORS,
        "有机硅" to CHEMICAL_SECTORS.filter { it.name == "有机硅" },
        "电子布" to CHEMICAL_SECTORS.filter { it.name.contains("电子布") },
        // 金属
        "有色金属" to METAL_SECTORS + RARE_METAL_SECTORS,
        "稀土" to METAL_SECTORS.filter { it.name == "稀土" },
        "稀有小金属" to RARE_METAL_SECTORS,
        "稀有金属" to RARE_METAL_SECTORS,
        "唯一金属之王" to RARE_METAL_SECTORS.filter { it.name.contains("铌") },
        "银行" to BANK_SECTORS,
        "ETF" to ETF_SECTORS
    )

    /** 获取某板块的子板块列表 */
    fun getSubSectors(sectorName: String): List<SubSector> {
        // 精确匹配
        ALL_SECTORS[sectorName]?.let { return it }

        // 别名映射（东方财富 API 板块名 → 内置板块 key）
        val aliasMap = mapOf(
            "玻纤" to "化工", "玻璃纤维" to "化工", "玻纤制造" to "化工",
            "玻璃" to "化工", "光伏玻璃" to "新能源", "汽车" to "新能源",
            "整车" to "新能源", "煤炭" to "有色金属", "钢铁" to "有色金属",
            "石油" to "化工", "天然气" to "化工", "航运" to "商业航天",
            "港口" to "商业航天", "航空" to "商业航天", "机场" to "商业航天",
            "船舶" to "商业航天", "纺织" to "化工", "服装" to "化工",
            "造纸" to "化工", "包装" to "化工", "橡胶" to "化工",
            "塑料" to "化工", "农药" to "化工", "化肥" to "化工",
            "建材" to "化工", "水泥" to "化工", "陶瓷" to "化工",
            "环保" to "绿电", "水务" to "绿电", "供热" to "绿电",
            "燃气" to "绿电", "食品" to "化工", "饮料" to "化工",
            "酿酒" to "白酒", "旅游" to "化工", "酒店" to "化工",
            "教育" to "AI", "传媒" to "AI", "游戏" to "AI",
            "软件" to "AI", "计算机" to "AI", "通信" to "AI",
            "电子" to "半导体", "光学" to "光通信", "仪器" to "半导体",
            "机械" to "新能源", "电气" to "电网", "电力" to "电网",
            "地产" to "银行", "保险" to "银行", "券商" to "银行",
            "建筑" to "银行", "铁路" to "银行", "公路" to "银行",
        )
        aliasMap[sectorName]?.let { base -> ALL_SECTORS[base]?.let { return it.take(5) } }
        for ((kw, base) in aliasMap) {
            if (sectorName.contains(kw, true)) {
                ALL_SECTORS[base]?.let { return it.take(5) }
            }
        }

        // 剥离合并后缀："有色金属·钼" → 只返回匹配子板块
        val parts = sectorName.split("·")
        if (parts.size >= 2) {
            val base = parts[0]; val sub = parts[1]
            val baseList = ALL_SECTORS[base]
            if (baseList != null) {
                val filtered = baseList.filter { it.name.contains(sub, true) || sub.contains(it.name, true) }
                if (filtered.isNotEmpty()) return filtered
                return baseList.take(3)
            }
        }

        // 模糊匹配
        val base = parts[0]
        for ((key, value) in ALL_SECTORS) {
            if (sectorName.contains(key, true) || key.contains(base, true)) return value.take(5)
        }

        // 跨板块子板块名搜索
        val cross = ALL_SECTORS.flatMap { (_, list) ->
            list.filter { it.name.contains(base, true) || it.stocks.any { s -> s.name.contains(base, true) || s.business.contains(base, true) } }
        }
        if (cross.isNotEmpty()) return cross.take(5)

        return emptyList()
    }
}