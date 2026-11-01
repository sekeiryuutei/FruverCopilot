const tls = require('tls');
const socket = tls.connect(993, 'imap.gmail.com', { rejectUnauthorized: false }, () => {});
socket.setEncoding('utf8');

socket.on('data', (d) => {
    if (d.includes('* OK Gimap')) {
        socket.write('a1 LOGIN citas.drjorgejimenez@gmail.com tbizucojkyxzoows\r\n');
    } else if (d.includes('a1 OK')) {
        socket.write('a2 SELECT INBOX\r\n');
    } else if (d.includes('a2 OK')) {
        socket.write('a3 SEARCH FROM "FromBrotherDevice@brother.com"\r\n');
    } else if (d.includes('a3 OK')) {
        const match = d.match(/\* SEARCH (.*)/);
        if (match && match[1].trim()) {
            const ids = match[1].trim().split(' ');
            const lastId = ids[ids.length - 1];
            socket.write(`a4 FETCH ${lastId} (BODY[TEXT] BODY[HEADER.FIELDS (SUBJECT DATE)])\r\n`);
        } else {
            socket.write('a4 LOGOUT\r\n');
        }
    } else if (d.includes('FETCH')) {
        console.log('--- BROTHER NOTIFICATION ---');
        console.log(d);
        socket.write('a5 LOGOUT\r\n');
    }
});
setTimeout(() => process.exit(0), 6000);

