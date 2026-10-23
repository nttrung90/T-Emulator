param(
    [string]$Bundle = 'F:\AI\T-Emulator\AndroidApp\app\build\outputs\bundle\release\app-release.aab',
    [string]$Mapping = 'F:\AI\T-Emulator\AndroidApp\app\build\outputs\mapping\release\mapping.txt',
    [string]$Label = 'release',
    [string]$SdkRoot = 'C:\Users\nguye\AppData\Local\Android\Sdk'
)
$ErrorActionPreference = 'Stop'
if ($Label -notmatch '^[a-z0-9-]+$') { throw 'Invalid audit label' }
$taskAuditDir = Join-Path $PSScriptRoot "dex-audit\$Label"
New-Item -ItemType Directory -Force -Path $taskAuditDir | Out-Null
$taskReverseNames = @{}
if (Test-Path -LiteralPath $Mapping) {
    Copy-Item -LiteralPath $Mapping -Destination (Join-Path $taskAuditDir 'mapping.txt') -Force
    foreach ($taskMappingLine in [System.IO.File]::ReadLines($Mapping)) {
        if ($taskMappingLine -match '^([^\s].*) -> ([^:]+):$') {
            $taskReverseNames[$Matches[2]] = $Matches[1]
        }
    }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskArchive = [System.IO.Compression.ZipFile]::OpenRead($Bundle)
$taskCalls = [System.Collections.Generic.List[object]]::new()
$taskClasses = [System.Collections.Generic.HashSet[string]]::new()
try {
    foreach ($taskEntry in $taskArchive.Entries) {
        if ($taskEntry.FullName -notmatch '^base/dex/classes\d*\.dex$') { continue }
        $taskDexName = [System.IO.Path]::GetFileName($taskEntry.FullName)
        $taskDexFile = Join-Path $taskAuditDir $taskDexName
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($taskEntry, $taskDexFile, $true)
        $taskDump = Join-Path $taskAuditDir "$taskDexName.txt"
        & (Join-Path $SdkRoot 'build-tools\36.0.0\dexdump.exe') -d $taskDexFile > $taskDump
        if ($LASTEXITCODE -ne 0) { throw "dexdump failed for $taskDexName" }
        $taskCurrentClass = ''
        $taskCurrentMethod = ''
        foreach ($taskLine in [System.IO.File]::ReadLines($taskDump)) {
            if ($taskLine -match "Class descriptor\s*:\s*'L([^;]+);'") {
                $taskCurrentClass = $Matches[1].Replace('/', '.')
                if ($taskReverseNames.ContainsKey($taskCurrentClass)) { $taskCurrentClass = $taskReverseNames[$taskCurrentClass] }
                [void]$taskClasses.Add($taskCurrentClass)
            } elseif ($taskLine -match "^\s+name\s*:\s*'([^']+)'") {
                $taskCurrentMethod = $Matches[1]
            }
            if ($taskLine -match 'invoke-.*Landroid/view/Window;\.(setStatusBarColor|setNavigationBarColor):') {
                $taskCalls.Add([pscustomobject]@{Dex=$taskDexName;Class=$taskCurrentClass;Method=$taskCurrentMethod;Api=$Matches[1]})
            }
        }
    }
} finally { $taskArchive.Dispose() }
$taskCalls | Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $taskAuditDir 'deprecated-window-call-sites.csv')
$taskWarnedClasses = @(
    'com.google.android.material.bottomsheet.BottomSheetDialog',
    'com.google.android.material.internal.EdgeToEdgeUtils',
    'com.google.android.material.sidesheet.SheetDialog'
)
$taskClassPresence = foreach ($taskWarnedClass in $taskWarnedClasses) {
    [pscustomobject]@{Class=$taskWarnedClass;PresentInDex=$taskClasses.Contains($taskWarnedClass)}
}
$taskClassPresence | Export-Csv -NoTypeInformation -Encoding utf8 (Join-Path $taskAuditDir 'warned-material-classes.csv')
$taskSummary = [pscustomobject]@{
    Bundle=$Bundle
    BundleSha256=(Get-FileHash -LiteralPath $Bundle -Algorithm SHA256).Hash
    BundleBytes=(Get-Item -LiteralPath $Bundle).Length
    TotalDexClasses=$taskClasses.Count
    DeprecatedWindowCallSites=$taskCalls.Count
    WarnedMaterialClasses=@($taskClassPresence)
    CallSites=@($taskCalls)
}
$taskSummary | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 (Join-Path $taskAuditDir 'summary.json')
$taskSummary | ConvertTo-Json -Depth 6
