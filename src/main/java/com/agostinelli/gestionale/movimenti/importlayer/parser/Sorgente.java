package com.agostinelli.gestionale.movimenti.importlayer.parser;

/**
 * Discriminatore di sorgente iniettato dai parser in {@code RawRow.campi} sotto la
 * chiave riservata {@link #KEY}. Permette al normalizzatore unico di applicare le
 * regole di formato corrette (Billy / BPM / CA) senza cambiare la firma
 * {@code normalize(RawRow)} né il record {@code RawRow}.
 */
public final class Sorgente {

    public static final String KEY = "_SORGENTE";

    public static final String BILLY = BillyParser.SORGENTE_VALUE;
    public static final String BPM = "BPM";
    public static final String CA = "CA";

    private Sorgente() {}
}
