# School — bootstrap de um PC Windows limpo até o comando `schooling`.
#
#   iwr https://raw.githubusercontent.com/raidenario/schooling/main/scripts/install.ps1 -OutFile install.ps1
#   powershell -ExecutionPolicy Bypass -File install.ps1
#
# Idempotente: pode rodar de novo que ele só completa o que falta.
# O que ele faz: scoop + ferramentas (git, JDK 21, Clojure, Node, Maven, pnpm),
# clona o layout de pastas irmãs, instala o jar do dice no ~/.m2, configura
# NVIDIA_APIKEY / SCHOOL_VAULT_ROOT e instala o comando `schooling` no PATH.
param(
  [string]$BaseDir = (Join-Path $env:USERPROFILE "fritas")
)
$ErrorActionPreference = "Stop"

function Passo($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Tem($cmd) { [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }

# --- 1. scoop + ferramentas --------------------------------------------------
if (-not (Tem "scoop")) {
  Passo "instalando o scoop"
  # rodando via -ExecutionPolicy Bypass, o escopo Process sobrepoe o CurrentUser
  # e o Set-ExecutionPolicy reclama (ExecutionPolicyOverride) MESMO tendo
  # funcionado — aviso benigno, nao pode derrubar o script
  try { Set-ExecutionPolicy RemoteSigned -Scope CurrentUser -Force -ErrorAction Stop } catch {}
  Invoke-RestMethod get.scoop.sh | Invoke-Expression
  $env:Path = "$env:USERPROFILE\scoop\shims;$env:Path"
}
Passo "ferramentas (so instala o que falta)"
try { scoop bucket add java *> $null } catch {}
if (-not (Tem "git"))     { scoop install git }
if (-not (Tem "java"))    { scoop install temurin21-jdk }
if (-not (Tem "clojure")) { scoop install clojure }
if (-not (Tem "node"))    { scoop install nodejs }
if (-not (Tem "mvn"))     { scoop install maven }
if (-not (Tem "pnpm"))    { scoop install pnpm }

# --- 2. o layout de pastas irmas (os :local/root dependem dele) --------------
#   <base>/schooling  +  <base>/embabel-lab/{embabel-clj, dice, dice-chronicle}
Passo "clonando em $BaseDir (pula o que ja existe)"
New-Item -ItemType Directory -Force $BaseDir | Out-Null
New-Item -ItemType Directory -Force (Join-Path $BaseDir "embabel-lab") | Out-Null
$clones = @(
  @{ url = "https://github.com/raidenario/schooling";      dir = "schooling" },
  @{ url = "https://github.com/raidenario/embabel-clj";    dir = "embabel-lab\embabel-clj" },
  @{ url = "https://github.com/embabel/dice";              dir = "embabel-lab\dice" },
  @{ url = "https://github.com/raidenario/dice-chronicle"; dir = "embabel-lab\dice-chronicle" }
)
foreach ($c in $clones) {
  $alvo = Join-Path $BaseDir $c.dir
  if (Test-Path (Join-Path $alvo ".git")) {
    Write-Host "    $($c.dir): ja clonado" -ForegroundColor DarkGray
  } else {
    git clone $c.url $alvo
  }
}
$school = Join-Path $BaseDir "schooling"

# --- 3. dice no ~/.m2 (o chronicle e o backend dependem do jar) ---------------
$diceJar = Join-Path $env:USERPROFILE ".m2\repository\com\embabel\dice\dice\0.1.1-SNAPSHOT"
if (Test-Path $diceJar) {
  Write-Host "    dice ja instalado no ~/.m2" -ForegroundColor DarkGray
} else {
  Passo "compilando o dice para o ~/.m2 (demora alguns minutos, uma vez so)"
  Push-Location (Join-Path $BaseDir "embabel-lab\dice")
  mvn install -pl dice -am -DskipTests -q
  Pop-Location
}

# --- 4. ambiente --------------------------------------------------------------
if (-not [Environment]::GetEnvironmentVariable("NVIDIA_APIKEY", "User") -and -not $env:NVIDIA_APIKEY) {
  Passo "chave do provider LLM"
  Write-Host "    crie uma chave gratis em https://build.nvidia.com (perfil > API keys)"
  $key = Read-Host "    cole sua NVIDIA_APIKEY (ou Enter para configurar depois)"
  if ($key) { [Environment]::SetEnvironmentVariable("NVIDIA_APIKEY", $key, "User"); $env:NVIDIA_APIKEY = $key }
}
if (-not [Environment]::GetEnvironmentVariable("SCHOOL_VAULT_ROOT", "User") -and -not $env:SCHOOL_VAULT_ROOT) {
  $vault = Join-Path $env:USERPROFILE "Documents\school-vault"
  Passo "vault do aprendiz em $vault (abra no Obsidian se quiser)"
  New-Item -ItemType Directory -Force $vault | Out-Null
  [Environment]::SetEnvironmentVariable("SCHOOL_VAULT_ROOT", $vault, "User")
}

# --- 5. o comando `schooling` no PATH -----------------------------------------
Passo "instalando o comando schooling"
$bin = Join-Path $env:USERPROFILE "bin"
New-Item -ItemType Directory -Force $bin | Out-Null
$ps1 = Join-Path $school "scripts\schooling.ps1"
Set-Content -Encoding ascii (Join-Path $bin "schooling.cmd") `
  "@echo off`r`npowershell -NoProfile -ExecutionPolicy Bypass -File `"$ps1`" %*"
$reg  = Get-Item "HKCU:\Environment"
$kind = try { $reg.GetValueKind("Path") } catch { "String" }
$raw  = $reg.GetValue("Path", "", "DoNotExpandEnvironmentNames")
if (($raw -split ";") -notcontains $bin) {
  $novo = ($raw.TrimEnd(';') + ";" + $bin)
  if ("$kind" -eq "ExpandString") {
    Set-ItemProperty "HKCU:\Environment" -Name Path -Value $novo -Type ExpandString
  } else {
    [Environment]::SetEnvironmentVariable("Path", $novo, "User")
  }
  Write-Host "    $bin adicionado ao PATH do usuario" -ForegroundColor DarkGray
}

Passo "pronto!"
Write-Host ""
Write-Host "  abra um TERMINAL NOVO (para o PATH valer) e digite:  schooling" -ForegroundColor Green
Write-Host "  primeira execucao demora (deps do Clojure + Spring); depois e rapido."
Write-Host ""
