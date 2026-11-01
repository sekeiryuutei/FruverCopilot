const tls = require('tls');

const socket = tls.connect(993, 'imap.gmail.com', { rejectUnauthorized: false }, () => {
    console.log('Connected to IMAP');
});

socket.setEncoding('utf8');

socket.on('data', (data) => {
    console.log('IMAP received:\n', data.trim());
    if (data.includes('* OK')) {
        socket.write('a1 LOGIN citas.drjorgejimenez@gmail.com tbizucojkyxzoows\r\n');
    } else if (data.includes('a1 OK')) {
        socket.write('a2 SELECT INBOX\r\n');
    } else if (data.includes('a2 OK')) {
        socket.write('a3 SEARCH ALL\r\n');
    } else if (data.includes('a3 OK')) {
        socket.write('a4 LOGOUT\r\n');
    }
});

socket.on('error', (err) => console.error('IMAP error:', err));
socket.on('end', () => console.log('IMAP connection closed'));
setTimeout(() => { socket.end(); process.exit(0); }, 7000);
