/**
 * Bandeja de Entrada (Inbox) y Salida (Outbox) persistente en PostgreSQL.
 * Garantiza entrega confiable a Spring Boot incluso si el backend se reinicia,
 * maneja mensajes que llegan inicialmente cifrados (CIPHERTEXT), y previene duplicados.
 */

function createInboxOutbox(pool, forwardToBackendFn, logger) {
    let isRunning = false;
    let workerTimer = null;

    /**
     * Registra un mensaje entrante (descifrado o pendiente de descifrado)
     */
    async function recordIncomingMessage({
        messageId,
        remoteJid,
        fromMe,
        participant,
        pushName,
        senderPhone,
        messageType,
        rawMessage,
        isCiphertext,
        formattedPayload
    }) {
        if (!messageId) return null;

        const client = await pool.connect();
        try {
            await client.query('BEGIN');

            const status = isCiphertext ? 'WAITING_DECRYPT' : 'READY';

            // 1. Guardar en Inbox (idempotente)
            const inboxRes = await client.query(`
                INSERT INTO whatsapp_bridge_inbox (
                    message_id, remote_jid, from_me, participant, push_name,
                    sender_phone, message_type, status, raw_message, received_at, decrypted_at
                ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, NOW(), $10)
                ON CONFLICT (message_id) DO UPDATE SET
                    raw_message = COALESCE(EXCLUDED.raw_message, whatsapp_bridge_inbox.raw_message),
                    status = CASE
                        WHEN whatsapp_bridge_inbox.status = 'WAITING_DECRYPT' AND EXCLUDED.status = 'READY' THEN 'READY'
                        ELSE whatsapp_bridge_inbox.status
                    END,
                    decrypted_at = CASE
                        WHEN whatsapp_bridge_inbox.status = 'WAITING_DECRYPT' AND EXCLUDED.status = 'READY' THEN NOW()
                        ELSE whatsapp_bridge_inbox.decrypted_at
                    END
                RETURNING id, status
            `, [
                messageId,
                remoteJid,
                !!fromMe,
                participant || null,
                pushName || null,
                senderPhone || null,
                messageType || 'UNKNOWN',
                status,
                rawMessage ? JSON.stringify(rawMessage) : null,
                isCiphertext ? null : new Date()
            ]);

            const inboxRow = inboxRes.rows[0];

            // 2. Si el mensaje está descifrado y listo para enviar, encolar en Outbox
            if (inboxRow && inboxRow.status === 'READY' && formattedPayload) {
                // Verificar si ya existe en Outbox para evitar duplicados
                const outboxCheck = await client.query(
                    'SELECT id, status FROM whatsapp_bridge_outbox WHERE message_id = $1',
                    [messageId]
                );

                if (outboxCheck.rows.length === 0) {
                    await client.query(`
                        INSERT INTO whatsapp_bridge_outbox (
                            message_id, inbox_id, payload, status, next_attempt_at, created_at
                        ) VALUES ($1, $2, $3::jsonb, 'PENDING', NOW(), NOW())
                    `, [messageId, inboxRow.id, JSON.stringify(formattedPayload)]);

                    logger.info({ messageId, phone: senderPhone }, 'Mensaje encolado en Outbox para despacho a Spring Boot');
                }
            } else if (isCiphertext) {
                logger.warn({ messageId, remoteJid }, 'Mensaje registrado como WAITING_DECRYPT (Esperando resolución de llaves Signal)');
            }

            await client.query('COMMIT');
            return inboxRow;
        } catch (err) {
            await client.query('ROLLBACK');
            logger.error({ err: err.message, messageId }, 'Error registrando mensaje en Inbox/Outbox');
            throw err;
        } finally {
            client.release();
        }
    }

    /**
     * Procesa la actualización de un mensaje que finalmente fue descifrado
     */
    async function recordDecryptedUpdate({
        messageId,
        rawMessage,
        formattedPayload
    }) {
        if (!messageId || !rawMessage) return null;

        const client = await pool.connect();
        try {
            await client.query('BEGIN');

            // Actualizar Inbox
            const inboxRes = await client.query(`
                UPDATE whatsapp_bridge_inbox
                SET raw_message = $1::jsonb,
                    status = 'READY',
                    decrypted_at = NOW()
                WHERE message_id = $2
                  AND status IN ('WAITING_DECRYPT', 'PENDING')
                RETURNING id, status, sender_phone
            `, [JSON.stringify(rawMessage), messageId]);

            if (inboxRes.rows.length > 0 && formattedPayload) {
                const inboxRow = inboxRes.rows[0];

                // Encolar en Outbox si no estaba encolado
                await client.query(`
                    INSERT INTO whatsapp_bridge_outbox (
                        message_id, inbox_id, payload, status, next_attempt_at, created_at
                    ) VALUES ($1, $2, $3::jsonb, 'PENDING', NOW(), NOW())
                    ON CONFLICT DO NOTHING
                `, [messageId, inboxRow.id, JSON.stringify(formattedPayload)]);

                logger.info({ messageId, phone: inboxRow.sender_phone }, '¡Mensaje descifrado exitosamente tras reintento! Encolado en Outbox');
            }

            await client.query('COMMIT');
            return inboxRes.rows[0] || null;
        } catch (err) {
            await client.query('ROLLBACK');
            logger.error({ err: err.message, messageId }, 'Error actualizando mensaje descifrado en Inbox');
            throw err;
        } finally {
            client.release();
        }
    }

    /**
     * Worker que despacha mensajes de Outbox hacia Spring Boot con backoff y reintentos
     */
    async function processOutboxBatch() {
        if (!isRunning) return;

        let claimedJobs = [];
        const client = await pool.connect();
        try {
            await client.query('BEGIN');

            const res = await client.query(`
                SELECT id, message_id, inbox_id, payload, attempts
                FROM whatsapp_bridge_outbox
                WHERE status IN ('PENDING', 'RETRY')
                  AND next_attempt_at <= NOW()
                ORDER BY id ASC
                LIMIT 15
                FOR UPDATE SKIP LOCKED
            `);

            claimedJobs = res.rows;
            if (claimedJobs.length > 0) {
                const ids = claimedJobs.map(j => j.id);
                await client.query(
                    'UPDATE whatsapp_bridge_outbox SET status = \'SENDING\' WHERE id = ANY($1)',
                    [ids]
                );
            }
            await client.query('COMMIT');
        } catch (err) {
            await client.query('ROLLBACK');
            logger.error({ err: err.message }, 'Error reclamando trabajos de Outbox');
            return;
        } finally {
            client.release();
        }

        for (const job of claimedJobs) {
            try {
                const payload = typeof job.payload === 'string' ? JSON.parse(job.payload) : job.payload;
                await forwardToBackendFn(payload);

                // Éxito: marcar DELIVERED en outbox y PROCESSED en inbox
                await pool.query(`
                    UPDATE whatsapp_bridge_outbox
                    SET status = 'DELIVERED', delivered_at = NOW(), last_error = NULL
                    WHERE id = $1
                `, [job.id]);

                if (job.inbox_id) {
                    await pool.query(`
                        UPDATE whatsapp_bridge_inbox
                        SET status = 'PROCESSED', processed_at = NOW()
                        WHERE id = $1
                    `, [job.inbox_id]);
                }
                logger.info({ outboxId: job.id, msgId: job.message_id }, 'Mensaje entregado exitosamente a Spring Boot');
            } catch (postErr) {
                const attempts = job.attempts + 1;
                const backoffSeconds = Math.min(120, Math.pow(2, Math.min(attempts, 6))); // 2, 4, 8, 16, 32, 64, 120s
                const newStatus = attempts >= 10 ? 'FAILED' : 'RETRY';

                await pool.query(`
                    UPDATE whatsapp_bridge_outbox
                    SET status = $1,
                        attempts = $2,
                        next_attempt_at = NOW() + ($3 * interval '1 second'),
                        last_error = $4
                    WHERE id = $5
                `, [newStatus, attempts, backoffSeconds, (postErr.message || String(postErr)).slice(0, 300), job.id]);

                logger.warn({ outboxId: job.id, msgId: job.message_id, attempts, backoffSeconds, status: newStatus },
                    `Fallo temporal enviando webhook a Spring Boot: ${postErr.message}`
                );
            }
        }
    }

    function startWorker() {
        if (isRunning) return;
        isRunning = true;
        const tick = async () => {
            if (!isRunning) return;
            try {
                await processOutboxBatch();
            } catch (e) {
                logger.error({ err: e.message }, 'Error en tick de Outbox');
            } finally {
                if (isRunning) {
                    workerTimer = setTimeout(tick, 1000);
                }
            }
        };
        tick();
        logger.info('Worker de Outbox para entrega confiable a Spring Boot iniciado');
    }

    function stopWorker() {
        isRunning = false;
        if (workerTimer) {
            clearTimeout(workerTimer);
            workerTimer = null;
        }
        logger.info('Worker de Outbox detenido');
    }

    async function getStats() {
        const inbox = await pool.query('SELECT status, count(*) FROM whatsapp_bridge_inbox GROUP BY status');
        const outbox = await pool.query('SELECT status, count(*) FROM whatsapp_bridge_outbox GROUP BY status');
        return {
            inbox: Object.fromEntries(inbox.rows.map(r => [r.status, parseInt(r.count, 10)])),
            outbox: Object.fromEntries(outbox.rows.map(r => [r.status, parseInt(r.count, 10)]))
        };
    }

    return {
        recordIncomingMessage,
        recordDecryptedUpdate,
        startWorker,
        stopWorker,
        getStats
    };
}

module.exports = {
    createInboxOutbox
};

