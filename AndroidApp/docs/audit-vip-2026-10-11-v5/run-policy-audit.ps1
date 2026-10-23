$ErrorActionPreference = 'Stop'
$auditRoot = $PSScriptRoot
$androidRoot = (Resolve-Path (Join-Path $auditRoot '../..')).Path
$javaExe = 'C:/Users/nguye/.jdks/jbr-17.0.14/bin/java.exe'
$moduleCache = 'C:/Users/nguye/.gradle/caches/modules-2/files-2.1'
function Find-Jar([string]$group, [string]$artifact, [string]$version) {
    $found = Get-ChildItem (Join-Path $moduleCache "$group/$artifact/$version") -Recurse -Filter "$artifact-$version.jar" | Select-Object -First 1
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
$outJar = Join-Path $auditRoot 'policy-audit.jar'
$coroutines = Find-Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.7.3'
$json = Find-Jar 'org.json' 'json' '20231013'
$runtimeCp = "$stdlib;$annotations;$coroutines;$json"
$sources = @(
    (Join-Path $androidRoot 'app/src/main/java/com/temulator/app/billing/VipCachePolicy.kt'),
    (Join-Path $androidRoot 'app/src/main/java/com/temulator/app/billing/VipMetadataDecoder.kt'),
    (Join-Path $androidRoot 'app/src/main/java/com/temulator/app/auth/UserAccount.kt'),
    (Join-Path $androidRoot 'app/src/main/java/com/temulator/app/auth/AuthSessionTracker.kt'),
    (Join-Path $auditRoot 'PolicyAudit.kt')
)
& $javaExe -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $runtimeCp -d $outJar @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $javaExe -cp "$outJar;$runtimeCp" audit.PolicyAuditKt
exit $LASTEXITCODE
