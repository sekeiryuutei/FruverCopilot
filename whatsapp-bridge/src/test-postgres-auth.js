const { Pool } = require('pg');
const { usePostgresAuthState } = require('./postgres-auth');

const pool = new Pool({
    host: process.env.DB_HOST || 'postgres',
    port: parseInt(process.env.DB_PORT || '5432', 10),
    user: process.env.DB_USER || 'fruver',
    password: process.env.DB_PASSWORD || '-FiANc-B_yzU0JJijtQfj0Tr',
    database: process.env.DB_NAME || 'fruver'
});

async function runTests() {
    console.log('--- INICIANDO TEST SUITE DE POSTGRES SIGNAL STORAGE ---');

    // Test 1: usePostgresAuthState
    const { state, saveCreds } = await usePostgresAuthState(pool);
    console.log('✓ usePostgresAuthState cargado');

    if (!state.creds || !state.creds.me || !state.creds.me.id) {
        throw new Error('Test 1 falló: Credenciales no contienen JID válido');
    }
    console.log(`✓ Test 1 OK: JID recuperado = ${state.creds.me.id}`);

    // Test 2: Validar tipo Buffer en llaves criptográficas
    const identityKey = state.creds.signedIdentityKey?.public;
    if (!Buffer.isBuffer(identityKey) && !(identityKey instanceof Uint8Array)) {
        throw new Error('Test 2 falló: signedIdentityKey no es Buffer/Uint8Array');
    }
    console.log(`✓ Test 2 OK: signedIdentityKey es Buffer válido (${identityKey.length} bytes)`);

    // Test 3: Leer claves existentes
    const preKeyIds = ['1', '2', '3'];
    const preKeys = await state.keys.get('pre-key', preKeyIds);
    console.log(`✓ Test 3 OK: Claves pre-key recuperadas = ${Object.keys(preKeys).length}`);

    // Test 4: Escritura y lectura de clave de prueba
    const testKeyId = 'unit-test-key-999';
    const testData = { foo: 'bar', bufferTest: Buffer.from('FruverCopilot2026') };
    await state.keys.set({
        'session': {
            [testKeyId]: testData
        }
    });

    const readBack = await state.keys.get('session', [testKeyId]);
    if (!readBack[testKeyId] || readBack[testKeyId].foo !== 'bar') {
        throw new Error('Test 4 falló: La clave escrita no coincide al leerla');
    }
    if (!Buffer.isBuffer(readBack[testKeyId].bufferTest) || readBack[testKeyId].bufferTest.toString() !== 'FruverCopilot2026') {
        throw new Error('Test 4 falló: El Buffer no se deserializó correctamente');
    }
    console.log('✓ Test 4 OK: Escritura, lectura y deserialización binaria de Buffer comprobadas');

    // Test 5: Eliminación de clave de prueba
    await state.keys.set({
        'session': {
            [testKeyId]: null
        }
    });
    const readAfterDelete = await state.keys.get('session', [testKeyId]);
    if (readAfterDelete[testKeyId] !== null) {
        throw new Error('Test 5 falló: La clave eliminada aún existe');
    }
    console.log('✓ Test 5 OK: Eliminación de claves confirmada');

    // Test 6: Transacción y Rollback atómico
    const rollbackKey = 'unit-test-rollback';
    try {
        await state.keys.transaction(async () => {
            await state.keys.set({
                'session': {
                    [rollbackKey]: { test: true }
                }
            });
            throw new Error('Fallo intencional para verificar Rollback');
        });
    } catch (expected) {
        // esperado
    }
    const rollbackCheck = await state.keys.get('session', [rollbackKey]);
    if (rollbackCheck[rollbackKey] !== null) {
        throw new Error('Test 6 falló: El rollback no revirtió la inserción fallida');
    }
    console.log('✓ Test 6 OK: Rollback atómico de transacción comprobado');

    // Test 7: Advisory Lock para exclusión mutua
    const client1 = await pool.connect();
    const lock1 = await client1.query('SELECT pg_try_advisory_lock(987654321) as locked');
    if (!lock1.rows[0].locked) {
        throw new Error('Test 7 falló: No se pudo adquirir advisory lock principal');
    }

    const client2 = await pool.connect();
    const lock2 = await client2.query('SELECT pg_try_advisory_lock(987654321) as locked');
    if (lock2.rows[0].locked) {
        throw new Error('Test 7 falló: Segunda instancia pudo adquirir el lock simultáneamente (debió ser denegada)');
    }
    console.log('✓ Test 7 OK: Exclusión mutua con Advisory Lock confirmada (evita 2 bridges simultáneos)');

    await client2.query('SELECT pg_advisory_unlock(987654321)');
    client2.release();
    await client1.query('SELECT pg_advisory_unlock(987654321)');
    client1.release();

    console.log('====================================================');
    console.log('🎉 TODOS LOS 7 TESTS DE PERSISTENCIA SIGNAL PASARON');
    console.log('====================================================');
}

runTests()
    .then(() => pool.end())
    .catch((e) => {
        console.error('ERROR EN TESTS:', e);
        pool.end();
        process.exit(1);
    });
