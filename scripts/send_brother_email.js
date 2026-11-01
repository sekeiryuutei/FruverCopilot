const nodemailer = require('C:/Users/usuario/Documents/trabajos/DrJimenez/dr-jimenez-platform/backend/node_modules/nodemailer');
const fs = require('fs');
const path = require('path');

const pdfPath = path.resolve(__dirname, '../factura_6.pdf');

if (!fs.existsSync(pdfPath)) {
    console.error(`ERROR: No se encontró el archivo ${pdfPath}`);
    process.exit(1);
}

const stats = fs.statSync(pdfPath);
console.log(`Adjuntando PDF: ${pdfPath} (${stats.size} bytes)...`);

const transporter = nodemailer.createTransport({
    host: 'smtp.gmail.com',
    port: 465,
    secure: true, // SSL
    auth: {
        user: 'citas.drjorgejimenez@gmail.com',
        pass: 'tbizucojkyxzoows'
    }
});

const timestamp = Date.now();
const mailOptions = {
    from: '"Fruver El Oasis" <citas.drjorgejimenez@gmail.com>',
    to: '23363927290@print.brother.com',
    subject: `Factura 000006 - ${timestamp}`,
    attachments: [
        {
            filename: `factura_000006_${timestamp}.pdf`,
            path: pdfPath
        }
    ]
};

console.log(`Enviando correo a 23363927290@print.brother.com con filename único para evitar duplicados...`);

transporter.sendMail(mailOptions, (error, info) => {
    if (error) {
        console.error('❌ Error al enviar el correo:', error);
        process.exit(1);
    } else {
        console.log('✓ ¡CORREO ENVIADO CON ÉXITO A BROTHER CLOUD!');
        console.log('Message ID:', info.messageId);
        console.log('Response:', info.response);
        process.exit(0);
    }
});
