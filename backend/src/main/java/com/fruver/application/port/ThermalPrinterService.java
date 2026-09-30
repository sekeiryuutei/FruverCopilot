package com.fruver.application.port;

public interface ThermalPrinterService {
    /** Envia bytes ya codificados a la impresora. Lanza PrinterException si falla. */
    void print(byte[] payload);

    class PrinterException extends RuntimeException {
        public PrinterException(String message, Throwable cause) { super(message, cause); }
    }
}
