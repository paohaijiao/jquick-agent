# jquick-agent

> A production-oriented **Java Agent framework** for `premain` and `agentmain` mounting, built on top
> of [**jquick-asm**](https://github.com/paohaijiao/jquick-asm), that observes and governs a running
> JVM **without touching the application source code**.

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0.txt)
[![Java](https://img.shields.io/badge/JDK-1.8%2B-orange.svg)](https://www.oracle.com/java/)
[![ASM](https://img.shields.io/badge/ASM-9.6-red.svg)](https://asm.ow2.io/)
[![based on jquick-asm](https://img.shields.io/badge/based%20on-jquick--asm-brightgreen.svg)](https://github.com/paohaijiao/jquick-asm)
[![tests](https://img.shields.io/badge/tests-140%20green-brightgreen.svg)](#build--test)

---

## Table of Contents

- [Project Introduction](#project-introduction)
- [Features](#features)
- [Module Design & Package Structure](#module-design--package-structure)
- [What is jquick-asm](#what-is-jquick-asm)
- [Quick Start](#quick-start)
- [Java Agent Two Modes: premain / agentmain](#java-agent-two-modes-premain--agentmain)
- [Core Use Cases](#core-use-cases)
- [Runnable Demos](#runnable-demos)
- [Limitations & Caveats](#limitations--caveats)
- [Build & Test](#build--test)
- [FAQ](#faq)
- [License](#license)
- [Related JQuick Ecosystem Projects](#related-jquick-ecosystem-projects)
- [SEO / GitHub Topics](#seo--github-topics)

---

## Project Introduction

`jquick-agent` is mounted either at JVM startup (`-javaagent`, i.e. `premain`) or into an
already-running process (Attach API, i.e. `agentmain`). From that moment on it observes — and, when
asked, changes — the behaviour of the application at the bytecode level.

Three ideas define the project:

1. **Zero intrusion is a hard constraint, not a slogan.** The agent only *appends instructions* to
   method bodies. It never rewrites the class structure, never adds a field or a method, and — the
   rule that shapes the whole design — **never registers a competing exception handler**. The JVM
   resolves an exception to the *first* matching entry of the exception table, so an agent-installed
   `catch (Throwable)` would silently disable the application's own `try/catch`. Every failure inside
   the agent is contained and logged, so a broken enhancement can never take the host application
   down.
2. **jquick-asm does the bytecode work.** No `ClassVisitor` / `MethodVisitor` is hand-written in this
   repository. All instruction generation is delegated to jquick-asm's fluent high-level API
   (`JQuickMethodEnhancer`, `JQuickMethodAdvice`, `JQuickClassReaderTool`, `JQuickClassInfo`).
3. **The kernel names no capability.** The framework has exactly one extension root — `JQuickPlugin` —
   and it covers *both* sides of a capability: the bytecode side (`JQuickClassEnhancer`) and the
   runtime side (`JQuickMethodListener`, gates, transformers, stub resolvers). The kernel
   (`JQuickEnvironment`, `JQuickInstrumentEnhancer`, `JQuickSink`) never mentions a feature by name;
   the capability list lives in one readable, ordered place, `JQuickPlugins.builtIn()`, and can be
   extended from outside the jar through `META-INF/services`.

The default artifact is a **self-contained shaded jar**: jquick-asm and ASM are bundled, so
`-javaagent:jquick-agent-1.0.0.jar=...` works with no extra class path entries.

Everything the agent can do is configured with **one flat key/value string** on the command line or
the attach call, and the effective configuration is printed at startup — so an operator never has to
guess which capabilities are live.

---

## Features

| Feature | Description |
| --- | --- |
| Two mounting modes | `premain` (`-javaagent`) and `agentmain` (runtime attach) from a single jar |
| Retransformation | `agentmain` retransforms already-loaded classes; the demo reports `retransformed 6 already-loaded class(es)` |
| Distributed tracing | 128-bit trace id, 64-bit span id, parent span, per-thread span stack, `ENTRY` / `EXIT` / `LOCAL` span types, sampling |
| Payload capture | Arguments, return value and the exception stack trace, each **individually opt-in** and printed only when asked for |
| Database capability | JDBC statement spans, statement-type metrics, per-type latency, slow-query counter and log, tenant SQL rewriting through a pluggable `JQuickSqlRewriter` |
| Component integration | Inbound HTTP (`ENTRY`), and outbound cache / message-queue / JDBC (`EXIT`) spans, selected by **hierarchy**, not by driver class name |
| Metrics | Prometheus exposition (`counter` / `summary` / `gauge`) for calls, errors, latency, SQL and governance, with a built-in scrape endpoint |
| Service topology | Caller → callee edges derived from outgoing spans, exposed both as Prometheus series and as a readable call graph |
| Log enhancement | Trace id injected into the log message and sensitive values masked, without a logging configuration change and without MDC |
| Traffic governance | Method-level rate limiting (QPS + burst) and circuit breaking (failure rate over a window) with their own metrics |
| Security controls | Blocking checks for command execution, reflection, SQL injection and deserialization, plus a statement-length limit |
| Data protection | Transparent AES encryption/decryption of arguments and return values; pattern-based masking of mobile numbers, identity cards, bank cards and e-mail addresses |
| Audit | Structured audit records with bounded, truncation-safe rendering of every argument |
| Stubbing | Full stub resolver for unit tests: return a value, or throw, for any selected method, without touching it |
| Hot fix | Replace a method body in a live JVM through `redefineClasses`, bounded by the JVM's own structural rules |
| Coverage probe | Which methods ran, and how many times — collected by the same hooks, with no instrumentation of the test build |
| Runtime guards | Thread census, leak detection, chained uncaught-exception handler, class-load blacklist, jar-conflict detection |
| Declarative opt-in / opt-out | `@JQuickTrace` restricts instrumentation to annotated code; `@JQuickIgnored` excludes one hot method |
| Failure containment | Every transformer / enhancer / listener / gate call is individually wrapped; the application always keeps running |
| Discovery | Third-party capabilities are loaded through `ServiceLoader`; a jar can never silently replace a built-in capability |

---

## Module Design & Package Structure

```
io.github.paohaijiao.jquick.agent
├── bootstrap        Agent entry points and installation
│   ├── JQuickAgent             premain(String, Instrumentation) / agentmain(String, Instrumentation)
│   ├── JQuickBootstrap         parse → environment → plugins → transformer → retransform → report
│   ├── JQuickTransformer       the single ClassFileTransformer: screen → parse → enhancer chain
│   ├── JQuickArgumentParser    dependency-free parser for "jar=a=b;c=d"
│   ├── JQuickPlugins           the plugin catalogue (built-ins in a fixed order) + SPI merge
│   └── JQuickMetricsExporter   the built-in Prometheus endpoint (com.sun.net.httpserver)
├── core             The kernel — no capability names appear here
│   ├── JQuickPlugin            the only extension root: isEnabled / initialize / enhancers
│   ├── JQuickEnvironment       installed configuration, enhancers, listeners, gates, transformers, meters
│   ├── JQuickInstrumentEnhancer  turns a (class matcher, method matcher) into injected hooks
│   ├── JQuickHookPlan          what the injected sequence costs: args, transformArgs, result, transformResult, mock, threadPool
│   ├── JQuickClassMatcher      name / package / wildcard / super-type / annotation selection
│   ├── JQuickMethodMatcher     name, prefix, annotation, exclusion rules
│   ├── JQuickMethodListener    runtime side of a capability: capture flags + onMethodEvent
│   ├── JQuickInvocationGate    a veto point in front of the method body (rate limit, circuit, security)
│   ├── JQuickArgumentTransformer / JQuickResultTransformer   payload rewriting SPI
│   ├── JQuickStubResolver / JQuickStubAction                 test stubbing SPI
│   └── JQuickConfig            typed view over the flat key/value configuration
├── runtime
│   ├── JQuickSink              the static anchor the injected bytecode calls (enter / exit / error / mock)
│   └── JQuickTraceAwareRunnable  the task wrapper used by the thread-pool hand-off
├── trace            JQuickTraceContext, JQuickTraceScope, JQuickSpan, JQuickSpanType,
│                    JQuickTraceSampler, JQuickTraceSnapshot
├── metrics          JQuickMeterRegistry, JQuickCounter, JQuickTimer, JQuickGauge, JQuickSample
├── topology         JQuickTopology (edges, readable render, Prometheus line)
├── plugin           one package per capability
│   ├── trace        JQuickTracePlugin / JQuickTraceListener
│   ├── metrics      JQuickMetricsPlugin / JQuickMetricsListener
│   ├── jdbc         JQuickJdbcPlugin / JQuickJdbcListener / JQuickSqlRewriter / JQuickTenantSqlRewriter
│   ├── component    JQuickHttpPlugin / JQuickCachePlugin / JQuickMqPlugin / JQuickMessageHeaders
│   ├── log          JQuickLogPlugin / JQuickLogTransformer / JQuickLogSupport
│   ├── security     JQuickSecurityPlugin / JQuickSecurityGate / JQuickSecurityGuard
│   ├── governance   rate limiter, circuit breaker, authorizer, governance gate + metrics
│   ├── audit        JQuickAuditPlugin / JQuickAuditListener
│   ├── crypto       JQuickAesCipher, JQuickDataCipher, transformers
│   ├── mask         JQuickSensitiveMasker, JQuickSensitiveTransformer
│   ├── mock         JQuickMockRegistry
│   ├── hotfix       JQuickHotFixManager
│   ├── coverage     JQuickCoverage
│   ├── thread       JQuickThreadPlugin / JQuickThreadMonitor / JQuickUncaughtExceptionHandler
│   └── classload    JQuickClassLoadGuard / JQuickJarConflictDetector
├── support          JQuickBytecodeEmitter, JQuickValueFormatter, JQuickClassFilters,
│                    JQuickDescriptorUtil, JQuickStringUtil, JQuickIdGenerator, JQuickLogger
└── annotation       @JQuickTrace (opt in), @JQuickIgnored (opt out)
```

**Why the kernel is capability-free.** A framework whose kernel knows the names of its features can
only be extended by editing the kernel. Here, adding a capability means writing one `JQuickPlugin` and
listing it — or dropping a jar on the classpath with a `META-INF/services` file. A discovered plugin
whose name collides with a built-in **loses**, because a third-party jar must not be able to replace
the tracer; the collision is logged.

**Data flow**

```
JVM class load / retransformClasses
        │
        ▼
JQuickTransformer (one instance, one pass)
        │  cheap string screening first: unnamed → JVM core → agent packages
        │  → JDK (unless a plugin asked for that class) → includes / excludes
        ▼
JQuickClassMeta.of(bytes)                       ← the only parse, and only for real candidates
        │
        ▼
for each JQuickClassEnhancer ──▶ JQuickInstrumentEnhancer
        │                          JQuickMethodEnhancer.from(bytes)
        │                              .match(method matcher)
        │                              .advice(onEnter / onExit(+ATHROW))
        │                              .apply()
        │                          plan = environment plan ∪ own ∪ siblings' requirements
        ▼
enhanced bytes ──▶ Instrumentation
        │
        ▼  (at runtime, from inside the application's own class loader)
JQuickSink.enter/exit/error ──▶ JQuickMethodEvent ──▶ listeners ──▶ trace log / metrics / topology
                                   │
                                   ├─ gates      (may veto: rate limit, circuit breaker, security)
                                   ├─ argument transformers (log prefix, masking, tenant SQL, crypto)
                                   └─ result transformers   (masking, crypto)
```

Two invariants are worth stating because they are the reason the code looks the way it does:

- **Instrumentation is idempotent.** Before emitting, the enhancer scans the incoming bytes for a call
  to `JQuickSink` and leaves methods that already report alone. Without it, a class selected by two
  capabilities would be instrumented twice — observed twice, counted twice, and for a result
  transformer such as encryption, *transformed twice*, which is not idempotent and silently corrupts
  the value.
- **Hook emission is independent of registration order.** The hook plan for a class is the union of
  the environment plan, the enhancer's own requirement, and the requirement of *every sibling enhancer
  whose matcher also accepts that class*. Otherwise the first enhancer to accept a class would emit a
  minimal sequence and a capability that needed, say, result transformation would never see it.

---

## What is jquick-asm

[jquick-asm](https://github.com/paohaijiao/jquick-asm) is the JQuick ecosystem's foundational
bytecode library. It is a *pure native encapsulation* on top of ASM 9.x: it hides the tedium of
`ClassVisitor` / `MethodVisitor` and exposes exactly three capabilities — **read**, **write** and
**enhance** a class.

```java
// enhance: add enter/exit hooks to matching methods, without a visitor
byte[] enhanced = JQuickMethodEnhancer.from(originalBytes)
        .match(meta -> meta.name.startsWith("handle"))
        .advice(JQuickMethodAdvice.builder()
                .onEnter(ctx -> ctx.methodVisitor().visitLdcInsn("enter: " + ctx.name()))
                .onExit((ctx, opcode) -> { /* ... */ })
                .build())
        .apply();
```

`jquick-agent` uses four things from it: `JQuickMethodEnhancer` (the enhancement API),
`JQuickMethodAdvice` (the enter/exit advice), `JQuickClassInfo` / `JQuickMethodInfo` (class and method
metadata, used for matching and annotation lookup) and `JQuickEnhanceGuard` (the safety guard).
Nothing else in the agent touches ASM directly — which is what keeps the generated bytecode
consistent, and what makes the whole emit path testable as a unit.

---

## Quick Start

### Maven dependency

The agent is normally consumed as a **runnable jar**, not as a compile dependency:

```bash
mvn clean package
# -> target/jquick-agent-1.0.0.jar   (self-contained: jquick-asm + asm are bundled)
```

Add it to a build only when you want to *embed* the API (custom plugins, listeners, gates):

```xml
<dependency>
    <groupId>io.github.paohaijiao</groupId>
    <artifactId>jquick-agent</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Minimal demo

Target application — nothing about the agent is visible here:

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

Run it with the agent mounted:

```bash
java -javaagent:target/jquick-agent-1.0.0.jar=includes=com.demo;trace.print=true;trace.result=true \
     -cp target/demo-classes com.demo.Demo
```

Output:

```
[jquick-agent] [INFO] installing (mode=premain)
[jquick-agent] [8dce26ee:7edacf85] main
  |- com.demo.Demo#greet(java.lang.String) [0ms] component=method  result=hello jquick
hello jquick
```

Add `trace.args=true` and the arguments appear as well; add `trace.error.stack=true` and a failing call
prints its frames. Nothing is captured unless asked for, because every one of those flags is a cost.

### Agent arguments

```
-javaagent:jquick-agent-1.0.0.jar=includes=com.demo,com.acme;excludes=com.demo.legacy;trace.print=true
```

Pairs are separated by `;`, items inside a value by `,`. Every key below is read by the agent; the
effective set is printed at startup and served by `GET /config` (with secret-looking values redacted).

| Key | Default | Meaning |
| --- | --- | --- |
| `includes` | *(empty)* | class rules to instrument: exact name, package prefix, or glob. `*` = every application class. **Empty means nothing is instrumented.** |
| `excludes` | *(empty)* | class rules to skip even when included |
| `allowJdk` | *(empty)* | JDK classes that may be instrumented despite the infrastructure filter (e.g. `java.util.concurrent.ThreadPoolExecutor`) |
| `retransform` | `false` on premain, `true` on attach | re-run instrumentation on already-loaded classes |
| `quiet` / `debug` | `false` | suppress all diagnostics / verbose bootstrap diagnostics |
| `trace.enabled` | `true` | install the method tracer |
| `trace.print` | `false` | print one line per span |
| `trace.args` / `trace.result` | `false` | capture (and print) arguments / return values |
| `trace.error.stack` | `false` | print the frames of a failing call |
| `trace.slow.ms` | `200` | duration at or above which a span is flagged as slow |
| `trace.sampler.rate` | `1` | keep every Nth trace |
| `trace.annotation.only` | `false` | instrument only `@JQuickTrace` code |
| `metrics.enabled` | `true` | collect meters (in-process) |
| `metrics.port` | `0` | port of the Prometheus endpoint; **`0` disables the HTTP server** |
| `metrics.bind` / `metrics.path` | `0.0.0.0` / `/metrics` | bind address and exposition path |
| `metrics.labels` | `class` | `class` or `method` label granularity |
| `topology.enabled` | `false` | derive and expose the service topology |
| `jdbc.enabled` | `false` | database capability |
| `jdbc.includes` | `java.sql.Statement`, `PreparedStatement`, `CallableStatement`, `Connection`, `javax.sql.DataSource`, … | hierarchy names to select |
| `jdbc.slow.ms` | `trace.slow.ms` | slow-statement threshold |
| `jdbc.tenant.enabled` / `jdbc.tenant.column` | `false` / `tenant_id` | rewrite statements to add the tenant predicate |
| `http.enabled` / `cache.enabled` / `mq.enabled` | `false` | component capabilities, each with its own `.includes` list |
| `log.enabled` | `false` | trace-id injection into the log stream |
| `log.includes` | `org.slf4j.Logger`, Log4j 1/2, `java.util.logging.Logger` | logger interfaces/classes whose implementations are instrumented |
| `log.mask.enabled` | `false` | also mask sensitive values inside the message |
| `security.enabled` | `false` | blocking checks (`.command.check`, `.reflection.check`, `.sql.injection.check`, `.deserialize.check`, `.sql.max.length`) |
| `governance.enabled` | `false` | rate limiting (`rate.limit.qps`, `rate.limit.burst`), circuit breaking (`circuit.failure.rate`, `circuit.window`, `circuit.open.ms`), `governance.blacklist`, `governance.auth` |
| `audit.enabled` | `false` | structured audit records |
| `crypto.enabled` / `crypto.key` | `false` | AES transform of arguments and return values |
| `mask.enabled` / `mask.includes` | `false` | mask sensitive values in return values |
| `mock.enabled` | `false` | install the stub resolver (unit tests) |
| `thread.enabled` | `false` | thread-pool context hand-off, uncaught-exception capture, thread census |
| `coverage.enabled` | `false` | method coverage probe |
| `classload.enabled` | `false` | class-load blacklist (`classload.blacklist`) and conflict detection (`classload.conflict.detect`) |

> **The default posture is observation only.** Installing the agent with no arguments changes nothing
> about the application: `trace` and `metrics` are on because they only read, and all fourteen other
> capabilities are off until an operator asks for them by name.

### Adding your own capability

```java
public final class MyPlugin implements JQuickPlugin {

    public String name() { return "my-capability"; }

    public JQuickCategory category() { return JQuickCategory.TRACING; }

    public boolean isEnabled(JQuickConfig config) { return config.isPluginEnabled(name()); }

    public void initialize(JQuickEnvironment environment) {
        environment.addListener(new MyListener());                 // runtime side
        enhancers.add(new JQuickInstrumentEnhancer(
                name(),
                JQuickClassMatcher.byName("com.acme.OrderService"), // what to select
                JQuickMethodMatcher.named("pay"),                   // which methods
                JQuickSpanType.EXIT,                                // what the span means
                "acme",                                             // component label
                environment));
    }

    public List<JQuickClassEnhancer> enhancers() { return enhancers; }

    public String description() { return "my capability"; }
}
```

Register it by adding one line to `JQuickPlugins.builtIn()` — or, without recompiling the agent, by
shipping a jar with `META-INF/services/io.github.paohaijiao.jquick.agent.core.JQuickPlugin` naming
your class. A plugin must have a public no-argument constructor; it reads configuration in
`initialize`, from the environment that is actually installed.

---

## Java Agent Two Modes: premain / agentmain

| | `premain` | `agentmain` |
| --- | --- | --- |
| When | JVM startup, **before** `main` | at runtime, into a **running** JVM |
| How | `-javaagent:jquick-agent.jar=args` | Attach API: `VirtualMachine.attach(pid).loadAgent(jar, args)` |
| Entry point | `premain(String, Instrumentation)` | `agentmain(String, Instrumentation)` |
| Already-loaded classes | none (or few) | retransformed via `Instrumentation#retransformClasses` |
| `retransform` default | `false` | `true` |
| Typical use | APM agent, permanent tracing | live diagnosis, hot fix, feature switch |

### `premain`

```bash
java -javaagent:/opt/agents/jquick-agent-1.0.0.jar=includes=com.demo -jar app.jar
```

The JVM reads `Premain-Class` from the manifest and calls it before the application's `main`.

### `agentmain`

```java
VirtualMachine vm = VirtualMachine.attach(pid);          // JDK8: tools.jar, JDK9+: jdk.attach
try {
    vm.loadAgent("/opt/agents/jquick-agent-1.0.0.jar", "includes=com.demo,trace.print=true");
} finally {
    vm.detach();
}
```

Because the target classes are already loaded, the bootstrap immediately retransforms every matching,
modifiable class — one class per call, tolerating the ones the JVM refuses, so a single
un-retransformable class cannot abort the batch. That pass is what makes instrumentation effective
without a restart.

Both entry points are declared in the packaged manifest:

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

## Core Use Cases

The tables below are the **full capability scope** of the module, and the status column is deliberately
honest.

Legend — 🟢 shipped in this repository and covered by the test suite ·
🔵 shipped as a selection/extension point that needs a third-party class on the classpath to be
exercised · ⚪ needs a different mechanism, outside bytecode instrumentation.

### 1. Observability

| Capability | Status | How |
| --- | --- | --- |
| Method instrumentation: enter / exit / exception | 🟢 | `JQuickInstrumentEnhancer` on `JQuickMethodEnhancer` |
| Elapsed time per call (nanosecond source, millisecond reporting) | 🟢 | span opened at enter, closed at exit |
| Thread name + trace id + span id on every event | 🟢 | `JQuickSpan` |
| Argument capture | 🟢 | opt-in `trace.args=true`; the emitter materialises the array only then |
| Return-value capture | 🟢 | opt-in `trace.result=true`; the result is boxed and passed through the transform pipeline |
| Exception + stack trace capture | 🟢 | opt-in `trace.error.stack=true`; the JVM already filled the stack in, so no bytecode is emitted for it |
| Trace id propagation across a thread pool | 🟢 | `ThreadPoolExecutor#execute` wrapped via the JDK allow list — the same technique SkyWalking uses |
| Sampling | 🟢 | `trace.sampler.rate` |
| QPS / error rate / latency metrics | 🟢 | `JQuickMetricsListener` → counters and summaries |
| Slow-call detection | 🟢 | `trace.slow.ms` → `SLOW` marker on the span |
| Service topology | 🟢 | caller → callee edges from outgoing spans, as Prometheus series and as a readable graph |
| Prometheus scrape endpoint | 🟢 | `metrics.port`; `/metrics`, `/topology`, `/config`, `/health` |
| JDBC / SQL: statement mix, latency, slow queries | 🟢 | `JQuickJdbcPlugin` selects `java.sql.Statement` and friends by hierarchy |
| Uncaught-exception capture, thread census, leak check | 🟢 | chained default handler + `jquick_threads` gauge |
| Log enhancement: trace id in the message, sensitive-value masking | 🟢 | argument transformer on the logger implementation; no MDC, no logging configuration change |
| Inbound HTTP entry spans (servlet / Spring MVC / Netty) | 🔵 | target list shipped; nothing is bundled and no container is on the test classpath |
| Cache / message-queue outbound spans (Redis / JMS / Kafka / RabbitMQ / RocketMQ) | 🔵 | target lists shipped; validate against your broker |
| Runtime diagnosis (call counts, hot spots, dynamic logging) | 🟢 | the same hooks power the coverage probe and the debug logger |
| GC event monitoring | ⚪ | needs JVMTI callbacks, not bytecode instrumentation |

### 2. Traffic governance & resilience

| Capability | Status | How |
| --- | --- | --- |
| Method-level rate limiting (QPS + burst) | 🟢 | `JQuickRateLimiter` behind `JQuickInvocationGate` |
| Method-level circuit breaking (failure rate over a window, open duration) | 🟢 | `JQuickCircuitBreaker`; a rejected call gets `JQuickRejectedException` |
| Method-level allow / deny list | 🟢 | `governance.blacklist` plus `includes` / `excludes` |
| Unified authorization | 🟢 | `JQuickAuthorizer` + `JQuickPrincipalResolver` |
| Governance metrics (accepted / rejected / open circuits) | 🟢 | `JQuickGovernanceMetrics` |
| Security audit records | 🟢 | `JQuickAuditListener` |
| Traffic colouring / gray routing | ⚪ | belongs in the gateway/router, not in a method hook |

### 3. Runtime protection & repair

| Capability | Status | How |
| --- | --- | --- |
| Block `Runtime.exec` / `ProcessBuilder` | 🟢 | `security.command.check` |
| Block dangerous reflection | 🟢 | `security.reflection.check` |
| SQL-injection heuristics + statement-length limit | 🟢 | `security.sql.injection.check`, `security.sql.max.length` |
| Deserialization guard (`readObject`) | 🟢 | `security.deserialize.check` |
| Method-body hot fix without a restart | 🟢 | `JQuickHotFixManager` + `redefineClasses`; bounded by the JVM's structural rules |
| Class-load blacklist | 🟢 | `classload.blacklist` |
| Jar / duplicate-class conflict detection | 🟢 | `JQuickJarConflictDetector` |
| Thread leak detection | 🟢 | two `JQuickThreadMonitor` snapshots compared by role |
| Unit-test stubbing of external dependencies | 🟢 | `JQuickStubResolver` — return a value or throw, per method |
| Code coverage probe | 🟢 | `JQuickCoverage`, collected from the same hooks |
| RASP: forbid loading dangerous classes | 🔵 | return `null` from the transformer + alert |

### 4. Data & ORM

| Capability | Status | How |
| --- | --- | --- |
| JDBC interception | 🟢 | `JQuickJdbcPlugin`, hierarchy based |
| SQL rewriting: tenant filtering, sharding hints, dialect adaptation | 🟢 | `JQuickSqlRewriter` SPI, argument write-back |
| Transparent argument/return encryption and decryption | 🟢 | `JQuickCryptoPlugin` + AES cipher, applied in the transform pipeline |
| Transparent database-field masking | 🟢 | `JQuickMaskPlugin` masks the return value at the method boundary |
| Multi-tenant id auto-fill | 🟢 | `JQuickTenantSqlRewriter` + `JQuickTenantContext` |
| Unit-test mocking | 🟢 | see *Runtime protection & repair* |

---

## Runnable Demos

Four scripts, two modes, one tiny application — fully documented in [`demo/README.md`](demo/README.md):

```bash
demo/run-premain.bat     # or ./demo/run-premain.sh    -javaagent, attached at startup
demo/run-agentmain.bat   # or ./demo/run-agentmain.sh  Attach API + retransformClasses
```

The demos were built to make each mechanism **visible in the output** rather than asserted in prose. A
premain run of a 5-class application produces, among other lines:

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

Read that output carefully and four capabilities are demonstrated at once: the trace id injected into
the application's own log line (`1a2f9979…`) **and the mobile number masked in it**, the exact same
trace id on a worker thread that never received one, the error span with its stack frames, and the
application's own `catch` still running — instrumentation added instructions, it did not take the
developer's handler away.

The attach demo shows the difference between the two modes directly: the target JVM runs the same code
before the attach and produces **no** `[jquick-agent]` lines, then after

```
[jquick-agent] [INFO] installing (mode=agentmain)
[jquick-agent] [INFO] retransformed 6 already-loaded class(es)
```

it produces them for every call. Against a live `metrics.port`:

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

## Limitations & Caveats

These are JVM realities, not implementation gaps. Read them before deploying.

1. **Only method bodies can be changed in a live JVM.** The JVMS forbids redefining a loaded class in a
   way that adds or removes fields or methods, changes signatures, or alters the class hierarchy.
   `redefineClasses` / `retransformClasses` therefore cannot add a field or change a superclass. That is
   exactly the boundary the hot-fix capability advertises — and it is enforced by the JVM, not by our
   bookkeeping.
2. **JDK 9+ and JPMS.** Named modules restrict deep reflection and reduce what can be retransformed.
   Expect to need `--add-opens` / `--add-exports`, and note that the JDK 9+ `ClassFileTransformer`
   has a `transform(Module, …)` overload this agent does not implement. JDK 8 is the verified
   configuration.
3. **JDK core classes are mostly not retransformable.** `isModifiableClass` returns `false` for much of
   the bootstrap. The JDK filter can be relaxed per class through `allowJdk` (the thread-pool hand-off
   needs `java.util.concurrent.ThreadPoolExecutor`), but only deliberately: it is printed at startup
   because instrumenting a class the whole JVM uses is a decision an operator must make knowingly.
4. **Native methods have no body to modify.** Only code around the call site can be instrumented.
5. **Retransformation cannot be undone.** `addTransformer` has no counterpart. A capability can always be
   made inert at runtime (`JQuickEnvironment#setEnabled(false)`), but the injected instructions stay in
   the class.
6. **Other agents and AOP frameworks can conflict.** Transformer order is unspecified and this jar
   bundles ASM **without relocation**, so a host application — or another agent — using a different ASM
   version is the most common collision. Relocate when embedding. The agent does defend itself where it
   can: an enhancement that would fail is abandoned with a log line rather than applied, and untouched
   bytecode is returned.
7. **Lombok and bytecode-generating build plugins are a compile-time concern only.** They produce
   ordinary bytecode, so they do not conflict with the agent; what they *do* change is the shape the
   agent sees (generated getters, synthetic methods). Synthetic and bridge methods are skipped.
8. **Constructors and static initializers are not instrumented.** A static initializer has no caller, so
   a span for it would be meaningless — and injecting the agent's runtime call into one would make the
   agent's own classes resolve during class initialisation, where a failure poisons the class for the
   whole life of the JVM. That is precisely the class of risk the failure-containment rule forbids.
9. **Exception capture covers the method's own `ATHROW` plus anything the JVM already recorded.** An
   exception raised by a callee and merely propagating through a method exits it without executing any
   of its own instructions, so no event is generated *for that frame*; the frame that threw it does
   report. This is a deliberate consequence of zero intrusion: a whole-body `catch (Throwable)` would be
   the first matching entry of the exception table and would silently disable the developer's own
   `try/catch`. `EnhancementTest` pins this behaviour.
10. **Overhead.** Each armed hook costs one `ThreadLocal` read plus, when payload capture is on, a small
    array and box. `trace.args` / `trace.result` / `trace.error.stack` are off by default because each
    one is a real cost — the hook plan (`hook plan: …` in the startup report) tells you exactly which.
    Measure before enabling on a hot path.
11. **A listener sees every instrumented call.** Capabilities that filter by argument shape must also
    check the component label the enhancer wrote; without it a private helper taking a SQL string is
    counted as a database round trip. `JdbcCapabilityTest` pins this for the database capability.
12. **The metrics endpoint is off by default and must not outlive the application.** It is bound to
    `0.0.0.0` when enabled (the scraper is usually a different process or host), so set
    `metrics.bind=127.0.0.1` on a host whose network is not trusted — and note that its HTTP server is
    started on a daemon thread on purpose, because a non-daemon one would stop the JVM from ever
    exiting. `MetricsEndpointTest` pins that too.

---

## Build & Test

```bash
mvn clean test      # 140 tests
mvn clean package   # produces the self-contained agent jar
```

| Item | Requirement |
| --- | --- |
| JDK | 1.8+ (source/target 1.8); verified on JDK 8 |
| Maven | 3.x |
| Runtime dependency | jquick-asm 1.3.0 (bundled into the packaged jar) |
| Test dependency | JUnit 4.13.2 |

Artifacts produced in `target/`:

| File | Purpose |
| --- | --- |
| `jquick-agent-1.0.0.jar` | **the agent** — self-contained (jquick-asm + asm bundled), correct manifest |
| `jquick-agent-1.0.0-sources.jar` | sources |
| `jquick-agent-1.0.0-javadoc.jar` | javadoc |
| `original-jquick-agent-1.0.0.jar` | thin jar, before shading |

What the 140 tests cover:

| Suite | Focus |
| --- | --- |
| `ConfigurationTest` | the argument grammar (`;` between pairs, `,` inside a value) and the typed view |
| `EnhancementTest` | real enhancement → private loader → real invocation; readable signatures, timing, the application's own `try/catch` |
| `TraceRuntimeTest` | trace context, span nesting, sampling, cross-thread capture/restore |
| `PayloadCaptureTest` | argument/return/stack capture, truncation, the value formatter's safety rules |
| `MetricsAndTopologyTest` | Prometheus exposition format, label cardinality, topology edges |
| `MetricsEndpointTest` | the HTTP endpoint: paths, secret redaction, busy port, **and that it never keeps the JVM alive** |
| `JdbcCapabilityTest` | the database capability, including that a SQL-looking argument alone is *not* a database call |
| `GovernanceTest` | rate limiting, circuit breaking, rejection, governance metrics |
| `DataProtectionTest` | AES transform, masking patterns, the transform pipeline |
| `StubbingTest` | stubs: return values, thrown exceptions, isolation between methods |
| `HotFixTest` | `redefineClasses` paths and defensive inputs |
| `CoverageTest` | the coverage probe |
| `RuntimeGuardTest` | thread census, leak detection, uncaught-exception chaining, class-load blacklist, jar conflicts |
| `BootstrapTest` | the install order, the default posture (observation only), retransformation per launch mode |

The end-to-end suites do not assert on bytes: they enhance a real fixture class, define the result in a
private class loader so the JVM verifier decides whether it is legal, and then call it. Four real
defects were found this way rather than by inspection — a swallowed `stubThrow`, an array cast, a
thread-pool ordering bug, and a classpath-conflict detector that reported the wrong class name.

---

## FAQ

**Q1. Which JDK versions are supported?**
Compiled and verified on JDK 8. The bytecode engine (ASM 9.6 through jquick-asm) reads and writes class
files from JDK 8 through JDK 21, but on JDK 9+ be aware of JPMS (Limitation 2) and of the module-aware
transformer overload.

**Q2. Nothing is instrumented — where do I start?**
`includes` is empty by default, and empty means *instrument nothing* (the safe default for an agent).
Pass `includes=com.demo`, or `includes=*`. The agent says so at install time:
`no 'includes' rule configured: no class will be instrumented`. Then read the startup report:
`candidates=0` means the filter rejected your class, while `candidates>0, enhanced=0` means every
enhancer declined it.

**Q3. `VirtualMachine.attach(pid)` fails in a container or Kubernetes.**
Attach needs a writable temp directory and the same OS user. Common causes: `java.io.tmpdir` not
writable or not shared, the JVM started with `-XX:+DisableAttachMechanism`, a different user/UID, or a
PID-namespace mismatch — the PID you see from outside the container is not the PID inside. Use
`jcmd <pid> VM.command_line` to confirm visibility, or prefer `premain` in containers.

**Q4. Attach fails on JDK 8 with `NoClassDefFoundError: com/sun/tools/attach/VirtualMachine`.**
On JDK 8 the Attach API lives in `${JAVA_HOME}/lib/tools.jar`; add it to the class path
(`-cp app.jar;$JAVA_HOME/lib/tools.jar`). On JDK 9+ it is in the `jdk.attach` module and needs no extra
entry. The demo scripts do this detection for you.

**Q5. Two agents on the same JVM — what breaks?**
Transformer order is unspecified and both may see the same bytes. Two tracing agents double-instrument
(doubled events, doubled overhead), and a transformer returning `null` cannot "undo" a previous one.
This jar bundles ASM **without relocation**, so a host application with a different ASM version is the
most likely collision. Test the combination, or relocate ASM.

**Q6. Why is my `retransformClasses` call rejected or silently ignored?**
`isModifiableClass` returns `false` for bootstrap classes, for a class already being redefined, and for
any attempt at a forbidden structural change. The agent checks `isModifiableClass`, retransforms one
class at a time, and logs what it refused.

**Q7. Why is my `catch` block still working after instrumentation?**
That is by design. The agent never registers a catch-all handler, so the exception table is unchanged
and the JVM resolves exceptions exactly as the compiler intended. See Limitation 9 for why whole-body
wrapping is avoided.

**Q8. Why does the agent print `retransformed 0 already-loaded class(es)` at startup?**
Because at `premain` time your application's classes are not loaded yet. `retransform` defaults to
`false` in premain mode for that reason, and is useful there only for classes a framework happened to
preload — which is why the demo passes `retransform=true`: it also covers
`java.util.concurrent.ThreadPoolExecutor`.

**Q9. My logs still have no trace id.**
Three things to check, in order: `log.enabled=true`; `log.includes` naming a type your logger
*implements* (the plugin selects implementations of the named logger interface, not the interface
itself); and that the log call happens inside an instrumented method — the prefix comes from the
current thread's trace context, so a log line emitted on a thread that never had one is not decorated.
The thread-pool hand-off is what fixes the common case.

**Q10. What is the performance impact?**
With nothing armed, the injected entry sequence is a `ThreadLocal` read. With payload capture off and a
trace log on, you pay the id generation and one small object per call, plus whatever your listener does
(I/O if it prints). The hook plan line in the startup report lists exactly which pieces are emitted.

**Q11. How do I add a new capability?**
Implement `JQuickPlugin` and register it — in `JQuickPlugins.builtIn()`, or through
`META-INF/services` from another jar. Inside the plugin, use `JQuickInstrumentEnhancer` for the
bytecode side and register listeners, gates, transformers or stub resolvers for the runtime side. The
kernel needs no change.

---

## License

[Apache License 2.0](LICENSE) — the same license as jquick-asm.

Author: Martin (<goudingcheng@gmail.com>)

---

## Related JQuick Ecosystem Projects

| Project | Description |
| --- | --- |
| [jquick-asm](https://github.com/paohaijiao/jquick-asm) | the ASM 9.x high-level bytecode library this agent is built on |
| [javelin](https://github.com/paohaijiao/javelin) | core utilities / build parent shared by the JQuick modules |
| [jquick-curl](https://github.com/paohaijiao/jquick-curl) | declarative HTTP client |
| [jquick-excel](https://github.com/paohaijiao/jquick-excel) | annotation-driven Excel import/export |
| [jquick-json](https://github.com/paohaijiao/jquick-json) | lightweight JSON toolkit |
| [jquick-java](https://github.com/paohaijiao/jquick-java) | in-process Java compilation / execution |
| [jquick-mcp](https://github.com/paohaijiao/jquick-mcp) | MCP tooling for the JQuick ecosystem |

---

## SEO / GitHub Topics

Suggested GitHub repository **Topics**:

```
java-agent, javaagent, bytecode, bytecode-manipulation, asm, asm9, instrumentation,
premain, agentmain, attach-api, retransform, redefineclasses, javassist-alternative,
apm, jvm, observability, distributed-tracing, traceid, prometheus, metrics, topology,
sql-monitoring, slow-query, log-masking, data-masking, rate-limiting, circuit-breaker,
rasp, hotfix, coverage, java-agent-framework, jquick, jquick-asm, apache-2
```

Suggested **search phrases** (README keywords):

> java agent premain agentmain example, javaagent bytecode instrumentation, ASM 9 bytecode
> enhancement, retransformClasses attach api, jvm method tracing library, trace id injection into logs,
> jdbc monitoring without changing code, slow sql detection java agent, prometheus metrics java agent,
> service topology from method calls, apm java agent open source, jquick-asm based agent,
> java hot fix agent without restart, RASP java agent, rate limiting circuit breaker java agent,
> unit test mocking with java agent, method coverage probe java agent,
> zero-intrusion java instrumentation, javassist alternative, spring boot java agent
