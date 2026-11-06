const express = require('express');
const cors = require('cors');
const qrcode = require('qrcode');
const axios = require('axios');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const pino = require('pino');
const { Pool } = require('pg');
const { NodeCache } = require('@cacheable/node-cache');
const {
  default: makeWASocket,
  DisconnectReason,
  fetchLatestBaileysVersion,
  downloadMediaMessage,
  proto
} = require('@whiskeysockets/baileys');

const { usePostgresAuthState } = require('./postgres-auth');
const { createInboxOutbox } = require('./inbox-outbox');

const app = express();
app.use(cors());
app.use(express.json({ limit: '50mb' }));

const PORT = parseInt(process.env.PORT || '3000', 10);
const BACKEND_WEBHOOK_URL = process.env.BACKEND_WEBHOOK_URL || 'http://backend:8080/webhook/whatsapp';
const APP_SECRET = process.env.WHATSAPP_APP_SECRET || 'ignore';

const logger = pino({ level: 'info' });

// Pool de conexiones PostgreSQL
const pool = new Pool({
  host: process.env.DB_HOST || 'postgres',
  port: parseInt(process.env.DB_PORT || '5432', 10),
  user: process.env.DB_USER || 'fruver',
  password: process.env.DB_PASSWORD || '-FiANc-B_yzU0JJijtQfj0Tr',
  database: process.env.DB_NAME || 'fruver',
  max: 10,
  idleTimeoutMillis: 30000
});

// Advisory lock key única para la instancia maestra de Baileys
const BAILEYS_ADVISORY_LOCK_ID = 987654321;
let hasAdvisoryLock = false;
let advisoryLockClient = null;

let sock = null;
let connectionStatus = 'DISCONNECTED'; // DISCONNECTED, QR_READY, CONNECTED
let qrCodeBase64 = null;
let connectedPhone = null;
let isStarting = false;
let reconnectTimer = null;
let lastActivityTimestamp = Date.now();
let isShuttingDown = false;

// Media cache en memoria (ID -> { buffer, mimeType, timestamp })
const mediaStore = new Map();
setInterval(() => {
  const now = Date.now();
  for (const [id, item] of mediaStore.entries()) {
    if (now - item.timestamp > 30 * 60 * 1000) {
      mediaStore.delete(id);
    }
  }
}, 15 * 60 * 1000);

// Mapeo persistente de LID -> Teléfono
const lidPhoneMap = new Map();
// Semilla conocida de Victor
lidPhoneMap.set('96701255786676', '573156793777');

// Seguimiento de IDs de mensajes enviados desde la interfaz web (evitar duplicados al recibir eco 'fromMe')
const sentViaApiMsgIds = new Set();
function trackSentViaApi(id) {
  if (!id) return;
  sentViaApiMsgIds.add(id);
  if (sentViaApiMsgIds.size > 2000) {
    const it = sentViaApiMsgIds.values();
    for (let i = 0; i < 500; i++) {
      sentViaApiMsgIds.delete(it.next().value);
    }
  }
}

// Caché de mensajes en memoria para reintentos criptográficos rápidos (Anti "Esperando mensaje...")
const messageCache = new Map();
const MAX_CACHED_MESSAGES = 3000;

function cacheMessage(id, messageProto, key = null) {
  if (!id || !messageProto) return;
  messageCache.set(id, { message: messageProto, key: key || null });
  if (messageCache.size > MAX_CACHED_MESSAGES * 1.5) {
    const keysToDelete = Array.from(messageCache.keys()).slice(0, messageCache.size - MAX_CACHED_MESSAGES);
    for (const k of keysToDelete) messageCache.delete(k);
  }
}

function recordActivity() {
  lastActivityTimestamp = Date.now();
}

function cleanupSocket() {
  if (!sock) return;
  try {
    sock.ev.removeAllListeners();
    sock.end(undefined);
  } catch (e) {
    logger.debug({ err: e.message }, 'Aviso al limpiar socket previo');
  }
  sock = null;
}

// Enviar payload a Spring Boot con firma HMAC
async function sendPayloadToBackendHttp(payload) {
  const bodyStr = JSON.stringify(payload);
  const headers = {
    'Content-Type': 'application/json',
    'X-Bridge-Origin': 'fruver-baileys'
  };

  if (APP_SECRET && APP_SECRET !== 'ignore' && APP_SECRET !== 'bypass') {
    const hmac = crypto.createHmac('sha256', APP_SECRET);
    hmac.update(bodyStr);
    headers['X-Hub-Signature-256'] = `sha256=${hmac.digest('hex')}`;
  }

  const res = await axios.post(BACKEND_WEBHOOK_URL, payload, { headers, timeout: 10000 });
  return res;
}

// Inicializar Inbox / Outbox persistente
const inboxOutbox = createInboxOutbox(pool, sendPayloadToBackendHttp, logger);

function buildForwardPayload(from, pushName, messageObj) {
  return {
    object: 'whatsapp_business_account',
    entry: [
      {
        id: 'baileys_entry',
        changes: [
          {
            value: {
              messaging_product: 'whatsapp',
              metadata: {
                display_phone_number: connectedPhone || '',
                phone_number_id: 'baileys'
              },
              contacts: [
                {
                  profile: { name: pushName },
                  wa_id: from
                }
              ],
              messages: [messageObj]
            },
            field: 'messages'
          }
        ]
      }
    ]
  };
}

function unwrapMessage(msg) {
  let m = msg;
  while (m) {
    if (m.ephemeralMessage?.message) {
      m = m.ephemeralMessage.message;
    } else if (m.viewOnceMessage?.message) {
      m = m.viewOnceMessage.message;
    } else if (m.viewOnceMessageV2?.message) {
      m = m.viewOnceMessageV2.message;
    } else if (m.documentWithCaptionMessage?.message) {
      m = m.documentWithCaptionMessage.message;
    } else {
      break;
    }
  }
  return m;
}

async function formatMessageObject(msg, rawMessage, msgId, from, isFromMe) {
  const messageContent = unwrapMessage(rawMessage);
  let formattedMessage = null;

  // 1. TEXTO
  const textBody = messageContent.conversation || messageContent.extendedTextMessage?.text;
  if (textBody && textBody.trim().length > 0) {
    formattedMessage = {
      id: msgId,
      from: from,
      from_me: isFromMe,
      type: 'text',
      text: { body: textBody.trim() }
    };
  }
  // 2. AUDIO
  else if (messageContent.audioMessage) {
    try {
      const buffer = await downloadMediaMessage(msg, 'buffer', {});
      const mediaId = `audio_${msgId}`;
      const mimeType = messageContent.audioMessage.mimetype || 'audio/ogg';
      mediaStore.set(mediaId, { buffer, mimeType, timestamp: Date.now() });

      formattedMessage = {
        id: msgId,
        from: from,
        from_me: isFromMe,
        type: 'audio',
        audio: {
          id: mediaId,
          mime_type: mimeType
        }
      };
    } catch (audioErr) {
      logger.error({ err: audioErr.message, msgId }, 'Error descargando audio de WhatsApp');
    }
  }
  // 3. IMAGEN
  else if (messageContent.imageMessage) {
    try {
      const buffer = await downloadMediaMessage(msg, 'buffer', {});
      const mediaId = `image_${msgId}`;
      const mimeType = messageContent.imageMessage.mimetype || 'image/jpeg';
      const caption = messageContent.imageMessage.caption || '';
      mediaStore.set(mediaId, { buffer, mimeType, timestamp: Date.now() });

      formattedMessage = {
        id: msgId,
        from: from,
        from_me: isFromMe,
        type: 'image',
        image: {
          id: mediaId,
          mime_type: mimeType,
          caption: caption
        }
      };
    } catch (imgErr) {
      logger.error({ err: imgErr.message, msgId }, 'Error descargando imagen de WhatsApp');
    }
  }

  return formattedMessage;
}

async function acquireAdvisoryLock() {
  if (hasAdvisoryLock) return true;
  try {
    advisoryLockClient = await pool.connect();
    const res = await advisoryLockClient.query('SELECT pg_try_advisory_lock($1) as locked', [BAILEYS_ADVISORY_LOCK_ID]);
    if (res.rows[0].locked) {
      hasAdvisoryLock = true;
      logger.info('✓ Advisory lock PostgreSQL adquirido: Instancia maestra de Baileys activa');
      return true;
    } else {
      logger.error('🔴 ADVERTENCIA: Otra instancia de WhatsApp Bridge ya se encuentra activa. Bloqueando inicio para proteger la sesión criptográfica.');
      advisoryLockClient.release();
      advisoryLockClient = null;
      return false;
    }
  } catch (err) {
    logger.error({ err: err.message }, 'Error al intentar adquirir Advisory Lock de PostgreSQL');
    return false;
  }
}

async function startWhatsApp() {
  if (isShuttingDown) return;
  if (isStarting) {
    logger.warn('startWhatsApp ya en ejecución, ignorando llamada redundante');
    return;
  }
  isStarting = true;
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }

  try {
    // 1. Asegurar exclusión mutua
    const locked = await acquireAdvisoryLock();
    if (!locked) {
      logger.warn('No se pudo adquirir el bloqueo de instancia única. Reintentando en 10 segundos...');
      reconnectTimer = setTimeout(startWhatsApp, 10000);
      return;
    }

    cleanupSocket();

    // 2. Inicializar autenticación con persistencia en PostgreSQL
    logger.info('Cargando credenciales Signal desde PostgreSQL (modo producción ACID)...');
    const { state, saveCreds } = await usePostgresAuthState(pool);
    const { version } = await fetchLatestBaileysVersion();

    logger.info({ version, jid: state.creds.me?.id }, 'Iniciando socket Baileys WhatsApp...');

    const msgRetryCounterCache = new NodeCache({
      stdTTL: 3600,
      useClones: false
    });

    sock = makeWASocket({
      version,
      auth: state,
      logger: pino({ level: 'warn' }),
      printQRInTerminal: false,
      generateHighQualityLinkPreview: false,
      syncFullHistory: false,
      fireInitQueries: false,
      shouldSyncHistoryMessage: () => false,
      browser: ['FruverCopilot', 'Chrome', '124.0.0.0'],
      keepAliveIntervalMs: 25000,
      connectTimeoutMs: 30000,
      defaultQueryTimeoutMs: 15000,
      retryRequestDelayMs: 250,
      maxMsgRetryCount: 5,
      msgRetryCounterCache,
      markOnlineOnConnect: true,
      getMessage: async (key) => {
        const cached = messageCache.get(key.id);
        if (cached) {
          const raw = cached.message || cached;
          logger.info({ id: key.id, remoteJid: key.remoteJid }, 'Respondiendo reintento de llaves con mensaje en caché');
          return proto.Message.fromObject(raw);
        }
        return undefined;
      }
    });

    sock.ev.on('creds.update', async () => {
      recordActivity();
      try {
        await saveCreds();
      } catch (err) {
        logger.error({ err: err.message }, 'Error guardando creds en PostgreSQL');
      }
    });

    sock.ev.on('contacts.upsert', (contacts) => {
      recordActivity();
      for (const c of (contacts || [])) {
        if (c.lid && c.id && c.id.endsWith('@s.whatsapp.net')) {
          const lidClean = c.lid.replace(/@.*/, '').replace(/:.*/, '');
          const phoneClean = c.id.replace(/@.*/, '').replace(/:.*/, '');
          if (lidClean && phoneClean) {
            lidPhoneMap.set(lidClean, phoneClean);
          }
        }
      }
    });

    sock.ev.on('contacts.update', (updates) => {
      recordActivity();
      for (const c of (updates || [])) {
        if (c.lid && c.id && c.id.endsWith('@s.whatsapp.net')) {
          const lidClean = c.lid.replace(/@.*/, '').replace(/:.*/, '');
          const phoneClean = c.id.replace(/@.*/, '').replace(/:.*/, '');
          if (lidClean && phoneClean) {
            lidPhoneMap.set(lidClean, phoneClean);
          }
        }
      }
    });

    sock.ev.on('connection.update', async (update) => {
      recordActivity();
      const { connection, lastDisconnect, qr } = update;

      if (qr) {
        connectionStatus = 'QR_READY';
        try {
          qrCodeBase64 = await qrcode.toDataURL(qr, { margin: 2, scale: 7 });
          logger.info('Nuevo código QR generado para vinculación');
        } catch (err) {
          logger.error('Error generando Base64 del código QR:', err);
        }
      }

      if (connection === 'close') {
        const statusCode = lastDisconnect?.error?.output?.statusCode;
        const shouldReconnect = statusCode !== DisconnectReason.loggedOut;
        connectionStatus = 'DISCONNECTED';
        qrCodeBase64 = null;
        connectedPhone = null;

        logger.warn({ statusCode, shouldReconnect }, 'Conexión cerrada de WhatsApp.');

        if (statusCode === DisconnectReason.loggedOut) {
          logger.warn('Sesión cerrada por el usuario en WhatsApp móvil. Limpiando credenciales en PostgreSQL...');
          await pool.query('DELETE FROM whatsapp_auth_credentials WHERE id = \'DEFAULT\'');
          await pool.query('DELETE FROM whatsapp_signal_keys');
          startWhatsApp();
        } else if (shouldReconnect) {
          logger.info('Reconectando en 3 segundos...');
          if (reconnectTimer) clearTimeout(reconnectTimer);
          reconnectTimer = setTimeout(startWhatsApp, 3000);
        }
      } else if (connection === 'open') {
        connectionStatus = 'CONNECTED';
        qrCodeBase64 = null;
        const jid = sock.user?.id || '';
        connectedPhone = jid.replace(/:.*@/, '@').replace(/@.*/, '');
        logger.info({ connectedPhone }, '¡WhatsApp Conectado exitosamente con persistencia PostgreSQL!');
      }
    });

    // ── 3. LISTENER messages.upsert (Mensajes entrantes y mensajes acumulados) ──
    sock.ev.on('messages.upsert', async (upsert) => {
      recordActivity();
      if (upsert.type !== 'notify' && upsert.type !== 'append') return;

      for (const msg of (upsert.messages || [])) {
        try {
          const msgId = msg.key?.id;
          const remoteJid = msg.key?.remoteJid || '';
          if (!msgId || remoteJid.endsWith('@g.us') || remoteJid.endsWith('@broadcast')) continue;

          const isFromMe = !!msg.key.fromMe;
          if (isFromMe && sentViaApiMsgIds.has(msgId)) {
            continue; // Originado en FruverCopilot Web
          }

          // Caché local para resolver posibles reintentos de llaves
          if (msg.message) {
            cacheMessage(msgId, msg.message, msg.key);
          }

          // Detección de mensajes pendientes de descifrado (CIPHERTEXT / "Esperando mensaje...")
          const isCiphertext = !msg.message || msg.messageStubType === proto.WebMessageInfo.StubType.CIPHERTEXT;

          // Resolución de teléfono / LID
          let rawId = remoteJid.replace(/@.*/, '').replace(/:.*/, '');
          let resolvedPhone = null;
          const altJid = msg.key.remoteJidAlt || msg.key.participantAlt || msg.key.senderPn || msg.key.participant;
          if (altJid && altJid.includes('@s.whatsapp.net')) {
            resolvedPhone = altJid.replace(/@.*/, '').replace(/:.*/, '');
          }
          if (!resolvedPhone && remoteJid.endsWith('@lid') && lidPhoneMap.has(rawId)) {
            resolvedPhone = lidPhoneMap.get(rawId);
          }
          if (!resolvedPhone && !remoteJid.endsWith('@lid')) {
            resolvedPhone = rawId;
          }
          const from = resolvedPhone || rawId;
          const pushName = msg.pushName || '';

          if (isCiphertext) {
            // Guardar en Inbox como WAITING_DECRYPT (¡NO descartar silenciosamente!)
            await inboxOutbox.recordIncomingMessage({
              messageId: msgId,
              remoteJid,
              fromMe: isFromMe,
              participant: msg.key.participant || null,
              pushName,
              senderPhone: from,
              messageType: 'CIPHERTEXT',
              rawMessage: null,
              isCiphertext: true,
              formattedPayload: null
            });
            continue;
          }

          // Si el mensaje está descifrado, formatearlo y registrarlo para despacho en Outbox
          const formattedMessage = await formatMessageObject(msg, msg.message, msgId, from, isFromMe);
          if (formattedMessage) {
            const payload = buildForwardPayload(from, pushName, formattedMessage);
            await inboxOutbox.recordIncomingMessage({
              messageId: msgId,
              remoteJid,
              fromMe: isFromMe,
              participant: msg.key.participant || null,
              pushName,
              senderPhone: from,
              messageType: formattedMessage.type,
              rawMessage: msg.message,
              isCiphertext: false,
              formattedPayload: payload
            });
          }
        } catch (msgErr) {
          logger.error({ err: msgErr.message, id: msg?.key?.id }, 'Error procesando mensaje en messages.upsert');
        }
      }
    });

    // ── 4. LISTENER messages.update (Recuperación de mensajes tras reintento de llaves) ──
    sock.ev.on('messages.update', async (updates) => {
      recordActivity();
      for (const update of (updates || [])) {
        try {
          const msgId = update.key?.id;
          const rawMessage = update.update?.message;
          if (!msgId || !rawMessage) continue;

          // Cachear mensaje actualizado
          cacheMessage(msgId, rawMessage, update.key);

          const remoteJid = update.key.remoteJid || '';
          if (remoteJid.endsWith('@g.us') || remoteJid.endsWith('@broadcast')) continue;

          let rawId = remoteJid.replace(/@.*/, '').replace(/:.*/, '');
          const from = lidPhoneMap.get(rawId) || rawId;
          const isFromMe = !!update.key.fromMe;

          logger.info({ msgId, from }, '✓ Mensaje recuperado/actualizado mediante messages.update');

          const formattedMessage = await formatMessageObject({ key: update.key, message: rawMessage }, rawMessage, msgId, from, isFromMe);
          if (formattedMessage) {
            const payload = buildForwardPayload(from, '', formattedMessage);
            await inboxOutbox.recordDecryptedUpdate({
              messageId: msgId,
              rawMessage,
              formattedPayload: payload
            });
          }
        } catch (updErr) {
          logger.error({ err: updErr.message, id: update?.key?.id }, 'Error procesando actualización en messages.update');
        }
      }
    });

  } catch (err) {
    logger.error({ err: err.message }, 'Error en startWhatsApp');
    connectionStatus = 'DISCONNECTED';
  } finally {
    isStarting = false;
  }
}

// ----------------- ENDPOINTS REST -----------------

// 1. Estado y QR
app.get('/status', async (req, res) => {
  let stats = {};
  try {
    stats = await inboxOutbox.getStats();
  } catch (e) {}
  res.json({
    status: connectionStatus,
    phone: connectedPhone,
    qr: qrCodeBase64,
    storage: 'POSTGRESQL',
    stats
  });
});

// 2. Descarga de Multimedia interna para Spring Boot
app.get('/media/:id', (req, res) => {
  const media = mediaStore.get(req.params.id);
  if (!media) {
    return res.status(404).json({ error: 'Media no encontrada o expirada' });
  }
  res.setHeader('Content-Type', media.mimeType);
  res.setHeader('Content-Length', media.buffer.length);
  res.send(media.buffer);
});

// 3. Envío de Mensajes y Reacciones
app.post('/messages', async (req, res) => {
  recordActivity();
  if (connectionStatus !== 'CONNECTED' || !sock) {
    return res.status(503).json({ error: 'WhatsApp no está conectado' });
  }

  const { to, text, reaction, messageId, imageUrl, caption, quotedMessageId } = req.body;
  if (!to) {
    return res.status(400).json({ error: 'El campo "to" es obligatorio' });
  }

  const cleanPhone = to.replace(/[^\d]/g, '');
  let jid;
  if (cleanPhone.length > 12 && !cleanPhone.startsWith('57') && lidPhoneMap.has(cleanPhone)) {
    jid = `${lidPhoneMap.get(cleanPhone)}@s.whatsapp.net`;
  } else {
    jid = `${cleanPhone}@s.whatsapp.net`;
  }

  try {
    let sent = null;
    // 3a. Reacción con Emoji a un mensaje específico
    if (reaction && messageId) {
      const cached = messageCache.get(messageId);
      const targetKey = cached?.key ? { ...cached.key } : {
        remoteJid: jid,
        fromMe: false,
        id: messageId
      };
      const targetJid = targetKey.remoteJid || jid;

      logger.info({ messageId, targetJid, reaction, hasCachedKey: !!cached?.key }, 'Enviando reacción');
      sent = await sock.sendMessage(targetJid, {
        react: {
          text: reaction,
          key: targetKey
        }
      });
      if (sent?.key?.id) {
        trackSentViaApi(sent.key.id);
        if (sent?.message) {
          cacheMessage(sent.key.id, sent.message, sent.key);
        }
      }
      return res.json({ success: true, type: 'reaction', messageId: sent?.key?.id });
    }

    // 3b. Imagen
    if (imageUrl) {
      const imgOptions = {};
      if (quotedMessageId) {
        const cached = messageCache.get(quotedMessageId);
        const qMsg = cached?.message || cached;
        const qKey = cached?.key || { remoteJid: jid, id: quotedMessageId };
        imgOptions.quoted = { key: qKey, message: qMsg };
      }
      sent = await sock.sendMessage(jid, {
        image: { url: imageUrl },
        caption: caption || ''
      }, imgOptions);
      if (sent?.key?.id) {
        trackSentViaApi(sent.key.id);
        if (sent?.message) {
          cacheMessage(sent.key.id, sent.message, sent.key);
        }
      }
      return res.json({ success: true, type: 'image', messageId: sent?.key?.id });
    }

    // 3c. Texto
    if (text) {
      const textOptions = {};
      if (quotedMessageId) {
        const cached = messageCache.get(quotedMessageId);
        const qMsg = cached?.message || cached;
        const qKey = cached?.key || { remoteJid: jid, id: quotedMessageId };
        textOptions.quoted = { key: qKey, message: qMsg };
      }
      sent = await sock.sendMessage(jid, { text: text }, textOptions);
      if (sent?.key?.id) {
        trackSentViaApi(sent.key.id);
        if (sent?.message) {
          cacheMessage(sent.key.id, sent.message, sent.key);
        }
      }
      return res.json({ success: true, type: 'text', messageId: sent?.key?.id });
    }

    return res.status(400).json({ error: 'Debe especificar text, reaction o imageUrl' });
  } catch (err) {
    logger.error({ err: err.message, to }, 'Error enviando mensaje por WhatsApp Baileys');
    return res.status(500).json({ error: err.message });
  }
});

// 4. Cerrar sesión / Desvincular
app.post('/logout', async (req, res) => {
  try {
    if (sock) {
      await sock.logout().catch(() => {});
    }
    await pool.query('DELETE FROM whatsapp_auth_credentials WHERE id = \'DEFAULT\'');
    await pool.query('DELETE FROM whatsapp_signal_keys');
    connectionStatus = 'DISCONNECTED';
    qrCodeBase64 = null;
    connectedPhone = null;
    startWhatsApp();
    res.json({ success: true, message: 'Sesión cerrada correctamente' });
  } catch (err) {
    logger.error('Error cerrando sesión:', err);
    res.status(500).json({ error: err.message });
  }
});

// 5. Healthcheck con estado real del socket y PostgreSQL
app.get('/health', (req, res) => {
  const wsReady = sock?.ws?.socket?.readyState ?? (sock?.ws?.isOpen ? 1 : 3);
  const isHealthy = connectionStatus === 'CONNECTED' && (sock?.ws?.isOpen || wsReady === 1);
  const idleSeconds = Math.round((Date.now() - lastActivityTimestamp) / 1000);
  res.json({
    ok: isHealthy || connectionStatus === 'QR_READY',
    status: connectionStatus,
    wsReady,
    idleSeconds,
    connectedPhone,
    storage: 'POSTGRESQL',
    hasAdvisoryLock
  });
});

// 6. Endpoint de observabilidad de métricas Inbox/Outbox
app.get('/metrics', async (req, res) => {
  try {
    const stats = await inboxOutbox.getStats();
    res.json({
      status: connectionStatus,
      connectedPhone,
      hasAdvisoryLock,
      ...stats
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 7. Endpoint para consultar mensajes en espera de descifrado
app.get('/inbox/pending', async (req, res) => {
  try {
    const rows = await pool.query(`
      SELECT message_id, remote_jid, sender_phone, message_type, status, retry_count, received_at
      FROM whatsapp_bridge_inbox
      WHERE status = 'WAITING_DECRYPT'
      ORDER BY id DESC LIMIT 50
    `);
    res.json(rows.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// 8. Reconexión forzada
app.post('/reconnect', async (req, res) => {
  logger.info('Solicitud manual de reconexión recibida vía HTTP');
  startWhatsApp();
  res.json({ success: true, message: 'Reconexión iniciada' });
});

// Watchdog para detectar sockets muertos o zombies
setInterval(() => {
  if (connectionStatus === 'CONNECTED' && sock) {
    const readyState = sock?.ws?.socket?.readyState ?? (sock?.ws?.isOpen ? 1 : 3);
    if (readyState > 1) {
      logger.warn({ readyState }, 'Watchdog: socket cerrado detectado. Reconectando...');
      connectionStatus = 'DISCONNECTED';
      startWhatsApp();
      return;
    }

    const idleMs = Date.now() - lastActivityTimestamp;
    if (idleMs > 2 * 60 * 1000) {
      try {
        const rawSocket = sock?.ws?.socket;
        if (rawSocket && typeof rawSocket.ping === 'function') {
          rawSocket.ping();
          recordActivity();
        }
      } catch (e) {
        logger.warn({ err: e.message }, 'Watchdog: fallo en ping de WebSocket. Reconectando...');
        connectionStatus = 'DISCONNECTED';
        startWhatsApp();
      }
    }
  }
}, 30000);

// Iniciar servidor HTTP y Outbox worker
app.listen(PORT, '0.0.0.0', () => {
  logger.info(`Microservicio whatsapp-bridge escuchando en puerto ${PORT}`);
  inboxOutbox.startWorker();
  startWhatsApp();
});

// ── APAGADO GRÁCIL (GRACEFUL SHUTDOWN) ──
async function gracefulShutdown(signal) {
  if (isShuttingDown) return;
  isShuttingDown = true;
  logger.info({ signal }, 'Recibida señal de apagado. Iniciando apagado grácil...');

  if (reconnectTimer) clearTimeout(reconnectTimer);
  inboxOutbox.stopWorker();
  cleanupSocket();

  try {
    if (advisoryLockClient) {
      await advisoryLockClient.query('SELECT pg_advisory_unlock($1)', [BAILEYS_ADVISORY_LOCK_ID]);
      advisoryLockClient.release();
      advisoryLockClient = null;
    }
    await pool.end();
  } catch (e) {
    logger.warn({ err: e.message }, 'Aviso liberando recursos PostgreSQL en apagado');
  }

  logger.info('WhatsApp Bridge finalizó ordenadamente.');
  process.exit(0);
}

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));

process.on('uncaughtException', (err) => {
  logger.error({ err: err.message, stack: err.stack }, 'Excepción no capturada en whatsapp-bridge (protegido contra cierre)');
});

process.on('unhandledRejection', (reason) => {
  logger.error({ reason: reason?.message || reason }, 'Promesa rechazada en whatsapp-bridge (protegido contra cierre)');
  if (reason && (String(reason).includes('Timed Out') || String(reason.message).includes('Timed Out'))) {
    logger.warn('Timeout detectado en consultas de Baileys. Forzando reinicio del socket...');
    setTimeout(startWhatsApp, 3000);
  }
});
