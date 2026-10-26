param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { throw 'Pass -JavaHome with a Java 17 JDK directory.' }
$project = Split-Path $PSScriptRoot -Parent
$source = Join-Path $project 'app/src/main/java/com/maowu/gravitypet/gengpet'
$classes = Join-Path $project 'work/tools-test-classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d $classes (Join-Path $source 'KinshipEngine.java') (Join-Path $source 'ReminderRules.java') (Join-Path $source 'ThursdayCalendar.java') (Join-Path $PSScriptRoot 'ToolsRulesTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Rule compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -cp $classes ToolsRulesTest
if ($LASTEXITCODE -ne 0) { throw 'Rule tests failed' }
