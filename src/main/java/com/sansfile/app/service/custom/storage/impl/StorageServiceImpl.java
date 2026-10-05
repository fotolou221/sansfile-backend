package com.sansfile.app.service.custom.storage.impl;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.sansfile.app.config.ApplicationProperties;
import com.sansfile.app.service.custom.storage.StorageService;
import com.sansfile.app.service.custom.storage.StorageUsageTracker;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Implémentation du service de stockage et téléversement de fichiers.
 * Gère le stockage cloud Cloudinary et le stockage local sur disque avec bascule automatique.
 */
@Service
public class StorageServiceImpl implements StorageService {

    private static final Logger LOG = LoggerFactory.getLogger(StorageServiceImpl.class);
    // SVG exclu : il peut contenir du script exécuté par le navigateur (XSS)
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/jpg", "image/png", "image/webp");
    private static final java.util.regex.Pattern SAFE_FOLDER = java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]{1,40}$");

    private final Path rootLocation;
    private final ApplicationProperties applicationProperties;
    private final Cloudinary cloudinary;
    private final StorageUsageTracker usageTracker;

    public StorageServiceImpl(ApplicationProperties applicationProperties, Cloudinary cloudinary, StorageUsageTracker usageTracker) {
        this.usageTracker = usageTracker;
        this.applicationProperties = applicationProperties;
        this.cloudinary = cloudinary;
        String uploadDir = applicationProperties.getStorage().getUploadDir();
        this.rootLocation = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootLocation);
        } catch (IOException e) {
            LOG.error("Impossible d'initialiser le dossier de stockage local", e);
        }
    }

    @Override
    public String store(MultipartFile file, String folder) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est vide.");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("Format de fichier non supporté. Formats acceptés : JPEG, PNG, WebP.");
        }

        // Le type déclaré par le navigateur ne suffit pas : on vérifie la signature réelle du fichier
        byte[] bytes = file.getBytes();
        String extension = detectImageExtension(bytes);
        if (extension == null) {
            throw new IllegalArgumentException("Le contenu du fichier n'est pas une image JPEG, PNG ou WebP valide.");
        }
        folder = sanitizeFolder(folder);

        boolean isCloudinary = "cloudinary".equalsIgnoreCase(applicationProperties.getStorage().getProvider());
        String cloudName = applicationProperties.getStorage().getCloudinary().getCloudName();
        String cloudinaryUrl = applicationProperties.getStorage().getCloudinary().getUrl();
        boolean hasCloudinaryCreds = (cloudName != null && !cloudName.isBlank()) || (cloudinaryUrl != null && !cloudinaryUrl.isBlank());

        // 1. Téléversement Cloudinary si actif et configuré
        if (isCloudinary && hasCloudinaryCreds) {
            try {
                String targetFolder = folder != null && !folder.isBlank() ? "sansfile/" + folder : "sansfile";
                Map<?, ?> uploadParams = ObjectUtils.asMap("folder", targetFolder, "resource_type", "image");
                Map<?, ?> uploadResult = cloudinary.uploader().upload(bytes, uploadParams);
                String secureUrl = (String) uploadResult.get("secure_url");
                LOG.info("☁️ [CLOUDINARY] Image téléversée avec succès sur le CDN : {}", secureUrl);
                usageTracker.cloudinaryUploaded();
                return secureUrl;
            } catch (Exception e) {
                LOG.error("❌ Échec du téléversement sur Cloudinary, bascule automatique sur le stockage local : {}", e.getMessage());
                usageTracker.cloudinaryFailed();
            }
        }

        // 2. Stockage local (par défaut si provider 'local' ou si Cloudinary indisponible)
        return storeLocally(bytes, extension, folder);
    }

    /** Extension déduite de la signature binaire (« magic bytes »), jamais du nom envoyé par le client. */
    private static String detectImageExtension(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return ".png";
        }
        if (
            b.length >= 12 &&
            b[0] == 'R' &&
            b[1] == 'I' &&
            b[2] == 'F' &&
            b[3] == 'F' &&
            b[8] == 'W' &&
            b[9] == 'E' &&
            b[10] == 'B' &&
            b[11] == 'P'
        ) {
            return ".webp";
        }
        return null;
    }

    /** Dossier limité à un nom simple : empêche l'écriture hors du répertoire d'upload (../). */
    private static String sanitizeFolder(String folder) {
        return folder != null && SAFE_FOLDER.matcher(folder).matches() ? folder : "media";
    }

    private String storeLocally(byte[] bytes, String extension, String folder) throws IOException {
        String uniqueFilename = UUID.randomUUID().toString() + extension;
        Path targetDir = folder != null && !folder.isBlank() ? this.rootLocation.resolve(folder) : this.rootLocation;
        Files.createDirectories(targetDir);

        Path targetPath = targetDir.resolve(uniqueFilename);

        if (!targetPath.normalize().startsWith(this.rootLocation)) {
            throw new IllegalArgumentException("Emplacement de fichier invalide.");
        }
        Files.write(targetPath, bytes);
        usageTracker.storedLocally();

        String relativePath = folder != null && !folder.isBlank() ? folder + "/" + uniqueFilename : uniqueFilename;
        String publicUrl = buildPublicUrl(relativePath);

        LOG.info("📁 [LOCAL] Fichier enregistré localement : {} -> {}", targetPath, publicUrl);
        return publicUrl;
    }

    /**
     * URL publique du fichier local : {@code cdn-url} absolu (http/https) utilisé tel quel, sinon chemin
     * relatif au site (ex. « /api/files/salons/x.png », servi par le même domaine que les pages).
     * Ne pas la déduire de la requête : derrière Nginx, Tomcat remplace le port par 80/443 selon
     * X-Forwarded-Proto (« http://localhost:4200 » devenait « http://localhost », image introuvable).
     */
    private String buildPublicUrl(String relativePath) {
        String cdnUrl = applicationProperties.getStorage().getCdnUrl();
        if (cdnUrl == null || cdnUrl.isBlank()) {
            cdnUrl = "/api/files/";
        }
        if (!cdnUrl.endsWith("/")) {
            cdnUrl = cdnUrl + "/";
        }
        if (!cdnUrl.startsWith("http://") && !cdnUrl.startsWith("https://") && !cdnUrl.startsWith("/")) {
            cdnUrl = "/" + cdnUrl;
        }
        return cdnUrl + relativePath;
    }

    @Override
    public Resource loadAsResource(String filename) {
        try {
            Path file = rootLocation.resolve(filename).normalize();
            if (!file.startsWith(rootLocation)) {
                throw new IllegalArgumentException("Chemin de fichier invalide.");
            }
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() || resource.isReadable()) {
                return resource;
            } else {
                throw new IllegalArgumentException("Fichier introuvable : " + filename);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Impossible de lire le fichier : " + filename, e);
        }
    }

    @Override
    public String getActiveProvider() {
        return applicationProperties.getStorage().getProvider();
    }
}
