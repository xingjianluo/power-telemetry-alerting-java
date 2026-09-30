# 设备功率遥测接入、状态计算与告警服务

纯本地、无外部依赖（不需要消息中间件 / 数据库 / 云端）的设备功率遥测服务：
批量接收电压、电流、功率与采样时间，按时间窗口计算平均功率、峰值功率与能耗，
对过压 / 欠压 / 过流 / 过功率 / 采样中断做带防抖的告警触发与恢复，
并把全部状态落盘到本地 JSON，服务重启后状态可预测、可复现。

## 1. 能力总览

- **批量上报**：一个请求可携带同一设备的多条记录；逐条给出结论，**一个坏点不会拖垮整批**。
- **去重幂等**：同一设备 + 同一采样时间 + 相同读数只计一次；重复重放（含并发重放）不重复累计、不推进版本。
- **乱序归位**：记录按采样时间（`TreeMap`）存放，迟到点到达后对保留窗口整体重算，
  因此"先发晚的、后补早的"与"按顺序到"得到完全一致的窗口结果。
- **可区分的拒绝原因**：字段缺失、非法数值、未来时间、超龄历史、明显时间回退、超保留窗口、
  完全重复、同时间戳读数冲突，均返回不同枚举原因与人读消息。
- **窗口聚合**：固定窗口、epoch 对齐；平均功率（算术均值）、峰值功率，
  能耗用相邻采样点间**梯形积分**（线性功率假设，单位 Wh），跨窗口边界按边界切分。
- **告警状态机**：连续越限 N 次才触发、连续恢复 M 次才恢复（防抖）；
  事件带 `basis` 判定依据，全程留痕；迟到点只修统计、**不倒翻告警**（水位线机制）。
- **采样中断**：后台定时扫描"最后采样时间距今 > 超时阈值"触发，收到新点即恢复。
- **并发一致**：每台设备一把锁，同设备批次串行（不重不漏），不同设备完全并行。
- **设备级配置**：可单独调阈值 / 防抖次数 / 中断超时 / 告警开关；先校验后生效，
  非法配置 400 且不动既有状态，写接口需管理令牌（401）。
- **重启恢复**：每设备一个 JSON 文件，临时文件 + 原子 move；重启后采样点、窗口、
  告警状态、水位线、事件、设备配置全部恢复；损坏文件自动隔离为 `.corrupt-*`。

## 2. 数据语义

### 2.1 采样点

| 字段 | 单位 | 约束 |
| --- | --- | --- |
| `timestampMs` | 毫秒（epoch） | 必填 |
| `voltageV` | V | 必填，有限数，>= 0 |
| `currentA` | A | 必填，有限数，>= 0 |
| `powerW` | W | 必填，有限数，允许负（馈网场景） |

同一设备以 `timestampMs` 为唯一键：

- 读数完全相同 -> `DUPLICATE`，幂等忽略；
- 读数不同 -> `REJECTED / CONFLICT`，不会用新值覆盖旧值。

### 2.2 时间校验（按顺序执行，原因互斥）

| 拒绝原因 | 含义 |
| --- | --- |
| `MISSING_TIMESTAMP/VOLTAGE/CURRENT/POWER` | 关键字段缺失 |
| `INVALID_VALUE` | NaN / Infinity / 负电压 / 负电流 |
| `FUTURE_TIMESTAMP` | `ts > now + future-skew-ms`（默认 60s） |
| `TOO_OLD` | `ts < now - max-age-ms`（默认 24h） |
| `TIME_ROLLBACK` | 比该设备最新已接受点回退超过 `reorder-lag-ms`（默认 10min） |
| `RETENTION_EXCEEDED` | 所属窗口早于保留界（默认最新窗口之前 14 个窗口） |
| `DUPLICATE` | 同时间同读数，幂等 |
| `CONFLICT` | 同时间不同读数 |

保留下界**只锚定设备最新采样窗口**，墙钟只用于未来 / 超龄的绝对校验——
所以迟到的合法数据不会把早先窗口挤掉；设备第一批数据不做保留淘汰，允许补发近期历史。

### 2.3 窗口

- 长度 `window-ms`（默认 60_000ms），`startMs = floor(ts / windowMs) * window-ms`（floor 兼容负时间戳）；
- 保留最近 `retain-windows` 个窗口（默认 15）；
- 每次有新点入库，对保留范围内全部采样点**确定性整体重算**（乱序 / 重放结果幂等）；
- 能耗：相邻点 `t0/t1` 间功率线性，`E(Wh) = ∫P dt / 3.6e9`；跨边界线段在边界处切分，
  每段归属其起始样本所在窗口。

### 2.4 告警规则与防抖

| 类型 | 越限条件（默认阈值） |
| --- | --- |
| `OVER_VOLTAGE` | `voltageV > 253.0` |
| `UNDER_VOLTAGE` | `voltageV < 207.0` |
| `OVER_CURRENT` | `currentA > 16.0` |
| `OVER_POWER` | `powerW > 3680.0` |
| `SAMPLE_INTERRUPTION` | `now - 最后采样时间 > 300000ms`（后台 tick 判定） |

- 阈值告警：**连续 `breach-times`（默认 3）个采样点越限才触发；
  告警中连续 `recover-times`（默认 3）个点正常才恢复**，期间单个抖动点只会打断/回退计数，不横跳；
- 告警按采样时间顺序推进，只处理**水位线之后**的点：迟到点不重放历史告警；
- 中断告警由墙钟驱动，**服务重启后仅依赖"最后采样时间"即可复现当前状态**，不会重复触发；
- 停用某设备告警（`alertsEnabled=false`）会立即恢复其全部活动告警并清零防抖；
  停用期间不产生新告警，重新启用后从水位线之后的新数据重新计数（不翻旧账）。

每个事件包含：`eventId`（设备+类型+触发/恢复+时间，确定性）、`occurredAtMs`、
当时的 v/a/w 与 `basis`（如 `voltageV=260.5 > overVoltageV=253.0; consecutive breach streak=3 reached breachTimes=3`）。

## 3. HTTP 接口

启动后监听 `8080`。

### 3.1 批量上报（无需管理令牌，设备可直接调用）

```bash
curl -X POST localhost:8080/api/telemetry/device-001 \
  -H 'Content-Type: application/json' \
  -d '{"samples":[
    {"timestampMs":1700000000000,"voltageV":220.3,"currentA":5.1,"powerW":1120},
    {"timestampMs":1700000001000,"voltageV":220.4,"currentA":5.0,"powerW":1110}
  ]}'
# 或把 deviceId 放在请求体：POST /api/telemetry  {"deviceId":"...","samples":[...]}
```

响应逐条返回（`records` 保留原始下标）：

```json
{
  "deviceId": "device-001", "total": 2, "accepted": 1, "duplicate": 0, "rejected": 1,
  "records": [
    {"index": 0, "timestampMs": 1700000000000, "status": "ACCEPTED", "reason": null, "message": "accepted"},
    {"index": 1, "timestampMs": 1700000001000, "status": "REJECTED",
     "reason": "MISSING_VOLTAGE", "message": "voltageV is required, timestampMs=1700000001000"}
  ],
  "alerts": [ /* 本批次产生的触发/恢复事件 */ ]
}
```

### 3.2 查询

- `GET /api/devices`：已知设备 ID 列表；
- `GET /api/devices/{deviceId}`：完整状态（采样点、窗口、各告警运行时、事件历史、水位线、revision）；
- `GET /api/devices/{deviceId}/config`：设备配置覆盖 + 合并后的生效配置。

### 3.3 设备级配置（写接口需 `X-Admin-Token`，默认 `local-admin-token`）

```bash
# PATCH：只更新提供的字段（合并语义，未知 JSON 字段一律 400）
curl -X PATCH localhost:8080/api/devices/device-001/config \
  -H 'X-Admin-Token: local-admin-token' -H 'Content-Type: application/json' \
  -d '{"overVoltageV":260.0,"breachTimes":5}'

# PUT：全量替换（未提供的字段回到全局默认）
curl -X PUT localhost:8080/api/devices/device-001/config \
  -H 'X-Admin-Token: local-admin-token' -H 'Content-Type: application/json' \
  -d '{"alertsEnabled":false}'
```

可配字段：`overVoltageV / underVoltageV / overCurrentA / overPowerW / interruptionTimeoutMs /
breachTimes / recoverTimes / alertsEnabled`。

校验规则（不满足返回 400，状态与旧配置不变）：所有阈值为正有限数；
`underVoltageV < overVoltageV`；`interruptionTimeoutMs > 0`；`breachTimes/recoverTimes >= 1`。
令牌缺失或错误返回 401，不泄露设备状态。

## 4. 配置方式

全部配置在 `src/main/resources/application.properties`，均有默认值：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `server.port` | 8080 | 本地端口 |
| `power.telemetry.window-ms` | 60000 | 窗口长度 |
| `power.telemetry.retain-windows` | 15 | 保留窗口数 |
| `power.telemetry.future-skew-ms` | 60000 | 允许的未来偏差 |
| `power.telemetry.max-age-ms` | 86400000 | 允许的最大数据年龄 |
| `power.telemetry.reorder-lag-ms` | 600000 | 允许乱序回退范围 |
| `power.telemetry.data-dir` | `data` | 本地状态目录（启动自动创建） |
| `power.telemetry.admin-token` | local-admin-token | 写接口令牌 |
| `power.telemetry.tick-interval-ms` | 1000 | 中断扫描间隔 |
| `power.telemetry.default-over-voltage-v` | 253.0 | 默认过压 |
| `power.telemetry.default-under-voltage-v` | 207.0 | 默认欠压 |
| `power.telemetry.default-over-current-a` | 16.0 | 默认过流 |
| `power.telemetry.default-over-power-w` | 3680.0 | 默认过功率 |
| `power.telemetry.default-interruption-timeout-ms` | 300000 | 默认中断超时 |
| `power.telemetry.default-breach-times` | 3 | 默认触发防抖次数 |
| `power.telemetry.default-recover-times` | 3 | 默认恢复防抖次数 |

设备 ID 规范：1–64 位 `[A-Za-z0-9_-]`（同时用于存储文件名，非法 ID 直接 400）。

## 5. 本地运行与验证

要求 JDK 21+。

```bash
# 运行（默认 8080，状态写入 ./data）
mvn spring-boot:run
# 或打包后运行
mvn package -DskipTests
java -jar target/power-telemetry-alerting-java-1.0-SNAPSHOT.jar
```

一条完整手工验证链：

```bash
NOW=$(($(date +%s)*1000))
# 混合批次：1 合法 + 1 缺字段 + 1 负值
curl -s -X POST localhost:8080/api/telemetry/demo \
 -H 'Content-Type: application/json' \
 -d "{\"samples\":[
  {\"timestampMs\":$((NOW-3000)),\"voltageV\":220,\"currentA\":5,\"powerW\":1100},
  {\"timestampMs\":$((NOW-2000)),\"currentA\":5,\"powerW\":1100},
  {\"timestampMs\":$((NOW-1000)),\"voltageV\":220,\"currentA\":-1,\"powerW\":1100}]}"
# 连续 3 次过压 -> 第 3 次返回 OVER_VOLTAGE 触发事件
for i in 1 2 3; do
  curl -s -X POST localhost:8080/api/telemetry/demo -H 'Content-Type: application/json' \
   -d "{\"samples\":[{\"timestampMs\":$((NOW+i*1000)),\"voltageV\":260.5,\"currentA\":5.2,\"powerW\":1350}]}"
done
curl -s localhost:8080/api/devices/demo                 # 查看窗口、告警状态、事件与 basis
```

中断告警可把超时调小后等待定时扫描：

```bash
curl -X PATCH localhost:8080/api/devices/demo/config \
 -H 'X-Admin-Token: local-admin-token' -H 'Content-Type: application/json' \
 -d '{"interruptionTimeoutMs":3000}'
sleep 5   # 后台 tick 每秒一次
curl -s localhost:8080/api/devices/demo | grep -o SAMPLE_INTERRUPTION
```

重启验证：直接停掉进程再启动，`GET /api/devices/demo` 返回的采样点、窗口、
`alertRuntimes`、`events` 与重启前一致；旧数据重放全部 `DUPLICATE`，不会重复告警。

## 6. 自动化测试

```bash
mvn test
```

37 个测试（全部为确定性测试，使用可控 `ManualClock` 与独立临时目录）：

- `WindowCalculatorTest`：均值 / 峰值 / 梯形积分 / 跨边界切分 / 乱序重算幂等 / 旧窗口淘汰；
- `AlertEvaluatorTest`：防抖触发与恢复、迟到点不倒翻、四类阈值独立、中断触发恢复、停用即恢复；
- `TelemetryValidatorTest`：每种拒绝原因逐一可区分，边界行为（futureSkew / reorderLag）；
- `IngestFlowTest`：重复幂等、乱序归位、坏点不拖垮整批、超保留拒绝、非法设备 ID；
- `ConcurrencyTest`：16 线程并发批次不重不漏（1800 唯一点 / 3000 次幂等重复）、
  并发改配置的设备隔离与非法值原子拒绝、并发重放不推进 revision；
- `RestartRecoveryTest`：JSON 落盘后"重启"（全新 registry 实例）恢复全部状态、
  中断状态重启可预测且不重复触发、损坏文件隔离；
- `DeviceConfigTest`：PATCH 合并、PUT 替换、非法配置原子拒绝、告警启停；
- `TelemetryApiIntegrationTest`：端到端 HTTP（逐条拒绝原因、401/400/404、未知字段拒绝）。

日志中可见设备、采样时间、窗口与判定依据，例如：

```
ALERT TRIGGER device=demo type=OVER_VOLTAGE ts=1700000003000 v=260.5 a=5.2 w=1350.0 basis='voltageV=260.5 > overVoltageV=253.0; consecutive breach streak=3 reached breachTimes=3'
ingest done device=demo total=3 accepted=1 duplicate=0 rejected=2 alerts=1 rev=2
interruption tick changed devices: [demo]
```

## 7. 设计要点（为什么结论稳定可复现）

1. **单设备串行 + 批次整体落盘**：同设备锁内完成"校验→去重→重算→告警→落盘"，
   并发批次等价于某一种确定的串行顺序；每设备独立锁不影响其他设备。
2. **重算而非增量**：窗口聚合从保留采样点整体重算，乱序插入、并发交错、重复重放都得到同一结果。
3. **告警水位线**：状态机只按时间向前推进，统计可以被迟到点修正，告警结论不会被历史改写。
4. **确定性事件 ID**：事件由业务键天然去重，重放不会产生重复事件。
5. **原子持久化**：临时文件写完再原子改名，崩溃不留半截文件；损坏文件隔离而非阻断启动。
