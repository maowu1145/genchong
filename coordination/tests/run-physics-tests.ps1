param([string]$JdkHome = 'C:\Users\STG-ARTIST2\AppData\Local\jdk17\jdk-17.0.20.1+1')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$testOutput = Join-Path $projectRoot 'work\overlay-physics-test'
New-Item -ItemType Directory -Force $testOutput | Out-Null
$sourceRoot = Join-Path $projectRoot 'app\src\main\java\com\maowu\gravitypet\gengpet'
$sources = @('PhysicsWorld', 'BalloonBody', 'BalloonWorld', 'BalloonCage', 'BalloonMesh', 'BalloonSweep') | ForEach-Object { Join-Path $sourceRoot ($_ + '.java') }
& (Join-Path $JdkHome 'bin\javac.exe') -encoding UTF-8 -d $testOutput $sources (Join-Path $PSScriptRoot 'PhysicsWorldTest.java') (Join-Path $PSScriptRoot 'SoftPhysicsWorldTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Physics compilation failed' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $testOutput com.maowu.gravitypet.gengpet.PhysicsWorldTest
if ($LASTEXITCODE -ne 0) { throw 'Physics tests failed' }
$catalog = Get-Content -Raw -Encoding UTF8 (Join-Path $projectRoot 'app\src\main\assets\pets\catalog.json') | ConvertFrom-Json
$fixture = Join-Path $testOutput 'catalog-contours.txt'
$catalog.characters | ForEach-Object {
  $_.id + ' ' + (($_.contourUV | ForEach-Object { ([double]$_).ToString('R', [Globalization.CultureInfo]::InvariantCulture) }) -join ' ')
} | Set-Content -Encoding ASCII $fixture
& (Join-Path $JdkHome 'bin\java.exe') -cp $testOutput com.maowu.gravitypet.gengpet.SoftPhysicsWorldTest $fixture
if ($LASTEXITCODE -ne 0) { throw 'Soft physics tests failed' }
