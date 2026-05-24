package com.agostinelli.gestionale.infrastructure.storage;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test unitario (no Quarkus/CDI/rete) delle sole validazioni di
 * {@link R2StorageService#uploadMenuPdf}: il controllo mime/size avviene prima
 * di costruire il client S3, quindi non richiede credenziali né connessione.
 */
class R2StorageServiceTest {

    private final R2StorageService service = new R2StorageService();
    private final UUID eventoId = UUID.randomUUID();

    @Test
    void uploadMenuPdf_mimeNonPdf_lancia400() {
        InputStream in = new ByteArrayInputStream(new byte[]{1, 2, 3});
        ApiException ex = assertThrows(ApiException.class, () ->
                service.uploadMenuPdf(eventoId, in, 3,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertEquals(Response.Status.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("FILE_NON_PDF", ex.getCode());
    }

    @Test
    void uploadMenuPdf_oltre10MB_lancia400() {
        long size = 10L * 1024 * 1024 + 1;
        InputStream in = new ByteArrayInputStream(new byte[]{1});
        ApiException ex = assertThrows(ApiException.class, () ->
                service.uploadMenuPdf(eventoId, in, size, "application/pdf"));
        assertEquals(Response.Status.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("FILE_TROPPO_GRANDE", ex.getCode());
    }

    @Test
    void uploadMenuPdf_sizeZero_lancia400() {
        InputStream in = new ByteArrayInputStream(new byte[0]);
        ApiException ex = assertThrows(ApiException.class, () ->
                service.uploadMenuPdf(eventoId, in, 0, "application/pdf"));
        assertEquals(Response.Status.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("FILE_TROPPO_GRANDE", ex.getCode());
    }
}
