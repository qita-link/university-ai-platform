@echo off
chcp 65001 >nul
rem ============================================================
rem  校园 AI 平台 - 启动后端（Windows）
rem  数据库与 JWT 配置在 config/application-local.yml（已 gitignore）
rem ============================================================
cd /d "%~dp0.."

rem 这个 JDK 只是把 bin 下的 VC 运行库换成了系统的新版（14.50），
rem 原因是 Oracle JDK 17 自带 msvcp140.dll 14.36，会把 OR-Tools 的
rem abseil_dll.dll 绑定到旧运行库上，一求解就 ACCESS_VIOLATION 崩溃。
rem 详见 docs/11-scheduling-deployment.md「Windows 上求解器崩溃」。
set "JAVA_EXE=%USERPROFILE%\jdk17-ort\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=%CD%\jdk-17.0.20.1+1\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java"

rem 显式指定端口，避免环境里残留的 SERVER_PORT / SERVER__PORT 把端口改掉
set "SERVER_PORT=8080"

echo [backend] 启动中... http://127.0.0.1:8080
echo [backend] JVM: %JAVA_EXE%
"%JAVA_EXE%" -Xms256m -Xmx1024m -Dfile.encoding=UTF-8 ^
  -jar target\campus-ai-platform-0.1.0-SNAPSHOT.jar --server.port=8080
