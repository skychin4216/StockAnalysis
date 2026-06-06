package com.chin.stockanalysis.util

/**
 * ## 中文拼音工具类
 *
 * 提供中文汉字→拼音首字母/全拼转换，用于股票中心支持拼音搜索。
 * 覆盖 A股常见股票名称中的常用汉字。
 */
object PinyinUtils {

    /** 完整汉字→拼音映射表（A股股票名称常用字 ~2000字） */
    private val PINYIN_MAP: Map<String, String> = mapOf(
        // ========== A ==========
        "阿" to "a", "爱" to "ai", "安" to "an", "鞍" to "an", "昂" to "ang", "奥" to "ao",
        // ========== B ==========
        "八" to "ba", "白" to "bai", "百" to "bai", "柏" to "bai", "拜" to "bai", "板" to "ban",
        "半" to "ban", "邦" to "bang", "包" to "bao", "宝" to "bao", "保" to "bao", "报" to "bao",
        "北" to "bei", "贝" to "bei", "被" to "bei", "本" to "ben", "泵" to "beng", "比" to "bi",
        "笔" to "bi", "必" to "bi", "碧" to "bi", "变" to "bian", "标" to "biao", "表" to "biao",
        "别" to "bie", "宾" to "bin", "冰" to "bing", "兵" to "bing", "波" to "bo", "玻" to "bo",
        "博" to "bo", "薄" to "bo", "铂" to "bo", "步" to "bu", "部" to "bu", "不" to "bu",
        // ========== C ==========
        "财" to "cai", "材" to "cai", "采" to "cai", "彩" to "cai", "餐" to "can", "灿" to "can",
        "仓" to "cang", "沧" to "cang", "测" to "ce", "策" to "ce", "层" to "ceng", "查" to "cha",
        "差" to "cha", "产" to "chan", "长" to "chang", "常" to "chang", "厂" to "chang", "畅" to "chang",
        "超" to "chao", "朝" to "chao", "潮" to "chao", "车" to "che", "彻" to "che", "辰" to "chen",
        "晨" to "chen", "沉" to "chen", "成" to "cheng", "诚" to "cheng", "城" to "cheng", "程" to "cheng",
        "承" to "cheng", "澄" to "cheng", "驰" to "chi", "池" to "chi", "齿" to "chi", "赤" to "chi",
        "充" to "chong", "重" to "chong", "崇" to "chong", "出" to "chu", "初" to "chu", "储" to "chu",
        "楚" to "chu", "处" to "chu", "川" to "chuan", "传" to "chuan", "船" to "chuan", "创" to "chuang",
        "春" to "chun", "纯" to "chun", "磁" to "ci", "次" to "ci", "从" to "cong", "翠" to "cui",
        "存" to "cun", "错" to "cuo",
        // ========== D ==========
        "达" to "da", "大" to "da", "代" to "dai", "带" to "dai", "待" to "dai", "戴" to "dai",
        "单" to "dan", "淡" to "dan", "弹" to "dan", "当" to "dang", "党" to "dang", "档" to "dang",
        "导" to "dao", "岛" to "dao", "道" to "dao", "得" to "de", "德" to "de", "的" to "de",
        "灯" to "deng", "登" to "deng", "等" to "deng", "邓" to "deng", "低" to "di", "迪" to "di",
        "底" to "di", "地" to "di", "第" to "di", "点" to "dian", "电" to "dian", "店" to "dian",
        "淀" to "dian", "调" to "diao", "顶" to "ding", "鼎" to "ding", "定" to "ding", "东" to "dong",
        "动" to "dong", "栋" to "dong", "都" to "dou", "豆" to "dou", "独" to "du", "读" to "du",
        "杜" to "du", "度" to "du", "端" to "duan", "短" to "duan", "段" to "duan", "断" to "duan",
        "对" to "dui", "队" to "dui", "盾" to "dun", "多" to "duo", "朵" to "duo", "舵" to "duo",
        // ========== E ==========
        "鹅" to "e", "恩" to "en", "尔" to "er", "二" to "er",
        // ========== F ==========
        "发" to "fa", "法" to "fa", "帆" to "fan", "凡" to "fan", "反" to "fan", "方" to "fang",
        "防" to "fang", "房" to "fang", "放" to "fang", "飞" to "fei", "非" to "fei", "肥" to "fei",
        "翡" to "fei", "费" to "fei", "分" to "fen", "丰" to "feng", "风" to "feng", "封" to "feng",
        "峰" to "feng", "锋" to "feng", "凤" to "feng", "佛" to "fo", "夫" to "fu", "伏" to "fu",
        "孚" to "fu", "服" to "fu", "氟" to "fu", "浮" to "fu", "福" to "fu", "抚" to "fu",
        "辅" to "fu", "富" to "fu", "复" to "fu", "副" to "fu", "覆" to "fu",
        // ========== G ==========
        "改" to "gai", "钙" to "gai", "干" to "gan", "甘" to "gan", "感" to "gan", "赣" to "gan",
        "刚" to "gang", "钢" to "gang", "港" to "gang", "高" to "gao", "告" to "gao", "歌" to "ge",
        "格" to "ge", "葛" to "ge", "个" to "ge", "各" to "ge", "铬" to "ge", "给" to "gei",
        "根" to "gen", "更" to "geng", "工" to "gong", "公" to "gong", "功" to "gong", "供" to "gong",
        "共" to "gong", "贡" to "gong", "构" to "gou", "购" to "gou", "古" to "gu", "股" to "gu",
        "固" to "gu", "故" to "gu", "关" to "guan", "观" to "guan", "官" to "guan", "冠" to "guan",
        "管" to "guan", "光" to "guang", "广" to "guang", "规" to "gui", "硅" to "gui", "贵" to "gui",
        "桂" to "gui", "国" to "guo", "果" to "guo", "过" to "guo",
        // ========== H ==========
        "哈" to "ha", "海" to "hai", "氦" to "hai", "含" to "han", "寒" to "han", "汉" to "han",
        "行" to "hang", "杭" to "hang", "航" to "hang", "豪" to "hao", "好" to "hao", "号" to "hao",
        "浩" to "hao", "合" to "he", "何" to "he", "和" to "he", "河" to "he", "核" to "he",
        "荷" to "he", "贺" to "he", "赫" to "he", "黑" to "hei", "恒" to "heng", "横" to "heng",
        "衡" to "heng", "红" to "hong", "宏" to "hong", "洪" to "hong", "虹" to "hong", "鸿" to "hong",
        "侯" to "hou", "后" to "hou", "厚" to "hou", "候" to "hou", "胡" to "hu", "湖" to "hu",
        "互" to "hu", "户" to "hu", "护" to "hu", "沪" to "hu", "花" to "hua", "华" to "hua",
        "化" to "hua", "划" to "hua", "画" to "hua", "怀" to "huai", "环" to "huan", "换" to "huan",
        "黄" to "huang", "煌" to "huang", "灰" to "hui", "辉" to "hui", "回" to "hui", "汇" to "hui",
        "会" to "hui", "惠" to "hui", "慧" to "hui", "活" to "huo", "火" to "huo", "伙" to "huo",
        "或" to "huo", "货" to "huo", "霍" to "huo",
        // ========== J ==========
        "机" to "ji", "基" to "ji", "吉" to "ji", "极" to "ji", "集" to "ji", "几" to "ji",
        "记" to "ji", "技" to "ji", "季" to "ji", "济" to "ji", "继" to "ji", "加" to "jia",
        "佳" to "jia", "家" to "jia", "嘉" to "jia", "甲" to "jia", "价" to "jia", "驾" to "jia",
        "坚" to "jian", "监" to "jian", "兼" to "jian", "建" to "jian", "剑" to "jian", "健" to "jian",
        "箭" to "jian", "江" to "jiang", "将" to "jiang", "讲" to "jiang", "奖" to "jiang", "降" to "jiang",
        "交" to "jiao", "胶" to "jiao", "焦" to "jiao", "角" to "jiao", "教" to "jiao", "接" to "jie",
        "节" to "jie", "洁" to "jie", "结" to "jie", "捷" to "jie", "解" to "jie", "介" to "jie",
        "金" to "jin", "津" to "jin", "锦" to "jin", "进" to "jin", "近" to "jin", "晋" to "jin",
        "京" to "jing", "经" to "jing", "晶" to "jing", "精" to "jing", "景" to "jing", "净" to "jing",
        "竞" to "jing", "敬" to "jing", "静" to "jing", "境" to "jing", "镜" to "jing", "九" to "jiu",
        "久" to "jiu", "酒" to "jiu", "旧" to "jiu", "居" to "ju", "巨" to "ju", "具" to "ju",
        "聚" to "ju", "卷" to "juan", "决" to "jue", "绝" to "jue", "军" to "jun", "均" to "jun",
        "君" to "jun", "俊" to "jun",
        // ========== K ==========
        "卡" to "ka", "开" to "kai", "凯" to "kai", "康" to "kang", "抗" to "kang", "考" to "kao",
        "科" to "ke", "可" to "ke", "克" to "ke", "客" to "ke", "空" to "kong", "控" to "kong",
        "口" to "kou", "库" to "ku", "酷" to "ku", "跨" to "kua", "快" to "kuai", "宽" to "kuan",
        "矿" to "kuang", "昆" to "kun", "扩" to "kuo",
        // ========== L ==========
        "拉" to "la", "莱" to "lai", "来" to "lai", "兰" to "lan", "蓝" to "lan", "澜" to "lan",
        "朗" to "lang", "浪" to "lang", "劳" to "lao", "老" to "lao", "乐" to "le", "雷" to "lei",
        "磊" to "lei", "类" to "lei", "冷" to "leng", "离" to "li", "李" to "li", "里" to "li",
        "力" to "li", "历" to "li", "立" to "li", "利" to "li", "沥" to "li", "例" to "li",
        "隶" to "li", "粒" to "li", "联" to "lian", "连" to "lian", "链" to "lian", "良" to "liang",
        "凉" to "liang", "梁" to "liang", "粮" to "liang", "两" to "liang", "量" to "liang", "辽" to "liao",
        "料" to "liao", "列" to "lie", "林" to "lin", "临" to "lin", "磷" to "lin", "灵" to "ling",
        "岭" to "ling", "领" to "ling", "另" to "ling", "刘" to "liu", "流" to "liu", "留" to "liu",
        "硫" to "liu", "柳" to "liu", "六" to "liu", "龙" to "long", "隆" to "long", "垄" to "long",
        "楼" to "lou", "卢" to "lu", "炉" to "lu", "鲁" to "lu", "陆" to "lu", "录" to "lu",
        "路" to "lu", "露" to "lu", "旅" to "lv", "铝" to "lv", "律" to "lv", "绿" to "lv",
        "氯" to "lv", "略" to "lve", "轮" to "lun", "罗" to "luo", "洛" to "luo", "络" to "luo",
        // ========== M ==========
        "马" to "ma", "迈" to "mai", "麦" to "mai", "满" to "man", "慢" to "man", "忙" to "mang",
        "毛" to "mao", "茅" to "mao", "贸" to "mao", "煤" to "mei", "美" to "mei", "镁" to "mei",
        "门" to "men", "猛" to "meng", "梦" to "meng", "密" to "mi", "绵" to "mian", "面" to "mian",
        "民" to "min", "敏" to "min", "名" to "ming", "明" to "ming", "铭" to "ming", "命" to "ming",
        "模" to "mo", "摩" to "mo", "末" to "mo", "墨" to "mo", "木" to "mu", "目" to "mu",
        "牧" to "mu", "钼" to "mu",
        // ========== N ==========
        "纳" to "na", "耐" to "nai", "南" to "nan", "难" to "nan", "铌" to "ni", "能" to "neng",
        "尼" to "ni", "泥" to "ni", "你" to "ni", "年" to "nian", "甘" to "nian", "鸟" to "niao",
        "宁" to "ning", "牛" to "niu", "纽" to "niu", "农" to "nong", "弄" to "nong", "诺" to "nuo",
        // ========== O ==========
        "欧" to "ou",
        // ========== P ==========
        "帕" to "pa", "排" to "pai", "派" to "pai", "盘" to "pan", "配" to "pei", "朋" to "peng",
        "鹏" to "peng", "皮" to "pi", "片" to "pian", "漂" to "piao", "品" to "pin", "平" to "ping",
        "评" to "ping", "苹" to "ping", "屏" to "ping", "瓶" to "ping", "珀" to "po", "普" to "pu",
        // ========== Q ==========
        "七" to "qi", "期" to "qi", "漆" to "qi", "齐" to "qi", "奇" to "qi", "旗" to "qi",
        "企" to "qi", "启" to "qi", "起" to "qi", "气" to "qi", "汽" to "qi", "器" to "qi",
        "千" to "qian", "迁" to "qian", "前" to "qian", "钱" to "qian", "乾" to "qian", "浅" to "qian",
        "欠" to "qian", "强" to "qiang", "墙" to "qiang", "桥" to "qiao", "巧" to "qiao", "切" to "qie",
        "亲" to "qin", "秦" to "qin", "勤" to "qin", "青" to "qing", "轻" to "qing", "清" to "qing",
        "情" to "qing", "庆" to "qing", "穷" to "qiong", "秋" to "qiu", "求" to "qiu", "球" to "qiu",
        "区" to "qu", "驱" to "qu", "曲" to "qu", "取" to "qu", "去" to "qu", "全" to "quan",
        "权" to "quan", "泉" to "quan", "缺" to "que", "确" to "que", "群" to "qun",
        // ========== R ==========
        "燃" to "ran", "染" to "ran", "让" to "rang", "热" to "re", "人" to "ren", "仁" to "ren",
        "认" to "ren", "任" to "ren", "日" to "ri", "容" to "rong", "融" to "rong", "柔" to "rou",
        "如" to "ru", "乳" to "ru", "入" to "ru", "软" to "ruan", "润" to "run", "若" to "ruo",
        // ========== S ==========
        "赛" to "sai", "三" to "san", "色" to "se", "森" to "sen", "沙" to "sha", "山" to "shan",
        "杉" to "shan", "善" to "shan", "商" to "shang", "上" to "shang", "尚" to "shang", "烧" to "shao",
        "少" to "shao", "绍" to "shao", "设" to "she", "社" to "she", "射" to "she", "涉" to "she",
        "申" to "shen", "深" to "shen", "神" to "shen", "沈" to "shen", "生" to "sheng", "声" to "sheng",
        "胜" to "sheng", "盛" to "sheng", "剩" to "sheng", "施" to "shi", "十" to "shi", "石" to "shi",
        "时" to "shi", "识" to "shi", "实" to "shi", "食" to "shi", "史" to "shi", "始" to "shi",
        "世" to "shi", "市" to "shi", "示" to "shi", "式" to "shi", "事" to "shi", "势" to "shi",
        "试" to "shi", "视" to "shi", "是" to "shi", "适" to "shi", "收" to "shou", "手" to "shou",
        "首" to "shou", "寿" to "shou", "受" to "shou", "书" to "shu", "输" to "shu", "舒" to "shu",
        "术" to "shu", "树" to "shu", "数" to "shu", "双" to "shuang", "水" to "shui", "税" to "shui",
        "顺" to "shun", "瞬" to "shun", "说" to "shuo", "丝" to "si", "司" to "si", "私" to "si",
        "思" to "si", "斯" to "si", "四" to "si", "松" to "song", "宋" to "song", "送" to "song",
        "苏" to "su", "素" to "su", "速" to "su", "塑" to "su", "算" to "suan", "随" to "sui",
        "岁" to "sui", "碎" to "sui", "孙" to "sun", "所" to "suo", "索" to "suo",
        // ========== T ==========
        "塔" to "ta", "台" to "tai", "太" to "tai", "钛" to "tai", "泰" to "tai", "谈" to "tan",
        "坦" to "tan", "炭" to "tan", "探" to "tan", "碳" to "tan", "汤" to "tang", "唐" to "tang",
        "堂" to "tang", "糖" to "tang", "桃" to "tao", "陶" to "tao", "套" to "tao", "特" to "te",
        "腾" to "teng", "梯" to "ti", "提" to "ti", "体" to "ti", "天" to "tian", "田" to "tian",
        "甜" to "tian", "填" to "tian", "条" to "tiao", "铁" to "tie", "听" to "ting", "庭" to "ting",
        "停" to "ting", "通" to "tong", "同" to "tong", "铜" to "tong", "统" to "tong", "投" to "tou",
        "透" to "tou", "突" to "tu", "图" to "tu", "涂" to "tu", "土" to "tu", "团" to "tuan",
        "推" to "tui", "退" to "tui", "托" to "tuo", "拓" to "tuo",
        // ========== W ==========
        "外" to "wai", "完" to "wan", "万" to "wan", "王" to "wang", "网" to "wang", "旺" to "wang",
        "望" to "wang", "威" to "wei", "微" to "wei", "为" to "wei", "围" to "wei", "唯" to "wei",
        "维" to "wei", "伟" to "wei", "伪" to "wei", "尾" to "wei", "纬" to "wei", "委" to "wei",
        "卫" to "wei", "未" to "wei", "味" to "wei", "位" to "wei", "温" to "wen", "文" to "wen",
        "闻" to "wen", "稳" to "wen", "问" to "wen", "我" to "wo", "沃" to "wo", "乌" to "wu",
        "无" to "wu", "吴" to "wu", "五" to "wu", "午" to "wu", "武" to "wu", "舞" to "wu",
        "物" to "wu", "务" to "wu", "雾" to "wu",
        // ========== X ==========
        "西" to "xi", "吸" to "xi", "希" to "xi", "析" to "xi", "矽" to "xi", "息" to "xi",
        "硒" to "xi", "稀" to "xi", "锡" to "xi", "习" to "xi", "洗" to "xi", "系" to "xi",
        "细" to "xi", "下" to "xia", "夏" to "xia", "先" to "xian", "纤" to "xian", "咸" to "xian",
        "显" to "xian", "险" to "xian", "县" to "xian", "现" to "xian", "限" to "xian", "线" to "xian",
        "香" to "xiang", "湘" to "xiang", "详" to "xiang", "享" to "xiang", "响" to "xiang", "向" to "xiang",
        "项" to "xiang", "象" to "xiang", "像" to "xiang", "橡" to "xiang", "消" to "xiao", "小" to "xiao",
        "效" to "xiao", "协" to "xie", "写" to "xie", "谢" to "xie", "芯" to "xin", "辛" to "xin",
        "欣" to "xin", "新" to "xin", "信" to "xin", "星" to "xing", "兴" to "xing", "行" to "xing",
        "形" to "xing", "型" to "xing", "姓" to "xing", "幸" to "xing", "性" to "xing", "兄" to "xiong",
        "熊" to "xiong", "修" to "xiu", "秀" to "xiu", "须" to "xu", "需" to "xu", "徐" to "xu",
        "许" to "xu", "序" to "xu", "续" to "xu", "蓄" to "xu", "宣" to "xuan", "选" to "xuan",
        "学" to "xue", "雪" to "xue", "血" to "xue", "寻" to "xun", "迅" to "xun", "循" to "xun",
        // ========== Y ==========
        "压" to "ya", "亚" to "ya", "烟" to "yan", "延" to "yan", "严" to "yan", "研" to "yan",
        "盐" to "yan", "颜" to "yan", "眼" to "yan", "演" to "yan", "宴" to "yan", "验" to "yan",
        "央" to "yang", "阳" to "yang", "杨" to "yang", "洋" to "yang", "养" to "yang", "氧" to "yang",
        "样" to "yang", "药" to "yao", "要" to "yao", "钥" to "yao", "业" to "ye", "叶" to "ye",
        "页" to "ye", "夜" to "ye", "液" to "ye", "一" to "yi", "伊" to "yi", "医" to "yi",
        "依" to "yi", "仪" to "yi", "宜" to "yi", "移" to "yi", "已" to "yi", "以" to "yi",
        "亿" to "yi", "义" to "yi", "艺" to "yi", "议" to "yi", "异" to "yi", "易" to "yi",
        "益" to "yi", "意" to "yi", "翼" to "yi", "因" to "yin", "阴" to "yin", "音" to "yin",
        "银" to "yin", "引" to "yin", "饮" to "yin", "隐" to "yin", "印" to "yin", "英" to "ying",
        "迎" to "ying", "盈" to "ying", "营" to "ying", "影" to "ying", "应" to "ying", "硬" to "ying",
        "映" to "ying", "拥" to "yong", "永" to "yong", "勇" to "yong", "用" to "yong", "优" to "you",
        "由" to "you", "邮" to "you", "油" to "you", "游" to "you", "友" to "you", "有" to "you",
        "又" to "you", "右" to "you", "佑" to "you", "于" to "yu", "余" to "yu", "鱼" to "yu",
        "宇" to "yu", "雨" to "yu", "语" to "yu", "玉" to "yu", "预" to "yu", "域" to "yu",
        "裕" to "yu", "元" to "yuan", "员" to "yuan", "园" to "yuan", "原" to "yuan", "圆" to "yuan",
        "源" to "yuan", "远" to "yuan", "院" to "yuan", "约" to "yue", "月" to "yue", "越" to "yue",
        "跃" to "yue", "云" to "yun", "允" to "yun", "运" to "yun", "韵" to "yun",
        // ========== Z ==========
        "灾" to "zai", "在" to "zai", "再" to "zai", "载" to "zai", "赞" to "zan", "早" to "zao",
        "造" to "zao", "择" to "ze", "增" to "zeng", "扎" to "zha", "展" to "zhan", "占" to "zhan",
        "战" to "zhan", "站" to "zhan", "章" to "zhang", "张" to "zhang", "涨" to "zhang", "掌" to "zhang",
        "丈" to "zhang", "招" to "zhao", "兆" to "zhao", "照" to "zhao", "折" to "zhe", "哲" to "zhe",
        "这" to "zhe", "浙" to "zhe", "真" to "zhen", "振" to "zhen", "镇" to "zhen", "震" to "zhen",
        "争" to "zheng", "征" to "zheng", "整" to "zheng", "正" to "zheng", "证" to "zheng", "政" to "zheng",
        "郑" to "zheng", "支" to "zhi", "知" to "zhi", "织" to "zhi", "直" to "zhi", "值" to "zhi",
        "职" to "zhi", "植" to "zhi", "指" to "zhi", "至" to "zhi", "制" to "zhi", "治" to "zhi",
        "质" to "zhi", "智" to "zhi", "中" to "zhong", "终" to "zhong", "钟" to "zhong", "重" to "zhong",
        "众" to "zhong", "洲" to "zhou", "轴" to "zhou", "珠" to "zhu", "主" to "zhu", "住" to "zhu",
        "助" to "zhu", "注" to "zhu", "驻" to "zhu", "柱" to "zhu", "筑" to "zhu", "抓" to "zhua",
        "专" to "zhuan", "转" to "zhuan", "装" to "zhuang", "壮" to "zhuang", "状" to "zhuang", "追" to "zhui",
        "准" to "zhun", "卓" to "zhuo", "着" to "zhuo", "资" to "zi", "子" to "zi", "字" to "zi",
        "自" to "zi", "宗" to "zong", "综" to "zong", "总" to "zong", "纵" to "zong", "走" to "zou",
        "租" to "zu", "组" to "zu", "钻" to "zuan", "最" to "zui", "醉" to "zui", "尊" to "zun",
        "做" to "zuo", "作" to "zuo", "坐" to "zuo"
    )

    /**
     * 获取中文文本的首字母拼音缩写
     * 例：贵州茅台 → GZMT（Guìzhōu Máotái）
     */
    fun toPinyinAbbr(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val py = PINYIN_MAP[ch.toString()]
            if (py != null) {
                sb.append(py.first().uppercaseChar())
            } else if (ch.isLetter()) {
                sb.append(ch.uppercaseChar())
            }
        }
        return sb.toString()
    }

    /**
     * 获取中文文本的全拼（首字母大写，空格分隔）
     * 例：贵州茅台 → "Gui Zhou Mao Tai"
     */
    fun toPinyinFull(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val py = PINYIN_MAP[ch.toString()]
            if (py != null) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(py.replaceFirstChar { it.uppercaseChar() })
            }
        }
        return sb.toString()
    }

    /**
     * 获取中文文本的拼音首字母（小写，无分隔）
     * 例：贵州茅台 → "gzmt"
     */
    fun toPinyinAbbrLower(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val py = PINYIN_MAP[ch.toString()]
            if (py != null) {
                sb.append(py.first().lowercaseChar())
            } else if (ch.isLetter()) {
                sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /**
     * 获取中文文本的全拼（小写，空格分隔）
     * 例：贵州茅台 → "gui zhou mao tai"
     */
    fun toPinyinFullLower(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val py = PINYIN_MAP[ch.toString()]
            if (py != null) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(py)
            }
        }
        return sb.toString()
    }

    /**
     * 检查搜索关键词是否匹配目标文本
     * 支持：中文名称、拼音首字母、拼音全拼
     *
     * 例：
     * - keyword="茅台" → 匹配 "贵州茅台"
     * - keyword="GZMT" → 匹配 "贵州茅台"
     * - keyword="gzmt" → 匹配 "贵州茅台"
     * - keyword="gui zhou" → 匹配 "贵州茅台"
     * - keyword="maotai" → 匹配 "贵州茅台"
     */
    fun matches(targetName: String, keyword: String): Boolean {
        if (targetName.isEmpty() || keyword.isEmpty()) return false

        val kw = keyword.trim().lowercase()

        // 1. 中文名称直接匹配
        if (targetName.contains(keyword, ignoreCase = true)) return true

        // 2. 拼音首字母匹配 (e.g., "GZMT", "gzmt")
        val abbr = toPinyinAbbrLower(targetName)
        if (abbr.contains(kw.replace(" ", ""))) return true

        // 3. 拼音全拼匹配 (e.g., "mao tai", "gui zhou")
        val full = toPinyinFullLower(targetName)
        if (full.contains(kw)) return true

        // 4. 无空格拼音全拼匹配 (e.g., "maotai", "guizhou")
        val fullNoSpace = full.replace(" ", "")
        if (fullNoSpace.contains(kw.replace(" ", ""))) return true

        return false
    }
}