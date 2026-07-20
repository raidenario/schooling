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
  Write-Host "· subindo o backend (primeira vez demora: deps + Spring)…" -ForegroundColor DarkGray
  $backendProc = Start-Process -FilePath "clojure" -ArgumentList "-M:server" `
    -WorkingDirectory $backend -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $log -RedirectStandardError $err
  $deadline = (Get-Date).AddSeconds(300)
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
