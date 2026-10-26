param([string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
if(-not $JavaHome){throw 'Pass a Java17 JDK with -JavaHome'}
$project=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $project 'work/recycle-gesture-test/classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d $classes (Join-Path $project 'app/src/main/java/com/maowu/gravitypet/gengpet/RecycleGesture.java') (Join-Path $PSScriptRoot 'RecycleGestureTest.java')
if($LASTEXITCODE -ne 0){throw 'RecycleGesture compilation failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp $classes RecycleGestureTest
if($LASTEXITCODE -ne 0){throw 'RecycleGesture regression failed'}
