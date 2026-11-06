const fs = require('fs');

const input = fs.readFileSync('scripts/import_vcf_contacts.sql', 'utf8');
const lines = input.split('\n');

const outLines = [];
let count = 0;

for (let line of lines) {
    line = line.trim();
    if (!line) continue;
    if (line === 'BEGIN;' || line === 'COMMIT;') {
        outLines.push(line);
        continue;
    }
    // Match line pattern:
    // INSERT INTO customer (name, phone, customer_type, address) VALUES ('name', 'phone', 'WHOLESALE', address) ON CONFLICT ...
    const match = line.match(/^INSERT INTO customer \(name, phone, customer_type, address\) VALUES \((.+?),\s*'([0-9+]+)',\s*'WHOLESALE',\s*(.+?)\)\s*ON CONFLICT/);
    if (match) {
        const namePart = match[1];
        const phone = match[2];
        const addrPart = match[3];
        const newLine = `INSERT INTO customer (name, phone, customer_type, address, name_source) VALUES (${namePart}, '${phone}', 'WHOLESALE', ${addrPart}, 'IMPORTED_CONTACT') ON CONFLICT (phone) DO UPDATE SET name = EXCLUDED.name, name_source = 'IMPORTED_CONTACT', address = COALESCE(customer.address, EXCLUDED.address);`;
        outLines.push(newLine);
        count++;
    } else {
        outLines.push(line);
    }
}

fs.writeFileSync('scripts/sync_contacts_overwrite.sql', outLines.join('\n'), 'utf8');
console.log(`Successfully generated sync_contacts_overwrite.sql with ${count} contacts.`);

