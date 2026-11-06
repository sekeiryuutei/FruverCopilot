# ==============================================================================
# FruverCopilot - Watcher de Impresora Remota en Raspberry Pi (Tailscale)
# ==============================================================================
# Este script sondea cada 5 segundos hasta que la Raspberry Pi esté en línea,
# auto-aprovisiona la clave SSH si es necesario, verifica CUPS con la Brother HL-L2405W,
# ejecuta UNA SOLA impresión de prueba controlada y finaliza de manera limpia.
# ==============================================================================

param(
    [string]$RpiHost = "100.105.30.91",
    [int]$RpiPort = 22,
    [string]$RpiUser = "victor",
    [string]$SshKey = "$env:USERPROFILE\.ssh\id_ed25519_tailscale_print",
    [string]$BackendUrl = "http://localhost:8085",
    [int]$PollIntervalSec = 5,
    [int]$MaxAttempts = 120 # 10 minutos máx
)

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   FruverCopilot - Watcher Impresora Brother HL-L2405W   " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Host RPi Tailscale: $RpiHost:$RpiPort"
Write-Host "Usuario: $RpiUser"
Write-Host "Llave SSH: $SshKey"
Write-Host "Backend API: $BackendUrl"
Write-Host "Sondeo: Cada $PollIntervalSec seg (Presione Ctrl+C para detener)`n"

$pubKeyPath = "$SshKey.pub"
$existingRsa = "$env:USERPROFILE\.ssh\id_rsa"

$attempt = 0
$connected = $false

while (-not $connected -and $attempt -lt $MaxAttempts) {
    $attempt++
    $timestamp = (Get-Date).ToString("HH:mm:ss")
    Write-Host "[$timestamp] Intento $attempt/$MaxAttempts - Verificando $RpiHost:$RpiPort..." -NoNewline

    # Prueba de socket TCP rápida (1.5s timeout)
    $tcp = New-Object System.Net.Sockets.TcpClient
    $iar = $tcp.BeginConnect($RpiHost, $RpiPort, $null, $null)
    $success = $iar.AsyncWaitHandle.WaitOne(1500)

    if ($success -and $tcp.Connected) {
        $tcp.EndConnect($iar)
        $tcp.Close()
        Write-Host " ¡EN LÍNEA! 🟢" -ForegroundColor Green
        $connected = $true
        break
    } else {
        $tcp.Close()
        Write-Host " Fuera de línea (esperando que encienda)... ⏳" -ForegroundColor DarkGray
    }

    Start-Sleep -Seconds $PollIntervalSec
}

if (-not $connected) {
    Write-Host "`n❌ Se alcanzó el tiempo máximo de espera sin que la Raspberry Pi respondiera." -ForegroundColor Red
    exit 1
}

Write-Host "`n🍓 Raspberry Pi detectada en línea. Verificando SSH..." -ForegroundColor Cyan

# 2. Verificar SSH con la llave dedicada
$sshTest = ssh -i $SshKey -o StrictHostKeyChecking=no -o ConnectTimeout=5 -p $RpiPort "$RpiUser@$RpiHost" "echo 'SSH_OK'; lpstat -p -d 2>/dev/null || true" 2>&1

if ($sshTest -match "SSH_OK") {
    Write-Host "✓ Clave SSH dedicada YA está autorizada y funcionando en la Raspberry Pi." -ForegroundColor Green
} else {
    Write-Host "⚠️ La llave dedicada aún no está en authorized_keys. Intentando auto-aprovisionar usando llave local existente (id_rsa)..." -ForegroundColor Yellow
    if (Test-Path $existingRsa -and (Test-Path $pubKeyPath)) {
        $pubContent = (Get-Content $pubKeyPath -Raw).Trim()
        $installCmd = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '$pubContent' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && echo 'AUTO_PROVISION_OK'"
        $provTest = ssh -i $existingRsa -o StrictHostKeyChecking=no -o ConnectTimeout=5 -p $RpiPort "$RpiUser@$RpiHost" $installCmd 2>&1

        if ($provTest -match "AUTO_PROVISION_OK") {
            Write-Host "✅ ¡Clave pública instalada automáticamente en la Raspberry Pi con éxito!" -ForegroundColor Green
        } else {
            Write-Host "⚠️ No se pudo auto-instalar la llave con id_rsa ($provTest). Puedes agregarla manualmente o ingresar con contraseña." -ForegroundColor DarkYellow
        }
    }
}

# 3. Verificar estado de CUPS e impresora
Write-Host "`nVerificando colas de impresión CUPS..." -ForegroundColor Cyan
$cupsStatus = ssh -i $SshKey -o StrictHostKeyChecking=no -o ConnectTimeout=5 -p $RpiPort "$RpiUser@$RpiHost" "lpstat -p -d 2>/dev/null || echo 'NO_CUPS'" 2>&1
Write-Host "$cupsStatus" -ForegroundColor Gray

# 4. Ejecutar UNA SOLA impresión de prueba a través del Backend API
Write-Host "`n🖨️ Ejecutando 1 impresión de prueba controlada a través de FruverCopilot Backend..." -ForegroundColor Cyan
try {
    $response = Invoke-RestMethod -Uri "$BackendUrl/api/printer/rpi/test-print" -Method Post -TimeoutSec 15 -ErrorAction Stop
    if ($response.success) {
        Write-Host "✅ Impresión de prueba completada exitosamente!" -ForegroundColor Green
        Write-Host "Mensaje: $($response.message)" -ForegroundColor Green
        Write-Host "Duración: $($response.durationMs) ms" -ForegroundColor Gray
    } else {
        Write-Host "⚠️ El backend reportó: $($response.message)" -ForegroundColor Yellow
    }
} catch {
    Write-Host "⚠️ No se pudo invocar el endpoint /api/printer/rpi/test-print: $($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host "Intentando prueba directa vía SSH..." -ForegroundColor Gray
    $directPrint = ssh -i $SshKey -o StrictHostKeyChecking=no -p $RpiPort "$RpiUser@$RpiHost" "echo 'TEST FRUVERCOPILOT DIRECTO' | lp -d Brother_HL_L2405W -o raw 2>&1"
    Write-Host "Resultado directo: $directPrint"
}

Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host "   Watcher finalizado exitosamente. Saliendo limpiamente.  " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
