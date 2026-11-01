# 行为验证码通用方案（jkit-captcha behavior）

针对滑块 / 旋转 / 点选等行为验证码的通用通过方案与参考实现。以开源
[dromara/tianai-captcha](https://github.com/dromara/tianai-captcha) 为实验靶场，
`jkit-captcha` 的 `behavior` 包提供缺口定位、人类轨迹合成、行为规则自检与开源协议客户端
四个纯 JDK8 组件；`jkit-captcha-bench` 提供带真值的离线校准基准与端到端成功率基准。

> 本文同时回答两个问题：**怎么通用**（方法论，适用于任意行为验证码厂商）与
> **对 tianai 具体怎么解**（实验数据全部来自本文所述基准的可复现运行）。

## 一、行为验证码的分类与通用攻击面

| 类型 | 交互 | 服务端校验的本质 | 解算核心 |
|------|------|------------------|----------|
| 滑块（SLIDER / CONCAT） | 拖动拼块对准缺口 | 终点位移 / 背景宽 ≈ 真实位置 / 背景宽，容差 ±2% | 缺口定位（图像算法） |
| 旋转（ROTATE / ROTATE_DEGREE） | 拖动转正图片 | 角度 / 360 ≈ 真实角度 / 360，同上 | 主方向 / 梯度直方图 |
| 点选（WORD_CLICK 等） | 按序点击文字/图标 | 各点击点百分比与答案比对（默认校验顺序） | 检测 + OCR + 语义对齐 |
| 工作量证明（POW） | 浏览器算哈希 | nonce 验证 | 哈希碰撞计算 |
| 增强版行为验证 | 拖动 + 环境采集 | 位置 + 轨迹 + 指纹 / 风险模型 | 前三者 + 对抗层 |

无论交互形式如何，服务端校验都可归约为两层：

1. **答案层**：终点（位置/角度/点序）是否正确——图像算法可解，误差需落在容差内
   （tianai 默认容差为背景宽的 2%，增强版可能收紧）；
2. **行为层**：轨迹与环境是否像人——规则引擎（开源版 7 条规则）到风险模型（增强版）。

通用方案 = 答案层算法 + 行为层合成 + 协议适配，三层都要过硬。

## 二、通用方案五层流水线

```
┌─① 获取层──→ 拉取验证码（图对/图串 + 会话 id），解析下发契约
├─② 解算层──→ 图像算法求答案：缺口 x / 旋转角 / 点选坐标（多策略 + 置信度融合）
├─③ 轨迹层──→ 人类行为合成：缓动主段 + 过冲回修 + 微抖，先过本地规则自检再出门
├─④ 提交层──→ 按厂商协议打包提交；坐标系自洽（显示宽或自然宽，二者比例必须一致）
└─⑤ 对抗层──→ 失败码分类（位置错/轨迹假/过期/参数错）→ 反馈重试与策略切换
```

工程约定：

- **坐标系自洽**：服务端校验的是「百分比」，客户端用显示坐标还是自然坐标都可以，
  但轨迹、`bgImageWidth` 必须同系。tianai 前端用显示宽（如 300），接口直调建议直接用
  自然宽（如 600），省一次缩放误差。
- **容差预算**：位置误差必须控制在容差的一半以内（默认容差 ±2% 即自然宽 ±12px，
  建议做到 ±2px），给轨迹与网络抖动留余量。
- **失败码驱动**：位置错、轨迹假、过期、参数错要能区分——只有「位置错」值得换图重试，
  「轨迹假」要改轨迹参数，「过期」要重取验证码。

## 三、实验靶场：tianai-captcha 开源栈

### 3.1 服务端校验算法（源码结论）

取验证码时服务端记录 `oriPercentage = randomX / backgroundImageWidth` 与容差（默认
`0.02`）。校验时从轨迹取首尾两点：

```
calc = (lastTrack.x - firstTrack.x) / bgImageWidth（客户端上报宽）
通过 ⇔ |calc - oriPercentage| ≤ tolerant
```

开源版 `BasicCaptchaTrackValidator` 在此之上叠加 7 条行为规则（`TianaiTrackRules`
已一一移植，用于本地自检）：

1. 滑动总时长 ≥ 300ms；
2. 轨迹点数 ∈ [10, 背景宽 × 5]；
3. 首点坐标 ∈ [-10, 10]；
4. y 全程与首点相同 → 判机器；
5. 相邻点 x/y 跳变 > 50 → 判异常；
6. x 超过背景宽度的点 > 200 个 → 判异常；
7. 末 30% 轨迹的平均时间间隔必须大于前 70%（先快后慢）。

> 关键几何（读 `StandardSliderImageCaptchaGenerator` 得出）：拼块贴在模板条 x=0 处，
> 凹槽模板贴在背景图 `(randomX, randomY)` 处，**条带平移量 = randomX**。
> 客户端下发的 `bgImageWidth` 用显示宽（前端 300）还是自然宽（600）决定百分比分母，
> 两种都能过，但轨迹坐标系必须跟它一致。

### 3.2 缺口定位：三策略 + 置信度融合

`SliderGapLocator` 输出三个候选（`SliderCaptchaSolver` 按置信度融合）：

| 策略 | 原理 | 适用 |
|------|------|------|
| `ring` | 模板条不透明边界环在背景图上扫 Sobel 梯度均值，环对齐处出现峰值 | 通用；开源模板上最准 |
| `interior` | 模板内容区（alpha 腐蚀掉边框环）与背景图亮度差 SSD | 切块未被厂商做色彩扰动时 |
| `flatCore` | 背景图平铺灰芯（低饱和平坦最大连通域）+ 环宽自洽公式反推 | 缺口被平铺色填充的增强版渲染 |

腐蚀用 summed-area table 做 O(N)；融合策略：`ring ≥ 0.12` 直接采信 → `interior ≥ 0.75`
→ `flatCore ≥ 0.4` → 取最高置信度。

### 3.3 离线校准（带真值）

`jkit-captcha-bench` 的 `TianaiOfflineBenchMain` 用开源库本地生成滑块验证码
（`randomX` 即真值），跑 100 张：

```
ring     : n=100 miss=0   mean=-0.0 median=0 maxAbs=2   |err|<=3: 100/100
flatCore : n=67  miss=33  mean=-1.2 median=-1 maxAbs=16  |err|<=3: 46/67
chosen   : n=100 miss=0   mean=-0.0 median=0 maxAbs=2   |err|<=3: 100/100   ← 全部命中 ring
轨迹规则自检：trackFail=0 / 100
```

`ring` 策略 100/100、最大误差 2px——形状环对缺口边框的响应远比内容匹配稳健
（内容匹配会被厂商的洗白/扰动破坏）。

### 3.4 端到端成功率（开源协议栈）

`TianaiServerBenchMain` 在 JVM 内起一个与开源 demo 同契约的 HTTP 服务
（`/gen` + `/check`，`BasicCaptchaTrackValidator` 行为规则全开），
再用 `TianaiCaptchaClient` 走完整流程，20 次全部通过：

```
pass=20 fail=0 / 20  successRate=100.0%  avg=117ms
```

单次全流程（生成 + 定位 + 轨迹 + 校验往返）平均 ~117ms，纯 Java 无外部依赖。

## 四、增强版（线上 demo captcha.tianai.cloud）实验

线上 demo 跑的是闭源增强版，与开源版有三处代差，实验给出了清晰的边界：

**① 协议加密**。verify 请求体为 `{id, data:<密文>, drives:<加密环境指纹>, ki:<密钥信息>}`，
生成请求也带加密 `drives`；加解密由 CDN 下发的 `cw.wasm` 暴露的 `__encrypt__ / __drives__ /
__ki__` 全局函数完成（AES 族分组密码，9 字节明文 → 16 字节密文块）。
密钥内嵌于 wasm/SDK，逐字节逆向成本高且随版本轮换。**通用工程答案是「SDK 执行器」：
解算与轨迹在本地算，提交前把答案注回页面里厂商自己的 SDK，让它自己加密上报**
（实验中通过注入页面驱动 SDK 提交路径即可复现其加密报文）。

**② 坐标与容差**。增强版 SDK 上报显示宽 `bgImageWidth=300`（滑块行程上限
`end = 300 - 63 + 5 = 242`），旋转类把 `bgImageWidth` 换成 `end`，让「角度百分比」
复用同一条校验逻辑——这一归一化技巧在自研协议时值得借鉴。

**③ 行为/环境层**。实测失败码语义：`4001` 基础位置失败、`4000` 已失效（缓存约 2 分钟）、
`403` 无效参数、`50000` 基础比对之外的失败。对照实验：解算位置提交得到 `50000`
（即已越过基础位置比对），而故意拖偏 45% 的对照得到 `4001`——说明解算答案本身可用，
被拒绝的是闭源的行为/环境检测层（真实鼠标轨迹仍被判负，具体规则未公开）。
这验证了增强版的对抗强度主要不在「图难解」而在「行为难仿」，与方案第五层的判断一致。

> 结论：对增强版，②答案层通用算法照常可用；①③两层的工程选项是
> SDK 执行器（实验已验证可复现其加密与提交通路）或协议逆向（随版本失效，不推荐沉淀）。

## 五、jkit 使用

依赖（`jkit-captcha`，纯 JDK8 + jkit-core JSON，无新增第三方依赖）：

```java
// 推荐入口：厂商无关编排器（带失败码驱动的重试，默认 3 轮）
BehaviorCaptchaSolver solver = new BehaviorCaptchaSolver(
        new TianaiBehaviorProtocol(new TianaiCaptchaClient(), genUrl, checkUrl),
        new Random());

BehaviorCaptchaSolver.Attempt attempt = solver.solve();
attempt.isSuccess();  // true=通过
attempt.getLog();     // 每轮明细：sessionId、定位策略/置信度、厂商原始码
```

也可分层使用：`TianaiCaptchaClient`（协议细节）→ `SliderCaptchaSolver.solve/buildTrack`
（解算与轨迹）→ `TianaiTrackRules.validate`（规则自检）。

### 新增厂商（SPI）

厂商差异收敛在两个适配点，解算与轨迹层零改动复用：

```java
public interface BehaviorCaptchaProtocol {
    String vendor();
    BehaviorChallenge fetch() throws IOException;                       // 装图 + 会话标识（自然尺寸）
    SubmitResult submit(BehaviorChallenge c, BehaviorAnswer a) throws IOException;
}                                                                       // 翻译报文/加密，并把厂商码映射为
                                                                        // SUCCESS/POSITION_MISMATCH/TRACK_REJECTED/
                                                                        // EXPIRED/BAD_PARAM/UNKNOWN
public interface TrackRulePack {
    String validate(List<TrackPoint> track, int bgWidth, long startMs, long stopMs);
}                                                                       // null=通过；按厂商已知规则自检
```

接入步骤：① `fetch()` 把厂商响应的图与会话标识装入 `BehaviorChallenge`；
② `submit()` 把答案翻译成厂商报文（轨迹坐标系/字段名/加密指纹按厂商处理，
增强厂商建议 SDK 执行器）；③ 码值映射到 `FailureKind`——编排器据此决定
换图重试（过期/位置错）还是立即停止（轨迹被拒/参数错）；④ 有已知规则就写
`TrackRulePack`，没有则先用 `TianaiTrackRulePack` 之外的宽松实现。
完整可运行样例见 `BehaviorCaptchaSolverTest` 里的 `FakeVendorProtocol`（假厂商）。

```java
// 新厂商即插即用
BehaviorCaptchaSolver solver = new BehaviorCaptchaSolver(
        new GeetestProtocol(config), new GeetestRulePack(), new SliderCaptchaSolver(), new Random());
```

基准工具（`jkit-captcha-bench`，依赖 `cloud.tianai.captcha:tianai-captcha:1.5.6`，仅基准使用；
`TianaiServerBenchMain` 已改走编排器 + 协议 SPI，自证适配层全链路）：

```bash
# 离线校准：定位误差分布 + 轨迹规则自检（带 randomX 真值）
java -cp jkit-captcha-bench/target/classes:$CP \
  com.alianga.jkit.captcha.bench.behavior.TianaiOfflineBenchMain 100

# 端到端：内嵌同契约 HTTP 服务端 + 客户端全流程成功率
java -cp jkit-captcha-bench/target/classes:$CP \
  com.alianga.jkit.captcha.bench.behavior.TianaiServerBenchMain 20
```

## 六、对抗升级路线（通用）

- **多类型**：旋转 = 梯度主方向 / 环形相关性；点选 = 文本检测 + OCR + 语义对齐
  （可复用 jkit-captcha 既有 ONNX 通道）；PoW = 并行哈希。答案层套路不变。
- **容差收紧**：增强版可能把 2% 收到 0.5% 以内，定位必须做到亚像素
  （上采样 + 抛物线峰值插值）。
- **轨迹对抗**：开源规则只是下限；增强版会做事件可信性（isTrusted）、时序分布、
  指纹一致性检测。合成轨迹要按「真事件驱动」产生（真实输入设备/CDP trusted event），
  而不是纯构造报文。
- **指纹层**：`drives` 类环境采集建议走 SDK 执行器，不自己伪造。
- **频控与缓存**：验证码 id 有效期短（实验测得约 2 分钟），解算要在取图后立刻做；
  重试须换图，不要重放旧 id。

## 七、合规边界

本方案与代码用于**自有系统验证码强度评估、授权测试与教学研究**（本仓库实验对象为
开源库自带/官方公开 demo）。请遵守目标服务条款与当地法律；不要用于绕过与己无关系统的
防护或大规模自动化场景。
