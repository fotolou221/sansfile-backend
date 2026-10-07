package com.sansfile.app.web.rest.custom;

import com.sansfile.app.service.custom.order.PartnerInvoiceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Facture d'une commande, envoyée au partenaire sur WhatsApp : page HTML ouverte sans connexion par un lien
 * impossible à deviner, imprimable ou enregistrable en PDF depuis le navigateur.
 */
@Tag(name = "6. Boutique & Commandes", description = "Facture envoyée au partenaire")
@RestController
@RequestMapping("/api/public/invoices")
public class PartnerInvoiceResource {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);

    private final PartnerInvoiceService invoiceService;

    public PartnerInvoiceResource(PartnerInvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> invoice(@PathVariable("token") String token) {
        String nonce = newNonce();
        return invoiceService
            .find(token)
            .map(invoice -> page(HttpStatus.OK, invoiceService.render(invoice, nonce), nonce))
            .orElseGet(() -> page(HttpStatus.NOT_FOUND, NOT_FOUND_PAGE, nonce));
    }

    /**
     * Page autonome : ses styles, le logo et les photos des produits (adresses web), un seul script autorisé
     * (nonce). Sans Referer, le lien de la facture ne fuit pas vers les hébergeurs d'images.
     */
    private static ResponseEntity<String> page(HttpStatus status, String html, String nonce) {
        return ResponseEntity.status(status)
            .contentType(HTML)
            .cacheControl(CacheControl.noStore())
            .header(
                "Content-Security-Policy",
                "default-src 'none'; img-src 'self' data: https: http:; style-src 'unsafe-inline'; script-src 'nonce-" +
                    nonce +
                    "'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
            )
            .header("Referrer-Policy", "no-referrer")
            .header("X-Robots-Tag", "noindex, nofollow")
            .body(html);
    }

    private static String newNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static final String NOT_FOUND_PAGE = """
        <!doctype html>
        <html lang="fr">
          <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1" />
            <title>Facture introuvable · SansFile</title>
          </head>
          <body style="margin:0;font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:#f1f5f9;color:#0f172a">
            <main style="max-width:480px;margin:15vh auto;padding:24px;text-align:center">
              <h1 style="font-size:20px">Facture introuvable</h1>
              <p style="color:#64748b">Ce lien n'est pas valide. Demandez à SansFile de vous renvoyer la facture.</p>
            </main>
          </body>
        </html>
        """;
}
