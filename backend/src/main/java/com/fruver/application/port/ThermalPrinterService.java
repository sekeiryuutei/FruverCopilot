package com.fruver.application.port;

public interface ThermalPrinterService {
    /** Envia bytes ya codificados a la impresora en la ubicacion objetivo (OFICINA o CASA). */
    void print(byte[] payload, String targetLocation);

    default void print(byte[] payload) {
        print(payload, null);
    }

    class PrinterException extends RuntimeException {
        public PrinterException(String message, Throwable cause) { super(message, cause); }
    }
}
