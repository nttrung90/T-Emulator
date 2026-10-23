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
$runtimeCp = @(
    $stdlib, $annotations,
    (Find-Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.7.3'),
    (Find-Jar 'org.json' 'json' '20231013'),
    (Find-Jar 'androidx.room' 'room-common' '2.6.1'),
    $mockAndroid
) -join ';'
$srcRoot = Join-Path $androidRoot 'app/src/main/java/com/temulator/app'
$sources = @(
    (Join-Path $srcRoot 'storage/GameStorageManager.kt'),
    (Join-Path $srcRoot 'cloud/GameRestoreService.kt'),
    (Join-Path $srcRoot 'data/db/GameRecord.kt'),
    (Join-Path $srcRoot 'data/db/GameDao.kt'),
    (Join-Path $srcRoot 'data/db/NativeGameMapping.kt'),
    (Join-Path $auditRoot 'AppDatabaseStub.kt'),
    (Join-Path $auditRoot 'RestoreAudit.kt')
)
$outJar = Join-Path $auditRoot 'restore-audit.jar'
& $javaExe -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $runtimeCp -d $outJar @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $javaExe -cp "$outJar;$runtimeCp" audit.RestoreAuditKt
exit $LASTEXITCODE
