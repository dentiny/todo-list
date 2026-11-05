# Daymark 免费多人协作版

现有 Android 界面保留。共享任务和事件存放在 Supabase PostgreSQL；SQLite 用来保存原来的单机数据，以及按账号、项目和清单隔离的离线副本。本版本联网编辑，离线查看。前台每 15 秒检查云端版本，数据有变更才下载，也会在重新打开 App 和保存后刷新；不是逐字实时共同输入。没有短信登录、付费服务器或自建后台。

## 1. 创建免费项目

在 https://supabase.com/dashboard 创建一个 **Free** 项目。不要升级 Pro，也不要开启付费附加功能。保存数据库密码，但不要把数据库密码放进 App。

在 SQL Editor 执行项目内 `supabase/migrations/202611040001_daymark.sql` 的全部内容，执行一次即可。脚本新建 `daymark` 私有 schema 和一个公开的受权限控制的 RPC；不会读取或修改其他应用的数据。不要重复执行，也不要为了修复权限错误给 `anon` 或 `authenticated` 开放私有表。

Data API 需要启用，`public` 在 exposed schemas 中；**不要**把 `daymark` 加入 exposed schemas。所有读写都经过 `public.daymark_rpc`，由登录身份、成员关系、服务端版本号和事务统一控制。私有表开启 RLS 且不向客户端授予访问权限。

## 2. 设置账号

在 Authentication 的 Email provider 中启用邮箱密码登录。

小范围亲友使用、不想配置邮件服务时，可以关闭 **Confirm email**，让每个人在 App 内自行创建账号。这样不验证邮箱归属；清单访问仍需要邀请码，并受成员权限控制。请不要用邮箱文字判断成员身份，本应用只使用 Supabase user ID。新账号密码至少 8 位。

如果需要验证邮箱，请配置自己的 SMTP，并保留 Confirm email；默认 SMTP 只向项目团队中预先授权的邮箱发信，不适合直接给普通协作者发确认邮件。邮箱确认后的网页跳转可能显示默认 localhost 地址；账号完成确认后可返回 App 直接登录。本版本不处理网页登录回跳或忘记密码；账号管理可在 Supabase Dashboard 完成。

协作者不需要成为 Supabase 项目管理员；不要把项目 Dashboard 管理权限当作 App 清单权限。

## 3. 连接 App

在 Supabase 项目的 Connect / API Keys 页面找到：

- Project URL，例如 `https://xxxx.supabase.co`。
- **Publishable key**（`sb_publishable_…`），或旧版 **anon key**。

不要使用 `sb_secret_…`、`service_role`、数据库连接字符串或数据库密码。App 会拒绝管理密钥。

安装更新版 APK，打开 **Shared lists**，填写 URL 和公开 key，点击 **Save project**，再 **Create account** 或 **Sign in**。项目配置在界面填写，不用重新编译 APK。

## 4. 创建清单并邀请

1. 填写 New list name，点击 **Create shared list**。清单时区取创建者手机当前时区，之后保持固定。
2. 点击 **Show invitation and members**，再 **Share invitation**。分享内容包含项目 URL、公开 key、邀请码和加入说明。
3. 对方安装同一版 App，连接同一个项目，注册／登录自己的账号，填写邀请码并点击 **Join shared list**。
4. 双方看到同一清单。前台通常在下次 15 秒刷新时看到对方的修改。

成员可以读取、添加、编辑、删除清单里的全部任务和事件。只有创建者能查看／替换邀请码和移除成员。邀请码持有者可以加入；替换邀请码只会让旧码失效，不会移除已有成员。移除成员后，云端立即拒绝其后续请求，在线设备下次刷新会退出清单。已下载到对方设备的数据无法远程擦除。

## 5. 导入旧 SQLite 数据

在目标共享清单中点击 **Import original local data** 并确认。

- 一次性上传这个手机原来的任务、每日历史和事件。
- 所有成员都能查看和编辑这些内容，请先确认清单选对。
- 原始 `daymark.db` 保留；导入成功后不会持续双向同步原来的本机数据。
- 同一手机向同一账号／项目／清单重复导入不会重复插入；重新安装会重置本机导入标记，所以不要卸载后重复导入旧数据库备份。
- 清单的离线副本和原始单机数据相互独立。切换 **Use original local data** 可以回到本机数据。
- 升级应直接覆盖安装；不要先卸载，卸载会删除原有本机数据。

## 同步与提醒的行为

云端统一执行任务跨天结转，按共享清单时区计算“今天”，避免不同设备重复结转。同一条任务／事件使用版本号保护：别人已经改过时，旧编辑会被拒绝；关闭编辑窗口、重新打开后再编辑。

共享事件显示、重复规则和提醒统一使用清单时区。每个设备独立保存已提醒状态，不会因为另一个人收到通知就跳过自己的提醒。仍然需要 Android 的通知和精确提醒权限。

本版本不会在后台持续联网收取修改。App 关闭期间，提醒使用最后成功同步的事件；要收到最新安排的提醒，需要重新打开 App 同步。Android 设备关机、强制停止或权限限制仍会影响提醒。

网络不通时显示最后同步的副本。无法确认保存结果时，打开 Shared lists 点击 **Retry pending save**，会复用同一个请求 ID，避免网络超时导致重复添加；在这个请求解决前，应用不接受其他新修改。不要通过卸载清除待确认保存状态。

Supabase 免费版有用量上限，低活跃项目可能在约一周后暂停，需要从 Dashboard 恢复；没有付费版的自动备份。定期自行备份云端数据。15 秒轮询会消耗少量请求流量；数据未变更时只返回版本信息。清单历史很大或编辑很频繁时仍应检查 Dashboard 用量，必要时延长间隔或改成逐条增量同步。

## 开发验证

Android：`./gradlew testDebugUnitTest lintDebug assembleDebug`。运行测试需要 Java 17 和 Android SDK。

数据库集成测试：在**空白、本地临时 PostgreSQL 数据库**中设置 `PGHOST`、`PGPORT`、`PGDATABASE` 后运行 `python3 supabase/tests/verify.py`。测试创建 mock Auth 和 3 个用户，检查权限隔离、共同编辑、版本冲突、重复请求、并发结转、导入事务和成员撤销。不要在真实 Supabase 项目上执行测试引导脚本。

本地测试不等于已配置真实云端项目。项目 URL／公开 key 配好后，还需用两个真实账号完成创建、邀请、双向修改、断网重连和提醒验收。

参考：[Free 额度](https://supabase.com/docs/guides/platform/billing-on-supabase)、[免费项目暂停](https://supabase.com/docs/guides/platform/free-project-pausing)、[邮箱认证设置](https://supabase.com/docs/guides/auth/general-configuration)、[SMTP 限制](https://supabase.com/docs/guides/auth/auth-smtp)。
