package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.importlayer.parser.BancaBpmParser;
import com.agostinelli.gestionale.movimenti.importlayer.parser.BancaCaParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Copre sia Banco BPM che Crédit Agricole: stessa fonte DB IMPORT_BANCA, parser distinto per chiave. */
@ApplicationScoped
public class BancaImportStrategy implements ImportStrategy {

    @Inject BancaBpmParser bpmParser;
    @Inject BancaCaParser caParser;
    @Inject MovimentoNormalizerImpl normalizer;

    @Override
    public boolean supports(String fonteStr) {
        return "IMPORT_BANCA_BPM".equals(fonteStr) || "IMPORT_BANCA_CA".equals(fonteStr);
    }

    @Override
    public String fonte() {
        return "IMPORT_BANCA";
    }

    @Override
    public MovimentoParser parserFor(String fonteStr) {
        return "IMPORT_BANCA_CA".equals(fonteStr) ? caParser : bpmParser;
    }

    @Override
    public MovimentoNormalizer getNormalizer() {
        return normalizer;
    }
}
