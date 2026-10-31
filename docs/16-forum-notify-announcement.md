# 16 · 论坛通知与官方公告（V12）

> 目标：把论坛接进站内通知中心；官方公告既要弹窗提示，也要留一条站内信。
> 迁移脚本：`src/main/resources/db/migration/V12__forum_notify_announcement.sql`

## 1. 论坛通知

论坛只做「和我有关」的通知，避免变成刷屏源。

| 事件 | 收件人 | 标题 | 开关 |
|---|---|---|---|
| 帖子有新评论 | 帖子作者（评论者本人除外） | `你的帖子有新评论` | `forum.notify.comment` |
| 评论被回复 | 被回复评论的作者（回复者本人除外） | `X 回复了你的评论` | `forum.notify.reply` |
| 版主隐藏 / 恢复 | 帖子作者 | `你的帖子被隐藏` / `你的帖子已恢复` | `forum.notify.moderate` |
| 版主置顶 / 取消置顶 | 帖子作者 | `你的帖子被置顶` / `你的帖子已取消置顶` | `forum.notify.moderate` |
| 版主删除帖子 | 帖子作者（删除者本人除外） | `你的帖子被删除` | `forum.notify.moderate` |
| 版主删除评论 | 评论作者（删除者本人除外） | `你的评论被删除` | `forum.notify.moderate` |

细节：

- 一条回复只发一条通知：先判断是否回复他人（发「回复了你的评论」），若被回复人恰好是帖子作者，
  就不再额外发「你的帖子有新评论」。
- 所有通知的分类是 `FORUM`，`biz_type = FORUM_POST / FORUM_COMMENT`，链接直达 `/forum/posts/{id}`。
- 通知在业务事务内调用、写入用 `REQUIRES_NEW` 独立事务，通知失败不会让发帖/评论回滚。
- 四个开关都在「管理 → 设置 → 论坛与公告」，默认全开。

## 2. 官方公告

### 2.1 公告分类

`forum_category.announcement = TRUE` 的分类即公告分类，迁移时自动创建「通知公告」（若已存在同名或
已有公告分类则跳过）。

- 在公告分类发帖、或把帖子改到公告分类，都需要 `forum:announce` 权限（默认只给 `ADMIN`）；
  其他账号会收到 403，前端发布按钮同时置灰并提示换分类。
- 分类管理对话框里勾选「作为公告分类」即可把任意分类改为公告分类。
- 列表与帖子页对公告分类的帖子打「公告」标，侧栏分类名旁也带标记；侧栏不再显示各分类的帖子数。

### 2.2 广播

发布公告时由 `ForumPostService.publishAnnouncement` 调用
`NotificationService.broadcast(...)`，内部是一条 SQL：

```sql
INSERT INTO notification (user_id, category, level, title, body, link_url, biz_type, biz_id,
                          popup_required, created_at)
SELECT u.id, 'ANNOUNCEMENT', 'INFO', ..., #{now}
  FROM sys_user u
 WHERE u.status = 'ACTIVE' AND u.id != #{excludeUserId}
   [AND EXISTS (角色过滤)]
```

- 不逐用户开事务，百人/千人量级也在一次 SQL 内完成；发布者本人被排除。
- 广播不生成渠道投递记录：邮件/微信需要逐用户发送，放在请求里会拖慢发公告；
  后续接渠道应改为异步消费 `notification` 表，而不是在请求内循环发送。
- 广播失败只记日志，公告本身仍然发布成功；审计记 `FORUM_ANNOUNCEMENT_PUBLISH`（含收件人数）。

### 2.3 弹窗

`notification.popup_required` 标记「必须让用户看到」。前端 `AnnouncementPopup`（挂在 `AppShell`）：

- 打开页面时请求 `GET /notifications/popups`，取「未读 + 需要弹窗」的公告，按发布顺序排队，
  一次展示一条，标题带「官方公告」，多条目显示「第 n / m 条」；
- 点「我知道了」/「查看详情」（或 Esc、右上角关闭）都会把该条标为已读，因此同一条公告不会反复弹；
- 已读后仍保留在通知中心，可在「通知 → 公告」分类里回看；
- 每 120 秒轮询一次（页面隐藏时不请求），新公告最多两分钟内出现。

关闭 `announcement.popup` 后公告只进站内信，不再弹窗，适合「通知量大、不想打扰」的时期。

## 2.4 限时置顶（V13）

版主在帖子详情点「置顶 / 调整置顶」打开对话框，选择时长：1 天、3 天、7 天（默认）、30 天、长期。

- 后端 `PATCH /forum/posts/{id}/status` 接收 `pinned` 与 `pinnedDays`，`pinned_until = now + days`，
  `pinnedDays = 0` 表示长期（`NULL`）。
- 排序 SQL 用 `(pinned = TRUE AND (pinned_until IS NULL OR pinned_until > NOW(3)))`，
  到期自动回到普通排序，不依赖定时任务；过期数据在接口里表现为 `pinned = false`。
- 只改状态（隐藏/恢复）时不会重置置顶期限；若置顶已过期，则顺手清掉 `pinned` 与 `pinned_until`。
- 帖子详情在标题下方显示「置顶至 2026/11/06 19:10」或「置顶（长期）」。

## 2.5 阅读抽屉与头像

- 列表点帖子不再整页跳转：地址栏写 `?post=ID`，从右侧滑出抽屉（桌面 760px、手机整屏），列表留在原处；
  抽屉里直接复用帖子页组件（`PostView` 的 `embedded` 模式：隐藏面包屑、删除后通知关闭），
  评论、回复、编辑、置顶、删除都在抽屉内完成。
- 列表、帖子头部、评论与回复都显示头像：后端在帖子/评论视图里带 `authorAvatarVersion`，
  前端统一用 `UserAvatar` 组件按「用户 + 版本」取图并缓存，没有头像回落姓名末字。
- 直接访问 `/forum/posts/:id` 仍是完整页面（通知、个人资料里的链接走这条），抽屉只服务列表内阅读。

## 3. 接口变更

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/notifications` | 新增 `category` 过滤（如 `ANNOUNCEMENT`） |
| GET | `/notifications/popups` | 未读且要求弹窗的公告，默认最多 5 条 |
| POST | `/forum/posts` | 公告分类发帖需 `forum:announce`，成功即广播 |
| PUT | `/forum/posts/{id}` | 目标或原分类为公告分类时同样校验权限 |
| POST/PUT | `/forum/categories` | 请求体新增 `announcement` |

`NotificationView` 新增 `popupRequired`；`ForumCategoryView` 新增 `announcement`。

## 4. 权限与设置

- 新权限：`forum:announce`（在公告分类发布官方公告），已授予 `ADMIN`。
- 新设置项：
  - `forum.notify.comment` / `forum.notify.reply` / `forum.notify.moderate`（默认 `true`）
  - `announcement.popup`（默认 `true`）

## 5. 验证记录（2026-10-30，本机 MySQL 8 + Chrome 390×844）

`mvn package`、`vue-tsc -b`、`npm run build` 均通过；`.localrun/e2e-v12.py` 全流程通过：

1. 管理员在「通知公告」发帖 → 学生、教师各收到 1 条 `ANNOUNCEMENT` 站内信，`popupRequired = true`；
2. 学生关掉弹窗（标已读）后 `/notifications/popups` 为空，通知中心「公告」分类仍可见；
3. 学生往公告分类发帖 → `403 FORBIDDEN`；
4. 学生评论公告 → 管理员收到「你的帖子有新评论」；管理员回复该评论 → 学生收到「系统管理员 回复了你的评论」；
5. 管理员隐藏学生帖子（带理由）→ 学生收到「你的帖子被隐藏」，正文包含管理员说明；
6. 关闭 `announcement.popup` 后再发公告 → 站内信正常、弹窗列表无该条，随后恢复开关。

UI 截图确认：移动端弹窗排版正常（标题、正文、时间、两个按钮），通知页三个页签可用，
论坛侧栏与列表都有公告标记，设置页「论坛与公告」四个开关渲染正常，桌面端无横向溢出。

## 6. 已知限制

- 广播面向全部启用账号，暂不支持按学院/年级定向；`broadcast` 已预留 `roleCode` 参数与 SQL 分支。
- 公告发布后不提供「撤回并删除已发通知」，只能在论坛删除帖子（通知仍在，点进去会是 404）。
- 论坛通知不合并同类（连续 5 条评论就是 5 条通知），后续可做按业务聚合或摘要推送。
