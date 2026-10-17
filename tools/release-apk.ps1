<#
.SYNOPSIS
  ToolBox APK 一键双发布：GitHub Release（检查更新 + 下载源）+ Gitee Release（国内下载镜像）。

.DESCRIPTION
  命名约定（与 AppUpdater.kt 严格一致，改了要同步改 Kotlin）：
    GitHub tag      : <Tag>            例如 v1.9.59
    GitHub 附件名   : ToolBox-<Tag>.apk 例如 ToolBox-v1.9.59.apk
    Gitee  tag      : toolbox-<Tag>    例如 toolbox-v1.9.59
    Gitee  附件名   : ToolBox-<Tag>.apk
  检查更新始终走 GitHub；APP 内下载优先走 gh-proxy 加速代理（多域名互备）、
  失败回退 Gitee 镜像、最后 GitHub 直连。

  前置：
    1. gh CLI 已登录（gh auth login，账号 godweiyang）；
    2. 已设置环境变量 GITEE_TOKEN（Gitee 私人令牌，仅勾 projects 权限）。
       令牌只读取自环境变量，不写入任何文件 / 仓库。

.EXAMPLE
  .\tools\release-apk.ps1 -Tag v1.9.59
  # 默认上传仓库根目录下的 ToolBox-v1.9.59.apk 到两个平台

.EXAMPLE
  .\tools\release-apk.ps1 -Tag v1.9.60 -Apk .\build\app-release.apk -Prerelease
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^v\d+\.\d+\.\d+$')]
    [string]$Tag,

    [string]$Apk,

    [string]$Notes,

    # 发布前先在本地重新构建 release APK（需 JAVA_HOME 指向 JDK17）
    [switch]$Build,

    [switch]$Prerelease
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

# 执行 gh/node 等原生命令：PowerShell 5.1 在 Stop 模式下会把原生命令的 stderr
# 文本误判为终止错误，这里统一合并 stdout/stderr、按退出码判定，避免脚本中途崩掉。
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Block,
        [string]$FailMessage = '原生命令执行失败'
    )
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Block 2>&1 | ForEach-Object { $_.ToString() } | ForEach-Object { Write-Host "    $_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEAP
    }
    if ($code -ne 0) { throw "$FailMessage（exit $code）" }
}

$GiteeOwner = 'godweiyang'
$GiteeRepo  = 'storage'
$GiteeTag   = "toolbox-$Tag"
$ApkName    = "ToolBox-$Tag.apk"
if (-not $Apk) { $Apk = Join-Path $RepoRoot $ApkName }
$Apk = (Resolve-Path $Apk -ErrorAction Stop).Path

Write-Host "==> 发布目标：$Tag" -ForegroundColor Cyan
Write-Host "    APK       : $Apk"
Write-Host "    GitHub tag: $Tag"
Write-Host "    Gitee tag : $GiteeTag"

# ---------- 0. 前置检查 ----------
if (-not (Test-Path $Apk)) { throw "APK 不存在：$Apk" }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "未找到 gh CLI，请先安装并 gh auth login。" }
if (-not $env:GITEE_TOKEN) { throw "未设置环境变量 GITEE_TOKEN（Gitee 私人令牌，projects 权限）。" }
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw "未找到 node（用于上传 Gitee 附件）。" }

# ---------- 可选：先构建 ----------
if ($Build) {
    if (-not $env:JAVA_HOME) { throw "开启 -Build 时需要设置 JAVA_HOME（例如 C:\jdk17）。" }
    Write-Host "==> 构建 release APK ..." -ForegroundColor Cyan
    Invoke-Native -Block { & .\gradlew.bat assembleRelease --console=plain } -FailMessage 'gradlew assembleRelease 失败'
    Copy-Item '.\app\build\outputs\apk\release\app-release.apk' $Apk -Force
    Write-Host "    已复制为 $ApkName" -ForegroundColor Green
}

$sha = (Get-FileHash $Apk -Algorithm SHA256).Hash.ToLower()
$size = [math]::Round((Get-Item $Apk).Length / 1MB, 2)
Write-Host "    大小 ${size} MB, sha256=$($sha.Substring(0,16))..." -ForegroundColor DarkGray

if (-not $Notes) {
    $Notes = @"
ToolBox $Tag

国内下载镜像（无需 VPN，速度更快）：
https://gitee.com/$GiteeOwner/$GiteeRepo/releases/download/$GiteeTag/$ApkName

SHA256: $sha
"@
}

# ---------- 1. GitHub Release ----------
Write-Host "==> [1/2] GitHub Release ..." -ForegroundColor Cyan
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& gh release view $Tag --repo godweiyang/ToolBox 2>&1 | Out-Null
$releaseExists = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $prevEAP

if ($releaseExists) {
    Write-Host "    Release $Tag 已存在，上传 / 覆盖附件 ..." -ForegroundColor DarkGray
    Invoke-Native -Block { & gh release upload $Tag $Apk --repo godweiyang/ToolBox --clobber } -FailMessage 'GitHub 附件上传失败'
} else {
    $ghArgs = @('release', 'create', $Tag, $Apk,
        '--repo', 'godweiyang/ToolBox',
        '--title', "ToolBox $Tag",
        '--notes', $Notes)
    if ($Prerelease) { $ghArgs += '--prerelease' }
    Invoke-Native -Block { & gh @ghArgs } -FailMessage 'GitHub Release 创建失败'
}
$ghUrl = "https://github.com/godweiyang/ToolBox/releases/download/$Tag/$ApkName"
Write-Host "    GitHub 附件：$ghUrl" -ForegroundColor Green

# ---------- 2. Gitee Release（国内镜像） ----------
Write-Host "==> [2/2] Gitee Release（国内镜像）..." -ForegroundColor Cyan
$giteeScript = Join-Path $PSScriptRoot 'gitee-release.mjs'
$giteeArgs = @($giteeScript,
    '--owner', $GiteeOwner, '--repo', $GiteeRepo,
    '--tag', $GiteeTag, '--name', "ToolBox $Tag",
    '--body', $Notes,
    '--file', $Apk)
if ($Prerelease) { $giteeArgs += '--prerelease' }
Invoke-Native -Block { & node @giteeArgs } -FailMessage 'Gitee 附件上传失败'
$giteeUrl = "https://gitee.com/$GiteeOwner/$GiteeRepo/releases/download/$GiteeTag/$ApkName"

Write-Host ""
Write-Host "==> 双发布完成：" -ForegroundColor Green
Write-Host "    GitHub : $ghUrl"
Write-Host "    Gitee  : $giteeUrl"
Write-Host ""
Write-Host "    注意：仍在更旧版本（不含国内加速逻辑）的用户，升级到 $Tag 这一次仍走原逻辑；"
Write-Host "          安装 $Tag 后，模型与 APK 下载均优先走 gh-proxy、回退 Gitee。" -ForegroundColor Yellow
