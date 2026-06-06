# UI 布局架构说明

> 本文档描述 StockAnalysis App 的 UI 层布局结构，参考豆包 Android App 设计风格。

---

## 一、整体导航结构

```
MainActivity（底部 4 Tab 导航）
├── Tab 0: 对话（ChatTabFragment）       ← 默认 Tab
├── Tab 1: 股票（StockTabFragment）
├── Tab 2: 策略（StrategyFragment）
└── Tab 3: 我的（SettingsFragment）
```

底部导航使用 `BottomNavigationView` + `ViewPager2`，资源文件：
- `activity_main.xml` — 主 Activity 布局（ViewPager2 + BottomNavigationView）
- `menu/nav_menu.xml` — 4 个导航 Tab 的菜单定义

---

## 二、对话 Tab（fragment_chat.xml）—— 类似豆包 app1

```
┌─────────────────────────────────────────┐
│  ≡(两横线) │  标题(自动取首句) ›  │  ⋮  │  ← 白色标题栏
│            │  内容由AI生成，请仔细甄别  │
├─────────────────────────────────────────┤
│                                         │
│   [AI头像]  消息气泡（白色背景）         │  ← 消息列表
│   操作栏：复制 🔊 ⭐ 转发 🔄重新生成    │     点击气泡展开
│                                         │
│               用户消息气泡（蓝色） [U]  │
│               操作栏：复制 编辑 删除    │
│                                         │
│            聊聊新话题                   │  ← AI完成后出现
├─────────────────────────────────────────┤
│  📷 │ 发消息或按住说话...  │ 🎤 │ ➕  │  ← 豆包同款输入栏
└─────────────────────────────────────────┘
```

**交互说明：**
- `≡` 按钮（ic_menu_lines.xml，两条不等长横线）→ 弹出全屏历史对话 BottomSheet
- 标题自动取第一条用户消息的前 12 字
- AI 消息气泡**点击**展开操作栏（复制/🔊播放/⭐收藏/转发/🔄重新生成）
- 用户消息气泡**点击**展开操作栏（复制/编辑/删除）
- ➕ 键发送消息；🎤 语音输入（开发中）；📷 图片（开发中）

**涉及文件：**
| 文件 | 说明 |
|------|------|
| `fragment_chat.xml` | 聊天窗口主布局 |
| `item_message.xml` | 消息列表项（用户/AI/流式/错误） |
| `ChatTabFragment.kt` | Fragment 控制器 |
| `ChatAdapter.kt` | RecyclerView 适配器 |
| `Message.kt` | 消息数据类 |

---

## 三、历史对话 BottomSheet（fragment_conversation_list.xml）—— 类似豆包 app2

点击聊天页左上角 `≡` 展开：

```
┌─────────────────────────────────────────┐
│  ⬜  │        对话         │  🔍  ✏️   │  ← 白色标题栏
├─────────────────────────────────────────┤
│ 🟣 解套股票分析          摘要第一行...  │
│ 🟢 商业航天产业链分析    马斯克商业...  │
│ 🔵 有色金属板块今天      板块核心龙...  │
│ 🟡 早盘追涨选股          10:30 后...   │
│   ...                                   │
└─────────────────────────────────────────┘
```

**涉及文件：**
| 文件 | 说明 |
|------|------|
| `fragment_conversation_list.xml` | 对话列表布局（BottomSheet 内容） |
| `item_conversation.xml` | 单个对话列表项（彩色头像+标题+摘要） |
| `ConversationListFragment.kt` | BottomSheetDialogFragment 实现 |

---

## 四、ChatActivity（activity_chat.xml）—— 独立 Activity 版（备用）

> 注：当前 App 主入口使用 `ChatTabFragment`，`ChatActivity` 为备用入口。

```
DrawerLayout（根布局）
├── 主内容 LinearLayout
│   ├── Toolbar（蓝色，56dp）
│   │   └── ≡ │ Logo │ 标题 │ 📝新建 │ ⋮菜单
│   ├── RecyclerView（消息列表）
│   └── 底部输入区
│       ├── 输入框行（输入框 + 发送键）
│       └── HorizontalScrollView（快捷操作：+ ⚡快速 🔍深入研究 📈股票 ⋮更多）
│           ← paddingBottom="12dp" 适配系统手势导航栏
└── 左侧抽屉（288dp）
    ├── 蓝色顶部（Logo + 标题 + 新建）
    ├── 历史对话 RecyclerView
    └── 底部账号信息区
```

**涉及文件：**
| 文件 | 说明 |
|------|------|
| `activity_chat.xml` | ChatActivity 完整布局 |
| `ChatActivity.kt` | Activity 控制器 |

---

## 五、其他 Tab 布局

| 文件 | Tab | 说明 |
|------|-----|------|
| `fragment_stock.xml` | 股票 | 行情列表、搜索、Tab（A股/ETF/热门/涨幅/跌幅） |
| `fragment_settings.xml` | 我的 | API 配置、模型选择、Key 管理 |
| `fragment_conversation_list.xml` | 对话历史 | BottomSheet 历史列表 |
| `StrategyFragment.kt` | 策略 | 占位页（量化策略，即将上线） |

---

## 六、消息条目布局（item_message.xml）

```
LinearLayout（垂直，每条消息一行）
├── tvTime（时间戳，居中）
├── layoutUser（用户消息，右对齐，GONE/VISIBLE）
│   ├── tvUserMessage（蓝色气泡）
│   └── layoutUserActions（复制 编辑 删除）
└── layoutBot（AI消息，左对齐，GONE/VISIBLE）
    ├── ivAvatar（36dp 圆形 AI 头像）
    └── 右侧内容 LinearLayout
        ├── tvBotMessage（白色气泡）＋ tvTypingIndicator（流式中显示...）
        ├── layoutBotActions（复制 🔊 ⭐ 转发 🔄重新生成，点击气泡切换可见性）
        └── tvErrorHint（错误状态）
```

---

## 七、Drawable 资源

| 文件 | 用途 |
|------|------|
| `bg_message_user.xml` | 用户消息气泡（蓝色圆角） |
| `bg_message_bot.xml` | AI 消息气泡（白色圆角） |
| `bg_input.xml` | 输入框背景（浅灰圆角） |
| `bg_send.xml` | 发送按钮背景（蓝色圆形） |
| `bg_action_btn.xml` | 操作按钮背景（白色圆角描边） |
| `bg_avatar.xml` | AI 头像背景（蓝色渐变圆形） |
| `ic_menu_lines.xml` | ≡ 两条不等长横线导航图标 |
| `ic_nav_chat.xml` | 底部导航"对话"图标 |
| `ic_nav_stock.xml` | 底部导航"股票"图标（折线图） |
| `ic_nav_strategy.xml` | 底部导航"策略"图标 |
| `ic_nav_mine.xml` | 底部导航"我的"图标（人形） |

---

## 八、UI 状态流

```
App 启动
    └→ MainActivity.onCreate()
         └→ ViewPager 默认 Tab 0 = ChatTabFragment
              └→ 显示欢迎消息
              └→ 用户输入 → sendMessage()
                   └→ StockQueryEngine.buildSystemPrompt()（注入实时数据）
                        └→ ApiProvider.sendMessageStream()（流式响应）
                             └→ ChatAdapter.updateStreamingMessage()（实时更新气泡）
              └→ ≡ 点击 → ConversationListFragment.show()（全屏 BottomSheet）
                   └→ 选择历史会话 / 新建对话
```
