# SMS 应用 · 界面与交互说明

> 本文档描述当前（初版）实现的 UI 布局与交互流程，全部以文字 / ASCII 线框 / 表格描述，不含图片。

## 1. 应用定位

个人自用、不上架的纯 SMS 文本短信替代 App。已实现「接收 + 会话列表展示 + 会话详情二级页 + 发送（回复 / 新建）」，页面间用 `NavHost` 导航（见第 4 节）；无 MMS 处理。

## 2. 四大默认短信组件（AndroidManifest）

满足「默认短信 App」要求的四件套已在 `AndroidManifest.xml` 声明：

| 组件 | 类 | 角色 | 当前状态 |
|------|----|------|----------|
| Activity | `.MainActivity` | `SENDTO`(sms/smsto)，可作为默认短信 App 被系统拉起 | 已实现 |
| Receiver | `.receiver.SmsReceiver` | `SMS_DELIVER`，接收新短信并落库 | 已实现 |
| Receiver | `.receiver.MmsReceiver` | `WAP_PUSH_DELIVER`，MMS 投递 | **占位（不处理 MMS）** |
| Service | `.service.HeadlessSmsSendService` | `RESPOND_VIA_MESSAGE`，通知栏/车载快捷回复 | **占位（需求已排除快捷回复）** |
| 权限 | `READ_CONTACTS` | 读取通讯录以把号码解析为显示名（可选增强） | 已实现（不授权也能正常使用短信） |

> `READ_CONTACTS` 为**可选增强权限**：授权后列表/详情优先显示通讯录名称；不授权时仅回退显示号码与内置公共号码库，不影响短信收发。

## 4. 主界面 UI（MainWindow / MainActivity）

`MainActivity` 的 `setContent` 结构——**未就绪时仅显示设置卡片（独占一屏），两条件都满足后才显示 `NavHost`（会话列表 + 详情）**：

```
Scaffold / MaterialTheme
 └─ Surface
     ├─ [未就绪] SetupCard（无权限 / 非默认时出现其一，独占一屏）
     └─ [已就绪] NavHost(start = "conversationList", fillMaxSize)
             ├─ "conversationList"            → ConversationListScreen（见 4.2）
             └─ "conversationDetail/{threadId}" → ConversationDetailScreen（见 4.3）
```

### 4.1 设置卡片 SetupCard（组件，非独立页）

无权限时：
```
┌──────────────────────────────┐
│ 需要短信权限才能读取会话        │
│                    [ 授予权限 ]│
└──────────────────────────────┘
```
非默认短信 App 时：
```
┌──────────────────────────────┐
│ 设为默认短信应用后才能收发短信  │
│                    [ 设为默认 ]│
└──────────────────────────────┘
```

交互（未就绪时独占一屏）：
- 点击「授予权限」→ 弹出系统权限申请，回调 `refreshStates()` 刷新状态。
- 点击「设为默认」→ 跳系统默认短信设置页，返回后 `refreshStates()` 刷新。

> 出现条件：未就绪（无权限 / 非默认短信 App）时显示其一；两条件都满足后，`MainActivity` 才渲染 `NavHost`（见本节顶部结构图）。

### 4.2 会话列表页 ConversationListScreen（独立页面 = 子项）

> 以下线框、组件树、列表项均属本页面范畴，列表项作为本页面的内部组件嵌套在 4.2.1。

线框：
```
┌──────────────────────────────┐
│  短信                  (顶栏)  │
├──────────────────────────────┤
│ 10086              14:30:05  │
│ 这是一条模拟短信…             │
│ ──────────────────────────── │
│ 13800138000        13:02:10  │
│ 验证码是 123456               │
│ ──────────────────────────── │
│ (无数据时居中显示「暂无会话」) │
└──────────────────────────────┘
```

组件树：
```
ConversationListScreen  (@Composable)
 └─ Scaffold
     ├─ topBar: TopAppBar → Text("短信")   // 背景 #E3F2FD（与置顶项同色）
     │    └─ actions(右上角): [回收站] IconButton(DeleteSweep) → navigate("recycleBin")；[新建] FAB(+)
     ├─ floatingActionButton: FAB(Add) → onNewConversation / navigate("compose")
     └─ content:
         ├─ 空状态: Column 居中 → Text("暂无会话")
         └─ 有数据: LazyColumn
                 └─ items(conversations, key=threadId)
                         └─ ConversationSwipeItem(conv)   // 左滑删除包裹层（见下）
                                 └─ ConversationItem(conv)   ← 见 4.2.1
```

交互：
- 列表项可点击，行为见 4.2.1。
- 点击右下角悬浮按钮 `+` → 跳转发送页（见 4.4）。
- **长按列表项** → 弹出操作菜单（见 4.2.1），目前含「置顶 / 取消置顶」一项。
- **左滑列表项** → 右侧露出红色「删除」按钮（宽 100dp），点击删除：
  - 会话仅 1 条消息（`messageCount <= 1`）→ 直接删除，无二次确认；
  - 会话含多条消息 → 弹二次确认对话框（标题「删除会话」，确认才删）。
  - 删除均走回收站：先把会话内所有消息副本写入 Room 回收站，再从系统短信库按 `thread_id` 删除，列表随之刷新；回收站记录超 30 天自动清理（每次进入首页时触发）。
  - 露出删除态时点击气泡可滑回复位。

#### 4.2.1 列表项 ConversationItem 布局（本页内部组件）

```
Column (fillMaxWidth)
 ├─ Box (fillMaxWidth, 置顶时背景 #E3F2FD, menuAnchor + combinedClickable → onClick / onLongClick)
 │    └─ Row (fillMaxWidth, 垂直居中, padding 水平 16dp / 垂直 12dp)
 │         ├─ Avatar(头像字)                      // 圆形 40dp, primary 底, 白字; 与详情页同逻辑
 │         ├─ Spacer(8dp)
 │         ├─ Column (weight = 1f)
 │         │    ├─ Text(displayName ?: address ?: "(未知号码)")   // titleMedium, 优先显示名称；**未读与已读均加粗；未读时字体色 = 纠错色(error，与红点背景同色)，已读时 = 主色(primary，与头像背景同色)**
 │         │    └─ Text(snippet)                  // bodyMedium, 单行, 超出省略号
 │         ├─ [未读时] Box(align=TopEnd, padding top6dp/end12dp, 圆角12dp, 红底 error, 白字 labelSmall) → 显示未读数 `unreadCount`   // **绝对定位在 item 右上角**的红底白字圆角 badge
 │         └─ Text(时间, 格式见下)                  // labelSmall, MM-dd HH:mm；超一年补 yyyy-
 ├─ HorizontalDivider (1dp, 浅灰 #E0E0E0)  // 列表项底部分割线
 └─ DropdownMenu (长按弹出; expanded = 本项为菜单目标)
        └─ DropdownMenuItem("置顶" / "取消置顶") → viewModel.togglePin(threadId)
```

> 头像字逻辑（与详情页一致）：取 `displayName ?: address` 的首字符，空则 `"?"`。置顶通过列表项背景色（#E3F2FD）区分，行内不再显示图钉图标。

交互：
- **未读会话**：标题加粗且字体色 = 纠错色（colorScheme.error，与右上角红点背景同色），**item 右上角绝对定位**一个红底白字圆角 badge 显示未读数；已读会话标题亦加粗、字体色 = 主色（primary，与头像背景同色）；进入会话详情（见 4.3）后由 `ConversationDetailViewModel` 自动调用 `SmsRepository.markThreadRead(threadId)` 把该会话下所有未读短信标记为已读（`READ=1`），列表随即刷新、未读标识消失。验证码会话与其他会话未读逻辑一致。
- 点击本条 → `navController.navigate("conversationDetail/{threadId}")`，跳转到该会话的详情页（见 4.3）。
- 长按本条 → 弹出操作菜单，点「置顶」/「取消置顶」切换置顶状态；置顶会话排到列表最前（组内仍按时间倒序），行尾显示图钉图标。
- 左滑本条 → 由外层 `ConversationSwipeItem` 处理（见 4.2 交互），露出删除按钮；露出删除态时 `ConversationItem` 自身点击被禁用（`clickEnabled=false`），避免误触打开会话。

### 4.3 会话详情页 ConversationDetailScreen（独立页面 = 子项）

> 点击 4.2 列表项后由 `NavHost` 跳转至此，路由为 `conversationDetail/{threadId}`（路由参数，路径字符串解析为 `Long`）。

线框：
```
┌──────────────────────────────┐
│ ‹ 10086              (顶栏)   │
├──────────────────────────────┤
│ (●) 这是一条模拟短信…  (收件左) │   ← (●)=圆形头像，内为联系人首字
│ ──────────────────────────── │
│              验证码是123456 (我)│   ← (我)=圆形头像(白字)，发件气泡为绿色
│ ──────────────────────────── │
│ (无数据时居中显示「暂无消息」) │
└──────────────────────────────┘
```

组件树：
```
ConversationDetailScreen  (@Composable)
 └─ Scaffold
     ├─ topBar: TopAppBar
     │    ├─ navigationIcon: IconButton(ArrowBack) → onBack / popBackStack
     │    └─ title: Text(displayName ?: address ?: "(未知号码)")   // 优先显示名称
     ├─ content: LazyColumn(verticalArrangement = spacedBy(6.dp))
     │        └─ items(messages, key = id)
     │                └─ MessageSwipeItem(msg)        // 左滑删除包裹层（见 4.3 交互）
     │                        └─ Surface(...) → MessageBubble(msg)
     │                └─ if (menuMessageId == msg.id) Popup(alignment = Center)  // 长按气泡弹出，居中卡片菜单
     │                        └─ Surface(圆角12dp, 抬升3dp) → TextButton("复制文本") → 复制整条正文到剪贴板 + Toast「已复制」
     ├─ bottomBar: ConversationSendBar（见 4.3.2）
     └─ (overlay) MessageExpandedOverlay(message)  // 双击气泡弹出：全屏居中放大显示该条消息
```

交互：
- 点击顶栏返回箭头 `‹` → `popBackStack()` 返回会话列表页（见 4.2）。
- 底部输入并点击「发送」→ 见 4.3.2。
- **左滑单条消息（ITEM）→ 右侧露出红色「删除」按钮，点击即删，无二次确认**：先将该消息副本写入回收站（Room），再从系统短信库删除原记录，本页列表随之刷新（见 4.5）。
- **双击气泡 → 全屏居中弹层放大显示该条消息正文**：字号放大至 24sp，深色半透明背景（黑 92%）；弹层内文本由 `SelectionContainer` 包裹，支持系统级长按选词/复制（弹出系统选择菜单与「复制/全选」），长文本可在弹层内滚动；右上角提供 `✕` 关闭按钮，点击任意处空白处或系统返回键亦可关闭；仅在气泡未处于左滑删除态时触发。
- **长按气泡 → 弹出操作菜单（居中 `Popup` 卡片，`alignment = Alignment.Center`）**：目前含「复制文本」一项；点击即把整条消息正文写入系统剪贴板并 Toast「已复制」，菜单自动关闭。**验证码会话内的消息额外提供「移出验证码列表」一项**（仅 `isCodeConversation` 时显示），点击后调用 `ConversationDetailViewModel.moveOutOfVerification` → `SmsRepository.moveMessageOutOfVerification(id, address)`：`resolveRealThreadId(address)` 按发送方 address 查询其在系统短信库已有的正常会话 thread_id（`thread_id != -1`）并复用，若该发送方没有其它正常会话则新建一个不与 -1 冲突的负数 thread_id，刷新后该消息即从验证码会话消失（用于清理被一次性迁移误归的历史短信）；Toast「已移出验证码列表」。**长按范围覆盖整个气泡**，包括正文文本区（由气泡 `Text` 的 `detectTapGestures.onLongPress` 透传）与文本外的空白边距（由外层 `Surface.combinedClickable.onLongClick` 兜底），两者均仅在气泡未处于左滑删除态（`offsetX==0`）时触发。同一时刻全局仅一个菜单展开（`menuMessageId` 记录当前目标消息 id）。
- **验证码短信高亮 + 复制按钮**：当消息能提取出验证码（`extractVerificationCode`：关键词为**正则精确匹配**——中文明确词「验证码/校验码/动态码/动态口令/随机码/安全码/登录码/短信码/提现码/取件码/口令」、英文 `verification/verif/otp/captcha/one-time code/your code`，单独的 `code` 必须不是别的单词前缀（排除 CodeArts/Decode 等）；**不含**宽泛的「码」单字）后，在关键词**前后各 30 字窗口**内匹配第一个 4-8 位数字（覆盖「数字在验证码词之前」如「429406为您的登录验证码」与「数字在之后」如「验证码是 653721」两种语序），与归类判断 `isVerificationCodeMessage` 完全同一套逻辑）时，气泡内**该验证码数字段**加粗 + 黄底（`#FFF176`）高亮（与链接区间去重）；气泡底部出现「复制验证码」按钮，点击复制该码并 Toast「验证码已复制」。**不做任意位置兜底数字**：未命中明确验证码关键词的消息整体不高亮、无复制按钮（如营销短信「码道CodeArts…首开即送4000积分」不会被误判，积分数字 4000/1000 不高亮）。年份、订单号等非验证码数字**不会**被高亮（提取只取关键词附近窗口内的码，且未命中关键词的消息整体不高亮）。
- **验证码短信 1 天后自动清理**：归入验证码会话（`threadId == -1`）的消息，自接收时间起超过 1 天即被自动删除，流程与详情页「左滑删除按钮」**完全一致**——先将该消息副本写入本地回收站（Room），再从系统短信库删除原记录，随后刷新会话列表与详情页。触发时机为**每次进入首页**（会话列表页 `ConversationListScreen` 用 `LaunchedEffect(Unit)` 在 composable 进入组合时调用 `ConversationListViewModel.cleanupExpiredVerificationCodes()`，即每次从详情页返回首页或冷启动进入首页都会检查一次）；删除在 `SmsRepository` 的长生命周期 `scope` 中执行，即使快速离开首页也能完成，且幂等（反复进出不会重复删除）。已手动「移出验证码列表」的消息不再属于验证码会话，不会被自动清理。

#### 4.3.1 气泡 MessageBubble（本页内部组件）

```
Row (fillMaxWidth, 收件 Start / 发件 End, 垂直 Bottom)
 ├─ [收件时] Avatar   ← 圆形头像，位于气泡左侧
 ├─ Box (weight(1f, fill=false) → 宽度按内容自适应, RoundedCornerShape(12.dp), background)
 │    ├─ Text(body, Color.Black)              // bodyMedium，正文黑色
 │    └─ Text(时间 MM-dd HH:mm / 超一年 yyyy-MM-dd HH:mm, Color.Gray)  // labelSmall，灰色，右下角
 └─ [发件时] Avatar   ← 圆形头像，位于气泡右侧
```

组件 Avatar（本页内部组件，接收 `backgroundColor` 参数，默认 `colorScheme.primary`）：
```
Box (size 40.dp, CircleShape, background = 收件 primary / 发件 绿色 0xFF95EC69, 居中)
 └─ Text(char, Color.White)   // titleMedium，白字
```
- `char` 取值：收件 → `displayName ?: address` 的**首字符**（无则 `?`）；发件 → 固定 `"我"`。
- 发件（"我"字）头像背景与发件气泡同为绿色 `0xFF95EC69`。
- 气泡 Box 使用 `weight(1f, fill = false)`：先为头像（40dp + 8dp 间距）预留空间，气泡宽度按内容自适应，上限为剩余宽度；气泡内时间/验证码按钮行**不使用** `fillMaxWidth`，否则会把整行宽度吃掉，Row 顺序测量时头像被挤压为 0 而不可见（真机曾出现发件消息无头像的 bug 即源于此）。

配色与对齐规则（`isIncoming` 由 `type == MESSAGE_TYPE_INBOX` 推导）：
- 收件：左对齐、气泡背景 `surfaceVariant`、头像在左。
- 发件：右对齐、**气泡背景绿色 `0xFF95EC69`**、头像（`我`，绿底）在右。
- 正文一律黑色（Color.Black）；时间一律灰色（Color.Gray）。

#### 4.3.2 发送栏 ConversationSendBar（本页底部组件）

```
Surface (tonalElevation = 0dp, navigationBarsPadding)
 └─ Column (fillMaxWidth, padding 8dp, imePadding)
     ├─ [SIM 卡 > 1 张时显示] Text("发送 SIM 卡", labelMedium)
     │    Row (fillMaxWidth, spacedBy(16dp))
     │     └─ for sim in simCards: Row(selectable) → RadioButton + Text(sim.displayName)
     │    Spacer(8dp)
     └─ Row (fillMaxWidth, 垂直居中)
          ├─ OutlinedTextField(占位"输入消息", weight = 1f, maxLines = 4)
          └─ Button("发送")  → viewModel.send(address, draft, selectedSubId)   // 主色(primary)填充背景，白字
```

交互：
- 仅当设备有 **> 1 张**可用 SIM 卡（`simCards.size > 1`）时，才在输入框上方显示「发送 SIM 卡」单选栏；单卡或无卡时不显示，发送默认使用唯一可用卡（`selectedSubId` 初始化为 `simCards.firstOrNull()?.subId ?: -1`）。
- 多卡时点击某张卡的圆点 / 文字 → 切换 `selectedSubId`，决定本次发送使用的卡。
- 输入内容后点击「发送」→ 经 `SmsManager` 真正发出短信，并把已发送消息（`TYPE_SENT`）写入系统短信库；气泡列表与列表页随之刷新。号码取自本会话 `address`。

### 4.4 发送页 SendSmsScreen（独立页面 = 子项）

> 由列表页 FAB（`+`）经 `NavHost` 跳转至此，路由为 `compose`；发送成功后跳转 `conversationDetail/{threadId}` 并出栈本页。

线框：
```
┌──────────────────────────────┐
│ ‹ 发送短信            (顶栏)   │
├──────────────────────────────┤
│ [ 收件人号码          ]       │
│ ──────────────────────────── │
│ [ 内容                    ]   │
│ (多行输入框，占满剩余空间)     │
├──────────────────────────────┤
│        [ 发送 ]（底部按钮）   │
└──────────────────────────────┘
```

组件树：
```
SendSmsScreen  (@Composable)
 └─ Scaffold
     ├─ topBar: TopAppBar
     │    ├─ navigationIcon: IconButton(ArrowBack) → onBack / popBackStack
     │    └─ title: Text("发送短信")
     ├─ content: Column (spacedBy 12dp) → [SIM 卡 > 1 张时]「发送 SIM 卡」单选栏 + OutlinedTextField(收件人) + OutlinedTextField(内容, weight=1)
     └─ bottomBar: Button("发送") → viewModel.send(address, body, selectedSubId)   // 主色(primary)填充背景，白字
```

交互：
- 仅当设备有 **> 1 张**可用 SIM 卡时，才在输入框上方显示「发送 SIM 卡」单选栏；单卡或无卡时不显示，发送默认使用唯一可用卡。
- 点击顶栏返回箭头 `‹` → 返回列表页。
- 点击「发送」→ 调用 `SmsRepository.sendMessage`：经 `SmsManager` 发送并把已发送消息写入短信库，返回 `threadId` 后跳转到该会话详情页（见 4.3）；若发送 / 落库失败（如未设默认短信 App），自动返回列表页。

### 4.5 回收站页 RecycleBinScreen（独立页面 = 子项）

> 由首页 `TopAppBar` 右上角「回收站」按钮经 `NavHost` 跳转至此，路由为 `recycleBin`；展示被左滑删除的短信副本，提供「恢复」与「删除」。

线框：
```
┌──────────────────────────────┐
│ ‹ 回收站              (顶栏)   │
├──────────────────────────────┤
│ 10086                        │
│ 验证码是 123456…             │
│ 删除于 10-19 12:00 · 剩余 28 天│
│            [ 恢复 ] [ 删除 ]  │
├──────────────────────────────┤
│ (空时居中显示「回收站为空」)    │
└──────────────────────────────┘
```

组件树：
```
RecycleBinScreen  (@Composable)
 └─ Scaffold
     ├─ topBar: TopAppBar
     │    ├─ navigationIcon: IconButton(ArrowBack) → onBack / popBackStack
     │    └─ title: Text("回收站")
     └─ content: LazyColumn(items = observeAll())
             └─ RecycledItem(item)
                  ├─ Text(号码/正文)
                  ├─ Text("删除于 … · 剩余 N 天")
                  └─ Row: OutlinedButton("恢复") → viewModel.restore(item)
                          OutlinedButton("删除", 红色) → viewModel.remove(item)
```

数据（`RecycledMessage`，存于 `recycle_bin.db`，Room）：`rowId`(自增主键) / `originalId`(原系统短信 `_id`) / `threadId` / `address` / `body` / `date`(原时间) / `type` / `read` / `deletedAt`(入站时间)。

交互：
- 回收站中 `deletedAt` 超过 **30 天**的记录，由**每次进入首页**时统一清理（`ConversationListViewModel.purgeExpiredRecycleBin()` → `RecycleBinRepository.purgeExpired()`，与验证码清理同批触发；不再在 App 启动或打开回收站页时触发）。打开回收站页本身不再单独清理。
- 「恢复」→ 把消息写回系统短信库（`content://sms`，新 `_id`），并从回收站删除；会话列表页随之刷新（共享 `SmsRepository`）。
- 「删除」→ 仅从回收站永久删除，不写回系统库。

## 5. 数据模型

`Conversation`（会话聚合后的一行）：

| 字段 | 类型 | 含义 |
|------|------|------|
| `threadId` | Long | 会话 ID（按发件人号码归并，同一号码同 ID） |
| `address` | String | 对方号码，空则显示「(未知号码)」 |
| `snippet` | String | 该会话最新一条短信正文（单行省略） |
| `date` | Long | 最新短信时间戳(ms)，列表显示 `MM-dd HH:mm`（超一年补 `yyyy-`） |
| `read` | Boolean | 是否已读（由 `unreadCount == 0` 推导） |
| `unreadCount` | Int | 该会话下未读（系统短信库 `READ=0`）消息条数，列表未读标识用 |
| `messageCount` | Int | 该会话短信条数 |
| `displayName` | String? | 号码对应显示名（优先级：系统联系人 > 公共号码库 > 正文签名【…】兜底）；null 时 UI 回退显示 `address` |

`Message`（会话详情页中单条短信）：

| 字段 | 类型 | 含义 |
|------|------|------|
| `id` | Long | 短信行 `_ID` |
| `threadId` | Long | 所属会话 ID |
| `address` | String | 对方号码 |
| `body` | String | 短信正文 |
| `date` | Long | 时间戳(ms)，显示 `MM-dd HH:mm`（超一年补 `yyyy-`） |
| `type` | Int | `Telephony.Sms` 类型（INBOX / SENT …） |
| `read` | Boolean | 是否已读 |
| `displayName` | String? | 号码对应显示名（优先级：系统联系人 > 公共号码库 > 正文签名【…】兜底）；null 时 UI 回退显示 `address` |
| `isIncoming` | Boolean | 由 `type == MESSAGE_TYPE_INBOX` 推导，决定气泡左右与配色 |

## 6. 数据流向（接收短信 → 列表刷新）

```
系统 RIL
  │ SMS_DELIVER
  ▼
SmsReceiver.onReceive(pdus)
  │ EntryPointAccessors → SmsRepository
  ▼
SmsRepository.persistIncoming(sms)
  │ contentResolver.insert(content://sms/inbox, values)
  ▼
SmsRepository.reload()
  │ queryConversations()：按 threadId 聚合（同号码→同会话，
  │ 只保留每会话最新一条，并统计 messageCount）
  ▼
_conversations: MutableStateFlow<List<Conversation>>
  │
  ▼
ConversationListViewModel.conversations (StateFlow)
  │ collectAsStateWithLifecycle()
  ▼
ConversationListScreen 自动重组显示
```

> 说明：列表是**会话级聚合**，不是每条短信一行。同一发件人（如测试脚本默认 `10086`）的多条短信会归并成列表中的**一行**，其 `messageCount` 反映条数。

> 显示名解析：`queryConversations()` / `getMessages()` 在组装 `Conversation` / `Message` 时填充 `displayName`。解析优先级（命中即止）：① 系统通讯录（`ContactsContract`，需 `READ_CONTACTS`）② 内置公共号码库（`assets/public_numbers.json`，离线、无需权限）③ **正文签名兜底**：号码在 ①② 均未命中时，用 `extractSmsSignature(body)`（`util/SmsSignature.kt`，仅匹配正文开头 `【…】/【…】/[…]/(…)`、内容 ≤20 字且不含换行）提取签名身份作为显示名，符合国内企业短信签名行业规则（如 `10086` 无联系人 → 正文 `【中国移动】…` → 显示 `中国移动`）；④ 以上皆空则为 `null`，UI 回退显示原号码（如 `10086`）。列表（会话）用最新一条消息 body 提取，详情（每条消息）用各自 body 提取；验证码会话（`threadId == -1`）固定显示「验证码」，走不到签名兜底。两个数据源 ①② 均在进程冷启动时加载进内存 Map，不引入任何本地数据库。

详情页数据流向（点击列表项 → 展示该会话全部短信）：
```
ConversationListScreen 点击 → navController.navigate("conversationDetail/{threadId}")
  ▼
ConversationDetailScreen (hiltViewModel)
  │ SavedStateHandle 取 threadId
  ▼
ConversationDetailViewModel.load() → viewModelScope(Dispatchers.IO)
  ▼
SmsRepository.getMessages(threadId)
  │ contentResolver.query(content://sms, "thread_id = ?", ORDER BY date ASC)
  ▼
_messages: MutableStateFlow<List<Message>>
  │ collectAsStateWithLifecycle()
  ▼
ConversationDetailScreen 气泡列表重组显示
```

> 实时刷新：仓库 `SmsRepository` 在任何写库操作（接收/发送/删除/标已读/恢复）后自增 `dataVersion`（`MutableStateFlow<Long>`）；`ConversationDetailViewModel` 在 `init` 中 `collect(dataVersion)`，每次变更即 `markThreadRead(threadId)` + `load()` 重新查询当前会话消息，因此**打开详情页期间收到当前会话新消息会自动标记为已读并刷新气泡列表**（自己发送 / 删除单条同理），无需返回再进入。`markThreadRead` 仅当确有未读行被置已读（update 影响行数 > 0）时才 `reload()`，避免详情页监听 `dataVersion` 后反复触发导致无限刷新。


| 项 | 状态 | 备注 |
|----|------|------|
| 会话详情二级页 | 已实现 | 路由 `conversationDetail/{threadId}`，含返回箭头与气泡列表；监听仓库 `dataVersion` 实时刷新；打开期间收到当前会话新消息自动标已读（修复「打开详情页时收到新消息不更新」且遗留未读） |
| 发送短信 UI | 已实现 | 列表页 FAB 进发送页；详情页底部发送栏回复；经 `SmsManager` 发送并写库 |
| MMS 处理 | 已排除 | `MmsReceiver` 仅占位 |
| 快捷回复 | 已排除 | `HeadlessSmsSendService` 仅占位 |
| 未读标记展示 | 已实现 | 列表项未读时标题加粗+纠错色(error，与红点背景同色)、右上角红底未读数 badge；已读时标题加粗+主色（与头像背景同色）；打开会话即标已读（与系统短信库共享 `READ` 状态），验证码会话逻辑一致 |
| 号码显示名称 | 已实现 | 优先系统联系人，其次内置公共号码库（`public_numbers.json`），再次正文签名【…】兜底；`READ_CONTACTS` 为可选增强 |
| 左滑删除 / 回收站 | 已实现 | 详情页左滑单条消息删除（无二次确认）；首页列表项左滑删除会话，仅 1 条消息直接删、多条弹二次确认；删除副本均入 Room 回收站；首页右上角进入回收站页，支持「恢复」写回系统库；超 30 天自动清理（每次进入首页时触发，与验证码清理同批） |
| 验证码自动清理 | 已实现 | 验证码会话内消息接收超 1 天自动删除，流程与左滑删除一致（写回收站 + 删系统库），每次进入首页（`LaunchedEffect`）触发检查；手动移出验证码列表的消息不受影响 |
