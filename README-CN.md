# jquick-agent

> 一个面向生产环境的 **Java Agent 框架**，支持 `premain` 与 `agentmain` 两种挂载方式，底层完全基于
> [**jquick-asm**](https://github.com/paohaijiao/jquick-asm) 实现，在**不改动业务源码**的前提下观测与治理
> 运行中的 JVM。

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0.txt)
[![Java](https://img.shields.io/badge/JDK-1.8%2B-orange.svg)](https://www.oracle.com/java/)
[![ASM](https://img.shields.io/badge/ASM-9.6-red.svg)](https://asm.ow2.io/)
[![基于 jquick-asm](https://img.shields.io/badge/based%20on-jquick--asm-brightgreen.svg)](https://github.com/paohaijiao/jquick-asm)
[![单元测试](https://img.shields.io/badge/tests-140%20green-brightgreen.svg)](#构建--单元测试)

本文件是 [README.md](README.md)（英文主文档）的中文版本，内容与主文档一致，并补充了国内使用说明。

---

## 目录

- [项目介绍](#项目介绍)
- [核心能力](#核心能力)
- [模块设计与包结构](#模块设计与包结构)
- [jquick-asm 简介](#jquick-asm-简介)
- [快速上手](#快速上手)
- [Java Agent 两种模式：premain / agentmain](#java-agent-两种模式premain--agentmain)
- [典型业务场景](#典型业务场景)
- [可直接运行的 Demo](#可直接运行的-demo)
- [能力限制与注意事项](#能力限制与注意事项)
- [构建 & 单元测试](#构建--单元测试)
- [FAQ 常见问题](#faq-常见问题)
- [国内使用说明](#国内使用说明)
- [开源协议](#开源协议)
- [JQuick 生态相关项目](#jquick-生态相关项目)
- [SEO 关键词](#seo-关键词)

---

## 项目介绍

`jquick-agent` 既可以随 JVM 启动挂载（`-javaagent`，即 `premain`），也可以在进程运行期间动态挂载
（Attach API，即 `agentmain`）。挂载完成后，即可在字节码层面对应用行为进行观测，并在需要时加以干预。

整个模块由三条原则定义：

1. **零侵入是硬约束，不是口号。** Agent 只在方法体内**追加指令**，不改类结构、不加字段、不加方法，并且
   —— 这条规则决定了整个设计 —— **绝不注册竞争性的异常处理器**。JVM 会把异常交给异常表中**第一条**匹配的
   记录，因此由 Agent 安装的 `catch (Throwable)` 会静默地让业务代码自己的 `try/catch` 失效。Agent 内部的
   任何异常都会被兜住并打印日志，绝不让一次失败的增强把宿主应用带崩。
2. **字节码操作全部交给 jquick-asm。** 本仓库不手写任何 `ClassVisitor` / `MethodVisitor`，所有指令生成都
   委托给 jquick-asm 的高层流式 API（`JQuickMethodEnhancer`、`JQuickMethodAdvice`、
   `JQuickClassReaderTool`、`JQuickClassInfo`）。
3. **内核不出现任何能力名。** 框架只有一个扩展根 `JQuickPlugin`，它同时覆盖一项能力的两个面：字节码侧
   （`JQuickClassEnhancer`）与运行期侧（`JQuickMethodListener`、拦截闸门、参数/返回值改写器、打桩解析器）。
   内核（`JQuickEnvironment`、`JQuickInstrumentEnhancer`、`JQuickSink`）从不提到任何具体功能的名字；能力
   清单只存在于一处可读、有序的地方 —— `JQuickPlugins.builtIn()`，并且可以通过 `META-INF/services` 从
   jar 外部扩展。

默认产物是**自包含的 shaded jar**：jquick-asm 与 ASM 均已打进包内，因此
`-javaagent:jquick-agent-1.0.0.jar=...` 不需要额外配置 classpath。

Agent 的全部行为由一个**扁平的 key/value 字符串**配置（命令行或 attach 调用处），启动时会打印生效配置 ——
运维人员不需要猜当前到底开了哪些能力。

---

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 两种挂载模式 | 同一个 jar 同时支持 `premain`（`-javaagent`）与 `agentmain`（动态 attach） |
| 运行期重转换 | `agentmain` 会对已加载类执行 `retransformClasses`，Demo 实测输出 `retransformed 6 already-loaded class(es)` |
| 分布式链路 | 128 位 traceId、64 位 spanId、父 span、线程内 span 栈，`ENTRY` / `EXIT` / `LOCAL` 三种 span 类型，支持采样 |
| 上下文采集 | 入参、返回值、异常堆栈三块**各自独立开关**，不开就完全不采集 |
| 数据库能力 | JDBC 语句 span、语句类型指标、按类型耗时、慢 SQL 计数与日志、可插拔 `JQuickSqlRewriter` 完成租户 SQL 改写 |
| 组件集成 | 入站 HTTP（`ENTRY`），出站缓存 / 消息队列 / JDBC（`EXIT`），**按继承体系选择**，不依赖驱动类名 |
| 监控指标 | 请求量 / 错误 / 耗时 / SQL / 治理的 Prometheus 指标（`counter` / `summary` / `gauge`），内置抓取端点 |
| 服务拓扑 | 由出站 span 推导 caller → callee 调用边，同时输出 Prometheus 序列与可读调用图 |
| 日志增强 | 自动向日志消息注入 traceId 并对敏感字段脱敏，不改日志配置、不依赖 MDC |
| 流量治理 | 方法级限流（QPS + 突发）、熔断（窗口内失败率 + 打开时长），并自带治理指标 |
| 安全管控 | 命令执行、反射、SQL 注入、反序列化四类阻断检查，以及 SQL 长度上限 |
| 数据保护 | 入参/返回值透明 AES 加解密；手机号、身份证、银行卡、邮箱的模式化脱敏 |
| 安全审计 | 结构化审计记录，参数渲染带长度上限与安全截断 |
| 打桩 | 完整的 stub 解析器：任意方法返回指定值或抛出指定异常，不修改被测代码 |
| 热修复 | 通过 `redefineClasses` 在运行中的 JVM 内替换方法体，边界由 JVM 自身规则决定 |
| 覆盖率探针 | 哪些方法执行过、执行了多少次 —— 复用同一套钩子，无需为测试单独构建 |
| 运行期巡检 | 线程普查、泄漏检测、可级联的未捕获异常处理器、类加载黑名单、jar 冲突检测 |
| 声明式开关 | `@JQuickTrace` 让插桩只作用于标注代码；`@JQuickIgnored` 精准排除某个热点方法 |
| 故障隔离 | 转换器 / 增强器 / 监听器 / 闸门的每次调用都单独兜底，宿主应用始终可继续运行 |
| 插件发现 | 第三方能力通过 `ServiceLoader` 加载；第三方 jar 无法静默替换内置能力 |

---

## 模块设计与包结构

```
io.github.paohaijiao.jquick.agent
├── bootstrap        入口与装配
│   ├── JQuickAgent             premain(String, Instrumentation) / agentmain(String, Instrumentation)
│   ├── JQuickBootstrap         解析 → 环境 → 插件 → 转换器 → 重转换 → 报告
│   ├── JQuickTransformer       唯一的 ClassFileTransformer：筛类 → 解析 → 增强器链
│   ├── JQuickArgumentParser    无依赖解析 "jar=a=b;c=d"
│   ├── JQuickPlugins           插件清单（内置能力定序）＋ SPI 合并
│   └── JQuickMetricsExporter   内置 Prometheus 端点（com.sun.net.httpserver）
├── core             内核，此处不出现任何能力名
│   ├── JQuickPlugin            唯一的扩展根：isEnabled / initialize / enhancers
│   ├── JQuickEnvironment       生效配置、增强器、监听器、闸门、改写器、指标注册表
│   ├── JQuickInstrumentEnhancer 把（类匹配器 + 方法匹配器）变成注入的钩子
│   ├── JQuickHookPlan          注入序列的代价清单：args、transformArgs、result、transformResult、mock、threadPool
│   ├── JQuickClassMatcher      类名 / 包前缀 / 通配 / 父类型 / 注解选择
│   ├── JQuickMethodMatcher     方法名、前缀、注解、排除规则
│   ├── JQuickMethodListener    能力的运行期侧：采集开关 + onMethodEvent
│   ├── JQuickInvocationGate    方法体之前的否决点（限流、熔断、安全）
│   ├── JQuickArgumentTransformer / JQuickResultTransformer  载荷改写 SPI
│   ├── JQuickStubResolver / JQuickStubAction                单测打桩 SPI
│   └── JQuickConfig            扁平 key/value 配置的类型化视图
├── runtime
│   ├── JQuickSink              注入字节码调用的静态锚点（enter / exit / error / mock）
│   └── JQuickTraceAwareRunnable 线程池上下文传递所使用的任务包装器
├── trace            JQuickTraceContext、JQuickTraceScope、JQuickSpan、JQuickSpanType、
│                    JQuickTraceSampler、JQuickTraceSnapshot
├── metrics          JQuickMeterRegistry、JQuickCounter、JQuickTimer、JQuickGauge、JQuickSample
├── topology         JQuickTopology（调用边、可读渲染、Prometheus 行）
├── plugin           一项能力一个包
│   ├── trace        JQuickTracePlugin / JQuickTraceListener
│   ├── metrics      JQuickMetricsPlugin / JQuickMetricsListener
│   ├── jdbc         JQuickJdbcPlugin / JQuickJdbcListener / JQuickSqlRewriter / JQuickTenantSqlRewriter
│   ├── component    JQuickHttpPlugin / JQuickCachePlugin / JQuickMqPlugin / JQuickMessageHeaders
│   ├── log          JQuickLogPlugin / JQuickLogTransformer / JQuickLogSupport
│   ├── security     JQuickSecurityPlugin / JQuickSecurityGate / JQuickSecurityGuard
│   ├── governance   限流器、熔断器、鉴权器、治理闸门与治理指标
│   ├── audit        JQuickAuditPlugin / JQuickAuditListener
│   ├── crypto       JQuickAesCipher、JQuickDataCipher 与加解密改写器
│   ├── mask         JQuickSensitiveMasker、JQuickSensitiveTransformer
│   ├── mock         JQuickMockRegistry
│   ├── hotfix       JQuickHotFixManager
│   ├── coverage     JQuickCoverage
│   ├── thread       JQuickThreadPlugin / JQuickThreadMonitor / JQuickUncaughtExceptionHandler
│   └── classload    JQuickClassLoadGuard / JQuickJarConflictDetector
├── support          JQuickBytecodeEmitter、JQuickValueFormatter、JQuickClassFilters、
│                    JQuickDescriptorUtil、JQuickStringUtil、JQuickIdGenerator、JQuickLogger
└── annotation       @JQuickTrace（按需开启）、@JQuickIgnored（精准排除）
```

**为什么内核里不出现能力名。** 一个“内核知道自家功能名字”的框架，只能靠改内核来扩展。在这里，新增一项能力
＝写一个 `JQuickPlugin` 并把它登记进清单 —— 或者只把一个带 `META-INF/services` 的 jar 丢进 classpath。
被发现的第三方插件若与内置插件同名则**内置者胜**，因为第三方 jar 不应该有能力替换掉链路追踪；冲突会打印日志。

**数据流**

```
JVM 类加载 / retransformClasses
        │
        ▼
JQuickTransformer（单实例、单趟）
        │  先做最便宜的字符串筛选：匿名类 → JVM 内核 → Agent 自身包
        │  → JDK 类（除非某插件点名要它）→ includes / excludes
        ▼
JQuickClassMeta.of(bytes)                    ← 唯一一次解析，且只对真正的候选类做
        │
        ▼
for each JQuickClassEnhancer ──▶ JQuickInstrumentEnhancer
        │                          JQuickMethodEnhancer.from(bytes)
        │                              .match(方法匹配器)
        │                              .advice(onEnter / onExit(+ATHROW))
        │                              .apply()
        │                          plan = 环境计划 ∪ 自身要求 ∪ 兄弟增强器的要求
        ▼
增强后的字节码 ──▶ Instrumentation
        │
        ▼  （运行期，由宿主应用自己的类加载器执行）
JQuickSink.enter/exit/error ──▶ JQuickMethodEvent ──▶ 监听器 ──▶ 链路日志 / 指标 / 拓扑
                                   │
                                   ├─ 闸门        （可否决：限流、熔断、安全）
                                   ├─ 参数改写器  （日志前缀、脱敏、租户 SQL、加解密）
                                   └─ 返回值改写器（脱敏、加解密）
```

有两条不变量值得单独说明，因为它们正是代码长成现在这样的原因：

- **插桩是幂等的。** 发指令之前，增强器会扫描传入字节码里是否已经存在对 `JQuickSink` 的调用，已经上报过的方法
  就不再处理。没有这一步，一个被两项能力同时选中的类会被插桩两遍 —— 观测两次、计数两次，而对“加解密”这类
  返回值改写器来说会被**改写两次**，这不可幂等，会静默地把值改坏。
- **钩子的发射与注册顺序无关。** 某个类最终使用的钩子计划，等于「环境级计划 ∪ 本增强器要求 ∪ **所有匹配该类的
  兄弟增强器的要求**」的并集。否则，第一个接受该类的增强器只会发射它自己需要的最小序列，某个需要“改写返回值”
  的能力就永远看不到结果。

---

## jquick-asm 简介

[jquick-asm](https://github.com/paohaijiao/jquick-asm) 是 JQuick 生态的底层字节码库。它在 ASM 9.x 之上做了一层
*纯原生封装*：把 `ClassVisitor` / `MethodVisitor` 的繁琐细节藏起来，只暴露三件事 —— **读**、**写**、**增强**。

```java
// 增强：为匹配的方法加上进入/退出钩子，全程不碰 visitor
byte[] enhanced = JQuickMethodEnhancer.from(originalBytes)
        .match(meta -> meta.name.startsWith("handle"))
        .advice(JQuickMethodAdvice.builder()
                .onEnter(ctx -> ctx.methodVisitor().visitLdcInsn("enter: " + ctx.name()))
                .onExit((ctx, opcode) -> { /* ... */ })
                .build())
        .apply();
```

`jquick-agent` 只用到它的四样东西：`JQuickMethodEnhancer`（增强 API）、`JQuickMethodAdvice`（进入/退出建议）、
`JQuickClassInfo` / `JQuickMethodInfo`（类与方法元信息，用于匹配和注解查找）、`JQuickEnhanceGuard`（安全护栏）。
Agent 的其他部分不直接接触 ASM —— 这正是生成字节码能保持风格一致、并且整条发射链路可以单元测试的原因。

---

## 快速上手

### Maven 依赖

Agent 通常以**可运行 jar** 的形式使用，而不是编译期依赖：

```bash
mvn clean package
# -> target/jquick-agent-1.0.0.jar   （自包含：已内置 jquick-asm 与 asm）
```

只有在需要**嵌入 API**（自定义插件、监听器、闸门）时才加入构建依赖：

```xml
<dependency>
    <groupId>io.github.paohaijiao</groupId>
    <artifactId>jquick-agent</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 最小 Demo

目标应用 —— 这里看不到任何与 Agent 相关的东西：

```java
package com.demo;

public class Demo {
    public static void main(String[] args) {
        System.out.println(new Demo().greet("jquick"));
    }

    public String greet(String name) {
        return "hello " + name;
    }
}
```

挂载 Agent 运行：

```bash
java -javaagent:target/jquick-agent-1.0.0.jar=includes=com.demo;trace.print=true;trace.result=true \
     -cp target/demo-classes com.demo.Demo
```

输出：

```
[jquick-agent] [INFO] installing (mode=premain)
[jquick-agent] [8dce26ee:7edacf85] main
  |- com.demo.Demo#greet(java.lang.String) [0ms] component=method  result=hello jquick
hello jquick
```

再加上 `trace.args=true`，入参也会一并输出；加上 `trace.error.stack=true`，抛出异常时会打印调用栈。
不主动要求就不会采集 —— 因为每一个开关都是实打实的开销。

### Agent 参数说明

```
-javaagent:jquick-agent-1.0.0.jar=includes=com.demo,com.acme;excludes=com.demo.legacy;trace.print=true
```

键值对之间用 `;` 分隔，同一个值内部的列表用 `,` 分隔。下表每一个键都是 Agent 真正会读取的；生效配置会在启动时
打印，也可通过 `GET /config` 获取（看起来像密钥的值会被脱敏）。

| 键 | 默认值 | 含义 |
| --- | --- | --- |
| `includes` | *(空)* | 要插桩的类规则：精确类名、包前缀或通配。`*` 表示“全部业务类”。**留空＝不增强任何类。** |
| `excludes` | *(空)* | 即使命中 `includes` 也跳过的类规则 |
| `allowJdk` | *(空)* | 允许插桩的 JDK 类（例如 `java.util.concurrent.ThreadPoolExecutor`） |
| `retransform` | premain 为 `false`，attach 为 `true` | 是否对已加载类重跑插桩 |
| `quiet` / `debug` | `false` | 关闭全部诊断输出 / 打开详细启动诊断 |
| `trace.enabled` | `true` | 安装方法链路追踪 |
| `trace.print` | `false` | 每个 span 打印一行 |
| `trace.args` / `trace.result` | `false` | 采集（并打印）入参 / 返回值 |
| `trace.error.stack` | `false` | 打印失败调用的调用栈 |
| `trace.slow.ms` | `200` | 达到该耗时即标记为慢调用 |
| `trace.sampler.rate` | `1` | 每 N 条链路保留 1 条 |
| `trace.annotation.only` | `false` | 只插桩 `@JQuickTrace` 标注的代码 |
| `metrics.enabled` | `true` | 采集指标（进程内） |
| `metrics.port` | `0` | Prometheus 端点端口；**`0` 表示不开 HTTP 服务** |
| `metrics.bind` / `metrics.path` | `0.0.0.0` / `/metrics` | 绑定地址与暴露路径 |
| `metrics.labels` | `class` | 标签粒度：`class` 或 `method` |
| `topology.enabled` | `false` | 推导并暴露服务拓扑 |
| `jdbc.enabled` | `false` | 数据库能力 |
| `jdbc.includes` | `java.sql.Statement`、`PreparedStatement`、`CallableStatement`、`Connection`、`javax.sql.DataSource` 等 | 参与选择的继承体系名 |
| `jdbc.slow.ms` | 取 `trace.slow.ms` | 慢 SQL 阈值 |
| `jdbc.tenant.enabled` / `jdbc.tenant.column` | `false` / `tenant_id` | 改写 SQL 以附加租户条件 |
| `http.enabled` / `cache.enabled` / `mq.enabled` | `false` | 组件能力，各自带 `.includes` 列表 |
| `log.enabled` | `false` | 向日志流注入 traceId |
| `log.includes` | `org.slf4j.Logger`、Log4j 1/2、`java.util.logging.Logger` | 被插桩的是这些日志接口/类的**实现类** |
| `log.mask.enabled` | `false` | 同时对消息内的敏感字段脱敏 |
| `security.enabled` | `false` | 阻断式检查（`.command.check`、`.reflection.check`、`.sql.injection.check`、`.deserialize.check`、`.sql.max.length`） |
| `governance.enabled` | `false` | 限流（`rate.limit.qps`、`rate.limit.burst`）、熔断（`circuit.failure.rate`、`circuit.window`、`circuit.open.ms`）、`governance.blacklist`、`governance.auth` |
| `audit.enabled` | `false` | 结构化审计记录 |
| `crypto.enabled` / `crypto.key` | `false` | 对入参与返回值做 AES 变换 |
| `mask.enabled` / `mask.includes` | `false` | 对返回值做敏感信息脱敏 |
| `mock.enabled` | `false` | 安装打桩解析器（单测用） |
| `thread.enabled` | `false` | 线程池上下文传递、未捕获异常捕获、线程普查 |
| `coverage.enabled` | `false` | 方法覆盖率探针 |
| `classload.enabled` | `false` | 类加载黑名单（`classload.blacklist`）与冲突检测（`classload.conflict.detect`） |

> **默认姿态是“只观测”。** 不带任何参数挂载 Agent，不会改变应用行为：`trace` 与 `metrics` 默认开启是因为它们
> 只读；其余十四项能力在运维人员点名之前全部关闭。

### 自定义扩展

```java
public final class MyPlugin implements JQuickPlugin {

    public String name() { return "my-capability"; }

    public JQuickCategory category() { return JQuickCategory.TRACING; }

    public boolean isEnabled(JQuickConfig config) { return config.isPluginEnabled(name()); }

    public void initialize(JQuickEnvironment environment) {
        environment.addListener(new MyListener());                 // 运行期侧
        enhancers.add(new JQuickInstrumentEnhancer(
                name(),
                JQuickClassMatcher.byName("com.acme.OrderService"), // 选哪些类
                JQuickMethodMatcher.named("pay"),                   // 选哪些方法
                JQuickSpanType.EXIT,                                // span 的语义
                "acme",                                             // 组件标签
                environment));
    }

    public List<JQuickClassEnhancer> enhancers() { return enhancers; }

    public String description() { return "my capability"; }
}
```

在 `JQuickPlugins.builtIn()` 里加一行即可注册；也可以完全不重新编译 Agent，只提供一个包含
`META-INF/services/io.github.paohaijiao.jquick.agent.core.JQuickPlugin` 的 jar，写明你的实现类。
插件必须有不带参数的 public 构造函数；配置在 `initialize` 里从真正安装好的环境中读取。

---

## Java Agent 两种模式：premain / agentmain

| | `premain` | `agentmain` |
| --- | --- | --- |
| 时机 | JVM 启动时，**早于** `main` | 运行期，挂载到**已在运行**的 JVM |
| 方式 | `-javaagent:jquick-agent.jar=args` | Attach API：`VirtualMachine.attach(pid).loadAgent(jar, args)` |
| 入口 | `premain(String, Instrumentation)` | `agentmain(String, Instrumentation)` |
| 已加载类 | 无（或极少） | 通过 `Instrumentation#retransformClasses` 重转换 |
| `retransform` 默认值 | `false` | `true` |
| 典型用途 | APM 探针、常驻链路 | 线上诊断、热修复、功能开关 |

### `premain`

```bash
java -javaagent:/opt/agents/jquick-agent-1.0.0.jar=includes=com.demo -jar app.jar
```

JVM 读取 manifest 中的 `Premain-Class`，在应用 `main` 之前调用它。

### `agentmain`

```java
VirtualMachine vm = VirtualMachine.attach(pid);          // JDK8：tools.jar；JDK9+：jdk.attach
try {
    vm.loadAgent("/opt/agents/jquick-agent-1.0.0.jar", "includes=com.demo,trace.print=true");
} finally {
    vm.detach();
}
```

因为目标类已经加载，装配流程会立刻对所有命中且可修改的类执行一次重转换 —— 逐类调用，容忍 JVM 拒绝的那些，
因此单个不可重转换的类不会中断整批。正是这一趟让插桩无需重启即可生效。

打包后的 manifest 同时声明了两个入口：

```properties
Premain-Class: io.github.paohaijiao.jquick.agent.bootstrap.JQuickAgent
Agent-Class: io.github.paohaijiao.jquick.agent.bootstrap.JQuickAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
Can-Set-Native-Method-Prefix: true
Implementation-Title: jquick-agent
Implementation-Version: 1.0.0
```

---

## 典型业务场景

下表是本模块的**完整能力范围**，状态列刻意保持诚实。

图例 —— 🟢 本仓库已实现且有单元测试覆盖 · 🔵 已提供选择/扩展点，但需要有对应第三方类在 classpath 上才能跑通 ·
⚪ 需要其他机制，不属于字节码插桩范畴。

### 一、可观测性

| 能力 | 状态 | 实现方式 |
| --- | --- | --- |
| 方法埋点：进入 / 退出 / 异常 | 🟢 | `JQuickInstrumentEnhancer` + `JQuickMethodEnhancer` |
| 每次调用耗时（纳秒采集、毫秒上报） | 🟢 | 进入即开 span，退出即关 |
| 每个事件带线程名、traceId、spanId | 🟢 | `JQuickSpan` |
| 入参采集 | 🟢 | `trace.args=true` 按需开启，开启时才真正构造参数数组 |
| 返回值采集 | 🟢 | `trace.result=true` 按需开启，返回值装箱后走改写管线 |
| 异常与堆栈采集 | 🟢 | `trace.error.stack=true` 按需开启；栈是 JVM 抛异常时已经填好的，因此不需要额外发射字节码 |
| 线程池链路传递 | 🟢 | 通过 JDK 白名单包装 `ThreadPoolExecutor#execute` —— 与 SkyWalking 相同的做法 |
| 采样 | 🟢 | `trace.sampler.rate` |
| 请求量 / 错误率 / 耗时指标 | 🟢 | `JQuickMetricsListener` → counter 与 summary |
| 慢调用识别 | 🟢 | `trace.slow.ms` → span 上的 `SLOW` 标记 |
| 服务拓扑 | 🟢 | 由出站 span 推导 caller → callee 调用边，同时输出 Prometheus 序列与可读调用图 |
| Prometheus 抓取端点 | 🟢 | `metrics.port`；`/metrics`、`/topology`、`/config`、`/health` |
| JDBC / SQL：语句构成、耗时、慢查询 | 🟢 | `JQuickJdbcPlugin` 按继承体系选择 `java.sql.Statement` 及其同类 |
| 未捕获异常、线程普查、泄漏检测 | 🟢 | 可级联的默认处理器 + `jquick_threads` gauge |
| 日志增强：消息内 traceId、敏感信息脱敏 | 🟢 | 作用于日志实现类的参数改写器；不用 MDC，不改日志配置 |
| 入站 HTTP 入口 span（Servlet / Spring MVC / Netty） | 🔵 | 目标清单已提供；不内置任何框架，测试环境也没有容器可供验证 |
| 缓存 / 消息队列出站 span（Redis / JMS / Kafka / RabbitMQ / RocketMQ） | 🔵 | 目标清单已提供；请结合你的中间件实测 |
| 运行期诊断（调用次数、热点方法、动态打日志） | 🟢 | 同一套钩子也支撑覆盖率探针与调试日志 |
| GC 事件监听 | ⚪ | 需要 JVMTI 回调，不属于字节码插桩 |

### 二、流量治理与稳定性

| 能力 | 状态 | 实现方式 |
| --- | --- | --- |
| 方法级限流（QPS + 突发） | 🟢 | `JQuickRateLimiter`，挂在 `JQuickInvocationGate` 上 |
| 方法级熔断（窗口内失败率、打开时长） | 🟢 | `JQuickCircuitBreaker`；被拒绝的调用抛 `JQuickRejectedException` |
| 方法级黑白名单 | 🟢 | `governance.blacklist` 以及 `includes` / `excludes` |
| 统一鉴权 | 🟢 | `JQuickAuthorizer` + `JQuickPrincipalResolver` |
| 治理指标（通过 / 拒绝 / 熔断打开） | 🟢 | `JQuickGovernanceMetrics` |
| 安全审计记录 | 🟢 | `JQuickAuditListener` |
| 流量染色 / 灰度路由 | ⚪ | 属于网关/路由层职责，不应放在方法钩子里 |

### 三、运行期防护与修复

| 能力 | 状态 | 实现方式 |
| --- | --- | --- |
| 阻断 `Runtime.exec` / `ProcessBuilder` | 🟢 | `security.command.check` |
| 阻断危险反射 | 🟢 | `security.reflection.check` |
| SQL 注入启发式检查 + 语句长度上限 | 🟢 | `security.sql.injection.check`、`security.sql.max.length` |
| 反序列化防护（`readObject`） | 🟢 | `security.deserialize.check` |
| 不重启替换方法体（热修复） | 🟢 | `JQuickHotFixManager` + `redefineClasses`，边界由 JVM 结构规则决定 |
| 类加载黑名单 | 🟢 | `classload.blacklist` |
| jar / 重复类冲突检测 | 🟢 | `JQuickJarConflictDetector` |
| 线程泄漏检测 | 🟢 | 两次 `JQuickThreadMonitor` 快照按角色比对 |
| 单元测试打桩外部依赖 | 🟢 | `JQuickStubResolver` —— 逐方法返回指定值或抛指定异常 |
| 代码覆盖率探针 | 🟢 | `JQuickCoverage`，数据来自同一套钩子 |
| RASP：禁止加载危险类 | 🔵 | 转换器中返回 `null` 并告警 |

### 四、数据与 ORM

| 能力 | 状态 | 实现方式 |
| --- | --- | --- |
| JDBC 拦截 | 🟢 | `JQuickJdbcPlugin`，基于继承体系 |
| SQL 改写：租户过滤、分库分表提示、方言适配 | 🟢 | `JQuickSqlRewriter` SPI，写回参数 |
| 入参/返回值透明加解密 | 🟢 | `JQuickCryptoPlugin` + AES 实现，作用于改写管线 |
| 数据库字段透明脱敏 | 🟢 | `JQuickMaskPlugin` 在方法边界改写返回值 |
| 租户 ID 自动填充 | 🟢 | `JQuickTenantSqlRewriter` + `JQuickTenantContext` |
| 单元测试 Mock | 🟢 | 见「运行期防护与修复」 |

---

## 可直接运行的 Demo

四个脚本、两种模式、同一个极小的示例应用，完整说明见 [`demo/README.md`](demo/README.md)：

```bash
demo/run-premain.bat     # 或 ./demo/run-premain.sh     -javaagent，启动即挂载
demo/run-agentmain.bat   # 或 ./demo/run-agentmain.sh   Attach API + retransformClasses
```

Demo 的目标是让每种机制**在输出里看得见**，而不是只在文档里被断言。一个只有 5 个类的应用，在 premain 模式下会
输出（节选）：

```
[jquick-agent] [INFO] active capabilities: trace, metrics, jdbc, log, mask, thread
[jquick-agent] [1a2f9979:3a4950a3] main
    |- com.demo.DemoService#add(int, int) [0ms] component=method,mask  args=[2, 3]  result=5
[main] INFO  [1a2f99792a295a27bafc91ea2fe9a07a] checkout started for alice mobile=138****8000
[jquick-agent] [1a2f9979:38fa686f] main
    |- com.demo.DemoService#pay(java.lang.String, long) [0ms] component=method,mask ERROR=java.lang.IllegalArgumentException: amount must not be negative: -1  args=[bob, -1]
    at com.demo.DemoService.pay(Unknown Source)
    at com.demo.DemoApplication.exercise(Unknown Source)
[app] the application's own catch ran: amount must not be negative: -1
[jquick-agent] SLOW SQL (85ms) select id from orders where user_name = 'alice'
[main] INFO  [1a2f99792a295a27bafc91ea2fe9a07a] checkout finished for alice
[pool-1-thread-1] INFO  [1a2f99792a295a27bafc91ea2fe9a07a] worker: this line carries the trace id of the submitting thread
```

仔细看这段输出，四项能力同时得到证明：业务自己的日志行被注入了 traceId（`1a2f9979…`），**并同时把手机号脱敏了**；
一个从未拿到过上下文的线程打印出**完全相同的 traceId**；失败调用带出了异常与调用栈；而业务代码自己的 `catch`
依然照常执行 —— 插桩只是追加了指令，并没有拿走开发者的异常处理器。

attach 模式则直接展示了两种模式的差别：目标 JVM 在挂载前跑同一段代码，**不会**输出任何 `[jquick-agent]` 行；
挂载之后

```
[jquick-agent] [INFO] installing (mode=agentmain)
[jquick-agent] [INFO] retransformed 6 already-loaded class(es)
```

之后每一次调用都会被观测到。配合一个在线的 `metrics.port`：

```
$ curl -s http://127.0.0.1:9090/topology
com.demo.DemoService#record(java.lang.String, java.lang.String)
    -> com.demo.DemoStatement#execute(java.lang.String) [component=method,jdbc] calls=12 avgMs=44 maxMs=92 errors=0

$ curl -s http://127.0.0.1:9090/metrics | grep jquick_sql
jquick_sql_executions_total{sql_type="SELECT"} 6
jquick_sql_executions_total{sql_type="INSERT"} 6
jquick_slow_sql_total{sql_type="SELECT"} 6
jquick_sql_duration_seconds_count{sql_type="SELECT"} 6
jquick_sql_duration_seconds_sum{sql_type="SELECT"} 0.550924697
```

---

## 能力限制与注意事项

以下都是 JVM 的客观约束，而不是“还没做”。上线前请逐条确认。

1. **运行中的 JVM 只能改方法体。** JVMS 禁止以“增删字段/方法、修改签名、改动继承关系”的方式重定义已加载的类。
   因此 `redefineClasses` / `retransformClasses` 无法新增字段或更换父类。这正是热修复能力对外声明的边界 ——
   而且它是被 JVM 强制执行的，不是靠我们自己的账本。
2. **JDK 9+ 与 JPMS。** 具名模块限制深度反射，并缩小了可重转换的范围。预期需要 `--add-opens` / `--add-exports`；
   另外 JDK 9+ 的 `ClassFileTransformer` 有一个 `transform(Module, …)` 重载，本 Agent 未实现。**已验证的配置是
   JDK 8。**
3. **JDK 核心类大多不可重转换。** `isModifiableClass` 对相当一部分 bootstrap 类返回 `false`。可以通过 `allowJdk`
   按类放松 JDK 过滤（线程池上下文传递就需要 `java.util.concurrent.ThreadPoolExecutor`），但必须是明确决定：
   启动时会把白名单打印出来，因为对“整个 JVM 都在用的类”插桩，是运维人员必须知情的选择。
4. **native 方法没有方法体可改。** 只能对调用点周围的代码插桩。
5. **重转换不可撤销。** `addTransformer` 没有对应的反向操作。能力可以在运行期失效
   （`JQuickEnvironment#setEnabled(false)`），但已经注入的指令留在类里。
6. **与其他 Agent / AOP 框架可能冲突。** 转换器顺序无保证，且本 jar 打包 ASM 时**未做 relocate**，因此宿主机上
   已存在不同版本 ASM（应用自带或另一个 Agent 带入）是最常见的冲突。嵌入第三方产品时请做 relocate。Agent 在
   能力范围内会自保：会导致失败的增强会被放弃并打印日志，而不是硬套上去；不动的字节码会原样返回。
7. **Lombok 与编译期字节码生成插件只影响编译期。** 它们产出的就是普通字节码，因此与 Agent 不冲突；改变的是
   Agent 看到的形状（生成的 getter、合成方法）。合成方法与桥接方法会被跳过。
8. **不插桩构造方法与静态初始化块。** 静态初始化块没有调用者，为它生成 span 没有意义；更关键的是，把 Agent 的
   运行期调用注入其中，会让 Agent 自身的类在类初始化过程中被解析，而在那里发生失败会使该类在整个 JVM 生命周期内
   不可用。这正是“故障必须被兜住”这条规则要排除的风险类型。
9. **异常采集覆盖的是方法自身抛出的 `ATHROW`。** 由被调用方抛出、只是“穿过”某方法向外传播的异常，不会执行该方法
   自己的任何指令，因此**不会为这一帧**产生事件；真正抛出的那一帧会上报。这是零侵入的必然结果：整方法体的
   `catch (Throwable)` 会成为异常表里第一条匹配记录，从而静默地让开发者自己的 `try/catch` 失效。
   `EnhancementTest` 把这一行为固定了下来。
10. **性能开销。** 每启用一个钩子，成本是一次 `ThreadLocal` 读取，加上（开启载荷采集时的）一个小数组和一次装箱。
    `trace.args` / `trace.result` / `trace.error.stack` 默认全部关闭，因为每一项都是真实开销 —— 启动报告里的
    `hook plan: …` 行会明确告诉你当前到底发射了哪些。
11. **监听器会收到每一个被插桩的调用。** 凡是按“参数长得像什么”来过滤的能力，都必须同时校验增强器写下的组件标签；
    否则一个接收 SQL 字符串的私有辅助方法也会被计入数据库往返。`JdbcCapabilityTest` 已把这条固定下来。
12. **指标端点默认关闭，且不能比应用活得更久。** 启用后绑定 `0.0.0.0`（抓取方通常是另一个进程或另一台机器），
    因此在网络不可信的机器上请设 `metrics.bind=127.0.0.1`；同时注意它的 HTTP 服务是特意在守护线程上启动的 ——
    非守护线程会让 JVM 永远无法退出。`MetricsEndpointTest` 同样把这条固定了下来。

---

## 构建 & 单元测试

```bash
mvn clean test      # 140 个测试
mvn clean package   # 产出可直接使用的自包含 agent jar
```

| 项 | 要求 |
| --- | --- |
| JDK | 1.8+（source/target 1.8）；已在 JDK 8 上验证 |
| Maven | 3.x |
| 运行期依赖 | jquick-asm 1.3.0（已打进产物） |
| 测试依赖 | JUnit 4.13.2 |

`target/` 下的产物：

| 文件 | 用途 |
| --- | --- |
| `jquick-agent-1.0.0.jar` | **Agent 本体** —— 自包含（已内置 jquick-asm 与 asm），manifest 正确 |
| `jquick-agent-1.0.0-sources.jar` | 源码包 |
| `jquick-agent-1.0.0-javadoc.jar` | javadoc 包 |
| `original-jquick-agent-1.0.0.jar` | shade 之前的瘦 jar |

140 个测试的覆盖情况：

| 测试类 | 关注点 |
| --- | --- |
| `ConfigurationTest` | 参数语法（`;` 分隔键值对、`,` 分隔值内列表）与类型化视图 |
| `EnhancementTest` | 真实增强 → 私有类加载器 → 真实调用；可读签名、耗时、业务自己的 `try/catch` |
| `TraceRuntimeTest` | 链路上下文、span 嵌套、采样、跨线程 capture/restore |
| `PayloadCaptureTest` | 入参/返回值/堆栈采集、截断、值格式化的安全规则 |
| `MetricsAndTopologyTest` | Prometheus 暴露格式、标签基数、拓扑调用边 |
| `MetricsEndpointTest` | HTTP 端点：各路径、密钥脱敏、端口占用，**以及它永远不会拖住 JVM 退出** |
| `JdbcCapabilityTest` | 数据库能力，包括“仅参数像 SQL 并不等于数据库调用” |
| `GovernanceTest` | 限流、熔断、拒绝、治理指标 |
| `DataProtectionTest` | AES 变换、脱敏模式、改写管线 |
| `StubbingTest` | 打桩：返回值、抛异常、方法之间的隔离 |
| `HotFixTest` | `redefineClasses` 各条路径与防御性输入 |
| `CoverageTest` | 覆盖率探针 |
| `RuntimeGuardTest` | 线程普查、泄漏检测、未捕获异常级联、类加载黑名单、jar 冲突 |
| `BootstrapTest` | 装配顺序、默认姿态（只观测）、按启动模式决定是否重转换 |

端到端测试不断言字节数组：它们增强真实的夹具类，把结果定义在私有类加载器里（由 JVM 的校验器判定是否合法），然后
真实调用。已有四个真实缺陷是这样被发现、而不是靠肉眼审查发现的 —— 被吞掉的 `stubThrow`、一次数组类型转换、
一个线程池顺序问题，以及一个报错类名不对的 classpath 冲突检测器。

---

## FAQ 常见问题

**Q1. 支持哪些 JDK 版本？**
编译与验证均在 JDK 8 完成。字节码引擎（经由 jquick-asm 的 ASM 9.6）可以读写 JDK 8 到 JDK 21 的 class 文件，
但在 JDK 9+ 上要注意 JPMS（见限制 2）以及模块化的 transformer 重载。

**Q2. 什么都没被增强，从哪查起？**
`includes` 默认为空，而“空”意味着**不增强任何类**（Agent 的安全默认值）。请传 `includes=com.demo`，或
`includes=*`。这种情况下 Agent 会在安装时明确打印
`no 'includes' rule configured: no class will be instrumented`。然后看启动报告：
`candidates=0` 说明类被过滤器挡掉了，`candidates>0` 而 `enhanced=0` 说明所有增强器都拒绝了它。

**Q3. 在容器 / Kubernetes 里 `VirtualMachine.attach(pid)` 失败。**
Attach 需要可写的临时目录以及相同的 OS 用户。常见原因：`java.io.tmpdir` 不可写或未共享、
JVM 启动带了 `-XX:+DisableAttachMechanism`、用户/UID 不一致、或 PID 命名空间不匹配 —— 你在容器外看到的 PID
并不是容器内的 PID。可用 `jcmd <pid> VM.command_line` 确认可见性，或在容器场景直接使用 `premain`。

**Q4. JDK 8 上 attach 报 `NoClassDefFoundError: com/sun/tools/attach/VirtualMachine`。**
JDK 8 的 Attach API 位于 `${JAVA_HOME}/lib/tools.jar`，需要加入 classpath
（`-cp app.jar;$JAVA_HOME/lib/tools.jar`）；JDK 9+ 在 `jdk.attach` 模块里，无需额外配置。Demo 脚本已自动处理。

**Q5. 同一个 JVM 上挂两个 Agent 会怎样？**
转换器顺序不确定，两者可能看到同一份字节码。两个链路 Agent 会重复插桩（事件与开销都翻倍），而先返回 `null`
的转换器无法“撤销”前一个。本 jar 打包 ASM **未做 relocate**，因此宿主机上存在不同版本 ASM 是最可能的冲突源。
请实测组合，或做 relocate。

**Q6. 为什么 `retransformClasses` 被拒绝或被静默忽略？**
bootstrap 类、正在被重定义的类，以及任何试图做结构性改动的场景，`isModifiableClass` 都会返回 `false`。
本 Agent 会先检查 `isModifiableClass`，逐类重转换，并把拒绝的类打印出来。

**Q7. 插桩之后为什么我的 `catch` 还好好的？**
这正是设计目标。Agent 从不注册 catch-all 处理器，异常表保持不变，JVM 按编译器的意图解析异常。原因见限制 9。

**Q8. 启动时为什么打印 `retransformed 0 already-loaded class(es)`？**
因为在 `premain` 阶段你的业务类还没被加载。也正因如此，premain 模式下 `retransform` 默认是 `false`，它只对那些
被框架提前加载的类有意义 —— Demo 里显式传 `retransform=true`，就是为了同时覆盖
`java.util.concurrent.ThreadPoolExecutor`。

**Q9. 日志里还是没有 traceId。**
按顺序查三件事：`log.enabled=true` 是否生效；`log.includes` 写的是不是你的日志器**实现的**那个类型（插件选择的
是命名日志接口的实现类，而不是接口本身）；以及该日志调用是否发生在被插桩的方法内 —— 前缀来自当前线程的链路
上下文，一个从未拥有过上下文的线程打印的日志不会被装饰。线程池传递就是用来解决最常见的这种情况的。

**Q10. 性能影响有多大？**
没有任何钩子武装时，注入的入口序列只是一次 `ThreadLocal` 读取。关闭载荷采集、只开链路日志时，成本是 id 生成
加上每次调用一个小对象，外加你的监听器自身的开销（如果打印，就是 I/O）。启动报告里的 hook plan 行会列清当前
发射了哪些片段。

**Q11. 如何新增一项能力？**
实现 `JQuickPlugin` 并注册 —— 加到 `JQuickPlugins.builtIn()`，或者从另一个 jar 通过 `META-INF/services`
提供。插件内部，字节码侧用 `JQuickInstrumentEnhancer`，运行期侧注册监听器、闸门、改写器或打桩解析器即可。
内核不需要任何改动。

---

## 国内使用说明

- **Maven 中央仓库**：本模块坐标 `io.github.paohaijiao:jquick-agent`。国内网络如访问不畅，可在 `settings.xml`
  中配置阿里云等镜像仓库；本项目自身只依赖 `io.github.paohaijiao:jquick-asm` 与 JUnit，无需其他外网依赖。
- **JDK**：建议使用国内常用的 **JDK 8（8u202 及以上）**，本模块已验证的即为此配置；若使用 JDK 11/17，请留意
  限制 2 中的 JPMS 说明，并优先在测试环境验证后再上生产。
- **字符编码**：Demo 脚本与源码均显式使用 `-encoding UTF-8`。在 Windows 上使用 GBK 控制台时，Maven 与 javac 的
  报错信息可能出现乱码，属控制台编码问题，不影响构建结果。
- **中间件适配**：数据库（MySQL / PostgreSQL / 达梦 / OceanBase 等，只要走 JDBC）、缓存（Redis）、消息队列
  （RocketMQ / Kafka / RabbitMQ）都可按继承体系命中；若你使用的客户端不在默认目标清单里，通过
  `jdbc.includes` / `cache.includes` / `mq.includes` 补上其父类型即可，无需改代码。
- **合规与脱敏**：内置脱敏规则覆盖手机号、身份证、银行卡、邮箱四类；面向国内业务的日志、审计与接口返回值均可
  直接受益。脱敏与加解密都是**在方法边界**完成的，不进入业务模型。
- **容器与国产化环境**：容器内 attach 失败请参考 FAQ Q3；麒麟、统信等环境请确保 `java.io.tmpdir` 可写。
- **问题反馈**：欢迎在 GitHub Issues 提交，附上启动报告（`hook plan` 与 `transformer` 统计那几行）能极大加速定位。

---

## 开源协议

[Apache License 2.0](LICENSE) —— 与 jquick-asm 保持一致。

作者：Martin（<goudingcheng@gmail.com>）

---

## JQuick 生态相关项目

| 项目 | 说明 |
| --- | --- |
| [jquick-asm](https://github.com/paohaijiao/jquick-asm) | 本 Agent 所依赖的 ASM 9.x 高层字节码库 |
| [javelin](https://github.com/paohaijiao/javelin) | JQuick 各模块共用的基础工具与构建父工程 |
| [jquick-curl](https://github.com/paohaijiao/jquick-curl) | 声明式 HTTP 客户端 |
| [jquick-excel](https://github.com/paohaijiao/jquick-excel) | 注解驱动的 Excel 导入导出 |
| [jquick-json](https://github.com/paohaijiao/jquick-json) | 轻量 JSON 工具包 |
| [jquick-java](https://github.com/paohaijiao/jquick-java) | 进程内 Java 编译与执行 |
| [jquick-mcp](https://github.com/paohaijiao/jquick-mcp) | JQuick 生态的 MCP 工具链 |

---

## SEO 关键词

GitHub 仓库 **Topics** 建议：

```
java-agent, javaagent, bytecode, bytecode-manipulation, asm, asm9, instrumentation,
premain, agentmain, attach-api, retransform, redefineclasses, javassist-alternative,
apm, jvm, observability, distributed-tracing, traceid, prometheus, metrics, topology,
sql-monitoring, slow-query, log-masking, data-masking, rate-limiting, circuit-breaker,
rasp, hotfix, coverage, java-agent-framework, jquick, jquick-asm, apache-2
```

**搜索短语**（README 关键词）建议：

> java agent premain agentmain 示例, javaagent 字节码插桩, ASM 9 字节码增强, retransformClasses attach api,
> jvm 方法链路追踪, 日志注入 traceId, 不改代码监控 JDBC, 慢 SQL 检测 java agent, Prometheus 指标 java agent,
> 方法调用生成服务拓扑, 开源 APM java agent, 基于 jquick-asm 的 agent, java 热修复不重启,
> RASP java agent, 限流熔断 java agent, java agent 单元测试打桩, java agent 覆盖率探针,
> 零侵入 java 插桩, javassist 替代方案, spring boot java agent
