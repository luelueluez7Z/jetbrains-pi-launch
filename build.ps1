<#
    一键打包 pichat 插件：先递增补丁版本，再构建 webview 前端（npm run build），
    最后使用非 daemon 模式打包后端插件。

    用法:
      .\build.ps1           # 全量打包（clean buildPlugin，前端/后端改动都适用，推荐）
      .\build.ps1 -NoClean  # 增量打包（仅 Kotlin 后端改动时更快，跳过 clean）

    说明:
      - 每次执行脚本都会把 src/main/resources/META-INF/plugin.xml 的补丁版本加一。
      - webview 的 npm run build 会把 dist/index.html 自动同步到
        src/main/resources/web/index.html（postbuild 脚本 scripts/copy-dist.mjs）。
      - 默认 clean buildPlugin 强制重新打包，避免 Gradle composedJar 对
        纯前端改动误判 UP-TO-DATE 复用旧 jar（见 AGENTS.md「构建与验证」）。
#>
param([switch]$NoClean)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

Write-Host "`n[0/2] 更新插件版本..." -ForegroundColor Cyan
$pluginXml = Join-Path $root "src/main/resources/META-INF/plugin.xml"
if (-not (Test-Path -LiteralPath $pluginXml)) {
    throw "未找到插件声明文件: $pluginXml"
}

$pluginXmlContent = [System.IO.File]::ReadAllText($pluginXml)
$versionMatch = [regex]::Match(
    $pluginXmlContent,
    '<version>\s*(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)\s*</version>'
)
if (-not $versionMatch.Success) {
    throw "无法从 plugin.xml 读取三段式版本号: $pluginXml"
}

$currentVersion = "{0}.{1}.{2}" -f `
    $versionMatch.Groups['major'].Value,
    $versionMatch.Groups['minor'].Value,
    $versionMatch.Groups['patch'].Value
$nextPatch = [int]::Parse($versionMatch.Groups['patch'].Value) + 1
$nextVersion = "{0}.{1}.{2}" -f `
    $versionMatch.Groups['major'].Value,
    $versionMatch.Groups['minor'].Value,
    $nextPatch
$patchGroup = $versionMatch.Groups['patch']
$pluginXmlContent = $pluginXmlContent.Remove($patchGroup.Index, $patchGroup.Length).Insert($patchGroup.Index, $nextPatch.ToString())
[System.IO.File]::WriteAllText(
    $pluginXml,
    $pluginXmlContent,
    [System.Text.UTF8Encoding]::new($false)
)
Write-Host "版本: $currentVersion -> $nextVersion" -ForegroundColor Gray

Write-Host "`n[1/2] 构建 webview 前端 (npm run build)..." -ForegroundColor Cyan
Push-Location "$root/webview"
try {
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "npm run build 失败 (exit=$LASTEXITCODE)" }
} finally { Pop-Location }

[string[]]$gradleTasks = if ($NoClean) { @('buildPlugin') } else { @('clean', 'buildPlugin') }
# 打包使用 --no-daemon，避免构建完成后留下长期驻留的 Gradle daemon。
[string[]]$gradleArgs = @('--no-daemon') + $gradleTasks
Write-Host "[2/2] 打包后端插件 (gradlew $($gradleArgs -join ' '))..." -ForegroundColor Cyan
Push-Location $root
try {
    & ./gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "gradle 打包失败 (exit=$LASTEXITCODE)" }
} finally { Pop-Location }

$zip = Join-Path $root "build/distributions/pichat.zip"
if (Test-Path $zip) {
    $f = Get-Item $zip
    Write-Host "`n✅ 打包完成: $zip (版本 $nextVersion, $([math]::Round($f.Length/1MB,1)) MB)" -ForegroundColor Green
} else {
    Write-Host "`n⚠️ 未找到产物 $zip" -ForegroundColor Yellow
}
