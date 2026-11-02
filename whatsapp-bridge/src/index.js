const express = require('express');
const cors = require('cors');
const qrcode = require('qrcode');
const axios = require('axios');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const pino = require('pino');
const {
  default: makeWASocket,
  useMultiFileAuthState,
  DisconnectReason,
  fetchLatestBaileysVersion,
  downloadMediaMessage
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

async function startWhatsApp() {
  if (!fs.existsSync(AUTH_DIR)) {
    fs.mkdirSync(AUTH_DIR, { recursive: true });
  }

  const { state, saveCreds } = await useMultiFileAuthState(AUTH_DIR);
  const { version } = await fetchLatestBaileysVersion();

  logger.info({ version }, 'Iniciando socket Baileys WhatsApp...');

  sock = makeWASocket({
    version,
    auth: state,
    logger: pino({ level: 'silent' }),
    printQRInTerminal: false,
    generateHighQualityLinkPreview: false,
    syncFullHistory: false
  });

  sock.ev.on('creds.update', saveCreds);

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
        fs.rmSync(AUTH_DIR, { recursive: true, force: true });
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

  // Escucha de mensajes entrantes
  sock.ev.on('messages.upsert', async (upsert) => {
    if (upsert.type !== 'notify') return;

    for (const msg of upsert.messages) {
      try {
        if (!msg.message || msg.key.fromMe) continue;
        const remoteJid = msg.key.remoteJid || '';
        if (remoteJid.endsWith('@g.us') || remoteJid.endsWith('@broadcast')) continue;

        const from = remoteJid.replace(/@.*/, '');
        const pushName = msg.pushName || '';
        const msgId = msg.key.id;

        const messageContent = msg.message;
        let formattedMessage = null;

        // 1. TEXTO
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

  const { to, text, reaction, messageId, imageUrl, caption } = req.body;
  if (!to) {
    return res.status(400).json({ error: 'El campo "to" es obligatorio' });
  }

  const cleanPhone = to.replace(/[^\d]/g, '');
  const jid = `${cleanPhone}@s.whatsapp.net`;

  try {
    // 3a. Reacción con Emoji a un mensaje específico
    if (reaction && messageId) {
      await sock.sendMessage(jid, {
        react: {
          text: reaction,
          key: {
            remoteJid: jid,
            fromMe: false,
            id: messageId
          }
        }
      });
      return res.json({ success: true, type: 'reaction' });
    }

    // 3b. Imagen
    if (imageUrl) {
      await sock.sendMessage(jid, {
        image: { url: imageUrl },
        caption: caption || ''
      });
      return res.json({ success: true, type: 'image' });
    }

    // 3c. Texto
    if (text) {
      await sock.sendMessage(jid, { text: text });
      return res.json({ success: true, type: 'text' });
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
    fs.rmSync(AUTH_DIR, { recursive: true, force: true });
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

// 5. Healthcheck
app.get('/health', (req, res) => {
  res.json({ ok: true, status: connectionStatus });
});

app.listen(PORT, '0.0.0.0', () => {
  logger.info(`Microservicio whatsapp-bridge escuchando en puerto ${PORT}`);
  startWhatsApp();
});
