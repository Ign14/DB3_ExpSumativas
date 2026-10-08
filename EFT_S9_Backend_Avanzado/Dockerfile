# Imagen comun a los componentes del sistema. El modulo a empaquetar se elige
# con el argumento MODULE, que docker-compose pasa por cada servicio.
#
# docker-compose construye once imagenes con este Dockerfile: los tres BFF, el
# gateway, los tres microservicios de dominio, los tres componentes de
# infraestructura y el broker de Artemis. Las otras dos piezas de la
# orquestacion son la imagen oficial de Kafka y un contenedor de un solo uso que
# crea sus topicos. El modulo batch-migracion tambien se construye con este
# mismo Dockerfile, pero fuera del compose: no es un servicio, es un proceso que
# se ejecuta y termina (ver la seccion 9 de despliegue.md).
#
# Es un build multi-etapa: la primera compila el reactor completo dentro de la
# imagen y la segunda se queda solo con el jar y un JRE. Dos consecuencias
# practicas:
#
#  - No hace falta tener Java ni Maven instalados para construir las imagenes.
#  - La etapa de compilacion es identica para todos los servicios, asi que
#    Docker la reutiliza de su cache y el reactor se compila una vez, no once.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Primero solo los descriptores: si no cambian, Docker reutiliza las
# dependencias ya descargadas y no vuelve a bajar medio repositorio.
COPY pom.xml ./
COPY common-dominio/pom.xml common-dominio/
COPY batch-migracion/pom.xml batch-migracion/
COPY broker-artemis/pom.xml broker-artemis/
COPY broker-kafka/pom.xml broker-kafka/
COPY config-server/pom.xml config-server/
COPY discovery-server/pom.xml discovery-server/
COPY auth-server/pom.xml auth-server/
COPY api-gateway/pom.xml api-gateway/
COPY cuentas-service/pom.xml cuentas-service/
COPY pagos-service/pom.xml pagos-service/
COPY clientes-service/pom.xml clientes-service/
COPY bff-common/pom.xml bff-common/
COPY bff-web/pom.xml bff-web/
COPY bff-movil/pom.xml bff-movil/
COPY bff-cajero/pom.xml bff-cajero/
RUN mvn -B -q dependency:go-offline -DskipTests || true
COPY . .
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre AS runtime
ARG MODULE
# curl es lo que usa el healthcheck de docker-compose para saber si el servicio
# ya esta listo y encadenar el orden de arranque.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/${MODULE}/target/${MODULE}.jar app.jar
# Sin esto el proceso corre como root dentro del contenedor, que es lo que la
# imagen base hace por defecto y no hay razon para aceptar. El grupo se crea de
# forma explicita en vez de confiar en que useradd lo derive del nombre: ese
# comportamiento depende de USERGROUPS_ENAB de la imagen base, y si cambiara, el
# chown de la linea siguiente fallaria y la imagen no se construiria.
RUN groupadd --system banco \
    && useradd --system --gid banco --uid 1001 banco \
    && chown -R banco:banco /app
USER banco
# MaxRAMPercentage en lugar de un -Xmx fijo: la JVM respeta el limite de memoria
# que Docker le ponga al contenedor sin tener que repetirlo en dos lugares.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
