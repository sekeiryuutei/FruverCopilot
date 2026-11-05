const express = require('express');
const cors = require('cors');
const qrcode = require('qrcode');
const axios = require('axios');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const pino = require('pino');
const { NodeCache } = require('@cacheable/node-cache');
const {
  default: makeWASocket,
  useMultiFileAuthState,
  DisconnectReason,
  fetchLatestBaileysVersion,
  downloadMediaMessage,
  proto
} = require('@whiskeysockets/baileys');

const app = express();
app.use(cors());
app.use(express.json({ limit: '50mb' }));

const PORT = parseInt(process.env.PORT || '3000', 10);
const AUTH_DIR = process.env.AUTH_DIR || path.join(__dirname, '..', 'auth_info');
const BACKEND_WEBHOOK_URL = process.env.BACKEND_WEBHOOK_URL || 'http://backend:8080/webhook/whatsapp';
const APP_SECRET = process.env.WHATSAPP_APP_SECRET || 'ignore';

const logger = pino({ level: 'info' });

let sock = null;
let connectionStatus = 'DISCONNECTED'; // DISCONNECTED, QR_READY, CONNECTED
let qrCodeBase64 = null;
let connectedPhone = null;

// Media cache en memoria (ID -> { buffer, mimeType, timestamp })
const mediaStore = new Map();

// Limpiar media antigua cada 15 min (> 30 min)
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
const LID_MAP_FILE = path.join(AUTH_DIR, 'lid_map.json');

function loadLidMap() {
  if (fs.existsSync(LID_MAP_FILE)) {
    try {
      const data = JSON.parse(fs.readFileSync(LID_MAP_FILE, 'utf8'));
      for (const [k, v] of Object.entries(data)) {
        lidPhoneMap.set(k, v);
      }
      logger.info({ count: lidPhoneMap.size }, 'Mapeos LID -> Teléfono cargados');
    } catch (e) {
      logger.error('Error cargando lid_map.json:', e);
    }
  }
  // Semilla conocida de Victor
  if (!lidPhoneMap.has('96701255786676')) {
    lidPhoneMap.set('96701255786676', '573156793777');
    saveLidMap();
  }
}

function saveLidMap() {
  try {
    const obj = Object.fromEntries(lidPhoneMap);
    fs.writeFileSync(LID_MAP_FILE, JSON.stringify(obj, null, 2));
  } catch (e) {
    logger.error('Error guardando lid_map.json:', e);
  }
}

loadLidMap();

// Cache de mensajes persistente para reintentos criptográficos (Anti "Esperando mensaje...")
const messageCache = new Map();
const MSG_CACHE_FILE = path.join(AUTH_DIR, 'messages_cache.json');
const MAX_CACHED_MESSAGES = 3000;

function loadMessageCache() {
  if (fs.existsSync(MSG_CACHE_FILE)) {
    try {
      const data = JSON.parse(fs.readFileSync(MSG_CACHE_FILE, 'utf8'));
      for (const [id, msg] of Object.entries(data)) {
        messageCache.set(id, msg);
      }
      logger.info({ count: messageCache.size }, 'Caché de mensajes cargado para resolución de reintentos');
    } catch (e) {
      logger.error('Error cargando messages_cache.json:', e);
    }
  }
}

let saveMsgCacheTimer = null;
function saveMessageCacheDebounced() {
  if (saveMsgCacheTimer) return;
  saveMsgCacheTimer = setTimeout(() => {
    saveMsgCacheTimer = null;
    try {
      const entries = Array.from(messageCache.entries()).slice(-MAX_CACHED_MESSAGES);
      fs.writeFileSync(MSG_CACHE_FILE, JSON.stringify(Object.fromEntries(entries)));
    } catch (e) {
      logger.error('Error guardando messages_cache.json:', e);
    }
  }, 3000);
}

function cacheMessage(id, messageProto) {
  if (!id || !messageProto) return;
  messageCache.set(id, messageProto);
  if (messageCache.size > MAX_CACHED_MESSAGES * 1.5) {
    const keysToDelete = Array.from(messageCache.keys()).slice(0, messageCache.size - MAX_CACHED_MESSAGES);
    for (const k of keysToDelete) messageCache.delete(k);
  }
  saveMessageCacheDebounced();
}

loadMessageCache();

async function startWhatsApp() {
  if (!fs.existsSync(AUTH_DIR)) {
    fs.mkdirSync(AUTH_DIR, { recursive: true });
  }

  const { state, saveCreds } = await useMultiFileAuthState(AUTH_DIR);
  const { version } = await fetchLatestBaileysVersion();

  logger.info({ version }, 'Iniciando socket Baileys WhatsApp...');

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
    browser: ['FruverCopilot', 'Chrome', '124.0.0.0'],
    keepAliveIntervalMs: 25000,
    connectTimeoutMs: 60000,
    defaultQueryTimeoutMs: 60000,
    retryRequestDelayMs: 250,
    maxMsgRetryCount: 5,
    msgRetryCounterCache,
    markOnlineOnConnect: true,
    getMessage: async (key) => {
      const cached = messageCache.get(key.id);
      if (cached) {
        logger.info({ id: key.id, remoteJid: key.remoteJid }, 'Respondiendo reintento de llaves con mensaje en caché');
        return proto.Message.fromObject(cached);
      }
      if (key.fromMe) {
        logger.warn({ id: key.id, remoteJid: key.remoteJid }, 'Reintento de mensaje saliente no en caché, respondiendo con confirmación');
        return proto.Message.fromObject({ conversation: '🙏🙏👍' });
      }
      return undefined;
    }
  });

  sock.ev.on('creds.update', saveCreds);

  sock.ev.on('contacts.upsert', (contacts) => {
    let changed = false;
    for (const c of (contacts || [])) {
      if (c.lid && c.id && c.id.endsWith('@s.whatsapp.net')) {
        const lidClean = c.lid.replace(/@.*/, '').replace(/:.*/, '');
        const phoneClean = c.id.replace(/@.*/, '').replace(/:.*/, '');
        if (lidClean && phoneClean && lidPhoneMap.get(lidClean) !== phoneClean) {
          lidPhoneMap.set(lidClean, phoneClean);
          changed = true;
        }
      }
    }
    if (changed) saveLidMap();
  });

  sock.ev.on('contacts.update', (updates) => {
    let changed = false;
    for (const c of (updates || [])) {
      if (c.lid && c.id && c.id.endsWith('@s.whatsapp.net')) {
        const lidClean = c.lid.replace(/@.*/, '').replace(/:.*/, '');
        const phoneClean = c.id.replace(/@.*/, '').replace(/:.*/, '');
        if (lidClean && phoneClean && lidPhoneMap.get(lidClean) !== phoneClean) {
          lidPhoneMap.set(lidClean, phoneClean);
          changed = true;
        }
      }
    }
    if (changed) saveLidMap();
  });

  sock.ev.on('connection.update', async (update) => {
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
        logger.warn('Sesión cerrada por el usuario. Limpiando credenciales...');
        clearAuthDir();
        startWhatsApp();
      } else if (shouldReconnect) {
        logger.info('Reconectando en 3 segundos...');
        setTimeout(startWhatsApp, 3000);
      }
    } else if (connection === 'open') {
      connectionStatus = 'CONNECTED';
      qrCodeBase64 = null;
      const jid = sock.user?.id || '';
      connectedPhone = jid.replace(/:.*@/, '@').replace(/@.*/, '');
      logger.info({ connectedPhone }, '¡WhatsApp Conectado exitosamente!');
    }
  });

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

// Escucha de mensajes entrantes
  sock.ev.on('messages.upsert', async (upsert) => {
    if (upsert.type !== 'notify') return;

    for (const msg of upsert.messages) {
      try {
        if (!msg.message) continue;
        if (msg.key?.id) {
          cacheMessage(msg.key.id, msg.message);
        }
        if (msg.key.fromMe) continue;
        const remoteJid = msg.key.remoteJid || '';
        if (remoteJid.endsWith('@g.us') || remoteJid.endsWith('@broadcast')) continue;

        let rawId = remoteJid.replace(/@.*/, '').replace(/:.*/, '');
        let resolvedPhone = null;

        // 1. Revisar si Baileys incluyó un JID alternativo en el mensaje
        const altJid = msg.key.remoteJidAlt || msg.key.participantAlt || msg.key.senderPn || msg.key.participant;
        if (altJid && altJid.includes('@s.whatsapp.net')) {
          resolvedPhone = altJid.replace(/@.*/, '').replace(/:.*/, '');
        }

        // 2. Si es un LID, consultar en memoria o repositorio Baileys
        if (!resolvedPhone && remoteJid.endsWith('@lid')) {
          if (lidPhoneMap.has(rawId)) {
            resolvedPhone = lidPhoneMap.get(rawId);
          } else if (sock?.signalRepository?.lidMapping?.getPNForLID) {
            try {
              const pn = await sock.signalRepository.lidMapping.getPNForLID(remoteJid);
              if (pn) {
                resolvedPhone = pn.replace(/@.*/, '').replace(/:.*/, '');
                lidPhoneMap.set(rawId, resolvedPhone);
                saveLidMap();
              }
            } catch (e) {}
          }
        }

        // 3. Si no es LID (ya termina en @s.whatsapp.net), es el teléfono directo
        if (!resolvedPhone && !remoteJid.endsWith('@lid')) {
          resolvedPhone = rawId;
        }

        const from = resolvedPhone || rawId;
        const pushName = msg.pushName || '';
        const msgId = msg.key.id;

        const messageContent = unwrapMessage(msg.message);
        let formattedMessage = null;

        // 1. TEXTO (incluyendo mensajes reenviados y extendidos)
        const textBody = messageContent.conversation || messageContent.extendedTextMessage?.text;
        if (textBody && textBody.trim().length > 0) {
          formattedMessage = {
            id: msgId,
            from: from,
            type: 'text',
            text: { body: textBody.trim() }
          };
        }

        // 2. AUDIO / NOTA DE VOZ
        else if (messageContent.audioMessage) {
          try {
            const buffer = await downloadMediaMessage(msg, 'buffer', {});
            const mediaId = `audio_${msgId}`;
            const mimeType = messageContent.audioMessage.mimetype || 'audio/ogg';
            mediaStore.set(mediaId, { buffer, mimeType, timestamp: Date.now() });

            formattedMessage = {
              id: msgId,
              from: from,
              type: 'audio',
              audio: {
                id: mediaId,
                mime_type: mimeType
              }
            };
          } catch (audioErr) {
            logger.error({ err: audioErr }, 'Error descargando audio');
          }
        }

        // 3. IMAGEN / FOTO
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
              type: 'image',
              image: {
                id: mediaId,
                mime_type: mimeType,
                caption: caption
              }
            };
          } catch (imgErr) {
            logger.error({ err: imgErr }, 'Error descargando imagen');
          }
        }

        if (formattedMessage) {
          await forwardToBackend(from, pushName, formattedMessage);
        }
      } catch (msgErr) {
        logger.error({ err: msgErr }, 'Error procesando mensaje entrante');
      }
    }
  });
}

async function forwardToBackend(from, pushName, messageObj) {
  const payload = {
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

  try {
    const res = await axios.post(BACKEND_WEBHOOK_URL, payload, { headers, timeout: 10000 });
    logger.info({ status: res.status, msgId: messageObj.id, from }, 'Mensaje reenviado a Spring Boot');
  } catch (err) {
    logger.error({ err: err.message, status: err.response?.status }, 'Error reenviando webhook al backend Spring Boot');
  }
}

// ----------------- ENDPOINTS REST -----------------

// 1. Estado y QR
app.get('/status', (req, res) => {
  res.json({
    status: connectionStatus,
    phone: connectedPhone,
    qr: qrCodeBase64
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
      sent = await sock.sendMessage(jid, {
        react: {
          text: reaction,
          key: {
            remoteJid: jid,
            fromMe: false,
            id: messageId
          }
        }
      });
      if (sent?.key?.id && sent?.message) {
        cacheMessage(sent.key.id, sent.message);
      }
      return res.json({ success: true, type: 'reaction', messageId: sent?.key?.id });
    }

    // 3b. Imagen
    if (imageUrl) {
      const imgOptions = {};
      if (quotedMessageId) {
        const cached = messageCache.get(quotedMessageId);
        if (cached) {
          imgOptions.quoted = { key: { remoteJid: jid, id: quotedMessageId }, message: cached };
        } else {
          imgOptions.quoted = { key: { remoteJid: jid, id: quotedMessageId } };
        }
      }
      sent = await sock.sendMessage(jid, {
        image: { url: imageUrl },
        caption: caption || ''
      }, imgOptions);
      if (sent?.key?.id && sent?.message) {
        cacheMessage(sent.key.id, sent.message);
      }
      return res.json({ success: true, type: 'image', messageId: sent?.key?.id });
    }

    // 3c. Texto
    if (text) {
      const textOptions = {};
      if (quotedMessageId) {
        const cached = messageCache.get(quotedMessageId);
        if (cached) {
          textOptions.quoted = { key: { remoteJid: jid, id: quotedMessageId }, message: cached };
        } else {
          textOptions.quoted = { key: { remoteJid: jid, id: quotedMessageId } };
        }
      }
      sent = await sock.sendMessage(jid, { text: text }, textOptions);
      if (sent?.key?.id && sent?.message) {
        cacheMessage(sent.key.id, sent.message);
      }
      return res.json({ success: true, type: 'text', messageId: sent?.key?.id });
    }

    return res.status(400).json({ error: 'Debe especificar text, reaction o imageUrl' });
  } catch (err) {
    logger.error({ err: err.message, to }, 'Error enviando mensaje por WhatsApp Baileys');
    return res.status(500).json({ error: err.message });
  }
});

function clearAuthDir() {
  try {
    if (fs.existsSync(AUTH_DIR)) {
      for (const file of fs.readdirSync(AUTH_DIR)) {
        try {
          fs.rmSync(path.join(AUTH_DIR, file), { recursive: true, force: true });
        } catch (e) {
          logger.warn(`No se pudo eliminar archivo ${file}: ${e.message}`);
        }
      }
    }
  } catch (e) {
    logger.warn('Error limpiando auth_dir:', e.message);
  }
}

// 4. Cerrar sesión / Desvincular
app.post('/logout', async (req, res) => {
  try {
    if (sock) {
      await sock.logout().catch(() => {});
    }
    clearAuthDir();
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

// 5. Healthcheck con estado real del socket
app.get('/health', (req, res) => {
  const wsReady = sock?.ws?.readyState;
  const isHealthy = connectionStatus === 'CONNECTED' && wsReady === 1;
  res.json({ ok: isHealthy || connectionStatus === 'QR_READY', status: connectionStatus, wsReady });
});

// Watchdog para detectar sockets muertos/zombies
setInterval(() => {
  if (connectionStatus === 'CONNECTED' && sock) {
    const readyState = sock.ws?.readyState;
    if (readyState !== undefined && readyState > 1) {
      logger.warn({ readyState }, 'Watchdog: socket cerrado/zombie detectado. Forzando reinicio...');
      connectionStatus = 'DISCONNECTED';
      try { sock.ws?.close(); } catch(e) {}
      startWhatsApp();
    }
  }
}, 30000);

app.listen(PORT, '0.0.0.0', () => {
  logger.info(`Microservicio whatsapp-bridge escuchando en puerto ${PORT}`);
  startWhatsApp();
});

process.on('uncaughtException', (err) => {
  logger.error({ err: err.message }, 'Excepción no capturada en whatsapp-bridge (protegido contra cierre)');
});
process.on('unhandledRejection', (reason) => {
  logger.error({ reason: reason?.message || reason }, 'Promesa rechazada en whatsapp-bridge (protegido contra cierre)');
  if (reason && (String(reason).includes('Timed Out') || String(reason.message).includes('Timed Out'))) {
    logger.warn('Timeout detectado en consultas de Baileys. Forzando reinicio del socket...');
    setTimeout(startWhatsApp, 3000);
  }
});
