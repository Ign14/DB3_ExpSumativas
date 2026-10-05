# Imagen comun a los siete componentes. El modulo a empaquetar se elige con el
# argumento MODULE, que docker-compose pasa por cada servicio.
#
# Es un build multi-etapa: la primera compila el reactor completo dentro de la
# imagen y la segunda se queda solo con el jar y un JRE. Dos consecuencias
# practicas:
#
#  - No hace falta tener Java ni Maven instalados para construir las imagenes.
#  - La etapa de compilacion es identica para los siete servicios, asi que
#    Docker la reutiliza de su cache y solo se compila una vez, no siete.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Primero solo los descriptores: si no cambian, Docker reutiliza las
# dependencias ya descargadas y no vuelve a bajar medio repositorio.
COPY pom.xml ./
COPY common-events/pom.xml common-events/
COPY broker-artemis/pom.xml broker-artemis/
COPY config-server/pom.xml config-server/
COPY discovery-server/pom.xml discovery-server/
COPY auth-server/pom.xml auth-server/
COPY api-gateway/pom.xml api-gateway/
COPY cuentas-service/pom.xml cuentas-service/
COPY movimientos-service/pom.xml movimientos-service/
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
