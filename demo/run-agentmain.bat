@echo off
rem ============================================================================
rem  jquick-agent demo - AGENTMAIN mode (runtime attach + retransformClasses)
rem  The target JVM is already running when the agent is loaded into it.
rem  On JDK 8 the Attach API lives in %JAVA_HOME%\lib\tools.jar.
rem ============================================================================
setlocal enabledelayedexpansion
set "ROOT=%~dp0.."
set "AGENT_JAR=%ROOT%\target\jquick-agent-1.0.0.jar"
set "OUT=%ROOT%\demo\target"
set "TOOLS_JAR=%JAVA_HOME%\lib\tools.jar"

if not exist "%AGENT_JAR%" (
    echo [demo] agent jar not found: %AGENT_JAR%
    echo [demo] run "mvn clean package" in the project root first.
    exit /b 1
)
if not exist "%OUT%" mkdir "%OUT%"

set "ATTACH_CP=%OUT%"
if exist "%TOOLS_JAR%" set "ATTACH_CP=%OUT%;%TOOLS_JAR%"

rem The same configuration as the premain demo. retransform defaults to true on an
rem attach, so it is not repeated here - but it is what makes the already-loaded target
rem class and the already-loaded ThreadPoolExecutor instrumentable at all.
set "AGENT_ARGS=includes=com.demo,java.util.concurrent.ThreadPoolExecutor"
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

echo [demo] compiling the target application and the attach runner...
javac -encoding UTF-8 -d "%OUT%" ^
    "%ROOT%\demo\src\com\demo\DemoLogger.java" ^
    "%ROOT%\demo\src\com\demo\ConsoleLogger.java" ^
    "%ROOT%\demo\src\com\demo\DemoLoggers.java" ^
    "%ROOT%\demo\src\com\demo\DemoService.java" ^
    "%ROOT%\demo\src\com\demo\DemoStatement.java" ^
    "%ROOT%\demo\src\com\demo\DemoApplication.java"
if errorlevel 1 exit /b 1
javac -encoding UTF-8 -cp "%ATTACH_CP%" -d "%OUT%" "%ROOT%\demo\src\com\demo\AgentAttachDemo.java"
if errorlevel 1 exit /b 1

echo [demo] starting the target application in loop mode (it exits by itself after 15s)...
del /q "%OUT%\app.pid" >nul 2>&1
start "jquick-agent-demo" /b java -Ddemo.pid.file="%OUT%\app.pid" -cp "%OUT%" com.demo.DemoApplication loop 15

echo [demo] waiting for the target JVM pid...
set /a TRIES=0
:wait_pid
if exist "%OUT%\app.pid" goto have_pid
set /a TRIES+=1
if !TRIES! GEQ 20 (
    echo [demo] timed out waiting for the pid file, aborting.
    goto cleanup
)
ping -n 2 127.0.0.1 >nul
goto wait_pid

:have_pid
set /p TARGET_PID=<"%OUT%\app.pid"
echo [demo] target pid = !TARGET_PID!
echo.
echo [demo] attaching the agent ^(agentmain + retransformClasses^)...
echo [demo] agent args: %AGENT_ARGS%
java -cp "%ATTACH_CP%" com.demo.AgentAttachDemo "!TARGET_PID!" "%AGENT_JAR%" "%AGENT_ARGS%"
echo.
echo [demo] calls made BEFORE the attach produced no [jquick-agent] lines;
echo [demo] every call AFTER it is instrumented. Waiting for the target to finish...
ping -n 16 127.0.0.1 >nul

:cleanup
echo [demo] the target JVM exits on its own; nothing had to be killed.
endlocal
