package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.importlayer.parser.BillyParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class BillyImportStrategy implements ImportStrategy {

    @Inject BillyParser billyParser;
    @Inject MovimentoNormalizerImpl normalizer;

    @Override
    public boolean supports(String fonteStr) {
        return "IMPORT_BILLY".equals(fonteStr);
    }

    @Override
    public String fonte() {
        return "IMPORT_BILLY";
    }

    @Override
    public MovimentoParser parserFor(String fonteStr) {
        return billyParser;
    }

    @Override
    public MovimentoNormalizer getNormalizer() {
        return normalizer;
    }
}
