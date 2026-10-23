$ErrorActionPreference = 'Stop'
$auditRoot = $PSScriptRoot
$androidRoot = (Resolve-Path (Join-Path $auditRoot '../..')).Path
$javaExe = 'C:/Users/nguye/.jdks/jbr-17.0.14/bin/java.exe'
$moduleCache = 'C:/Users/nguye/.gradle/caches/modules-2/files-2.1'
function Find-Jar([string]$group, [string]$artifact, [string]$version) {
    $base = Join-Path $moduleCache "$group/$artifact/$version"
    $found = Get-ChildItem $base -Recurse -Filter "$artifact-$version.jar" | Select-Object -First 1
    if (!$found) { throw "Missing cached dependency: $artifact $version" }
    return $found.FullName
}
$stdlib = Find-Jar 'org.jetbrains.kotlin' 'kotlin-stdlib' '1.9.0'
$annotations = Find-Jar 'org.jetbrains' 'annotations' '13.0'
$compilerCp = @(
    (Find-Jar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '1.9.0'),
    $stdlib,
    (Find-Jar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.9.0'),
    (Find-Jar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '1.9.0'),
    (Find-Jar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'),
    $annotations
) -join ';'
$mockAndroid = 'C:/Users/nguye/.gradle/caches/transforms-4/4218e55b17f9103bea8cd0288e16899f/transformed/android.jar'
$junitJar = Find-Jar 'junit' 'junit' '4.13.2'
$hamcrestJar = Find-Jar 'org.hamcrest' 'hamcrest-core' '1.3'

$runtimeCp = @(
    $stdlib, $annotations,
    (Find-Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.7.3'),
    (Find-Jar 'org.json' 'json' '20231013'),
    (Find-Jar 'androidx.room' 'room-common' '2.6.1'),
    $mockAndroid,
    $junitJar,
    $hamcrestJar
) -join ';'

$srcRoot = Join-Path $androidRoot 'app/src/main/java/com/temulator/app'
$testRoot = Join-Path $androidRoot 'app/src/test/java/com/temulator/app'

$sources = @(
    (Join-Path $srcRoot 'storage/GameStorageManager.kt'),
    (Join-Path $srcRoot 'cloud/GameRestoreService.kt'),
    (Join-Path $srcRoot 'data/db/GameRecord.kt'),
    (Join-Path $srcRoot 'data/db/GameDao.kt'),
    (Join-Path $srcRoot 'data/db/NativeGameMapping.kt'),
    (Join-Path $srcRoot 'util/EmulatorDetector.kt'),
    (Join-Path $srcRoot 'auth/UserAccount.kt'),
    (Join-Path $srcRoot 'auth/AuthSessionTracker.kt'),
    (Join-Path $srcRoot 'billing/VipCachePolicy.kt'),
    (Join-Path $srcRoot 'billing/VipMetadataDecoder.kt'),
    (Join-Path $auditRoot 'BuildConfigStub.kt'),
    (Join-Path $auditRoot 'AppDatabaseStub.kt'),
    (Join-Path $testRoot 'cloud/GameRestoreSecurityTest.kt'),
    (Join-Path $testRoot 'billing/VipEmulatorTest.kt'),
    (Join-Path $testRoot 'billing/VipCachePolicyTest.kt')
)
$outJar = Join-Path $auditRoot 'unit-tests-compiled.jar'
& $javaExe -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $runtimeCp -d $outJar @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "--- Running GameRestoreSecurityTest ---"
& $javaExe -cp "$outJar;$runtimeCp" org.junit.runner.JUnitCore com.temulator.app.cloud.GameRestoreSecurityTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "--- Running VipEmulatorTest ---"
& $javaExe -cp "$outJar;$runtimeCp" org.junit.runner.JUnitCore com.temulator.app.billing.VipEmulatorTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "--- Running VipCachePolicyTest ---"
& $javaExe -cp "$outJar;$runtimeCp" org.junit.runner.JUnitCore com.temulator.app.billing.VipCachePolicyTest
exit $LASTEXITCODE
