# School — o projeto inteiro num comando: `schooling [matéria]`
#
# Sobe o backend (clojure -M:server) escondido se a porta não estiver de pé,
# espera ficar pronto, garante as deps do TUI e abre o TUI em primeiro plano.
# Ao sair do TUI, derruba o backend SE foi este script que o subiu (um backend
# que você abriu à mão é reaproveitado e fica de pé).
#
# Logs do backend: %LOCALAPPDATA%\school\backend.log
$ErrorActionPreference = "Stop"

$root    = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$backend = Join-Path $root "backend"
$tui     = Join-Path $root "tui"
$port    = 7777
if ($env:SCHOOL_PORT) { $port = [int]$env:SCHOOL_PORT }

if (-not $env:NVIDIA_APIKEY -and -not $env:SCHOOL_APIKEY) {
  Write-Host "falta a chave do provider: exporte NVIDIA_APIKEY (ou SCHOOL_APIKEY)" -ForegroundColor Red
  exit 1
}

# o backend exige Java 21+ (embabel = bytecode 21); um JDK velho no PATH
# derruba o boot com UnsupportedClassVersionError — se for o caso, aponta
# para o JDK 21 do scoop SÓ neste processo (os filhos herdam)
function JavaMajor {
  if (-not (Get-Command java -ErrorAction SilentlyContinue)) { return 0 }
  try {
    # java -version escreve no stderr; redirecionar DENTRO do PS 5.1 com
    # EAP=Stop vira exceção (NativeCommandError) — o cmd /c redireciona fora
    $linha = (cmd /c "java -version 2>&1" | Select-Object -First 1).ToString()
    if ($linha -match '"(\d+)') { return [int]$Matches[1] } else { return 0 }
  } catch { return 0 }
}
if ((JavaMajor) -lt 21) {
  $jdk21 = Join-Path $env:USERPROFILE "scoop\apps\temurin21-jdk\current"
  if (Test-Path (Join-Path $jdk21 "bin\java.exe")) {
    $env:JAVA_HOME = $jdk21
    $env:Path = (Join-Path $jdk21 "bin") + ";" + $env:Path
    Write-Host "· java do PATH é antigo — usando o JDK 21 do scoop" -ForegroundColor DarkGray
  } else {
    Write-Host "preciso de Java 21+ e não achei o JDK do scoop — rode o install.ps1 de novo" -ForegroundColor Red
    exit 1
  }
}

function Test-Porta([int]$p) {
  [bool](Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue)
}

$backendProc = $null
if (Test-Porta $port) {
  Write-Host "· backend já de pé na porta $port — reaproveitando" -ForegroundColor DarkGray
} else {
  $logDir = Join-Path $env:LOCALAPPDATA "school"
  New-Item -ItemType Directory -Force $logDir | Out-Null
  $log = Join-Path $logDir "backend.log"
  $err = Join-Path $logDir "backend.err.log"
  Write-Host "· subindo o backend (primeira vez demora MUITO: baixa todas as deps)…" -ForegroundColor DarkGray
  # via wrapper powershell: o `clojure` do scoop pode ser .exe OU .ps1 conforme
  # a versão do manifest — Start-Process só lança executável direto
  $backendProc = Start-Process -FilePath "powershell" `
    -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", "clojure -M:server" `
    -WorkingDirectory $backend -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $log -RedirectStandardError $err
  $deadline = (Get-Date).AddSeconds(900)
  while (-not (Test-Porta $port)) {
    if ($backendProc.HasExited) {
      Write-Host "backend morreu no boot — veja $err" -ForegroundColor Red
      Get-Content $err -Tail 5 -ErrorAction SilentlyContinue
      exit 1
    }
    if ((Get-Date) -gt $deadline) {
      Write-Host "timeout esperando a porta $port — veja $log" -ForegroundColor Red
      taskkill /PID $backendProc.Id /T /F | Out-Null
      exit 1
    }
    Start-Sleep -Milliseconds 800
  }
  Write-Host "· backend pronto (porta $port · log em $log)" -ForegroundColor DarkGray
}

try {
  if (-not (Test-Path (Join-Path $tui "node_modules"))) {
    Write-Host "· instalando deps do TUI (pnpm install)…" -ForegroundColor DarkGray
    Push-Location $tui
    pnpm install
    Pop-Location
  }
  Push-Location $tui
  pnpm run dev -- @args
} finally {
  try { Pop-Location } catch {}
  if ($backendProc -and -not $backendProc.HasExited) {
    Write-Host "· encerrando o backend (pid $($backendProc.Id))…" -ForegroundColor DarkGray
    taskkill /PID $backendProc.Id /T /F | Out-Null
  }
}
