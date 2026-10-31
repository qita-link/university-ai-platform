# 站内通知、考勤申诉与教室借用

2026-10-30 · 对应迁移 `V11__init_notification_appeal_booking.sql`

这一轮补的是三处「流程断点」：状态变了没人知道、被记旷课无处申诉、查到空教室却不能借。

---

## 1. 模块与代码位置

| 模块 | 包 | 说明 |
|---|---|---|
| 通知中心 | `com.campus.platform.notification` | 站内信、未读数、投递记录、渠道扩展点 |
| 考勤申诉 | `com.campus.platform.attendance`（Appeal 部分） | 申诉、复核、我的考勤、缺勤预警、名单同步 |
| 教室借用 | `com.campus.platform.classroom`（Booking 部分） | 借用申请、冲突校验、审批、取消 |

---

## 2. 数据表（V11）

| 表 | 用途 | 关键约束 |
|---|---|---|
| `notification` | 站内信 | `idx(user_id, read_at, id)`；只追加，已读用 `read_at` 表达 |
| `notification_delivery` | 渠道投递记录 | `UNIQUE(notification_id, channel)`；状态 PENDING/SENT/FAILED/SKIPPED |
| `attendance_appeal` | 考勤申诉 | `UNIQUE(record_id)`，一条明细只能申诉一次；`version` 乐观锁 |
| `attendance_warning` | 缺勤预警去重 | `UNIQUE(student_id, course_id, absent_count)` |
| `room_booking` | 教室借用 | 索引 `(room_id, booking_date, status)`；时间区间在应用层校验 |

新增权限：`attendance:appeal`（学生/学生会）、`room:book`（学生/教师/学生会）、`room:approve`（管理员）。
新增设置：缺勤预警阈值（3）、申诉期限（7 天）、邮件/微信渠道开关（默认 false）、借用可提前天数（14）。

---

## 3. 通知中心

### 3.1 写入路径

业务状态变更后调用 `NotificationService.notify / notifyAll`：

- 通知在 `NotificationWriter` 的**独立事务**（REQUIRES_NEW）中写入；
- 写失败只记日志，不会把请假审批、成绩发布等主流程标记为 rollback-only；
- 站内信一定会落库，渠道投递失败只影响 `notification_delivery` 的状态。

### 3.2 渠道扩展点（为邮件/微信预留）

```
NotificationChannelSender
  ├─ channel()   渠道
  ├─ available() SDK/密钥/模板是否就绪
  └─ send()      实际发送
```

- 渠道开关：`notify.channel.email.enabled`、`notify.channel.wechat.enabled`；
- 开启但发送器不可用时，投递记录写 `SKIPPED`（原因「渠道尚未接入」），站内信不受影响；
- 当前 `EmailChannelSender`、`WechatChannelSender` 是占位实现，接入时只需替换 `send`；
- 运维可通过 `GET /notifications/deliveries`（`system:config`）查看投递状态。

### 3.3 已接入的通知点

| 业务 | 收件人 | 触发 |
|---|---|---|
| 请假提交 / 流转 | 当前环节审批人（教师或导员） | 提交、上一级通过 |
| 请假通过 / 驳回 | 学生 | 任一结论 |
| 成绩发布 | 全部选课学生 | 发布成功后 |
| 反馈回复 | 提交人（匿名也通知本人） | 回复成功后 |
| 考勤申诉提交 | 任课教师 + 班级导员 | 学生提交 |
| 考勤申诉结论 | 学生 | 通过/驳回 |
| 缺勤预警 | 学生 + 班级导员 | 考勤提交后累计旷课达阈值 |
| 教室借用提交 | 拥有 `room:approve` 的用户 | 提交 |
| 教室借用结论 | 申请人 | 通过/驳回 |

链接统一用前端路由（如 `/leave?tab=review`、`/services?tab=booking-review`），手机端点通知可直接落到对应页签。

### 3.3.1 顶栏通知抽屉

点顶栏铃铛不再整页跳到通知中心，而是从右侧滑出一块抽屉（`NotificationDrawer.vue`，Teleport 到 body）：

- 宽度 `min(420px, 88vw)`，手机上 90vw，只遮住一部分页面，背景压暗不跳转；
- 打开时拉取最近 30 条（全部 / 未读 / 公告 三个页签），锁定页面滚动、Esc 关闭、Tab 焦点限制在抽屉内，
  关闭后焦点回到铃铛；
- 点条目标为已读并跳到对应页面；底部「全部已读」与「查看全部」（进 `/notifications` 翻页回看）；
- 未读条目带浅底色与加粗标题，未读数与顶栏红点共用 `notify` store。

`/notifications` 页面保留，作为「查看全部」的落点与直接访问入口；抽屉只承担「扫一眼最近消息」。

### 3.4 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/notifications` | 我的通知，`unreadOnly` 筛选 + 分页 |
| GET | `/notifications/unread-count` | 未读数（顶栏轮询，60 秒） |
| POST | `/notifications/{id}/read` | 标记一条已读 |
| POST | `/notifications/read-all` | 全部已读 |
| GET | `/notifications/deliveries` | 投递记录（`system:config`） |

---

## 4. 考勤申诉与缺勤预警

### 4.1 申诉流程

```
学生：我的考勤 → 对旷课/迟到提交申诉（可写希望改为出勤/迟到/请假）
        ↓
教师 / 导员 / 管理员：考勤申诉 → 通过（选目标状态）或驳回
        ↓
通过：attendance_record.status 改为目标状态，备注「申诉通过：…」；通知学生
驳回：仅记录意见；通知学生
```

约束：

- 只能申诉本人明细；只有 `ABSENT/LATE` 可申诉；
- 申诉期限 `attendance.appeal.window.days`（默认 7 天，0 表示不限制）；
- 一条明细只能申诉一次（数据库唯一键）；
- 复核权限按数据归属：管理员全部、教师任教课程、导员所带班级；
- 复核使用 `WHERE status='PENDING'` 条件更新，并发只有一人成功。

### 4.2 缺勤预警

- 考勤提交（FINAL）后，对每个旷课学生统计「该课程累计旷课次数」；
- 达到 `attendance.warning.threshold`（默认 3）时：
  - 给学生发「缺勤提醒」；
  - 给班级导员发「学生缺勤预警」；
- 以 `(学生, 课程, 次数)` 去重：同一门槛只提醒一次，不会每次课重复轰炸；
- 阈值可在「管理 → 设置 → 考勤与通知」修改。

### 4.3 名单同步

考勤场次创建时保存的是当时的班级名单。后加入班级的学生不会自动出现在旧场次里，
因此提供「同步名单」：把缺失学生补进该场次，已通过请假仍自动标为 `LEAVE`。

### 4.4 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/attendance/me` | 本人考勤明细（含申诉状态、是否可申诉） |
| POST | `/attendance/appeals` | 提交申诉 |
| GET | `/attendance/appeals/me` | 我的申诉 |
| GET | `/attendance/appeals` | 待我复核（按权限与归属过滤） |
| POST | `/attendance/appeals/{id}/decide` | 通过/驳回 |
| POST | `/attendance/sessions/{id}/sync-roster` | 同步名单 |

---

## 5. 教室借用

### 5.1 流程

```
空教室列表 → 点「借用」→ 填日期/节次/用途/人数 → 提交（PENDING）
        ↓
管理员「借用审批」→ 通过（APPROVED）/ 驳回（REJECTED）
        ↓
申请人：「我的借用」可取消（仅 PENDING）；结果通过通知告知
```

### 5.2 冲突校验（两层）

1. **申请时**：
   - 调用空教室同一套口径检查课表占用（已发布排课 + 课程排课）；
   - 检查同一教室是否已有 `APPROVED` 借用重叠；
2. **审批时**：
   - 再检查一次已批准借用，避免两个申请分别提交后被同时批准。

其他约束：不能借过去日期；最多提前 `room.booking.advance.days`（默认 14 天）；结束节次不早于开始。

### 5.3 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/room-bookings` | 申请借用（`room:book`） |
| GET | `/room-bookings/me` | 我的借用 |
| GET | `/room-bookings` | 待审批（`room:approve`，默认 PENDING） |
| POST | `/room-bookings/{id}/decide` | 通过/驳回 |
| POST | `/room-bookings/{id}/cancel` | 取消（本人、PENDING） |

---

## 6. 前端

| 路由 / 位置 | 内容 |
|---|---|
| 顶栏铃铛 | 未读红点（60 秒轮询，页面隐藏时不请求） |
| `/notifications` | 全部/未读筛选、点击已读并跳转、全部已读 |
| `/leave?tab=mine-attendance` | 我的考勤：状态、申诉状态、申诉入口 |
| `/leave?tab=appeals` | 考勤申诉复核：通过时选择目标状态 |
| `/leave?tab=attendance` | 场次列表新增「同步名单」 |
| `/services?tab=bookings` | 我的借用、取消 |
| `/services?tab=booking-review` | 借用审批 |
| `/services?tab=rooms` | 每间空教室新增「借用」按钮 |
| `/admin?tab=settings` | 新增「考勤与通知」：预警阈值、申诉期限、渠道开关 |

---

## 7. 验证记录（2026-10-30，本机 MySQL 8.0）

- Flyway v10 → v11 成功；
- 端到端（`.localrun/e2e-v11.py`）全部通过：
  - 学生提交申诉 → 重复提交 409 → 导员可见 → 管理员通过 → 明细改为出勤、学生收到通知；
  - 连续三次旷课 → 学生收到「缺勤提醒」、导员收到「学生缺勤预警」；
  - 名单同步成功；
  - 学生借用 → 管理员可见并批准 → 同一时段再借 409 → 另一笔可取消；
  - 全部已读后未读数为 0；投递记录接口正常；
- 渠道开关验证：开启邮件后产生一条 `EMAIL / SKIPPED / 渠道尚未接入` 的投递记录，站内信正常；
- 前端 UI 实测：教师复核申诉、管理员审批借用、设置保存均成功；手机端相关页面无横向溢出。

---

## 8. 已知限制与后续

- 邮件/微信只完成了渠道抽象与投递记录，真实发送器（SMTP、服务号模板消息）尚未接入；
- 投递失败没有自动重试队列，目前靠运维查看记录后手动处理；
- 申诉不支持上传证明材料（病历、活动名单），后续可复用文件存储扩展；
- 借用暂不支持周期性申请（每周例会），也不支持按设备筛选教室；
- 通知未做批量已读以外的偏好设置（例如关闭某类通知）。
