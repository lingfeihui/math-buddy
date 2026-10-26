# 学生端界面 UI 改造计划

## 背景与目标
当前界面采用深蓝科技风（暗色背景、青色网格、多色渐变、彩色玻璃态卡片），视觉上颜色杂乱，不够像正规教育产品。本次改造目标：
- 做减法：统一配色，去掉花哨渐变和发光效果
- 增加留白与呼吸感
- 左侧学情看板用雷达图展示综合能力
- 右侧对话区更干净、输入框更突出
- 整体向 ChatGPT / Kimi / 腾讯元器等干净教育产品风格靠拢

最终采用**浅色主界面 + 单一品牌蓝色（#2563EB）**方案。

## 关键文件
- `frontend/src/style.css` — 全局背景、玻璃态、滚动条、Markdown 排版颜色
- `frontend/src/App.vue` — 整体布局、卡片、按钮层级、输入区、建议 pill、欢迎头像
- `frontend/src/components/StatsChart.vue` — 柱状图改为 5 轴雷达图
- `frontend/src/components/ChatBubble.vue` — 聊天气泡、机器人头像、标签、变式按钮
- `frontend/src/components/ReportPanel.vue` — 弹窗浅色化、图表颜色统一
- `frontend/src/components/MindMapPanel.vue` — 弹窗与思维导图画布浅色化
- `frontend/src/components/ConversationPanel.vue` — 历史列表浅色化
- `frontend/src/components/MistakeBookPanel.vue` — 错题卡片、标签、按钮浅色化
- `frontend/src/components/QuizPanel.vue` — 自测界面浅色化

## 设计方案

### 1. 配色规范
| 用途 | 色值 | Tailwind 类 |
|------|------|-------------|
| 页面背景 | `#F8FAFC` | `bg-slate-50` |
| 卡片背景 | `#FFFFFF` | `bg-white` |
| 品牌主色 | `#2563EB` | `bg-blue-600`, `text-blue-600` |
| 品牌浅底 | `#EFF6FF` | `bg-blue-50` |
| 主要文字 | `#0F172A` | `text-slate-900` |
| 次要文字 | `#64748B` | `text-slate-500` |
| 说明文字 | `#94A3B8` | `text-slate-400` |
| 边框 | `#E2E8F0` | `border-slate-200` |
| 轻边框 | `#F1F5F9` | `border-slate-100` |
| 错误 | `#EF4444` | `text-rose-500` |
| 成功 | `#10B981` | `text-emerald-600` |

### 2. 按钮规范
- **主按钮（实心）**：`bg-blue-600 text-white hover:bg-blue-700 rounded-xl`
  - 用于：查看学习报告、发送、开始自测、提交答案
- **次按钮（描边）**：`border border-slate-200 bg-white text-slate-700 hover:border-blue-300 hover:text-blue-600 hover:bg-blue-50 rounded-lg`
  - 用于：知识点思维导图、针对薄弱点推荐例题、智能错题本、知识点自测、举一反三、新对话、历史对话
- **删除/关闭 hover**：保持轻微玫瑰色，不滥用

### 3. 全局样式调整（style.css）
- `:root { color-scheme: light; }`
- `body` 背景改为 `#F8FAFC`，文字改为 `#0F172A`
- `.app-shell` 移除深蓝网格和光晕，改为纯色 `#F8FAFC`
- `.glass` 重定义为白底 + 浅灰边框 + 轻阴影
- 滚动条改为 slate 系
- `.md-body` 及子元素颜色改为深色主题适配浅色背景

### 4. App.vue 改造
- 左右布局间距从 `gap-5` 加大到 `gap-8`
- 左栏宽度 `minmax(300px,360px)`
- 所有卡片改为 `bg-white border border-slate-100 rounded-2xl shadow-sm p-5`
- 左侧拆成 4 个独立卡片：
  1. 顶部介绍（标题 + 14px 描述 + 3 个数据小卡片）
  2. 学情看板（雷达图 + 薄弱标签）
  3. 学习工具（所有功能按钮，主按钮突出）
  4. 使用小贴士
- 右侧对话区外框改为白底浅灰边框
- 输入框容器：白底 + `border-slate-200` + 阴影 + focus 蓝色 ring
- 发送按钮：更高更大、`bg-blue-600`、白色文字、阴影
- 建议 pill：浅灰底圆角、悬停变蓝底
- 顶部 logo 可同步换成机器人 SVG，与 ChatBubble 一致

### 5. StatsChart.vue 改造
- 柱状图改为 5 轴雷达图
- 5 轴固定为：函数、几何、概率、数列、导数
- 后端返回 `Map<String,Integer>` 按关键词聚合到 5 大维度
- `indicator.max` 动态取 `max(3, 最大提问次数)`，避免图形缩成一团
- ECharts 颜色统一为蓝色系 `#2563EB` / `rgba(37,99,235,0.18)`

### 6. ChatBubble.vue 改造
- AI 头像改为内联机器人 SVG，容器 `bg-blue-100 text-blue-600`
- 用户气泡：实心蓝底白字、细边框
- AI 气泡：白底、细灰边框、阴影
- 变式/推荐标签：`bg-blue-50 text-blue-700 border-blue-100`
- 举一反三按钮改为描边次按钮

### 7. 弹窗面板统一改造
所有面板（ReportPanel、MindMapPanel、ConversationPanel、MistakeBookPanel、QuizPanel）统一：
- 遮罩：`bg-slate-900/40 backdrop-blur-sm`
- 弹窗：`bg-white border border-slate-100 shadow-xl`
- 标题：`text-slate-900`
- 副标题/说明：`text-slate-500`
- 内部小卡片：`bg-slate-50 border border-slate-100`
- 图表颜色统一蓝色系
- 进度条背景 `bg-slate-200`，进度 `bg-blue-600`
- 保留绿色用于「已掌握/正确」语义，玫瑰色用于「删除/错误」语义

### 8. 不改动范围
- 后端 Java 代码不动
- SSE 流式输出逻辑不动
- MathText 公式渲染逻辑不动，只跟随父级颜色
- 业务逻辑（localStorage 历史、错题本状态、自测计分等）不动

## 验证步骤
1. 前端构建：
   ```powershell
   cd frontend
   npm run build
   ```
2. 视觉检查：
   - 页面整体为浅灰/白色背景，无深色网格和彩色渐变
   - 左侧学情看板显示 5 轴雷达图
   - 左侧功能按钮统一描边样式，「查看学习报告」实心主色
   - 右侧对话区为白卡，输入框白底带阴影，发送按钮蓝色突出
   - 欢迎消息左侧为机器人 SVG 头像
   - 用户气泡蓝底白字，AI 气泡白底灰边
3. 功能检查：
   - 发送消息、SSE 流式输出正常
   - 图片/文档上传、Ctrl+V 粘贴正常
   - 数学公式 KaTeX 渲染正常
   - 学习报告、自测、错题本、思维导图、历史对话弹窗打开正常
