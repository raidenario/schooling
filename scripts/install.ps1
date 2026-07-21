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
function JavaMajor {
  if (-not (Tem "java")) { return 0 }
  try {
    # java -version escreve no stderr; redirecionar DENTRO do PS 5.1 com
    # EAP=Stop vira exceção (NativeCommandError) — o cmd /c redireciona fora
    $linha = (cmd /c "java -version 2>&1" | Select-Object -First 1).ToString()
    if ($linha -match '"(\d+)') { return [int]$Matches[1] } else { return 0 }
  } catch { return 0 }
}

Passo "ferramentas (so instala o que falta)"
try { scoop bucket add java *> $null } catch {}
if (-not (Tem "git"))     { scoop install git }
# presenca de `java` nao basta: um JDK velho no PATH (17-) nao roda o embabel
# (bytecode 21 / class file 65) — checa a VERSAO
if ((JavaMajor) -lt 21)   { scoop install temurin21-jdk }
if (-not (Tem "clojure")) { scoop install clojure }
if (-not (Tem "node"))    { scoop install nodejs }
if (-not (Tem "mvn"))     { scoop install maven }
if (-not (Tem "pnpm"))    { scoop install pnpm }
# nesta MESMA sessao o PATH ainda pode resolver o java velho — aponta o
# JDK 21 do scoop para o mvn (dice) e qualquer passo seguinte
$jdk21 = Join-Path $env:USERPROFILE "scoop\apps\temurin21-jdk\current"
if (((JavaMajor) -lt 21) -and (Test-Path (Join-Path $jdk21 "bin\java.exe"))) {
  $env:JAVA_HOME = $jdk21
  $env:Path = (Join-Path $jdk21 "bin") + ";" + $env:Path
  Write-Host "    usando o JDK 21 do scoop nesta sessao (java do PATH e antigo)" -ForegroundColor DarkGray
}

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
    # o app (schooling) muda a cada versao — ATUALIZA (re-rodar o install
    # passa a trazer a versao nova, como o cabecalho promete). Os irmaos
    # (embabel-clj/dice/chronicle) mudam raro e tem versao pinada: ficam como
    # estao pra nao surpreender. `git pull --ff-only` nunca cria merge nem
    # sobrescreve trabalho local — se divergir, so avisa.
    if ($c.dir -eq "schooling") {
      Write-Host "    schooling: atualizando (git pull)" -ForegroundColor DarkGray
      Push-Location $alvo
      git pull --ff-only
      if ($LASTEXITCODE -ne 0) {
        Write-Host "    (nao consegui atualizar sozinho — rode 'git pull' em $alvo)" -ForegroundColor Yellow
      }
      Pop-Location
    } else {
      Write-Host "    $($c.dir): ja clonado" -ForegroundColor DarkGray
    }
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
