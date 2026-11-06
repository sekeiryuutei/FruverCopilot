const fs = require('fs');
const path = require('path');
const { Pool } = require('pg');
const { BufferJSON } = require('@whiskeysockets/baileys');
const { fixKeyId } = require('./postgres-auth');

const AUTH_DIR = process.env.AUTH_DIR || path.join(__dirname, '..', 'auth_info');

const pool = new Pool({
    host: process.env.DB_HOST || 'localhost',
    port: parseInt(process.env.DB_PORT || '5432', 10),
    user: process.env.DB_USER || 'fruver',
    password: process.env.DB_PASSWORD || '-FiANc-B_yzU0JJijtQfj0Tr',
    database: process.env.DB_NAME || 'fruver'
});

const CATEGORIES = [
    'app-state-sync-key',
    'app-state-sync-version',
    'sender-key-memory',
    'sender-key',
    'pre-key',
    'session'
];

async function migrate() {
    console.log(`[MIGRACIÓN] Iniciando lectura de archivos desde: ${AUTH_DIR}`);
    if (!fs.existsSync(AUTH_DIR)) {
        throw new Error(`Directorio de autenticación no encontrado: ${AUTH_DIR}`);
    }

    const files = fs.readdirSync(AUTH_DIR);
    console.log(`[MIGRACIÓN] Total de archivos detectados en disco: ${files.length}`);

    // 1. Migrar creds.json
    const credsFile = path.join(AUTH_DIR, 'creds.json');
    if (fs.existsSync(credsFile)) {
        console.log('[MIGRACIÓN] Migrando creds.json...');
        const credsRaw = fs.readFileSync(credsFile, 'utf8');
        // Validar parsing con BufferJSON
        const credsObj = JSON.parse(credsRaw, BufferJSON.reviver);
        const credsSerialized = JSON.stringify(credsObj, BufferJSON.replacer);

        await pool.query(`
            INSERT INTO whatsapp_auth_credentials (id, creds, updated_at)
            VALUES ('DEFAULT', $1::jsonb, NOW())
            ON CONFLICT (id) DO UPDATE SET creds = EXCLUDED.creds, updated_at = NOW()
        `, [credsSerialized]);
        console.log('✓ creds.json migrado exitosamente');
    } else {
        console.warn('⚠️ No se encontró creds.json en el directorio.');
    }

    // 2. Migrar claves Signal en lotes transaccionales
    let migratedCount = 0;
    let skippedCount = 0;
    const errors = [];

    const client = await pool.connect();
    try {
        await client.query('BEGIN');

        for (const file of files) {
            if (file === 'creds.json' || file === 'lid_map.json' || file === 'messages_cache.json' || file.endsWith('.tmp')) {
                skippedCount++;
                continue;
            }

            let matchedCat = null;
            for (const cat of CATEGORIES) {
                if (file.startsWith(cat + '-')) {
                    matchedCat = cat;
                    break;
                }
            }

            if (!matchedCat) {
                console.warn(`[OMITIDO] Archivo no corresponde a categoría Signal: ${file}`);
                skippedCount++;
                continue;
            }

            const rawId = file.slice(matchedCat.length + 1, -5); // Quita prefijo y .json
            const keyId = fixKeyId(rawId);
            const fullPath = path.join(AUTH_DIR, file);

            try {
                const content = fs.readFileSync(fullPath, 'utf8');
                const parsed = JSON.parse(content, BufferJSON.reviver);
                const serialized = JSON.stringify(parsed, BufferJSON.replacer);

                await client.query(`
                    INSERT INTO whatsapp_signal_keys (category, key_id, key_data, updated_at)
                    VALUES ($1, $2, $3::jsonb, NOW())
                    ON CONFLICT (category, key_id) DO UPDATE SET key_data = EXCLUDED.key_data, updated_at = NOW()
                `, [matchedCat, keyId, serialized]);

                migratedCount++;
                if (migratedCount % 200 === 0) {
                    console.log(`[PROGRESO] ${migratedCount} claves migradas...`);
                }
            } catch (err) {
                errors.push({ file, err: err.message });
            }
        }

        await client.query('COMMIT');
        console.log(`✓ Transacción confirmada. Total claves migradas: ${migratedCount}, omitidos/auxiliares: ${skippedCount}`);
    } catch (err) {
        await client.query('ROLLBACK');
        console.error('🔴 Error fatal en transacción de migración:', err);
        throw err;
    } finally {
        client.release();
    }

    if (errors.length > 0) {
        console.error(`⚠️ Se presentaron ${errors.length} errores al leer archivos:`, errors);
    }

    // 3. Verificación de Integridad y Recuperabilidad
    console.log('[VERIFICACIÓN] Verificando integridad de datos en PostgreSQL...');
    const credsCheck = await pool.query('SELECT creds FROM whatsapp_auth_credentials WHERE id = $1', ['DEFAULT']);
    if (credsCheck.rows.length === 0) {
        throw new Error('FALLO DE INTEGRIDAD: creds no encontrados en la base de datos.');
    }
    const retrievedCreds = JSON.parse(JSON.stringify(credsCheck.rows[0].creds), BufferJSON.reviver);
    console.log(`✓ Verificación credenciales OK: ID de registro = ${retrievedCreds.registrationId}, JID = ${retrievedCreds.me?.id}`);

    const keysCountRes = await pool.query('SELECT category, count(*) FROM whatsapp_signal_keys GROUP BY category ORDER BY category');
    console.log('[VERIFICACIÓN] Distribución de claves almacenadas en PostgreSQL:');
    for (const r of keysCountRes.rows) {
        console.log(`  - ${r.category}: ${r.count} claves`);
    }

    console.log('====================================================');
    console.log('✅ MIGRACIÓN DE SESIÓN A POSTGRESQL COMPLETADA CON ÉXITO');
    console.log('====================================================');
}

migrate()
    .then(() => pool.end())
    .catch((e) => {
        console.error('Error en migración:', e);
        pool.end();
        process.exit(1);
    });
