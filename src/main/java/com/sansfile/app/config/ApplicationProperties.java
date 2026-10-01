package com.sansfile.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties specific to SansFile Backend.
 * <p>
 * Properties are configured in the {@code application.yml} file.
 * See {@link tech.jhipster.config.JHipsterProperties} for a good example.
 */
@ConfigurationProperties(prefix = "application", ignoreUnknownFields = false)
public class ApplicationProperties {

    private final Liquibase liquibase = new Liquibase();
    private final Sms sms = new Sms();
    private final Storage storage = new Storage();
    private final Otp otp = new Otp();
    private final Business business = new Business();
    private final Push push = new Push();
    private final Admin admin = new Admin();

    public Admin getAdmin() {
        return admin;
    }

    /** Compte super-administrateur créé au démarrage. */
    public static class Admin {

        /** ADMIN_EMAIL : identifiant de connexion (et e-mail) du compte admin. */
        private String email = "admin@sansfile.sn";

        /** ADMIN_PASSWORD : obligatoire en production (12 caractères minimum). */
        private String password = "";

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public Liquibase getLiquibase() {
        return liquibase;
    }

    public Sms getSms() {
        return sms;
    }

    public Storage getStorage() {
        return storage;
    }

    public Otp getOtp() {
        return otp;
    }

    public Business getBusiness() {
        return business;
    }

    public Push getPush() {
        return push;
    }

    public static class Liquibase {

        private Boolean asyncStart = true;

        public Boolean getAsyncStart() {
            return asyncStart;
        }

        public void setAsyncStart(Boolean asyncStart) {
            this.asyncStart = asyncStart;
        }
    }

    public static class Sms {

        /** « mock » (développement, aucun SMS réel) ou « sendtext » (production). */
        private String provider = "mock";
        /** Nom d'expéditeur : doit être validé au préalable dans le tableau de bord SendText. */
        private String senderName = "SansFile";
        private String sendtextApiKey;
        private String sendtextApiSecret;
        private String sendtextBaseUrl = "https://api.sendtext.sn/v1";
        /** « normal » ou « flash » (affiché directement à l'écran, non stocké). */
        private String sendtextSmsType = "normal";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getSenderName() {
            return senderName;
        }

        public void setSenderName(String senderName) {
            this.senderName = senderName;
        }

        public String getSendtextApiKey() {
            return sendtextApiKey;
        }

        public void setSendtextApiKey(String sendtextApiKey) {
            this.sendtextApiKey = sendtextApiKey;
        }

        public String getSendtextApiSecret() {
            return sendtextApiSecret;
        }

        public void setSendtextApiSecret(String sendtextApiSecret) {
            this.sendtextApiSecret = sendtextApiSecret;
        }

        public String getSendtextBaseUrl() {
            return sendtextBaseUrl;
        }

        public void setSendtextBaseUrl(String sendtextBaseUrl) {
            this.sendtextBaseUrl = sendtextBaseUrl;
        }

        public String getSendtextSmsType() {
            return sendtextSmsType;
        }

        public void setSendtextSmsType(String sendtextSmsType) {
            this.sendtextSmsType = sendtextSmsType;
        }
    }

    public static class Storage {

        private String provider = "cloudinary";
        private String uploadDir = "uploads";
        private String cdnUrl = "/api/files/";
        private CloudinaryProperties cloudinary = new CloudinaryProperties();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getUploadDir() {
            return uploadDir;
        }

        public void setUploadDir(String uploadDir) {
            this.uploadDir = uploadDir;
        }

        public String getCdnUrl() {
            return cdnUrl;
        }

        public void setCdnUrl(String cdnUrl) {
            this.cdnUrl = cdnUrl;
        }

        public CloudinaryProperties getCloudinary() {
            return cloudinary;
        }

        public void setCloudinary(CloudinaryProperties cloudinary) {
            this.cloudinary = cloudinary;
        }

        public static class CloudinaryProperties {

            private String cloudName;
            private String apiKey;
            private String apiSecret;
            private String url;

            public String getCloudName() {
                return cloudName;
            }

            public void setCloudName(String cloudName) {
                this.cloudName = cloudName;
            }

            public String getApiKey() {
                return apiKey;
            }

            public void setApiKey(String apiKey) {
                this.apiKey = apiKey;
            }

            public String getApiSecret() {
                return apiSecret;
            }

            public void setApiSecret(String apiSecret) {
                this.apiSecret = apiSecret;
            }

            public String getUrl() {
                return url;
            }

            public void setUrl(String url) {
                this.url = url;
            }
        }
    }

    public static class Otp {

        private int expirationSeconds = 300;
        private int maxAttempts = 3;
        private int resendCooldownSeconds = 45;
        /** Code de test accepté uniquement avec le fournisseur « mock » (vide = désactivé). */
        private String testCode = "";

        public String getTestCode() {
            return testCode;
        }

        public void setTestCode(String testCode) {
            this.testCode = testCode;
        }

        public int getExpirationSeconds() {
            return expirationSeconds;
        }

        public void setExpirationSeconds(int expirationSeconds) {
            this.expirationSeconds = expirationSeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getResendCooldownSeconds() {
            return resendCooldownSeconds;
        }

        public void setResendCooldownSeconds(int resendCooldownSeconds) {
            this.resendCooldownSeconds = resendCooldownSeconds;
        }
    }

    public static class Business {

        private String whatsappNumber = "221778627052";
        private long deliveryFee = 2000L;

        public String getWhatsappNumber() {
            return whatsappNumber;
        }

        public void setWhatsappNumber(String whatsappNumber) {
            this.whatsappNumber = whatsappNumber;
        }

        public long getDeliveryFee() {
            return deliveryFee;
        }

        public void setDeliveryFee(long deliveryFee) {
            this.deliveryFee = deliveryFee;
        }
    }

    public static class Push {

        private String vapidPublicKey;
        private String vapidPrivateKey;
        private String subject = "mailto:support@sansfile.sn";

        public String getVapidPublicKey() {
            return vapidPublicKey;
        }

        public void setVapidPublicKey(String vapidPublicKey) {
            this.vapidPublicKey = vapidPublicKey;
        }

        public String getVapidPrivateKey() {
            return vapidPrivateKey;
        }

        public void setVapidPrivateKey(String vapidPrivateKey) {
            this.vapidPrivateKey = vapidPrivateKey;
        }

        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }
    }
}
