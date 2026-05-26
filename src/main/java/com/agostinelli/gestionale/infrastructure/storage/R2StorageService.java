package com.agostinelli.gestionale.infrastructure.storage;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.util.Set;
import java.util.UUID;

/**
 * Accesso allo storage Cloudflare R2 (S3-compatibile) per i menu degli eventi.
 * Supporta PDF (.pdf), Word (.docx) e Word legacy (.doc).
 *
 * Il client S3 è costruito una sola volta in modo lazy: questo permette di
 * eseguire le validazioni (mimeType/size) senza richiedere credenziali valide,
 * utile nei test che esercitano solo i percorsi di errore 400.
 */
@ApplicationScoped
public class R2StorageService {

    static final long MAX_SIZE_BYTES = 10_485_760L; // 10 MB
    static final Set<String> ALLOWED_MIMES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    @ConfigProperty(name = "r2.endpoint")
    String endpoint;

    @ConfigProperty(name = "r2.bucket-name")
    String bucketName;

    @ConfigProperty(name = "r2.access-key-id")
    String accessKeyId;

    @ConfigProperty(name = "r2.secret-access-key")
    String secretAccessKey;

    @ConfigProperty(name = "r2.public-base-url")
    String publicBaseUrl;

    private volatile S3Client client;

    /**
     * Carica il menu di un evento su R2. Accetta PDF, Word (.docx) e Word legacy (.doc).
     * La chiave S3 e l'URL pubblico usano l'estensione corretta derivata dal MIME type.
     *
     * @throws ApiException 400 se il mimeType non è tra quelli supportati o la size supera 10 MB.
     */
    public String uploadMenuPdf(UUID eventoId, InputStream content, long size, String mimeType) {
        if (!ALLOWED_MIMES.contains(mimeType)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "TIPO_FILE_NON_VALIDO",
                    "Il file deve essere un PDF o un documento Word (doc, docx)");
        }
        if (size <= 0 || size > MAX_SIZE_BYTES) {
            throw new ApiException(Response.Status.BAD_REQUEST, "FILE_TROPPO_GRANDE",
                    "Il file supera la dimensione massima consentita di 10 MB");
        }
        String key = objectKey(eventoId, extensionForMime(mimeType));
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(mimeType)
                .contentLength(size)
                .build();
        client().putObject(req, RequestBody.fromInputStream(content, size));
        return publicBaseUrl + "/" + key;
    }

    /**
     * Legge il menu di un evento da R2 usando l'URL già salvato sull'entità.
     * Lancia ApiException 404 se l'oggetto non esiste.
     *
     * @param storedUrl URL pubblica salvata in {@code Evento.menuPdfUrl}
     */
    public InputStream getMenuPdf(String storedUrl) {
        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(extractKey(storedUrl))
                .build();
        try {
            return client().getObject(req);
        } catch (NoSuchKeyException e) {
            throw new ApiException(Response.Status.NOT_FOUND, "MENU_NON_TROVATO",
                    "Nessun menu trovato per questo evento");
        }
    }

    /**
     * Elimina il menu di un evento da R2. Ignora silenziosamente l'assenza
     * dell'oggetto (NoSuchKey) per rendere l'operazione idempotente.
     *
     * @param storedUrl URL pubblica salvata in {@code Evento.menuPdfUrl}
     */
    public void deleteMenuPdf(String storedUrl) {
        DeleteObjectRequest req = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(extractKey(storedUrl))
                .build();
        try {
            client().deleteObject(req);
        } catch (NoSuchKeyException ignored) {
            // già assente: nulla da fare
        }
    }

    private String objectKey(UUID eventoId, String ext) {
        return "eventi/" + eventoId + "/menu." + ext;
    }

    /** Estrae la chiave S3 dall'URL pubblica (rimuove il prefisso publicBaseUrl + "/"). */
    private String extractKey(String storedUrl) {
        return storedUrl.substring(publicBaseUrl.length() + 1);
    }

    private String extensionForMime(String mimeType) {
        return switch (mimeType) {
            case "application/msword" -> "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            default -> "pdf";
        };
    }

    private S3Client client() {
        S3Client local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    local = buildClient();
                    client = local;
                }
            }
        }
        return local;
    }

    private S3Client buildClient() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .region(Region.of("auto"))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }
}
