param([string]$JdkHome = 'C:\Users\STG-ARTIST2\AppData\Local\jdk17\jdk-17.0.20.1+1')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$testOutput = Join-Path $projectRoot 'work\overlay-physics-test'
New-Item -ItemType Directory -Force $testOutput | Out-Null
& (Join-Path $JdkHome 'bin\javac.exe') -encoding UTF-8 -d $testOutput (Join-Path $projectRoot 'app\src\main\java\com\maowu\gravitypet\gengpet\PhysicsWorld.java') (Join-Path $PSScriptRoot 'PhysicsWorldTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Physics compilation failed' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $testOutput com.maowu.gravitypet.gengpet.PhysicsWorldTest
if ($LASTEXITCODE -ne 0) { throw 'Physics tests failed' }
