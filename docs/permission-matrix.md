# Android 权限矩阵（Extreme Mode）

这份文档只回答三件事：

1. 已申请并且已经在用的权限 / 能力有哪些
2. 已申请但现在还没真正用起来的有哪些
3. 还没有申请，但从个人信息采集器 MVP 视角值得补的有哪些

## 已申请且已使用

| 能力 | 权限 / Gate | 当前记录内容 | 备注 |
| --- | --- | --- | --- |
| 位置上下文 | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` | 经纬度、运动状态、城市、geofence | 当前核心采集链路 |
| 运动与活动识别 | `ACTIVITY_RECOGNITION` | walking / running / still 等状态 | 位置与传感器链路都在用 |
| 传感器融合 | `BODY_SENSORS`, `BODY_SENSORS_BACKGROUND` | 光照、距离、气压、步数、`activityState` | 目前是部分使用，不是完整 health 语义 |
| 环境音频 | `RECORD_AUDIO` | 本地 / 云端转写、音频事件 | 当前 MVP 核心能力之一 |
| 通知采集 | 通知监听设置授权 | 通知标题、正文、包名 | 高价值 collector |
| App 使用 / 前台应用 | Usage stats 设置授权 | 当前前台包名 | 已接通 |
| 通讯录 | `READ_CONTACTS` | 联系人总数 / 变化 | 当前只做到 count 级别 |
| 日历读取 | `READ_CALENDAR` | 最近 upcoming / ongoing 日程摘要 | 不是完整日历时间线 |
| 短信读取 | `READ_SMS` | 最新一条短信摘要 | 当前只取 inbox 最新一条 |
| 通话记录 | `READ_CALL_LOG` | 最新一条通话摘要 | 当前只取最新一条 |
| 照片 / 视频 / 音频库 | `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO` | 新增媒体元数据 | 现在只有 metadata，没有正文内容 |
| 附近设备与连接状态 | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES` | Wi-Fi / 已配对蓝牙 / 连接状态 | 当前是基础版 |
| NFC 状态 | `NFC` | 只记录 `nfcEnabled` 设备状态 | 不是 NFC 内容采集 |
| 勿扰策略状态 | `ACCESS_NOTIFICATION_POLICY` 设置授权 | DND filter / policy 状态 | 已进入 device-state snapshot |
| 悬浮窗状态 | `SYSTEM_ALERT_WINDOW` 设置授权 | overlay allowed 状态 | 现在只记录权限状态 |
| 修改系统设置状态 | `WRITE_SETTINGS` 设置授权 | write-settings allowed 状态 | 现在只是 capability status |
| 精确闹钟状态 | `SCHEDULE_EXACT_ALARM` 设置授权 | exact-alarm allowed 状态 | 当前调度已经够用 |
| 忽略电池优化状态 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 设置授权 | capability status | 当前主要用于保活 |
| Health Connect 状态 | Health Connect 授权 | health grant / sensor 相关信号 | 还不是完整 health record 摄入 |

## 已申请但未使用

| 能力 | 权限 / Gate | 当前状态 | 备注 |
| --- | --- | --- | --- |
| 日历写入 | `WRITE_CALENDAR` | 已申请，但没有写入逻辑 | 现在只读，不写 |
| 短信接收 | `RECEIVE_SMS` | 已申请，但没有短信广播接收器 | 当前不是实时接收 |
| 短信发送 | `SEND_SMS` | 已申请，但没有发送流程 | 还没接 action |
| 电话状态 | `READ_PHONE_STATE` | 已申请，但没有 telephony state collector | 现在没有相关记录 |
| 电话号码 | `READ_PHONE_NUMBERS` | 已申请，但没有读取逻辑 | 当前没有明显使用点 |
| 用户选择的视觉媒体 | `READ_MEDIA_VISUAL_USER_SELECTED` | 已申请，但没有独立 collector | 当前媒体链路没单独走它 |
| 全文件树访问 | `MANAGE_EXTERNAL_STORAGE` | 已申请，但没有文件树扫描 / 文档索引 | 现在只有媒体库元数据 |
| 相机采集 | `CAMERA` | 已申请，但没有相机 collector | 典型占坑权限 |
| 蓝牙广播 | `BLUETOOTH_ADVERTISE` | 已申请，但没有 advertise 逻辑 | 当前没有产品落点 |
| UWB | `UWB_RANGING` | 已申请，但没有 collector | 现在没有相关记录 |
| Accessibility UI 上下文 | 无障碍设置授权 | 已建模，但没有真实 `AccessibilityService` | 这是高价值缺口 |

## 尚未申请但值得申请

| 能力 | 建议的权限 / Gate | 为什么值得补 |
| --- | --- | --- |
| 屏幕截图 / 录屏 | MediaProjection 授权 | 如果要做完整手机上下文，这是最大的缺口之一 |
| 剪贴板 | Clipboard access | 简单但高价值的个人上下文来源 |
| 已安装应用清单 | PackageManager / 包可见性扫描 | 对设备画像和行为画像很有用 |

## 快速结论

- 当前已经真正跑起来的强项：位置、传感器、音频、通知、usage stats、通讯录 / 日历 / 短信 / 通话摘要、媒体元数据。
- 当前最明显的“已申请但没用”：`CAMERA`、`MANAGE_EXTERNAL_STORAGE`、`READ_PHONE_STATE`、`READ_PHONE_NUMBERS`、`WRITE_CALENDAR`、`SEND_SMS`、`RECEIVE_SMS`、Accessibility。
- 当前最值得继续补的“未申请能力”：屏幕截图 / 录屏、剪贴板、已安装应用清单。
