# GitHub 分支保护配置清单（照着点即可）

> 目标：`dev` / `main` 只有**你自己能写、能合并**，其他人**只读**。
> 仓库：`https://github.com/skychin4216/StockAnalysis`（当前为 **public**）
> 配套文件：`.github/CODEOWNERS`（已建好）

---

## 〇、先明确一件事（避免误解）

| 你想达到的效果 | 能不能做到 | 怎么做 |
|---|---|---|
| 别人**不能 push / 不能 merge** 到 dev、main | ✅ 能 | 下面「二、三」 |
| 别人**必须你批准**才能改 | ✅ 能 | 下面「四」 CODEOWNERS |
| 别人**不能 fork** | ❌ 不能 | 公开仓库天然允许 fork（fork 不影响你的分支）|
| 别人**连看都看不到** | ⚠️ 要转私有 | Settings → Danger zone → Change visibility → Private |

**结论**：公开仓库下你能锁死「写」，锁不住「看 / fork」。
**如果要彻底不给他们看，只能把仓库转 private**（GitHub Free 已支持私有仓库 + 无限协作者）。

---

## 一、进入设置页

1. 打开 `https://github.com/skychin4216/StockAnalysis`
2. 点顶部 **Settings**（需要你是仓库 owner/admin）
3. 左侧菜单：**Rules → Rulesets**
   （老版界面是 **Branches**，两种都行；下面以新版 Rulesets 为准，更细）

---

## 二、给 `main` 建规则

1. **New ruleset → New branch ruleset**
2. **Ruleset Name**：`protect-main`
3. **Enforcement status**：`Active`（不要选 Evaluate）
4. **Target branches**：
   - 点 `Add target` → `Include by pattern` → 填 `main`
5. **Branch rules**（逐个勾选）：

| 勾选项 | 作用 |
|---|---|
| ✅ **Restrict deletions** | 禁止删分支 |
| ✅ **Block force pushes** | 禁止强推（防历史被篡改）|
| ✅ **Require a pull request before merging** | 禁止直接 push |
| 　　└ Required approvals：`1` | 至少 1 人批准 |
| 　　└ ✅ **Dismiss stale pull request approvals when new commits are pushed** | 改了代码就得重新批准 |
| 　　└ ✅ **Require review from Code Owners** | **让 CODEOWNERS 生效** |
| ✅ **Require status checks to pass** | 按需；本项目无 CI，可先不勾 |
| ✅ **Require signed commits** | 按需（推荐勾，防冒名提交）|
| ✅ **Block creations** | 禁止别人新建同名分支绕过 |

6. 页面底部 **Bypass list**（关键）：
   - 点 `Add bypass`
   - 只加 **你自己** `skychin4216`
   - **不要**勾 "Allow repository admins to bypass"（勾了等于自己能绕过，规则形同虚设）

7. 点 **Create**

---

## 三、给 `dev` 建规则（同上，只改名字和分支名）

1. **New ruleset → New branch ruleset**
2. Name：`protect-dev`
3. Enforcement：`Active`
4. Target branches：`Include by pattern` → `dev`
5. 勾选项与「二」第 5 步**完全一致**
6. Bypass list：同样**只加你自己**
7. **Create**

> 💡 顺带建议：把 `feature/*` 也加一条只勾 `Restrict deletions + Block force pushes` 的宽松规则，防止误删。

---

## 四、确认 CODEOWNERS 已生效

1. 仓库页面 → **Settings → Rules** → 点进 `protect-main`
2. 确认 **Require review from Code Owners** 是勾选状态
3. 根目录下 `.github/CODEOWNERS` 已存在（本项目已建），内容默认 `* @skychin4216`

**验证方法**：让别人开一个 PR 到 dev，若出现
`Review required · Required reviewers: skychin4216 (Code owner)` 即成功。

---

## 五（可选）、给 `maosheng` 也加保护

`maosheng` 是**给别人看的只读快照**。建议也保护起来，避免被误改：

- Target：`maosheng`
- 勾选：`Restrict deletions` + `Block force pushes` + `Require a pull request before merging`
- Bypass list：只加你自己

> 注意：我们后续同步 dev → maosheng 仍由你（或我以你的凭据）执行，**不受影响**。

---

## 六、做完以后，我们这边的日常流程

| 场景 | 操作 |
|---|---|
| 日常开发 | 在 `dev` 上提交（只有你能推）|
| 要给别人最新版本 | 由我执行「删 maosheng → 从 dev 重建 → 推送」（**会先跟你确认**）|
| 别人想改代码 | 让他们 fork 后开 PR → 你（Code Owner）批准才合入 |

> ⚠️ 同步 maosheng 前我会先扫一遍有没有私密内容（AI 密钥 / 交易记录 / 实仓），
> 确认干净再推 —— 这正是我们做 git-crypt 加密的意义。

---

## 七、常见问题

**Q：我（owner）自己也会被挡住吗？**
A：会。若 Bypass list 里加了自己 + 勾选了 "Allow admins to bypass"，则你能绕过；
若**没勾** admins bypass，则你自己也要走 PR。**推荐：Bypass 只加自己、不勾 admins 绕过**。

**Q：CODEOWNERS 里写了不存在的用户名会怎样？**
A：该条目静默失效（GitHub 不报错）。所以加人前先确认用户名拼写。

**Q：这条规则能防住 GitHub Actions / 机器人吗？**
A：Rulesets 对 Actions 也生效（除你加进 Bypass 的）。我们自己的守护不推 GitHub，无影响。
