@echo off
rem ============================================================================
rem  jquick-agent demo - PREMAIN mode (-javaagent)
rem  The agent is attached at JVM startup, before main() runs.
rem
rem  Every key below is a real configuration key of the agent; see
rem  src/main/java/io/github/paohaijiao/jquick/agent/core/JQuickConfig.java.
rem ============================================================================
setlocal
set "ROOT=%~dp0.."
set "AGENT_JAR=%ROOT%\target\jquick-agent-1.0.0.jar"
set "OUT=%ROOT%\demo\target"

if not exist "%AGENT_JAR%" (
    echo [demo] agent jar not found: %AGENT_JAR%
    echo [demo] run "mvn clean package" in the project root first.
    exit /b 1
)
if not exist "%OUT%" mkdir "%OUT%"

rem ThreadPoolExecutor is a JDK class: it must be named explicitly in both the
rem include rule and the JDK allow list, because JDK classes are refused by default.
set "AGENT_ARGS=includes=com.demo,java.util.concurrent.ThreadPoolExecutor"
set "AGENT_ARGS=%AGENT_ARGS%;retransform=true"
set "AGENT_ARGS=%AGENT_ARGS%;trace.print=true"
set "AGENT_ARGS=%AGENT_ARGS%;trace.args=true"
set "AGENT_ARGS=%AGENT_ARGS%;trace.result=true"
set "AGENT_ARGS=%AGENT_ARGS%;trace.error.stack=true"
set "AGENT_ARGS=%AGENT_ARGS%;trace.slow.ms=100"
set "AGENT_ARGS=%AGENT_ARGS%;topology.enabled=true"
set "AGENT_ARGS=%AGENT_ARGS%;metrics.port=9090"
rem The database capability needs no JDBC driver here: the demo's Statement declares
rem java.sql.Statement, which is what the default jdbc.includes list names.
set "AGENT_ARGS=%AGENT_ARGS%;jdbc.enabled=true"
set "AGENT_ARGS=%AGENT_ARGS%;jdbc.slow.ms=50"
set "AGENT_ARGS=%AGENT_ARGS%;thread.enabled=true"
set "AGENT_ARGS=%AGENT_ARGS%;log.enabled=true"
set "AGENT_ARGS=%AGENT_ARGS%;log.includes=com.demo.DemoLogger"
set "AGENT_ARGS=%AGENT_ARGS%;log.mask.enabled=true"
set "AGENT_ARGS=%AGENT_ARGS%;mask.enabled=true"
set "AGENT_ARGS=%AGENT_ARGS%;mask.includes=com.demo.DemoService"

echo [demo] compiling the target application...
javac -encoding UTF-8 -d "%OUT%" ^
    "%ROOT%\demo\src\com\demo\DemoLogger.java" ^
    "%ROOT%\demo\src\com\demo\ConsoleLogger.java" ^
    "%ROOT%\demo\src\com\demo\DemoLoggers.java" ^
    "%ROOT%\demo\src\com\demo\DemoService.java" ^
    "%ROOT%\demo\src\com\demo\DemoStatement.java" ^
    "%ROOT%\demo\src\com\demo\DemoApplication.java"
if errorlevel 1 exit /b 1

echo [demo] launching with -javaagent ^(premain^)...
echo [demo] agent args: %AGENT_ARGS%
echo.
java "-javaagent:%AGENT_JAR%=%AGENT_ARGS%" -cp "%OUT%" com.demo.DemoApplication
endlocal
