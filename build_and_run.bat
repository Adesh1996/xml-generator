@echo off
echo Building the project...
REM Clean and install the Maven project, creating a single executable JAR with dependencies
call mvn clean compile assembly:single -DskipTests

REM Check if the Maven build was successful
IF %ERRORLEVEL% NEQ 0 (
    echo Maven build failed. Exiting.
    pause
    exit /b %ERRORLEVEL%
)

echo Starting the application server in the background...
REM Run the application using the generated JAR file (jar-with-dependencies) in the background
start "" /B java -jar target\xml-generator-0.0.1-SNAPSHOT-jar-with-dependencies.jar

echo Waiting for the server to start (5 seconds)...
REM Add a short delay to allow the server to fully initialize
timeout /t 5 /nobreak >NUL

echo Opening the application UI in your default browser...
REM Open the index.html file in the default web browser
start "" index.html

echo Application server is running. Close this window to stop the server.
pause
