# ---------- Etapa 1: compila o backend ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY backend/ backend/
RUN mkdir out \
 && javac -d out backend/*.java \
 && jar --create --file finsight.jar --main-class Main -C out .

# ---------- Etapa 2: imagem final, só com o Java de execução ----------
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home --shell /usr/sbin/nologin finsight
WORKDIR /app
COPY --from=build /src/finsight.jar /app/finsight.jar
COPY frontend/ /app/public/

# Na nuvem o servidor precisa escutar em 0.0.0.0 (o Render injeta a variável PORT)
ENV BIND_ADDRESS=0.0.0.0 \
    STATIC_DIR=/app/public

# Roda sem privilégios de administrador
USER finsight
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx300m", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/finsight.jar"]
