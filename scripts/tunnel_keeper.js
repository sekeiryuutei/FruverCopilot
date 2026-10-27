/**
 * TUNNEL KEEPER & SUPERVISOR (FruverCopilot)
 * -------------------------------------------------------------
 * Mantiene vivo el túnel de ngrok 24/7:
 * 1. Envía un "keep-alive ping" cada 10 minutos para evitar que ngrok o la red lo cierren por inactividad.
 * 2. Si detecta que el túnel no responde, reinicia ngrok automáticamente con el mismo dominio fijo:
 *    https://jurist-resubmit-ended.ngrok-free.dev
 */

const { spawn, execSync } = require('child_process');

const NGROK_DOMAIN = 'jurist-resubmit-ended.ngrok-free.dev';
const HEALTH_URL = `https://${NGROK_DOMAIN}/login.html`;
const LOCAL_PORT = 8085;
const PING_INTERVAL_MS = 10 * 60 * 1000; // cada 10 minutos

let ngrokProcess = null;

function log(msg) {
    const time = new Date().toLocaleTimeString('es-CO', { hour12: false });
    console.log(`[${time}] [Tunnel Keeper] ${msg}`);
}

async function checkTunnel() {
    try {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 10000);
        const res = await fetch(HEALTH_URL, {
            method: 'HEAD',
            signal: controller.signal,
            headers: { 'User-Agent': 'FruverCopilot-TunnelKeeper/1.0' }
        });
        clearTimeout(timeout);
        return res.status >= 200 && res.status < 400;
    } catch (err) {
        return false;
    }
}

function ensureNgrokRunning() {
    try {
        // Verificar si ngrok ya está corriendo
        const stdout = execSync('tasklist /FI "IMAGENAME eq ngrok.exe"', { encoding: 'utf8' });
        if (stdout.includes('ngrok.exe')) {
            return;
        }
    } catch (e) {}

    log(`Iniciando proceso ngrok con dominio fijo '${NGROK_DOMAIN}'...`);
    ngrokProcess = spawn('ngrok.exe', ['http', `--url=${NGROK_DOMAIN}`, LOCAL_PORT.toString()], {
        detached: true,
        stdio: 'ignore',
        shell: true
    });
    ngrokProcess.unref();
}

async function loop() {
    log(`Verificando salud del túnel en ${HEALTH_URL}...`);
    const isHealthy = await checkTunnel();

    if (isHealthy) {
        log(`✓ Túnel activo y respondiendo correctamente. (Próxima revisión en 10 min)`);
    } else {
        log(`⚠ El túnel no respondió o está caído. Reiniciando servicio ngrok...`);
        try {
            execSync('taskkill /F /IM ngrok.exe 2>nul');
        } catch (e) {}
        
        ensureNgrokRunning();

        // Esperar 5 segundos y verificar de nuevo
        await new Promise(r => setTimeout(r, 5000));
        const retryOk = await checkTunnel();
        if (retryOk) {
            log(`✓ ¡Túnel restablecido con éxito en https://${NGROK_DOMAIN}!`);
        } else {
            log(`✗ Aún no responde. Se reintentará en el próximo ciclo.`);
        }
    }
}

// Iniciar ciclo
ensureNgrokRunning();
loop();
setInterval(loop, PING_INTERVAL_MS);
