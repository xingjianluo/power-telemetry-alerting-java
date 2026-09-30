# 设备功率遥测接入、状态计算与告警服务（单机本地版）

纯内存 + 本地 JSON 快照的 Spring Boot 服务：接收设备批量上报的电压/电流/功率遥测，
按时间窗口计算平均功率、峰值功率与能耗，并对过压、欠压、过流、过功率、采样中断
进行带抖动抑制的触发/恢复判定。**不依赖任何外部消息中间件、数据库或云端服务**，
可在本地独立运行、独立验证。

## 1. 数据语义

### 1.1 上报

`POST /api/devices/{deviceId}/telemetry`

```json
{
  "points": [
    {"timestamp": 1790000000000, "voltage": 220.0, "current": 10.1, "power": 2222.0},
    {"timestamp": 1790000001000, "voltage": 220.1, "current": 10.0, "power": 2201.0}
  ]
}
```

- `timestamp`：采样时间，**epoch 毫秒**（不是接收时间）；
- `voltage/current/power`：电压(V)、电流(A)、功率(W)，均要求有限且非负；
- 一次请求属于 URL 中的同一台设备；批内逐条处理，**一个坏点不会拖垮整批**。

### 1.2 去重与乱序（以采样时间为准，不以上报顺序为准）

- 同设备 + 同采样时间 = 同一个采样点：跨批次、批内重复都返回 `DUPLICATE`，**不重复累计**；
- 允许在「乱序迟到窗口」内迟到（默认 5 分钟）：迟到点按采样时间插入正确位置，
  系统对全部点按采样时间做一次**确定性重放**，因此
  「数据以任意顺序到达」与「数据本就按序到达」的统计和告警结论完全一致、可复现；
- 批次处理以**设备级公平锁**串行化，同一设备多批次并发上报不会漏计或重计；
  不同设备各自独立，互不阻塞。

### 1.3 逐条拒绝原因（可区分）

| code | 含义 |
|---|---|
| `MISSING_FIELD` | 设备号/采样时间/电压/电流/功率为 null |
| `INVALID_VALUE` | NaN、无穷、电压/电流/功率为负 |
| `FUTURE_TIMESTAMP` | 采样时间晚于「当前时间 + 未来容忍偏差」 |
| `OUT_OF_RANGE` | 采样时间早于「当前时间 − 最大历史范围」 |
| `DUPLICATE` | 同设备同采样时间已存在（跨批或批内） |
| `TIME_ROLLBACK` | 早于该设备水位线且超过允许乱序迟到窗口 |

响应里每条拒绝都带批次下标 `index`、采样时间和中文 `reason`；
`accepted`/`duplicate` 分别计数。

### 1.4 窗口统计

- 固定长度滚动窗口（默认 60s），左闭右开 `[n*L, (n+1)*L)`；
- 窗口封存：当水位线推进到迟到点不可能再落入某窗口后，窗口标记 `closed`，不再变更；
- **平均功率**：相邻采样按时间做梯形积分，除以实际采样覆盖时长（单点窗口取该点功率）；
- **峰值功率**：窗口内最大功率；
- **能耗**：梯形积分 `Σ (P_i+P_{i+1})/2 × Δt`，单位 Wh。

查询：`GET /api/devices/{deviceId}/windows?limit=20`（按窗口起始时间倒序）。

## 2. 告警规则

| 类型 | 触发 | 恢复 |
|---|---|---|
| `OVER_VOLTAGE` 过压 | 电压 > 阈值，连续 N 点（默认 3） | 告警中连续 M 点不越限（默认 3） |
| `UNDER_VOLTAGE` 欠压 | 电压 < 阈值，连续 N 点 | 连续 M 点不越限 |
| `OVER_CURRENT` 过流 | 电流 > 阈值，连续 N 点 | 连续 M 点不越限 |
| `OVER_POWER` 过功率 | 功率 > 阈值，连续 N 点 | 连续 M 点不越限 |
| `SAMPLE_INTERRUPT` 采样中断 | 相邻采样点间隔 > 最大间隔；或墙钟扫描发现距最后一点超时 | 中断后收到第一个新采样点即恢复 |

要点：

- **抖动抑制**：未达连续次数只推进计数，不产生事件；恢复计数中途再次越限会清零，
  因此短暂抖动不会导致告警反复横跳；
- 每类告警各自独立计数，互不影响；每次触发/恢复都生成带**判定依据**（实际值、阈值、
  连续次数、间隔、采样时间）的事件，事件编号在设备内连续；
- 采样中断有两条互补检测路径（到点间隔检测 + 墙钟定时扫描），共用同一状态机，
  不会重复触发；
- 设备可单独停用告警（`POST /api/devices/{id}/alerts {"enabled":false}`）：
  停用时数据照常接入、历史事件保留，只是不再产生新事件；
- 查询：`GET /api/devices/{deviceId}/alerts?state=ACTIVE|RECOVERED`。

## 3. 配置方式

所有参数在 `src/main/resources/application.properties`，可用环境变量
`POWER_SERVICE_*` 或命令行 `--power.service.xxx=yyy` 覆盖：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `window-ms` | 60000 | 统计窗口长度(ms) |
| `max-history-ms` | 86400000 | 允许最大历史范围(ms) |
| `future-tolerance-ms` | 5000 | 允许未来时钟偏差(ms) |
| `allowed-lateness-ms` | 300000 | 允许乱序迟到窗口(ms)，超出判 `TIME_ROLLBACK` |
| `retained-windows` | 2880 | 内存保留窗口数 |
| `trigger-consecutive` / `recover-consecutive` | 3 / 3 | 告警触发/恢复连续点数 |
| `interruption-sweep-ms` | 1000 | 采样中断墙钟扫描周期(ms) |
| `default-over-voltage` | 253.0 V | 全局默认过压阈值 |
| `default-under-voltage` | 198.0 V | 全局默认欠压阈值 |
| `default-over-current` | 32.0 A | 全局默认过流阈值 |
| `default-over-power` | 7000.0 W | 全局默认过功率阈值 |
| `default-max-sample-gap` | 30000 ms | 全局默认最大采样间隔 |
| `snapshot-file` | data/snapshot.json | 本地快照路径 |
| `snapshot-after-each-batch` | true | 每批次后原子落盘 |
| `auth-enabled` | false | 是否开启 Bearer 令牌鉴权（本地验证默认关闭） |
| `admin-token` | 空 | 管理员令牌 |

**设备级配置**（null 字段表示回落全局默认；非法值返回 400 且不影响既有状态）：

```bash
curl -X POST http://127.0.0.1:8080/api/devices/meter-01/thresholds \
  -H 'Content-Type: application/json' \
  -d '{"overVoltage":270,"underVoltage":180,"overCurrent":35,"overPower":8000,"maxSampleGap":30000}'
```

校验规则：启用项必须为有限正数；欠压必须小于过压；间隔必须为正。
未注册设备的配置操作返回 404。

**鉴权（可选）**：`auth-enabled=true` 后，
`/api/devices/register`、`/api/devices` 仅管理员令牌可用；
单设备的上报/配置/查询允许管理员或该设备自己的 Bearer 令牌；
越权返回 403、无令牌返回 401，被拒绝的请求不会改动任何状态。
注册：`POST /api/devices/register {"deviceId":"meter-01","name":"1号柜"}`，
返回的设备令牌仅展示一次（服务端只存 SHA-256 哈希）。

## 4. 持久化与重启恢复

- 每个接入批次后把全部设备状态（配置、采样点、窗口、告警状态机计数、事件历史、令牌哈希）
  原子写入 `snapshot-file`（先写 `.tmp` 再 move，损坏不会覆盖旧文件）；
- 服务启动时先加载快照再对外服务，重启后窗口统计、阈值、ACTIVE 告警状态、事件历史全部恢复；
- 重启后重复上报历史采样点仍按 `DUPLICATE` 拒绝，不会重复累计。

## 5. 本地运行与验证

要求 JDK 21+、Maven 3.9+。

```bash
# 构建与全部单测（19 个用例：重复/乱序、阈值抖动、中断、重启恢复、并发写入、越权、HTTP 全链路）
mvn test

# 启动（随机端口见日志；指定端口：）
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=18099"
```

冒烟脚本（另开终端，毫秒时间戳）：

```bash
B=http://127.0.0.1:18099
NOW=$(python3 -c 'import time;print(int(time.time()*1000))')

# 上报（含好点、缺字段、非法值、未来时间）
curl -s -X POST $B/api/devices/meter-01/telemetry -H 'Content-Type: application/json' -d "{
 \"points\":[
  {\"timestamp\":$NOW,\"voltage\":220,\"current\":10,\"power\":2200},
  {\"timestamp\":$((NOW+1000)),\"voltage\":260.5,\"current\":10.2,\"power\":2650},
  {\"timestamp\":$((NOW+2000)),\"current\":10,\"power\":2300},
  {\"timestamp\":$((NOW+3000)),\"voltage\":-1,\"current\":10,\"power\":2300}
 ]}"

# 窗口统计 / 告警历史
curl -s "$B/api/devices/meter-01/windows?limit=5"
curl -s "$B/api/devices/meter-01/alerts"

# 非法配置 → 400；未注册设备 → 404
curl -i -X POST $B/api/devices/meter-01/thresholds -H 'Content-Type: application/json' \
  -d '{"overVoltage":100,"underVoltage":200}'
curl -i $B/api/devices/ghost/config
```

测试日志在控制台与 `target/surefire-reports/`，接入/告警日志均带
设备号、采样时间、窗口、连续计数与判定依据，便于追溯。

## 6. 代码结构

```
model/        采样、窗口统计、告警事件、阈值、拒绝原因等领域类型
engine/       RecordValidator(逐条校验) IngestService(接入+重放) WindowManager/WindowAccumulator(窗口积分)
              AlertEvaluator(四类电气告警去抖状态机) InterruptionMonitor+Scheduler(中断检测/墙钟扫描)
              StatsQueryService DeviceConfigService
store/        DeviceRegistry + DeviceState(每设备一把锁)
persistence/  SnapshotStore(JSON 原子落盘/恢复) SnapshotBootstrap
security/     AdminTokenService + TokenAuthenticator(Bearer 设备/管理员授权)
api/          REST 控制器、DTO、统一错误响应
config/       ServiceProperties(全部可调参数) 与 Bean 装配
```
