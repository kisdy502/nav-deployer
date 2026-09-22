# 一键发版（WSL 内 k3s）：构建镜像 -> 导入 WSL k3s containerd -> 滚动更新
# 前置：Docker Desktop 运行中；WSL 内 k3s systemd 运行中
# 用法：
#   powershell -ExecutionPolicy Bypass -File k8s\deploy.ps1              # 自动生成 v<pom版本>-<时间戳> tag
#   powershell -ExecutionPolicy Bypass -File k8s\deploy.ps1 -Tag v0.1.1  # 指定 tag
param(
    [string]$Tag = "",
    [string]$Namespace = "nav-deployer",
    [string]$Image = "nav-deployer"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

# 未指定 Tag 时自动生成 v<pom版本>-<时间戳>，保证每次都是新 tag
if (-not $Tag) {
    $pom = Get-Content (Join-Path $projectRoot "pom.xml") -Raw
    $ver = if ($pom -match "(?s)</parent>.*?<version>([^<]+)</version>") { $Matches[1] } else { "0.0.0" }
    $Tag = "v$ver-" + (Get-Date -Format "yyyyMMddHHmmss")
}
$fullImage = "$Image`:$Tag"

Write-Host "== 1/4 构建镜像 $fullImage ==" -ForegroundColor Cyan
docker build -f (Join-Path $projectRoot "docker\Dockerfile") -t $fullImage $projectRoot
if ($LASTEXITCODE -ne 0) { throw "docker build 失败" }

Write-Host "== 2/4 导出镜像 ==" -ForegroundColor Cyan
$tar = Join-Path $env:TEMP "k3s-import-$Tag.tar"
docker save $fullImage -o $tar
if ($LASTEXITCODE -ne 0) { throw "docker save 失败" }
# Windows 路径转 WSL 路径：C:\Users\...\x.tar -> /mnt/c/Users/.../x.tar
$wslTar = "/mnt/" + $tar.Substring(0, 1).ToLower() + $tar.Substring(2).Replace("\", "/")

Write-Host "== 3/4 导入 WSL k3s containerd ==" -ForegroundColor Cyan
wsl -u root -- sh -c "k3s ctr images import '$wslTar'"
if ($LASTEXITCODE -ne 0) { throw "k3s ctr images import 失败" }
Remove-Item $tar -Force

Write-Host "== 4/4 滚动更新 deployment/nav-deployer (namespace=$Namespace) ==" -ForegroundColor Cyan
wsl -u root -- k3s kubectl set image deployment/nav-deployer nav-deployer=$fullImage -n $Namespace
if ($LASTEXITCODE -ne 0) { throw "kubectl set image 失败" }
wsl -u root -- k3s kubectl rollout status deployment/nav-deployer -n $Namespace --timeout=300s
if ($LASTEXITCODE -ne 0) {
    Write-Host "回滚命令：wsl -u root -- k3s kubectl rollout undo deployment/nav-deployer -n $Namespace" -ForegroundColor Yellow
    throw "滚动更新失败"
}

Write-Host "完成：$fullImage 已上线 (namespace=$Namespace)" -ForegroundColor Green
Write-Host "回滚命令：wsl -u root -- k3s kubectl rollout undo deployment/nav-deployer -n $Namespace"
