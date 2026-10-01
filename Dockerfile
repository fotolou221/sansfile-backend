# ==============================================================================
# Multi-stage Dockerfile pour sansfile-backend (Java 21 / Spring Boot 4)
# Optimisé pour Docker Compose sur serveur VPS
# ==============================================================================

# --- Étape 1 : Compilation Maven ---
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /workspace/app

# Copie des configurations Maven, Sonar, Checkstyle et Prettier
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY sonar-project.properties* .
COPY checkstyle.xml* .
COPY package.json* .
COPY .prettierrc* .
COPY .prettierignore* .

# Dépendances Maven dans une couche à part : retéléchargées seulement quand pom.xml change
RUN chmod +x ./mvnw && ./mvnw dependency:go-offline -Pprod -Denforcer.skip=true -B -ntp

# Compilation du JAR de production (les tests tournent dans la CI, pas ici)
COPY src src
RUN ./mvnw clean package -Pprod -DskipTests -Denforcer.skip=true -Dmodernizer.skip=true -Dcheckstyle.skip=true -B -ntp

# --- Étape 2 : Image d'exécution légère (JRE 21) ---
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Création d'un utilisateur non-root pour la sécurité
RUN addgroup -S sansfile && adduser -S sansfile -G sansfile

# Récupération du JAR généré
COPY --from=builder /workspace/app/target/*.jar app.jar

# Dossier d'upload local (fallback si Cloudinary inactif)
RUN mkdir -p /app/uploads && chown -R sansfile:sansfile /app

USER sansfile

# Port d'écoute (modifiable via la variable PORT)
ENV PORT=8080
EXPOSE 8080

# Mémoire JVM proportionnelle à la limite du conteneur (surchargeable via JAVA_OPTS)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Santé lue par Docker (docker ps, déploiement, supervision) ; démarrage + Liquibase peuvent prendre ~1 min
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD wget -qO- "http://127.0.0.1:${PORT:-8080}/management/health" > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar /app/app.jar --spring.profiles.active=prod --server.port=${PORT:-8080} --server.address=0.0.0.0"]

