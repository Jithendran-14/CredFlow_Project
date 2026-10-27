@echo off
setlocal
cd /d "%~dp0"
echo Stop the running project with stop.cmd before building.
if not defined JAVA_HOME set "JAVA_HOME=C:\jdk25"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call mvn -f backend\member1\auth-service\pom.xml -DskipTests clean package
if errorlevel 1 goto failed
call mvn -f backend\member1\application-service\pom.xml -DskipTests clean package
if errorlevel 1 goto failed
call mvn -f backend\member1\consent-service\pom.xml -DskipTests clean package
if errorlevel 1 goto failed
call mvn -f backend\member2\pom.xml clean verify
if errorlevel 1 goto failed
call mvn -f backend\member3\pom.xml clean verify
if errorlevel 1 goto failed
pushd frontend
call node --test tests\contracts.test.mjs
set "FRONTEND_RESULT=%ERRORLEVEL%"
popd
if not "%FRONTEND_RESULT%"=="0" goto failed
echo Build completed. Run start.cmd next.
pause
exit /b 0
:failed
echo Build failed. Review the first error above.
pause
exit /b 1
