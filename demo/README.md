# jquick-agent demo

Two runnable demos, and a target application that knows nothing about the agent. **Build the agent
first** from the project root:

```bash
mvn clean package
```

The packaged, self-contained agent jar is `target/jquick-agent-1.0.0.jar` — jquick-asm and ASM are
already shaded into it, so nothing else has to be on the class path.

Every line of output quoted below was produced by running these scripts; nothing here is illustrative.

---

## The demo classes

`demo/src/com/demo/` — seven files, and the reason each one exists:

| File | What it is | What it exists to show |
| --- | --- | --- |
| `DemoService.java` | the business class | every method makes one injected sequence visible: `add` (primitive payload), `greet` (reference payload), `checkout` (log trace id + masking), `record` (two JDBC statements), `pay` (throws), `profile` (masked result), `runAsync` (pool hand-off) |
| `DemoApplication.java` | the `main` class | `exercise()` is one commented pass over all seven mechanisms; `loop [seconds]` keeps calling it so the agent can be attached at runtime |
| `DemoStatement.java` | a `java.sql.Statement` | lets the database capability be demonstrated with **no database and no JDBC driver**; `setLatencyMillis()` makes `jdbc.slow.ms` observable |
| `DemoLogger.java` | the logging facade | structurally what `org.slf4j.Logger` is: an interface the application calls |
| `ConsoleLogger.java` | the logging implementation | the class the agent actually instruments, selected only because it is a *subtype* of `DemoLogger` — it appears nowhere in the agent arguments |
| `DemoLoggers.java` | holds the logger | the indirection a real application has, and the reason one `log.includes` rule covers a whole process |
| `AgentAttachDemo.java` | the attach runner | `VirtualMachine.attach(pid).loadAgent(jar, args)` — the agentmain demo only |

No file in this package imports the agent. That is the point of the exercise.

---

## 1. premain mode (`-javaagent`)

The agent is attached at JVM startup, before `main()` runs.

```bash
# Windows
demo\run-premain.bat

# Linux / macOS / WSL / Git Bash
bash demo/run-premain.sh
```

The script compiles the six application classes and then runs:

```bash
java "-javaagent:target/jquick-agent-1.0.0.jar=$AGENT_ARGS" -cp demo/target com.demo.DemoApplication
```

### The agent arguments

Every key below is a real configuration key (`core/JQuickConfig.java`):

| Key | Value used | Why |
| --- | --- | --- |
| `includes` | `com.demo,java.util.concurrent.ThreadPoolExecutor` | the classes to instrument. `ThreadPoolExecutor` is a **JDK class and must be named explicitly** — the agent refuses JDK classes by default |
| `retransform` | `true` | the JDK's own thread machinery may already be loaded at premain time |
| `trace.print` / `trace.args` / `trace.result` | `true` | one span line per call, plus the arguments and the return value |
| `trace.error.stack` | `true` | print the frames of a failing call |
| `trace.slow.ms` | `100` | flag spans at or above this duration |
| `topology.enabled` | `true` | build the call graph from the spans |
| `metrics.port` | `9090` | serve `/metrics`, `/topology`, `/config`, `/health` |
| `jdbc.enabled` / `jdbc.slow.ms` | `true` / `50` | SQL statements, statement mix, slow queries. `DemoStatement` declares `java.sql.Statement`, which is in the default `jdbc.includes` list |
| `thread.enabled` | `true` | propagate the trace context across the pool |
| `log.enabled` / `log.includes` | `true` / `com.demo.DemoLogger` | inject the trace id into the application's log lines |
| `log.mask.enabled` / `mask.enabled` / `mask.includes` | `true` / `com.demo.DemoService` | mask sensitive values in log messages and on return values |

### Expected output (abridged)

```
[jquick-agent] [INFO] installing (mode=premain)
[jquick-agent] [INFO] thread pool context propagation enabled for [java.util.concurrent.ThreadPoolExecutor, java.util.concurrent.ScheduledThreadPoolExecutor, java.util.concurrent.ForkJoinPool] (declared in the JDK allow list)
[jquick-agent] [INFO] uncaught exception handler installed (previous=none)
[jquick-agent] [INFO] retransformed 0 already-loaded class(es)
[jquick-agent] [INFO] Prometheus endpoint listening on http://0.0.0.0:9090/metrics
[jquick-agent] [INFO] active capabilities: trace, metrics, jdbc, log, mask, thread
[jquick-agent] [INFO] hook plan: JQuickHookPlan{args=true, transformArgs=false, result=true, transformResult=false, mock=false, threadPool=true}

[app] started, pid=26512
[app] no logging framework, no agent API and no AOP on the class path: the agent sees a bare application
[jquick-agent] [c830cd01:9ffb49bf] main
    |- com.demo.DemoService#add(int, int) [0ms] component=method,mask  args=[2, 3]  result=5
[app] add(2, 3) = 5

[main] INFO  [c830cd016f91f4c66e1c44c25991d15e] checkout started for alice mobile=138****8000
[main] INFO  [c830cd016f91f4c66e1c44c25991d15e] checkout finished for alice
[jquick-agent] [c830cd01:f2d0cb28] main
    |- com.demo.DemoService#checkout(java.lang.String, java.lang.String) [43ms] component=method,mask  args=[alice, 13800138000]  result=receipt-alice

[jquick-agent] [c830cd01:8aacbff6] main
      << com.demo.DemoStatement#execute(java.lang.String) [87ms] component=method,jdbc  args=[select id from orders where user_name = 'alice']  result=true
[jquick-agent] SLOW SQL (87ms) select id from orders where user_name = 'alice'

[jquick-agent] [c830cd01:6b1f1f25] main
    |- com.demo.DemoService#pay(java.lang.String, long) [0ms] component=method,mask ERROR=java.lang.IllegalArgumentException: amount must not be negative: -1  args=[bob, -1]
    at com.demo.DemoService.pay(Unknown Source)
    at com.demo.DemoApplication.exercise(Unknown Source)
    at com.demo.DemoApplication.main(Unknown Source)
[app] the application's own catch ran: amount must not be negative: -1

[jquick-agent] [c830cd01:d6bfe0ce] main
    |- com.demo.DemoService#profile(java.lang.String) [0ms] component=method,mask  args=[13800138000]  result=mobile=138****8000
[app] profile returned: mobile=138****8000

[pool-1-thread-1] INFO  [c830cd016f91f4c66e1c44c25991d15e] worker: this line carries the trace id of the submitting thread
[app] done
```

### Reading that output

- **Two lines per span.** The header carries `[traceId[0..8]:spanId[0..8]]` and the thread name —
  the ids are truncated for readability; the injected log line carries all 32 hex characters of the
  trace id. All spans of one pass share `c830cd01:`.
- **`component=method,mask`** — labels merge. `method` is the tracing capability, `mask` is masking
  on the return value; the span had no `mask` label until masking was switched on.
- **`retransformed 0 already-loaded class(es)`** is correct at premain time: nothing is loaded yet.
  The transformers then fire as the classes are loaded — that is what `seen=481, candidates=0` in the
  startup summary means (zero at install time, before the first class arrived).
- **The masked value appears in the application's own log line.** `138****8000` is in the message
  `[main] INFO [c830cd01…] checkout started for alice mobile=138****8000`, while
  `ConsoleLogger.info` contains no masking code and the argument was passed as `13800138000`. Also note
  the span line still shows the real argument `args=[alice, 13800138000]` — masking is applied to what
  the application logs and to what it returns, not to what the agent records.
- **The worker thread carries the submitter's trace id.** That `pool-1-thread-1` line has the *same*
  trace id as the `main` lines above it. Nothing set it on the worker: `runAsync` submitted through
  `ThreadPoolExecutor.execute`, which the agent wrapped while a trace was active.
- **The application's own `catch` still runs.** Immediately after the agent logs the failure, the
  application prints `[app] the application's own catch ran: amount must not be negative: -1`.
  Instrumentation inserts instructions; it never installs a competing exception handler.

---

## 2. agentmain mode (runtime attach)

Here the target JVM is **already running** when the agent is loaded into it — which is why this demo
exists: only `retransformClasses` can reach classes that are already loaded.

```bash
# Windows
demo\run-agentmain.bat

# Linux / macOS / WSL / Git Bash
bash demo/run-agentmain.sh
```

What the script does:

1. compiles the six classes plus `AgentAttachDemo`;
2. starts `DemoApplication loop 15` — it publishes its PID to `demo/target/app.pid` so the script
   never has to guess it, and **it exits by itself after 15s**;
3. waits for that PID file, then runs
   `java -cp demo/target com.demo.AgentAttachDemo <pid> <agent-jar> <agent-args>`, which calls
   `VirtualMachine.attach(pid).loadAgent(jar, args)`;
4. the agent's `agentmain` installs the transformer and retransforms the already-loaded matching
   classes;
5. `wait`s for the target — **nothing is killed**. That is deliberate: "kill the process you just
   started" is the least portable part of a demo script, and a target that is still alive holds a
   lock on the agent jar on Windows.

### Expected output (abridged)

```
[demo] starting the target application in loop mode (it exits by itself after 15s)...
[app] loop mode: attach the agent now, then watch the [jquick-agent] lines; calls made before the attach produce none. This JVM exits by itself in 15s, so the script has nothing to kill.

[demo] attaching to pid 25064 (agentmain + retransformClasses)...
[attach] attaching to pid 25064 with agent args: includes=com.demo,java.util.concurrent.ThreadPoolExecutor;...

[app] add(2, 3) = 5                                          <- before the attach
[main] INFO  checkout started for alice mobile=13800138000    <- before: no trace id, no masking

[jquick-agent] [INFO] installing (mode=agentmain)
[jquick-agent] [INFO] retransformed 7 already-loaded class(es)

[jquick-agent] [a0f68d15:cefa2aaa] main
    |- com.demo.DemoService#add(int, int) [0ms] component=method,mask  args=[2, 3]  result=5
[main] INFO  [a0f68d15c833864a5b8341d6044ffc6e] checkout started for alice mobile=138****8000

[attach] agent loaded, the target JVM now reports [jquick-agent] events
  transformer: seen=310, candidates=7, enhanced=6, failed=0
  instrumentation: redefine=true, retransform=true, loadedClasses=1306

[jquick-agent] SLOW SQL (83ms) select id from orders where user_name = 'alice'
[pool-1-thread-1] INFO  [a0f68d15c833864a5b8341d6044ffc6e] worker: this line carries the trace id of the submitting thread

[app] loop finished
[app] done

[demo] the target JVM exited on its own; nothing had to be killed
```

### Before vs. after

The same `exercise()` pass runs once a second, so the two halves of the log are directly comparable:

| | before the attach | after the attach |
| --- | --- | --- |
| `add(2, 3) = 5` | identical | identical |
| log line | `[main] INFO  checkout started for alice mobile=13800138000` | `[main] INFO  [a0f68d15…] checkout started for alice mobile=138****8000` |
| `[jquick-agent]` spans | none | one per call, with `args=`/`result=` |
| `retransformed` | — | `7 already-loaded class(es)` |

`candidates=7, enhanced=6`: the seventh candidate is the `DemoLogger` **interface**, which is skipped
because it has no method body to instrument.

In premain mode the equivalent class list is: `ConsoleLogger`, `DemoApplication`,
`DemoApplication$1`, `DemoLoggers`, `DemoService`, `DemoStatement` (run with `debug=true` to see the
`instrumented …` lines).

---

## 3. The HTTP endpoints

Enabled by `metrics.port=9090`. The premain run is over in well under a second, so to look at them
use `loop`:

```bash
java "-javaagent:target/jquick-agent-1.0.0.jar=includes=com.demo;metrics.port=9090;..." \
     -cp demo/target com.demo.DemoApplication loop 30
```

| Endpoint | Returns |
| --- | --- |
| `/` | an index of the other paths |
| `/metrics` | Prometheus text exposition |
| `/topology` | the call graph edges |
| `/config` | the effective configuration, with secret-like values redacted |
| `/health` | `OK` |

Real captured output:

```
$ curl -s http://127.0.0.1:9090/metrics | grep '^jquick_'
jquick_slow_sql_total{sql_type="SELECT"} 5
jquick_sql_duration_seconds_count{sql_type="SELECT"} 5
jquick_sql_duration_seconds_sum{sql_type="SELECT"} 0.4581951
jquick_sql_duration_seconds_max{sql_type="SELECT"} 0.0958658
jquick_sql_executions_total{sql_type="SELECT"} 5
jquick_sql_executions_total{sql_type="INSERT"} 5
jquick_topology_calls_total{caller="com.demo.DemoService#record(java.lang.String, java.lang.String)",callee="com.demo.DemoStatement#execute(java.lang.String)",component="method,jdbc"} 10

$ curl -s http://127.0.0.1:9090/topology
com.demo.DemoService#record(java.lang.String, java.lang.String)
    -> com.demo.DemoStatement#execute(java.lang.String) [component=method,jdbc] calls=10 avgMs=45 maxMs=95 errors=0

$ curl -s http://127.0.0.1:9090/health
OK

$ curl -s http://127.0.0.1:9090/config
JQuick agent configuration (secret-like values redacted)
---------------------------------------------------
includes=com.demo,java.util.concurrent.ThreadPoolExecutor
jdbc.enabled=true
jdbc.slow.ms=50
log.enabled=true
log.includes=com.demo.DemoLogger
...
```

Note the SQL label: `sql_type="SELECT"` is derived from the statement text, and the statement text
itself is never a label — so the series count stays bounded no matter how many distinct queries run.
The counts are exact: two statements per pass over `record`, one `SELECT` (slow) and one `INSERT`.

---

## Requirements

| Item | Requirement |
| --- | --- |
| JDK | 1.8+ (these scripts are verified on JDK 1.8.0_191) |
| Attach API | JDK 8: `${JAVA_HOME}/lib/tools.jar`; JDK 9+: built into the `jdk.attach` module. The scripts detect which, and warn if JDK 8 has no `JAVA_HOME` |
| Agent jar | `mvn clean package` must have been run in the project root |
| Free port | `9090`; change `metrics.port` to run the demos side by side |

## Notes

- **Git Bash / MSYS / Cygwin on Windows.** The JVM is a native binary: it needs `D:\path\to\thing`,
  not `/d/path/to/thing`, and `;` rather than `:` as the class path separator. Both shell scripts
  therefore pass every path through `cygpath -w` and pick the separator by platform. Without that,
  `-javaagent` fails with *"Error opening zip file or JAR manifest missing : /d/…"*.
- **One shell trap worth knowing:** do not run Maven with `MSYS_NO_PATHCONV=1` set. It mangles the
  `-classpath` argument and Maven dies with *"找不到或无法加载主类
  org.codehaus.plexus.classworlds.launcher.Launcher"*.
- **Nothing has to be stopped.** The loop mode is self-terminating by design; if a run is interrupted,
  the leftover JVM will hold a lock on `target/jquick-agent-1.0.0.jar` on Windows and the next
  `mvn clean package` will fail to delete it. Stop it with PowerShell
  (`Stop-Process -Id <pid> -Force`) — `taskkill` from an MSYS shell is silently a no-op.
