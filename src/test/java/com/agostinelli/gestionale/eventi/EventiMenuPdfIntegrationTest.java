package com.agostinelli.gestionale.eventi;

import com.agostinelli.gestionale.infrastructure.storage.R2StorageService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test degli endpoint menu-pdf degli eventi.
 *
 * {@link R2StorageService} è mockato: i test verificano il wiring REST→service
 * (200/404/204/401/403) e la persistenza di {@code menuPdfUrl} sull'evento,
 * senza toccare la rete. Le validazioni mime/size sono coperte da
 * {@code R2StorageServiceTest}.
 */
@QuarkusTest
class EventiMenuPdfIntegrationTest {

    static final String TEST_USER_UUID = "00000000-0000-0000-0000-000000000099";
    static final String FAKE_URL = "https://workspace.agostinelli-gestionale.com/eventi/x/menu.pdf";
    static final byte[] PDF_BYTES = "%PDF-1.4\nfake test pdf\n%%EOF".getBytes();

    @InjectMock R2StorageService r2Storage;

    @BeforeEach
    void stubMock() {
        Mockito.when(r2Storage.uploadMenuPdf(
                        Mockito.any(UUID.class), Mockito.any(),
                        Mockito.anyLong(), Mockito.anyString()))
                .thenReturn(FAKE_URL);
        Mockito.when(r2Storage.getMenuPdf(Mockito.any(UUID.class)))
                .thenAnswer(inv -> new ByteArrayInputStream(PDF_BYTES));
    }

    private String creaEvento() {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"nome":"Evento Menu","tipo":"BANCHETTO_PRIVATO","dataEvento":"2026-12-10",
                         "contattoNome":"Test","importoTotalePreviventivato":1000,
                         "numeroTotalePartecipanti":50,"businessUnitId":2}
                        """)
                .when().post("/api/eventi")
                .then().statusCode(201)
                .extract().path("id");
    }

    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void uploadMenuPdf_ok_200_ePersisteUrl() {
        String id = creaEvento();

        given()
                .multiPart("file", "menu.pdf", PDF_BYTES, "application/pdf")
                .when().post("/api/eventi/" + id + "/menu-pdf")
                .then()
                .statusCode(200)
                .body("menuPdfUrl", equalTo(FAKE_URL));

        given().when().get("/api/eventi/" + id)
                .then().statusCode(200)
                .body("menuPdfUrl", equalTo(FAKE_URL));
    }

    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void uploadMenuPdf_eventoInesistente_404() {
        given()
                .multiPart("file", "menu.pdf", PDF_BYTES, "application/pdf")
                .when().post("/api/eventi/" + UUID.randomUUID() + "/menu-pdf")
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void deleteMenuPdf_ok_204_eAzzeraUrl() {
        String id = creaEvento();

        // prima carica, poi rimuove
        given().multiPart("file", "menu.pdf", PDF_BYTES, "application/pdf")
                .when().post("/api/eventi/" + id + "/menu-pdf")
                .then().statusCode(200);

        given().when().delete("/api/eventi/" + id + "/menu-pdf")
                .then().statusCode(204);

        given().when().get("/api/eventi/" + id)
                .then().statusCode(200)
                .body("menuPdfUrl", nullValue());
    }

    @Test
    void uploadMenuPdf_senzaToken_401() {
        given()
                .multiPart("file", "menu.pdf", PDF_BYTES, "application/pdf")
                .when().post("/api/eventi/" + UUID.randomUUID() + "/menu-pdf")
                .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"DIPENDENTE"})
    void uploadMenuPdf_dipendente_403() {
        given()
                .multiPart("file", "menu.pdf", PDF_BYTES, "application/pdf")
                .when().post("/api/eventi/" + UUID.randomUUID() + "/menu-pdf")
                .then().statusCode(403);
    }

    // ── GET /menu-pdf (proxy stream) ─────────────────────────────────────────

    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void getMenuPdf_admin_200_contentTypePdf() {
        String id = creaEvento();
        given().multiPart("file", "menu.pdf", PDF_BYTES, "application/pdf")
                .when().post("/api/eventi/" + id + "/menu-pdf")
                .then().statusCode(200);

        given().when().get("/api/eventi/" + id + "/menu-pdf")
                .then()
                .statusCode(200)
                .contentType("application/pdf");
    }

    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"DIPENDENTE"})
    void getMenuPdf_dipendente_eventoInesistente_404_nonForbidden() {
        // DIPENDENTE ha accesso in lettura: evento non esiste → 404, non 403
        given().when().get("/api/eventi/" + UUID.randomUUID() + "/menu-pdf")
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void getMenuPdf_eventoSenzaPdf_404() {
        String id = creaEvento();
        given().when().get("/api/eventi/" + id + "/menu-pdf")
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void getMenuPdf_eventoInesistente_404() {
        given().when().get("/api/eventi/" + UUID.randomUUID() + "/menu-pdf")
                .then().statusCode(404);
    }

    @Test
    void getMenuPdf_senzaToken_401() {
        given().when().get("/api/eventi/" + UUID.randomUUID() + "/menu-pdf")
                .then().statusCode(401);
    }
}
