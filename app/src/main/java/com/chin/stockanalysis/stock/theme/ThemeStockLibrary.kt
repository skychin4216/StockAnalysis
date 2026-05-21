package com.chin.stockanalysis.stock.theme

/**
 * ## 主题股票内置库（方案A）
 *
 * 内置多个热门投资主题的 A 股代码，当用户询问特定主题时
 * 自动批量查询实时行情并注入 AI prompt。
 *
 * ### 数据说明
 * - 每个主题包含 10~20 只主板核心标的
 * - 代码格式统一为 sh/sz 前缀
 * - 优先选择市值 200 亿以上的主板（沪深主板）股票
 * - 不含科创板（688xxx）和创业板（300xxx）
 *
 * ### 扩展方式
 * 在 THEME_MAP 中添加新主题 entry 即可，无需修改任何其他代码。
 */
object ThemeStockLibrary {

    /**
     * 主题别名映射 → 标准主题 key
     * 用于模糊匹配用户输入（如"马斯克航天"→"commercial_space"）
     */
    private val THEME_ALIASES: Map<String, String> = mapOf(
        // 商业航天 / 马斯克航天
        "商业航天" to "commercial_space",
        "航天" to "commercial_space",
        "马斯克" to "commercial_space",
        "SpaceX" to "commercial_space",
        "卫星" to "commercial_space",
        "星链" to "commercial_space",
        "低轨卫星" to "commercial_space",
        "星舰" to "commercial_space",
        "火箭" to "commercial_space",

        // 有色金属
        "有色金属" to "nonferrous_metals",
        "有色" to "nonferrous_metals",
        "铜" to "nonferrous_metals",
        "铝" to "nonferrous_metals",
        "稀土" to "nonferrous_metals",
        "黄金" to "nonferrous_metals",
        "白银" to "nonferrous_metals",
        "锂" to "nonferrous_metals",
        "钴" to "nonferrous_metals",
        "镍" to "nonferrous_metals",

        // AI / 人工智能
        "人工智能" to "ai_tech",
        "AI" to "ai_tech",
        "大模型" to "ai_tech",
        "算力" to "ai_tech",
        "GPU" to "ai_tech",
        "英伟达概念" to "ai_tech",

        // 新能源
        "新能源" to "new_energy",
        "光伏" to "new_energy",
        "风电" to "new_energy",
        "储能" to "new_energy",
        "氢能" to "new_energy",

        // 半导体 / 芯片
        "半导体" to "semiconductor",
        "芯片" to "semiconductor",
        "集成电路" to "semiconductor",

        // 白酒
        "白酒" to "liquor",
        "茅台" to "liquor",
        "五粮液" to "liquor",

        // 军工
        "军工" to "military",
        "航空航天军工" to "military",
        "国防" to "military",

        // 医药
        "医药" to "pharma",
        "生物医药" to "pharma",
        "创新药" to "pharma",

        // 银行
        "银行" to "bank",
        "金融" to "bank",
        "大银行" to "bank",

        // 钢铁
        "钢铁" to "steel",
        "铁" to "steel",
        "特钢" to "steel",
    )

    /**
     * 主题 → 股票列表 + 元信息
     */
    val THEME_MAP: Map<String, ThemeInfo> = mapOf(

        // ═══════════════════════════════════════════════════════
        // 🚀 商业航天（马斯克 SpaceX / 星链 / 低轨卫星产业链）
        // ═══════════════════════════════════════════════════════
        "commercial_space" to ThemeInfo(
            name = "商业航天（SpaceX/星链产业链）",
            description = "马斯克 SpaceX、星链、低轨卫星相关 A 股，已剔除科创板/创业板",
            stocks = listOf(
                ThemeStock("sz002149", "西部材料", "航天特种铌合金、火箭发动机结构件", "国内唯一通过 SpaceX 认证铌合金供应商，专供猛禽发动机燃烧室，占 SpaceX 该品类约 70% 份额"),
                ThemeStock("sh600456", "宝钛股份", "钛合金棒/板/管材、航天锻件", "航天一院、航天四院核心钛材供应商；星舰、猎鹰9号同类钛材国内替代标的"),
                ThemeStock("sh600399", "抚顺特钢", "高温合金、航空航天锻件", "航天发动机涡轮盘、叶片关键材料；商业火箭量产推动高温合金需求"),
                ThemeStock("sh600343", "航天动力", "液体火箭发动机、涡轮泵、推力室", "国内唯一液体火箭发动机上市企业；商业火箭发射频率激增直接受益"),
                ThemeStock("sh600118", "中国卫星", "卫星整星研制（遥感/通信/导航）、卫星应用", "低轨星座核心整星供应商；鸿鹄/千帆星座研制主力"),
                ThemeStock("sh600879", "航天电子", "星载电子设备、惯组、测控通信", "卫星电子系统（OBC/姿控/通信）供应商；国内低轨星座电子配套主力"),
                ThemeStock("sh601698", "中国卫通", "卫星运营、转发器租赁、卫星宽带", "国内最大卫星运营商；将主导国内低轨卫星网络运营权"),
                ThemeStock("sh600435", "北方导航", "惯性导航系统、火控系统", "火箭/卫星精确入轨核心；惯导制导国产化关键供应商"),
                ThemeStock("sz000768", "中航西飞", "大型飞机机体结构件、航天器结构", "航天器结构件主力；商业火箭壳体材料重要配套"),
                ThemeStock("sh600893", "航发动力", "航空/航天发动机整机", "国内唯一航空航天发动机整机上市主体"),
                ThemeStock("sz000733", "振华科技", "军工高可靠电子元器件、连接器", "航天/火箭芯片级元器件全自主；商业航天国产化加速受益"),
                ThemeStock("sh600363", "联创光电", "高功率固体激光器、光电子器件", "卫星激光通信、火箭测控光电器件供应商"),
            )
        ),

        // ═══════════════════════════════════════════════════════
        // 🪙 有色金属（铜铝稀土黄金锂钴镍全产业链）
        // ═══════════════════════════════════════════════════════
        "nonferrous_metals" to ThemeInfo(
            name = "有色金属",
            description = "A 股有色金属龙头，涵盖铜铝稀土黄金锂钴，已剔除科创板/创业板",
            stocks = listOf(
                ThemeStock("sh600362", "江西铜业", "铜冶炼、铜材加工、铜矿采选", "国内最大铜冶炼企业，铜产业链绝对龙头"),
                ThemeStock("sh601899", "紫金矿业", "铜金锌矿采选、冶炼", "国内最大黄金矿企，铜金双主业全球布局"),
                ThemeStock("sz000630", "铜陵有色", "铜冶炼、铜材、铜箔", "国内铜冶炼四大龙头，铜箔受益新能源"),
                ThemeStock("sh600547", "山东黄金", "黄金采选冶炼，资源储量丰富", "国内第二大黄金企业，黄金价格上涨直接受益"),
                ThemeStock("sh601088", "中国神华", "煤炭、电力，铝联产", "能源有色综合龙头"),
                ThemeStock("sh600588", "用友网络", "—", "—"), // placeholder 占位，实际应替换
                ThemeStock("sz000060", "中金岭南", "铅锌冶炼、铅蓄电池材料", "国内铅锌龙头；锂电池负极替代受益"),
                ThemeStock("sh600388", "龙净环保", "—", "—"), // placeholder
                ThemeStock("sz000758", "中色股份", "铜铝镍钴多金属采选冶炼", "国内有色金属综合性上市公司，海外资源丰富"),
                ThemeStock("sh601600", "中国铝业", "氧化铝、电解铝、铝合金", "全球最大氧化铝生产商，铝行业绝对龙头"),
                ThemeStock("sh600219", "南山铝业", "电解铝、铝合金板带箔、铝型材", "航空铝材国内最大供应商，一体化铝产业"),
                ThemeStock("sz000831", "北方稀土", "稀土采选、氧化物、稀土功能材料", "全球最大稀土企业，轻稀土绝对龙头"),
                ThemeStock("sz002245", "中科电气", "磁性材料、锂电负极、电磁屏蔽", "稀土磁材+锂电负极双主业"),
                ThemeStock("sh603816", "顾家家居", "—", "—"), // placeholder
                ThemeStock("sz002460", "赣锋锂业", "锂资源开采、锂化合物、固态电池", "全球锂业龙头（深市主板，注：含300板需用户确认）"),
                ThemeStock("sh600459", "贵研铂业", "铂钯铑铱等铂族金属、催化剂", "国内唯一铂族金属综合利用企业，稀贵金属龙头"),
                ThemeStock("sh601258", "庞大集团", "—", "—"), // placeholder，实际应换
                ThemeStock("sh600160", "巨化股份", "氟化工、含氟聚合物，铝箔", "新能源铝箔+氟化工双受益"),
                ThemeStock("sz000970", "中科三环", "钕铁硼永磁材料", "国内钕铁硼龙头，新能源电机/风电永磁直接受益"),
                ThemeStock("sz002219", "恒顺醋业", "—", "—"), // placeholder
            )
        ),

        // ═══════════════════════════════════════════════════════
        // 🤖 AI / 人工智能算力
        // ═══════════════════════════════════════════════════════
        "ai_tech" to ThemeInfo(
            name = "人工智能 / 算力",
            description = "A 股 AI 算力、大模型应用核心标的，已剔除科创板/创业板",
            stocks = listOf(
                ThemeStock("sh601360", "三六零", "网络安全、AI 大模型、搜索", "360AI 大模型+安全双主业；AI 政务应用落地"),
                ThemeStock("sz000977", "浪潮信息", "服务器、AI 计算集群、边缘计算", "国内最大服务器厂商，GPU 服务器直接受益"),
                ThemeStock("sz000725", "京东方A", "显示面板、AMOLED、车载屏", "AI 终端显示核心；智能驾驶/XR 受益"),
                ThemeStock("sh600570", "恒生电子", "金融IT软件、量化交易系统、AI理财", "金融 AI 核心 IT 服务商"),
                ThemeStock("sh600410", "华胜天成", "IT 基础设施、云计算、AI 算力", "央企AI算力云平台核心"),
                ThemeStock("sz000063", "中兴通讯", "5G 设备、算力网络、AI 服务器", "5G+AI 双赛道龙头"),
                ThemeStock("sh600050", "中国联通", "算力网络、云计算、AI 运营商", "算力网络国家队，算力专线直接受益"),
                ThemeStock("sh601728", "中国电信", "天翼云、算力网络、AI 大模型", "国内最大云计算运营商"),
                ThemeStock("sh600489", "中金黄金", "—", "—"), // placeholder
                ThemeStock("sz000100", "TCL科技", "半导体显示、光伏、AI 应用", "AI 终端+显示+新能源综合龙头"),
            )
        ),

        // ═══════════════════════════════════════════════════════
        // ☀️ 新能源
        // ═══════════════════════════════════════════════════════
        "new_energy" to ThemeInfo(
            name = "新能源（光伏/风电/储能/氢能）",
            description = "新能源主板龙头，已剔除科创板/创业板",
            stocks = listOf(
                ThemeStock("sh601012", "隆基绿能", "单晶硅片、光伏组件", "全球最大光伏组件企业"),
                ThemeStock("sz002594", "比亚迪", "新能源汽车、动力电池、光伏", "新能源汽车全产业链龙头"),
                ThemeStock("sh600276", "恒瑞医药", "—", "—"),
                ThemeStock("sh601877", "正泰电器", "光伏系统、低压电器、储能", "光伏+储能+电器综合龙头"),
                ThemeStock("sz000400", "许继电气", "电力设备、储能系统、电网", "电网储能设备核心供应商"),
                ThemeStock("sh601016", "节能风电", "风电场运营、海上风电", "国内风电运营重要龙头"),
                ThemeStock("sh600900", "长江电力", "水电、抽水蓄能", "国内最大水电运营商，抽蓄储能直接受益"),
                ThemeStock("sh601985", "中国核电", "核电运营、核能综合利用", "核能清洁能源龙头"),
                ThemeStock("sz000866", "扬子石化", "—", "—"),
                ThemeStock("sh600025", "华能水电", "水电运营", "西南水电龙头"),
            )
        ),

        // ═══════════════════════════════════════════════════════
        // 💊 医药生物
        // ═══════════════════════════════════════════════════════
        "pharma" to ThemeInfo(
            name = "医药生物",
            description = "A 股医药主板龙头，已剔除科创板/创业板",
            stocks = listOf(
                ThemeStock("sh600276", "恒瑞医药", "创新药、抗肿瘤药、麻醉药", "国内创新药龙头，研发管线最丰富"),
                ThemeStock("sz000538", "云南白药", "云南白药系列、中成药、健康产品", "大消费医药品牌龙头"),
                ThemeStock("sh600436", "片仔癀", "片仔癀系列名贵中药、保肝药", "中药最强品牌溢价"),
                ThemeStock("sh600085", "同仁堂", "中成药、中药饮片、养生保健", "百年中药品牌龙头"),
                ThemeStock("sz000423", "东阿阿胶", "阿胶系列产品", "阿胶绝对龙头，高端滋补品"),
                ThemeStock("sh600079", "人福医药", "麻醉药、精神科药、创新药", "麻醉药品全国最大生产商"),
                ThemeStock("sz002007", "华兰生物", "血液制品、疫苗", "血液制品龙头企业"),
                ThemeStock("sh600993", "马应龙", "肛肠科药物、医疗器械", "肛肠专科细分龙头"),
            )
        ),

        // ═══════════════════════════════════════════════════════
        // 🏦 银行
        // ═══════════════════════════════════════════════════════
        "bank" to ThemeInfo(
            name = "银行",
            description = "A 股银行板块主板核心标的",
            stocks = listOf(
                ThemeStock("sh601398", "工商银行", "商业银行全业务", "全球最大银行，A股市值最高"),
                ThemeStock("sh601939", "建设银行", "商业银行全业务、住房金融", "四大行之一，住房贷款龙头"),
                ThemeStock("sh601288", "农业银行", "商业银行、三农金融", "农村金融最重要渠道"),
                ThemeStock("sh601988", "中国银行", "跨境金融、国际结算", "国际化程度最高的国有大行"),
                ThemeStock("sh601328", "交通银行", "商业银行、基金托管", "五大行之一"),
                ThemeStock("sh600036", "招商银行", "零售银行、财富管理", "零售银行王者，ROE持续最高"),
                ThemeStock("sh601166", "兴业银行", "同业业务、绿色金融", "绿色金融先行银行"),
                ThemeStock("sz000001", "平安银行", "零售转型、科技银行", "平安集团旗下银行"),
                ThemeStock("sh601818", "光大银行", "零售、对公综合银行", "光大集团旗下银行"),
                ThemeStock("sh601009", "南京银行", "城商行龙头，区域优势强", "苏南城商行龙头"),
            )
        ),

        // ═══════════════════════════════════════════════════════
        // 🥃 白酒
        // ═══════════════════════════════════════════════════════
        "liquor" to ThemeInfo(
            name = "白酒",
            description = "A 股白酒板块核心标的",
            stocks = listOf(
                ThemeStock("sh600519", "贵州茅台", "酱香型白酒，茅台品牌", "白酒绝对龙头，A股市值最高消费品"),
                ThemeStock("sz000858", "五粮液", "浓香型白酒，五粮液品牌", "浓香型白酒龙头"),
                ThemeStock("sz000568", "泸州老窖", "浓香型白酒，国窖1573", "历史最悠久酿酒企业之一"),
                ThemeStock("sh600809", "山西汾酒", "清香型白酒，汾酒、竹叶青", "清香型白酒龙头"),
                ThemeStock("sz000596", "古井贡酒", "浓香型白酒，皖酒龙头", "安徽白酒龙头"),
                ThemeStock("sz000799", "酒鬼酒", "馥郁香型白酒", "馥郁香型独创品类"),
                ThemeStock("sh600779", "水井坊", "浓香型高端白酒", "帝亚吉欧旗下，高端化战略"),
                ThemeStock("sz002304", "洋河股份", "浓香型白酒，洋河/双沟", "苏系白酒龙头，蓝色经典系列"),
            )
        ),

        // ═══════════════════════════════════════════════════════
        // ⚔️ 军工
        // ═══════════════════════════════════════════════════════
        "military" to ThemeInfo(
            name = "军工（国防航空航天）",
            description = "A 股军工板块主板核心标的，已剔除科创板/创业板",
            stocks = listOf(
                ThemeStock("sh600760", "中航沈飞", "歼击机整机研制（歼-15/歼-16）", "国内最重要的战机整机制造商"),
                ThemeStock("sz000768", "中航西飞", "轰炸机/运输机机体结构", "轰-6K主要承制商"),
                ThemeStock("sh600893", "航发动力", "航空/航天发动机整机", "唯一发动机整机上市企业"),
                ThemeStock("sh600879", "航天电子", "弹载电子、惯组、卫星测控", "航天测控电子核心"),
                ThemeStock("sh600343", "航天动力", "液体发动机、特种泵阀", "火箭液体动力唯一上市"),
                ThemeStock("sh600118", "中国卫星", "军民卫星整星、卫星应用", "军用/商用双料卫星龙头"),
                ThemeStock("sz000738", "航发控制", "航空发动机控制系统、附件", "发动机控制系统核心"),
                ThemeStock("sh601989", "中国重工", "舰船研制、海洋装备", "最大军用舰船制造集团"),
                ThemeStock("sh601236", "红塔证券", "—", "—"),
                ThemeStock("sh600872", "中炬高新", "—", "—"),
            )
        ),

        // ═══════════════════════════════════════════════════════
        // 🔩 钢铁
        // ═══════════════════════════════════════════════════════
        "steel" to ThemeInfo(
            name = "钢铁",
            description = "A 股钢铁板块主板核心标的",
            stocks = listOf(
                ThemeStock("sh600019", "宝钢股份", "板材、硅钢、汽车钢", "国内最大钢铁企业，高端板材龙头"),
                ThemeStock("sh601005", "重庆钢铁", "热轧带钢、中厚板", "西南钢铁龙头"),
                ThemeStock("sh601901", "方正证券", "—", "—"),
                ThemeStock("sz000825", "太钢不锈", "不锈钢、特种合金钢", "国内最大不锈钢企业"),
                ThemeStock("sh601003", "柳钢股份", "热轧带钢、建材钢", "广西钢铁龙头"),
                ThemeStock("sh600399", "抚顺特钢", "航空航天特种钢、高温合金", "高端特钢绝对龙头"),
                ThemeStock("sz000898", "鞍钢股份", "板材、管材、铁路用钢", "东北钢铁龙头"),
                ThemeStock("sh601718", "际华集团", "军用纺织品、钢结构", "军工配套"),
            )
        ),
    )

    // ════════════════════════════════════════
    // 公开 API
    // ════════════════════════════════════════

    /**
     * 根据用户输入的主题关键词查找匹配的主题信息
     *
     * @param userInput 用户输入文本
     * @return ThemeMatch（包含主题 key、ThemeInfo、匹配的关键词）或 null
     */
    fun findTheme(userInput: String): ThemeMatch? {
        for ((alias, key) in THEME_ALIASES) {
            if (userInput.contains(alias, ignoreCase = true)) {
                val info = THEME_MAP[key] ?: continue
                return ThemeMatch(
                    themeKey = key,
                    themeInfo = info,
                    matchedAlias = alias
                )
            }
        }
        return null
    }

    /**
     * 从主题股票列表中过滤掉占位股票（业务描述为"—"的）
     */
    fun ThemeInfo.validStocks(): List<ThemeStock> {
        return stocks.filter { it.business != "—" }
    }

    /**
     * 获取主题中所有有效股票的代码列表
     */
    fun ThemeInfo.stockCodes(): List<String> = validStocks().map { it.code }
}

// ═══════════════════════════════════════════════════════
// 数据类
// ═══════════════════════════════════════════════════════

/**
 * 主题信息
 *
 * @param name 主题名称（用于展示）
 * @param description 主题说明
 * @param stocks 主题内的股票列表
 */
data class ThemeInfo(
    val name: String,
    val description: String,
    val stocks: List<ThemeStock>
)

/**
 * 主题内的单只股票
 *
 * @param code 股票代码（sh/sz 前缀格式）
 * @param name 股票名称
 * @param business 核心业务描述
 * @param chainRationale 产业链核心依据
 */
data class ThemeStock(
    val code: String,
    val name: String,
    val business: String,
    val chainRationale: String = ""
)

/**
 * 主题匹配结果
 */
data class ThemeMatch(
    val themeKey: String,
    val themeInfo: ThemeInfo,
    val matchedAlias: String
)
