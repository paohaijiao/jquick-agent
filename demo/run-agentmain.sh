#!/usr/bin/env bash
#
# jquick-agent demo - AGENTMAIN mode (runtime attach + retransformClasses)
# The target JVM is already running when the agent is loaded into it.
#
# Runs on Linux / macOS / WSL, and also under Git Bash / MSYS / Cygwin on Windows
# (where cygpath is used to hand the JVM the native path and separator it expects).
#
# Attach API: JDK 8 -> $JAVA_HOME/lib/tools.jar, JDK 9+ -> built-in (jdk.attach).
#
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AGENT_JAR="$ROOT/target/jquick-agent-1.0.0.jar"
OUT="$ROOT/demo/target"
TOOLS_JAR="${JAVA_HOME:-}/lib/tools.jar"

if [ ! -f "$AGENT_JAR" ]; then
    echo "[demo] agent jar not found: $AGENT_JAR"
    echo "[demo] run 'mvn clean package' in the project root first."
    exit 1
fi
mkdir -p "$OUT"

# On Windows javac and java are native binaries: they need "D:\path\to\thing" and ';' as the
# class path separator. Under Linux/macOS none of this applies.
if command -v cygpath >/dev/null 2>&1; then
    WINDOWS=1
    SEP=';'
else
    WINDOWS=0
    SEP=':'
fi

native() {
    if [ "$WINDOWS" = "1" ]; then
        cygpath -w "$1"
    else
        printf '%s' "$1"
    fi
}

# The Attach API is only a separate jar on JDK 8; on JDK 9+ it is in the jdk.attach module.
ATTACH_CP="$(native "$OUT")"
if [ -f "$TOOLS_JAR" ]; then
    ATTACH_CP="$ATTACH_CP$SEP$(native "$TOOLS_JAR")"
else
    echo "[demo] no $TOOLS_JAR: assuming JDK 9+ where the Attach API is built in"
fi

# The same configuration as the premain demo. retransform defaults to true on an attach,
# so it is not repeated here - but it is the reason this demo exists: the agent is loaded
# into a JVM whose classes are already running, and only retransformation can reach them.
AGENT_ARGS="includes=com.demo,java.util.concurrent.ThreadPoolExecutor"
AGENT_ARGS="$AGENT_ARGS;trace.print=true"
AGENT_ARGS="$AGENT_ARGS;trace.args=true"
AGENT_ARGS="$AGENT_ARGS;trace.result=true"
AGENT_ARGS="$AGENT_ARGS;trace.error.stack=true"
AGENT_ARGS="$AGENT_ARGS;trace.slow.ms=100"
AGENT_ARGS="$AGENT_ARGS;topology.enabled=true"
AGENT_ARGS="$AGENT_ARGS;metrics.port=9090"
# The database capability needs no JDBC driver here: the demo's Statement declares
# java.sql.Statement, which is what the default jdbc.includes list names.
AGENT_ARGS="$AGENT_ARGS;jdbc.enabled=true"
AGENT_ARGS="$AGENT_ARGS;jdbc.slow.ms=50"
AGENT_ARGS="$AGENT_ARGS;thread.enabled=true"
AGENT_ARGS="$AGENT_ARGS;log.enabled=true"
AGENT_ARGS="$AGENT_ARGS;log.includes=com.demo.DemoLogger"
AGENT_ARGS="$AGENT_ARGS;log.mask.enabled=true"
AGENT_ARGS="$AGENT_ARGS;mask.enabled=true"
AGENT_ARGS="$AGENT_ARGS;mask.includes=com.demo.DemoService"

echo "[demo] compiling the target application and the attach runner..."
javac -encoding UTF-8 -d "$(native "$OUT")" \
    "$(native "$ROOT/demo/src/com/demo/DemoLogger.java")" \
    "$(native "$ROOT/demo/src/com/demo/ConsoleLogger.java")" \
    "$(native "$ROOT/demo/src/com/demo/DemoLoggers.java")" \
    "$(native "$ROOT/demo/src/com/demo/DemoService.java")" \
    "$(native "$ROOT/demo/src/com/demo/DemoStatement.java")" \
    "$(native "$ROOT/demo/src/com/demo/DemoApplication.java")"
javac -encoding UTF-8 -cp "$ATTACH_CP" -d "$(native "$OUT")" \
    "$(native "$ROOT/demo/src/com/demo/AgentAttachDemo.java")"

rm -f "$OUT/app.pid"
echo "[demo] starting the target application in loop mode (it exits by itself after 15s)..."
java "-Ddemo.pid.file=$(native "$OUT/app.pid")" -cp "$(native "$OUT")" \
    com.demo.DemoApplication loop 15 &
APP_PID=$!

for _ in $(seq 1 20); do
    [ -f "$OUT/app.pid" ] && break
    sleep 1
done
if [ ! -f "$OUT/app.pid" ]; then
    echo "[demo] the target did not publish its pid, aborting"
    exit 1
fi
TARGET_PID="$(cat "$OUT/app.pid")"

echo
echo "[demo] attaching to pid $TARGET_PID (agentmain + retransformClasses)..."
echo "[demo] agent args: $AGENT_ARGS"
java -cp "$ATTACH_CP" com.demo.AgentAttachDemo \
    "$TARGET_PID" "$(native "$AGENT_JAR")" "$AGENT_ARGS"
echo
echo "[demo] calls made BEFORE the attach produced no [jquick-agent] lines;"
echo "[demo] every call AFTER it is instrumented. Waiting for the target to finish..."
wait "$APP_PID" || true
echo "[demo] the target JVM exited on its own; nothing had to be killed"
