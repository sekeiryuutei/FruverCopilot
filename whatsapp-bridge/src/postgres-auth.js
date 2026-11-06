const { proto, BufferJSON, initAuthCreds } = require('@whiskeysockets/baileys');

const fixKeyId = (id) => String(id).replace(/\//g, '__').replace(/:/g, '-');

/**
 * Adaptador de autenticación y claves criptográficas Signal sobre PostgreSQL transaccional.
 * Reemplaza useMultiFileAuthState eliminando los ~1.400 archivos JSON en disco y
 * garantizando persistencia ACID para evitar el error "Bad MAC" / "Esperando mensaje...".
 */
async function usePostgresAuthState(pool) {
    if (!pool) {
        throw new Error('usePostgresAuthState requiere un pool de PostgreSQL inicializado.');
    }

    let currentTxClient = null;
    const getClient = () => currentTxClient || pool;

    // 1. Cargar credenciales principales (creds.json)
    const credsRes = await pool.query('SELECT creds FROM whatsapp_auth_credentials WHERE id = $1', ['DEFAULT']);
    let creds;
    if (credsRes.rows.length > 0 && credsRes.rows[0].creds) {
        const raw = credsRes.rows[0].creds;
        creds = typeof raw === 'string'
            ? JSON.parse(raw, BufferJSON.reviver)
            : JSON.parse(JSON.stringify(raw), BufferJSON.reviver);
    } else {
        creds = initAuthCreds();
    }

    const writeData = async (data, category, id, client = getClient()) => {
        const keyId = fixKeyId(id);
        const jsonStr = JSON.stringify(data, BufferJSON.replacer);
        await client.query(`
            INSERT INTO whatsapp_signal_keys (category, key_id, key_data, updated_at)
            VALUES ($1, $2, $3::jsonb, NOW())
            ON CONFLICT (category, key_id)
            DO UPDATE SET key_data = EXCLUDED.key_data, updated_at = NOW()
        `, [category, keyId, jsonStr]);
    };

    const removeData = async (category, id, client = getClient()) => {
        const keyId = fixKeyId(id);
        await client.query('DELETE FROM whatsapp_signal_keys WHERE category = $1 AND key_id = $2', [category, keyId]);
    };

    return {
        state: {
            creds,
            keys: {
                get: async (type, ids) => {
                    const data = {};
                    if (!ids || ids.length === 0) return data;

                    const fixedToOrig = new Map();
                    const fixedIds = ids.map(id => {
                        const f = fixKeyId(id);
                        fixedToOrig.set(f, id);
                        return f;
                    });

                    const client = getClient();
                    const res = await client.query(
                        'SELECT key_id, key_data FROM whatsapp_signal_keys WHERE category = $1 AND key_id = ANY($2)',
                        [type, fixedIds]
                    );

                    for (const row of res.rows) {
                        const origId = fixedToOrig.get(row.key_id) || row.key_id;
                        let value = typeof row.key_data === 'string'
                            ? JSON.parse(row.key_data, BufferJSON.reviver)
                            : JSON.parse(JSON.stringify(row.key_data), BufferJSON.reviver);

                        if (type === 'app-state-sync-key' && value) {
                            value = proto.Message.AppStateSyncKeyData.fromObject(value);
                        }
                        data[origId] = value;
                    }

                    for (const id of ids) {
                        if (data[id] === undefined) data[id] = null;
                    }
                    return data;
                },
                set: async (data) => {
                    const client = getClient();
                    const isOwnTx = !currentTxClient;
                    let txClient = client;

                    if (isOwnTx) {
                        txClient = await pool.connect();
                        await txClient.query('BEGIN');
                    }

                    try {
                        for (const category in data) {
                            for (const id in data[category]) {
                                const value = data[category][id];
                                if (value) {
                                    await writeData(value, category, id, txClient);
                                } else {
                                    await removeData(category, id, txClient);
                                }
                            }
                        }
                        if (isOwnTx) {
                            await txClient.query('COMMIT');
                        }
                    } catch (e) {
                        if (isOwnTx) {
                            await txClient.query('ROLLBACK');
                        }
                        throw e;
                    } finally {
                        if (isOwnTx) {
                            txClient.release();
                        }
                    }
                },
                transaction: async (work) => {
                    if (currentTxClient) {
                        return await work();
                    }
                    const client = await pool.connect();
                    currentTxClient = client;
                    try {
                        await client.query('BEGIN');
                        const res = await work();
                        await client.query('COMMIT');
                        return res;
                    } catch (e) {
                        await client.query('ROLLBACK');
                        throw e;
                    } finally {
                        currentTxClient = null;
                        client.release();
                    }
                }
            }
        },
        saveCreds: async () => {
            const jsonStr = JSON.stringify(creds, BufferJSON.replacer);
            const client = getClient();
            await client.query(`
                INSERT INTO whatsapp_auth_credentials (id, creds, updated_at)
                VALUES ('DEFAULT', $1::jsonb, NOW())
                ON CONFLICT (id)
                DO UPDATE SET creds = EXCLUDED.creds, updated_at = NOW()
            `, [jsonStr]);
        }
    };
}

module.exports = {
    usePostgresAuthState,
    fixKeyId
};
