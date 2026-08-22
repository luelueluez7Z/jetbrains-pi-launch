<#
    一键打包 pichat 插件：先构建 webview 前端（npm run build），再打包后端插件。

    用法:
      .\build.ps1           # 全量打包（clean buildPlugin，前端/后端改动都适用，推荐）
      .\build.ps1 -NoClean  # 增量打包（仅 Kotlin 后端改动时更快，跳过 clean）

    说明:
      - webview 的 npm run build 会把 dist/index.html 自动同步到
        src/main/resources/web/index.html（postbuild 脚本 scripts/copy-dist.mjs）。
      - 默认 clean buildPlugin 强制重新打包，避免 Gradle composedJar 对
        纯前端改动误判 UP-TO-DATE 复用旧 jar（见 AGENTS.md「构建与验证」）。
#>
param([switch]$NoClean)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

Write-Host "`n[1/2] 构建 webview 前端 (npm run build)..." -ForegroundColor Cyan
Push-Location "$root/webview"
try {
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "npm run build 失败 (exit=$LASTEXITCODE)" }
} finally { Pop-Location }

$gradleArgs = if ($NoClean) { 'buildPlugin' } else { 'clean', 'buildPlugin' }
Write-Host "[2/2] 打包后端插件 (gradlew $($gradleArgs -join ' '))..." -ForegroundColor Cyan
Push-Location $root
try {
    & ./gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "gradle 打包失败 (exit=$LASTEXITCODE)" }
} finally { Pop-Location }

$zip = Join-Path $root "build/distributions/pichat.zip"
if (Test-Path $zip) {
    $f = Get-Item $zip
    Write-Host "`n✅ 打包完成: $zip ($([math]::Round($f.Length/1MB,1)) MB)" -ForegroundColor Green
} else {
    Write-Host "`n⚠️ 未找到产物 $zip" -ForegroundColor Yellow
}
