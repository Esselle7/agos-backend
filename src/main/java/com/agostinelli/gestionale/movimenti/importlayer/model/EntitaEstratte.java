package com.agostinelli.gestionale.movimenti.importlayer.model;

/**
 * Entità semantiche estratte dalla descrizione normalizzata di una riga
 * (ETL_CLASSIFICAZIONE_v2 §3). Vengono conservate sulla riga anche se questa
 * finisce in coda (scarto/triage/evento), così il motore di suggerimento e
 * l'apprendimento (F4/F5) possono riusarle senza ri-parsare il testo grezzo.
 *
 * Tutti i campi sono best-effort: null quando il pattern non è presente.
 */
public record EntitaEstratte(
        String ibanControparte, // IBAN IT… della controparte (chiave forte rubrica)
        String ordinante,       // nome ordinante (entrata): CA "ORD:…", BPM "BON.DA …"
        String beneficiario,    // nome beneficiario (uscita CA "DISPOSIZIONE DI PAGAMENTO")
        String codiceStripe     // codice Stripe PO… (data competenza / tag Alveare)
) {
    public static final EntitaEstratte EMPTY = new EntitaEstratte(null, null, null, null);
}
