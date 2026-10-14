#!/usr/bin/env bash
#
# jquick-agent demo - PREMAIN mode (-javaagent)
# The agent is attached at JVM startup, before main() runs.
#
# Runs on Linux / macOS / WSL, and also under Git Bash / MSYS / Cygwin on Windows
# (where cygpath is used to hand the JVM the native path it expects).
#
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AGENT_JAR="$ROOT/target/jquick-agent-1.0.0.jar"
OUT="$ROOT/demo/target"

if [ ! -f "$AGENT_JAR" ]; then
    echo "[demo] agent jar not found: $AGENT_JAR"
    echo "[demo] run 'mvn clean package' in the project root first."
    exit 1
fi
mkdir -p "$OUT"

# On Windows the JVM is a native binary: it needs "D:\path\to\thing", not "/d/path/to/thing",
# and ';' rather than ':' as the class path separator. On Linux/macOS the path is already right.
if command -v cygpath >/dev/null 2>&1; then
    WINDOWS=1
else
    WINDOWS=0
fi

native() {
    if [ "$WINDOWS" = "1" ]; then
        cygpath -w "$1"
    else
        printf '%s' "$1"
    fi
}

# ---------------------------------------------------------------------------
# Every key below is a real configuration key of the agent; see
# src/main/java/io/github/paohaijiao/jquick/agent/core/JQuickConfig.java.
# ---------------------------------------------------------------------------
AGENT_ARGS="includes=com.demo"
# ThreadPoolExecutor is a JDK class: it must be named explicitly in the include rule,
# because the agent refuses JDK classes unless they are named.
AGENT_ARGS="$AGENT_ARGS,java.util.concurrent.ThreadPoolExecutor"
# In premain mode nothing is loaded yet, but ThreadPoolExecutor and the JDK's own
# thread machinery may already be - retransform=true makes the pass cover them.
AGENT_ARGS="$AGENT_ARGS;retransform=true"
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

echo "[demo] compiling the target application..."
javac -encoding UTF-8 -d "$(native "$OUT")" \
    "$(native "$ROOT/demo/src/com/demo/DemoLogger.java")" \
    "$(native "$ROOT/demo/src/com/demo/ConsoleLogger.java")" \
    "$(native "$ROOT/demo/src/com/demo/DemoLoggers.java")" \
    "$(native "$ROOT/demo/src/com/demo/DemoService.java")" \
    "$(native "$ROOT/demo/src/com/demo/DemoStatement.java")" \
    "$(native "$ROOT/demo/src/com/demo/DemoApplication.java")"

echo "[demo] launching with -javaagent (premain)..."
echo "[demo] agent args: $AGENT_ARGS"
echo
java "-javaagent:$(native "$AGENT_JAR")=$AGENT_ARGS" -cp "$(native "$OUT")" com.demo.DemoApplication
