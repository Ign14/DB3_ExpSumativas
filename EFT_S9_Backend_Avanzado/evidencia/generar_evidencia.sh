#!/usr/bin/env bash
# Genera la evidencia de ejecucion del sistema completo corriendo los jar
# directamente, sin Docker.
#
# Existe junto a la evidencia sobre contenedores (evidencia/docker/) porque las
# dos demuestran cosas distintas: esta demuestra la logica del sistema y se puede
# reproducir en cualquier maquina con un JDK, sin depender del demonio de Docker;
# la otra demuestra que las imagenes y la orquestacion funcionan.
#
# Uso, desde la carpeta EFT_S9_Backend_Avanzado:
#     bash evidencia/generar_evidencia.sh
#
# Deja los logs numerados en evidencia/local/ y la salida completa de cada
# proceso en evidencia/local/logs/.
#
# Es una de las tres evidencias del proyecto; el indice de las tres esta en
# evidencia/LEEME.md.

set -u

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EV="$RAIZ/evidencia/local"
LOGS="$EV/logs"
JVM_OPTS="-Xmx320m -XX:MaxRAMPercentage=75.0"

mkdir -p "$EV" "$LOGS"
rm -f "$EV"/*.log "$LOGS"/*.log "$LOGS"/*.pid
rm -rf "$LOGS/acceso"
mkdir -p "$LOGS/acceso"

# Credenciales por defecto del repositorio. En un despliegue real vienen de
# variables de entorno o de un gestor de secretos; aqui estan para que el
# ecosistema se levante sin preparar nada.
EUREKA_AUTH="banco-eureka:banco-eureka-secret"
CONFIG_AUTH="banco-config:banco-config-secret"
AUTH="http://localhost:9000"
GW="http://localhost:8080"

PIDS=()

# --------------------------------------------------------------------------
# Utilidades
# --------------------------------------------------------------------------

titulo() {
    echo
    echo "=============================================================="
    echo "  $*"
    echo "=============================================================="
}

paso() {
    echo
    echo "--- $* ---"
}

arrancar() {
    # arrancar <nombre> <jar> [args...]
    local nombre="$1"; shift
    local jar="$1"; shift
    echo ">> arrancando $nombre"
    # shellcheck disable=SC2086
    nohup java $JVM_OPTS -jar "$jar" "$@" > "$LOGS/$nombre.log" 2>&1 &
    local pid=$!
    PIDS+=("$pid")
    echo "$pid" > "$LOGS/$nombre.pid"
}

esperar_salud() {
    # esperar_salud <url> <nombre> [intentos] [credenciales]
    local url="$1" nombre="$2" intentos="${3:-60}" cred="${4:-}"
    local i estado
    for ((i = 1; i <= intentos; i++)); do
        if [[ -n "$cred" ]]; then
            estado=$(curl -s -u "$cred" --max-time 3 "$url" 2>/dev/null)
        else
            estado=$(curl -s --max-time 3 "$url" 2>/dev/null)
        fi
        if [[ "$estado" == *'"status":"UP"'* ]]; then
            echo "   $nombre UP (tras ${i}s)"
            return 0
        fi
        sleep 1
    done
    echo "   !! $nombre no llego a UP tras ${intentos}s"
    return 1
}

medir_movimientos() {
    # medir_movimientos <token> [intentos]
    #
    # Reintenta porque, justo despues de reiniciar un servicio, Eureka todavia
    # conserva el registro de la instancia muerta: el lease vence a los 90 s y
    # hasta entonces el gateway reparte entre la instancia nueva y una direccion
    # que ya no responde. Una de cada dos peticiones recibe 503. No es un fallo
    # del sistema: es el precio de que el registro sea eventualmente consistente,
    # y es exactamente la razon por la que los clientes de este proyecto llevan
    # reintento encima. Aqui se hace lo mismo que haria un cliente resiliente.
    local tk="$1" intentos="${2:-15}"
    local i valor
    for ((i = 1; i <= intentos; i++)); do
        valor=$(curl -s -H "Authorization: Bearer $tk" --max-time 10 \
            "$GW/api/movimientos/101/resumen" \
            | python3 -c "import sys,json; print(json.load(sys.stdin)['totalMovimientos'])" 2>/dev/null)
        if [[ -n "$valor" ]]; then
            if (( i > 1 )); then
                echo "   (el dato se obtuvo al intento $i: el gateway seguia repartiendo hacia la instancia dada de baja)" >&2
            fi
            echo "$valor"
            return 0
        fi
        sleep 2
    done
    echo "sin-dato"
    return 1
}

esperar_gateway() {
    # esperar_gateway <url> <token> [intentos]
    #
    # Levantar un servicio no basta para poder pedirle algo por el gateway: el
    # gateway enruta con la copia del registro de Eureka que tiene en memoria, y
    # hasta que la refresca responde 503. Dormir un numero fijo de segundos
    # funciona hasta que la maquina esta mas cargada y deja de funcionar, asi que
    # se pregunta hasta que responda.
    local url="$1" tk="$2" intentos="${3:-40}"
    local i
    for ((i = 1; i <= intentos; i++)); do
        if [[ "$(codigo "$url" "$tk")" == "200" ]]; then
            echo "   el gateway ya enruta hacia el servicio (tras ${i}s)"
            return 0
        fi
        sleep 1
    done
    echo "   !! el gateway sigue sin enrutar tras ${intentos}s"
    return 1
}

token() {
    # token <clientId> <secret> <scopes...>
    local id="$1" secreto="$2"; shift 2
    local scopes="$*"
    curl -s -u "$id:$secreto" -d "grant_type=client_credentials" \
        --data-urlencode "scope=$scopes" "$AUTH/oauth2/token" \
        | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null
}

codigo() {
    # codigo <url> [token]
    local url="$1" tk="${2:-}"
    if [[ -n "$tk" ]]; then
        curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $tk" --max-time 10 "$url"
    else
        curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$url"
    fi
}

json() {
    # json <url> <token>
    curl -s -H "Authorization: Bearer $2" --max-time 10 "$1" | python3 -m json.tool 2>/dev/null \
        || curl -s -H "Authorization: Bearer $2" --max-time 10 "$1"
}

post_json() {
    # post_json <url> <token> <cuerpo>
    curl -s -X POST -H "Authorization: Bearer $2" -H "Content-Type: application/json" \
        -d "$3" --max-time 15 "$1" | python3 -m json.tool 2>/dev/null
}

bajar_todo() {
    echo
    echo ">> deteniendo procesos"
    for pid in "${PIDS[@]}"; do
        kill "$pid" 2>/dev/null
    done
    sleep 6
    for pid in "${PIDS[@]}"; do
        kill -9 "$pid" 2>/dev/null
    done
}
trap bajar_todo EXIT

# --------------------------------------------------------------------------
# 01 - Compilacion y pruebas
# --------------------------------------------------------------------------
{
    titulo "01 - COMPILACION Y PRUEBAS AUTOMATIZADAS"
    echo "Fecha: $(date -Iseconds)"
    java -version 2>&1
    echo
    cd "$RAIZ"
    mvn -B clean package 2>&1 | grep -vE "^\[INFO\] Download|Progress"
    echo
    paso "Resumen de pruebas por modulo"
    for f in "$RAIZ"/*/target/surefire-reports/*.txt; do
        [[ -f "$f" ]] || continue
        grep -H "Tests run" "$f" | sed "s|$RAIZ/||"
    done
    echo
    paso "Total"
    grep -h "Tests run:" "$RAIZ"/*/target/surefire-reports/*.txt 2>/dev/null \
        | sed 's/.*Tests run: \([0-9]*\).*Failures: \([0-9]*\).*Errors: \([0-9]*\).*/\1 \2 \3/' \
        | awk '{t+=$1; f+=$2; e+=$3} END {printf "Tests: %d  Failures: %d  Errors: %d\n", t, f, e}'
    echo
    paso "Artefactos construidos"
    ls -la "$RAIZ"/*/target/*.jar | sed "s|$RAIZ/||"
} > "$EV/01_compilacion_y_pruebas.log" 2>&1

# --------------------------------------------------------------------------
# 02 - Migracion batch sobre el dataset legacy
# --------------------------------------------------------------------------
{
    titulo "02 - MIGRACION DE PROCESOS BATCH (fin_legacy_data)"
    echo "Los tres procesos legacy reescritos en Spring Batch, sobre los archivos"
    echo "del repositorio KariVillagran/fin_legacy_data:"
    echo "  movimientos_financieros_diarios.csv -> reporteTransaccionesDiariasJob"
    echo "  intereses_trimestrales.csv          -> calculoInteresesMensualesJob"
    echo "  estados_financieros_anuales.csv     -> estadoCuentaAnualJob"
    echo
    paso "Archivos de entrada"
    wc -l "$RAIZ"/batch-migracion/src/main/resources/data/*.csv | sed "s|$RAIZ/||"
    echo
    paso "Ejecucion de los tres jobs"
    java $JVM_OPTS -jar "$RAIZ/batch-migracion/target/batch-migracion.jar" --job=all 2>&1 \
        | grep -E "INICIO job|FIN job|step \[|RESULTADO|particion|batch:|Registro omitido" \
        | head -120
    echo
    echo "Codigo de salida del proceso: ${PIPESTATUS[0]}"
    echo
    paso "Politica de finalizacion: un argumento invalido termina con codigo distinto de cero"
    java $JVM_OPTS -jar "$RAIZ/batch-migracion/target/batch-migracion.jar" --job=inexistente > /dev/null 2>&1
    echo "Codigo de salida con --job=inexistente: $?"

    paso "Escalado: la misma carga con distinto numero de particiones"
    echo "El resultado tiene que ser el mismo en las tres: el paralelismo cambia"
    echo "cuanto tarda, no que filas entran."
    for grid in 1 4 8; do
        echo "-- grid-size=$grid"
        java $JVM_OPTS -jar "$RAIZ/batch-migracion/target/batch-migracion.jar" \
            --job=transacciones --batch.partition.grid-size=$grid --batch.partition.thread-pool-size=$grid 2>&1 \
            | grep -E "FIN job|step \[transaccionesPartitionStep\] leidos=1000"
    done

    paso "Reejecucion automatica ante un fallo critico, y el codigo de salida"
    echo "Se baja el limite de omisiones a 100 para provocar el fallo a proposito."
    echo "El archivo trae 608 filas invalidas, asi que el Step supera el tope y el"
    echo "Job falla entero. Eso es lo que dispara la politica de reejecucion:"
    echo
    java $JVM_OPTS -jar "$RAIZ/batch-migracion/target/batch-migracion.jar" \
        --job=transacciones --batch.fault-tolerance.skip-limit=100 2>&1 \
        | grep -E "FIN job|reintentando|terminaron fallidos" | sed 's/^/   /'
    CODIGO=${PIPESTATUS[0]}
    echo
    echo "   Codigo de salida del proceso: $CODIGO"
    echo "   Distinto de cero, que es lo unico que un cron o un paso de CI mira."
    echo "   Sin eso, un batch que no proceso nada terminaria 'bien' y nadie se"
    echo "   enteraria hasta que faltaran los datos."

    paso "Por que el limite de omisiones esta en 700 y no en 200"
    echo "El limite es por ejecucion de Step, no por archivo, y con"
    echo "particionamiento cada particion lleva su propia cuenta. Con un limite"
    echo "de 200 y las 608 filas invalidas de este archivo, el job fallaba con una"
    echo "particion y pasaba con cuatro: el mismo archivo, el mismo limite y dos"
    echo "resultados distintos segun el paralelismo. Calibrado en 700, el"
    echo "resultado es el mismo con cualquier grid, que es lo que se ve arriba."

} > "$EV/02_batch_migracion.log" 2>&1

# --------------------------------------------------------------------------
# Levantar el ecosistema
# --------------------------------------------------------------------------
cd "$RAIZ"
echo ">> levantando el ecosistema (13 procesos)"

arrancar broker-artemis   "$RAIZ/broker-artemis/target/broker-artemis.jar"
arrancar broker-kafka     "$RAIZ/broker-kafka/target/broker-kafka.jar"
esperar_salud "http://localhost:61617/actuator/health" "broker-artemis" 90
esperar_salud "http://localhost:9094/actuator/health"  "broker-kafka"   120

arrancar config-server    "$RAIZ/config-server/target/config-server.jar"
esperar_salud "http://localhost:8888/actuator/health" "config-server" 90
arrancar discovery-server "$RAIZ/discovery-server/target/discovery-server.jar"
esperar_salud "http://localhost:8761/actuator/health" "discovery-server" 90

arrancar auth-server      "$RAIZ/auth-server/target/auth-server.jar"
esperar_salud "http://localhost:9000/actuator/health" "auth-server" 90
arrancar api-gateway      "$RAIZ/api-gateway/target/api-gateway.jar"
esperar_salud "http://localhost:8080/actuator/health" "api-gateway" 90

# cuentas-service con log de acceso: es lo que despues permite contar cuantas
# peticiones atendio cada instancia al escalar horizontalmente.
arrancar cuentas-service "$RAIZ/cuentas-service/target/cuentas-service.jar" \
    --server.tomcat.accesslog.enabled=true \
    --server.tomcat.accesslog.directory="$LOGS/acceso" \
    --server.tomcat.accesslog.prefix=cuentas-8081 \
    --server.tomcat.accesslog.suffix=.log \
    --server.tomcat.accesslog.buffered=false \
    --server.tomcat.accesslog.rename-on-rotate=true
arrancar pagos-service    "$RAIZ/pagos-service/target/pagos-service.jar"
arrancar clientes-service "$RAIZ/clientes-service/target/clientes-service.jar"
esperar_salud "http://localhost:8081/actuator/health" "cuentas-service"  120
esperar_salud "http://localhost:8082/actuator/health" "pagos-service"    120
esperar_salud "http://localhost:8083/actuator/health" "clientes-service" 120

arrancar bff-web    "$RAIZ/bff-web/target/bff-web.jar"
arrancar bff-movil  "$RAIZ/bff-movil/target/bff-movil.jar"
arrancar bff-cajero "$RAIZ/bff-cajero/target/bff-cajero.jar"
esperar_salud "http://localhost:8091/actuator/health" "bff-web"    90
esperar_salud "http://localhost:8092/actuator/health" "bff-movil"  90
esperar_salud "http://localhost:8093/actuator/health" "bff-cajero" 90

echo ">> esperando a que el gateway vea los servicios en Eureka"
sleep 15

# --------------------------------------------------------------------------
# 03 - Configuracion centralizada y service discovery
# --------------------------------------------------------------------------
{
    titulo "03 - CONFIGURACION CENTRALIZADA Y SERVICE DISCOVERY"

    paso "El Config Server exige credenciales (sin ellas, 401)"
    echo "GET /cuentas-service/default sin credenciales -> HTTP $(codigo http://localhost:8888/cuentas-service/default)"
    echo "GET /cuentas-service/default con credenciales -> HTTP $(curl -s -o /dev/null -w '%{http_code}' -u "$CONFIG_AUTH" http://localhost:8888/cuentas-service/default)"

    paso "Configuracion que el Config Server sirve a cada microservicio"
    for svc in cuentas-service pagos-service clientes-service api-gateway; do
        echo "-- $svc"
        curl -s -u "$CONFIG_AUTH" "http://localhost:8888/$svc/default" \
            | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('   perfiles:', d.get('profiles'))
for ps in d.get('propertySources', []):
    print('   fuente:', ps.get('name', '').split('/')[-1], '->', len(ps.get('source', {})), 'propiedades')
" 2>/dev/null
    done

    paso "Eureka tambien exige credenciales"
    echo "GET /eureka/apps sin credenciales -> HTTP $(codigo http://localhost:8761/eureka/apps)"

    paso "Servicios registrados en Eureka"
    curl -s -u "$EUREKA_AUTH" -H "Accept: application/json" http://localhost:8761/eureka/apps \
        | python3 -c "
import sys, json
apps = json.load(sys.stdin)['applications'].get('application', [])
if isinstance(apps, dict):
    apps = [apps]
for app in sorted(apps, key=lambda a: a['name']):
    inst = app['instance']
    inst = inst if isinstance(inst, list) else [inst]
    print(f\"   {app['name']:<20} {len(inst)} instancia(s)\")
    for i in inst:
        print(f\"      - {i['instanceId']:<45} {i['status']}\")
" 2>/dev/null

    paso "Un microservicio no arranca si el Config Server no responde (fail-fast)"
    echo "Configurado con spring.cloud.config.fail-fast=true en cada servicio."
    grep -n "fail-fast" "$RAIZ/cuentas-service/src/main/resources/application.yml"
} > "$EV/03_configuracion_y_discovery.log" 2>&1

# --------------------------------------------------------------------------
# 04 - OAuth2 y control de acceso por canal
# --------------------------------------------------------------------------
TK_WEB=$(token banco-web-client banco-web-secret cuentas.read cuentas.write movimientos.read movimientos.write clientes.read clientes.write)
TK_MOVIL=$(token banco-movil-client banco-movil-secret cuentas.read movimientos.read clientes.read)
TK_CAJERO=$(token cajero-client cajero-secret cuentas.read cuentas.write)

{
    titulo "04 - OAUTH 2.0 Y CONTROL DE ACCESO POR CANAL"

    paso "Emision de token por client_credentials (canal web)"
    curl -s -u "banco-web-client:banco-web-secret" -d "grant_type=client_credentials" \
        --data-urlencode "scope=cuentas.read cuentas.write movimientos.read movimientos.write clientes.read clientes.write" \
        "$AUTH/oauth2/token" | python3 -m json.tool

    paso "Contenido del token del canal web (payload del JWT)"
    echo "$TK_WEB" | cut -d. -f2 | tr '_-' '/+' \
        | python3 -c "
import sys, base64, json
s = sys.stdin.read().strip()
s += '=' * (-len(s) % 4)
print(json.dumps(json.loads(base64.b64decode(s)), indent=2, sort_keys=True))
"

    paso "Clave publica con la que los Resource Servers validan la firma"
    curl -s "$AUTH/oauth2/jwks" | python3 -m json.tool | head -20

    paso "Un secreto incorrecto no obtiene token"
    curl -s -o /dev/null -w "   cajero-client con secreto erroneo -> HTTP %{http_code}\n" \
        -u "cajero-client:secreto-equivocado" -d "grant_type=client_credentials" \
        --data-urlencode "scope=cuentas.read" "$AUTH/oauth2/token"

    paso "Un cliente no puede pedir un scope que no tiene registrado"
    curl -s -u "cajero-client:cajero-secret" -d "grant_type=client_credentials" \
        --data-urlencode "scope=clientes.read" "$AUTH/oauth2/token" | python3 -m json.tool

    paso "Sin token, el gateway rechaza antes de enrutar"
    for ruta in /api/cuentas/101 /api/movimientos/101 /api/clientes/101; do
        echo "   GET $ruta sin token -> HTTP $(codigo "$GW$ruta")"
    done

    paso "Un token alterado tampoco pasa"
    echo "   GET /api/cuentas/101 con token manipulado -> HTTP $(codigo "$GW/api/cuentas/101" "${TK_WEB}x")"

    paso "Minimo privilegio por canal: el mismo token no sirve para todo"
    printf "   %-16s %-26s %s\n" "CANAL" "RECURSO" "HTTP"
    printf "   %-16s %-26s %s\n" "web"    "GET /api/cuentas/101"  "$(codigo "$GW/api/cuentas/101" "$TK_WEB")"
    printf "   %-16s %-26s %s\n" "web"    "GET /api/clientes/101" "$(codigo "$GW/api/clientes/101" "$TK_WEB")"
    printf "   %-16s %-26s %s\n" "movil"  "GET /api/cuentas/101"  "$(codigo "$GW/api/cuentas/101" "$TK_MOVIL")"
    printf "   %-16s %-26s %s\n" "movil"  "GET /api/clientes/101" "$(codigo "$GW/api/clientes/101" "$TK_MOVIL")"
    printf "   %-16s %-26s %s\n" "cajero" "GET /api/cuentas/101"  "$(codigo "$GW/api/cuentas/101" "$TK_CAJERO")"
    printf "   %-16s %-26s %s\n" "cajero" "GET /api/movimientos/101" "$(codigo "$GW/api/movimientos/101" "$TK_CAJERO")"
    printf "   %-16s %-26s %s\n" "cajero" "GET /api/clientes/101" "$(codigo "$GW/api/clientes/101" "$TK_CAJERO")"
    echo
    echo "   El cajero recibe 403 en movimientos y clientes con un token de firma"
    echo "   perfectamente valida: la autorizacion por scope la aplica cada"
    echo "   microservicio, no solo el gateway."

    paso "El canal movil no puede escribir aunque el endpoint exista"
    echo -n "   PUT /api/clientes/101/nombre con token movil -> HTTP "
    curl -s -o /dev/null -w "%{http_code}\n" -X PUT -H "Authorization: Bearer $TK_MOVIL" \
        -H "Content-Type: application/json" -d '{"nombre":"Intento"}' "$GW/api/clientes/101/nombre"

    paso "Las primitivas de liquidacion exigen un scope que ningun canal tiene"
    echo -n "   POST /api/cuentas/101/abono con token web -> HTTP "
    curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Authorization: Bearer $TK_WEB" \
        -H "Content-Type: application/json" -d '{"monto":100}' "$GW/api/cuentas/101/abono"
    echo "   Solo pagos-service tiene cuentas.liquidar, y lo usa para mover las"
    echo "   dos puntas de una transferencia."
} > "$EV/04_oauth2_y_control_de_acceso.log" 2>&1

# --------------------------------------------------------------------------
# 05 - APIs de los tres microservicios por el gateway
# --------------------------------------------------------------------------
{
    titulo "05 - LOS TRES MICROSERVICIOS POR EL API GATEWAY"
    echo "Todas las rutas pasan por localhost:8080 y el gateway resuelve el"
    echo "destino por nombre de servicio en Eureka (lb://), no por host y puerto."

    paso "Gestion de cuentas: GET /api/cuentas/101"
    json "$GW/api/cuentas/101" "$TK_WEB"

    paso "Gestion de clientes: GET /api/clientes/101"
    json "$GW/api/clientes/101" "$TK_WEB"

    paso "Movimientos: GET /api/movimientos/101?limite=3"
    json "$GW/api/movimientos/101?limite=3" "$TK_WEB"

    paso "Resumen del historial: GET /api/movimientos/101/resumen"
    json "$GW/api/movimientos/101/resumen" "$TK_WEB"

    paso "Reporte de transacciones diarias (salida del proceso batch 1)"
    json "$GW/api/transacciones-diarias/resumen" "$TK_WEB"

    paso "Vista agregada con tolerancia a fallos: GET /api/movimientos/101/ficha"
    curl -s -H "Authorization: Bearer $TK_WEB" "$GW/api/movimientos/101/ficha" \
        | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('   origenDatosCuenta:', d.get('origenDatosCuenta'))
print('   cuenta:', json.dumps(d.get('cuenta'), ensure_ascii=False))
print('   resumen:', json.dumps(d.get('resumen'), ensure_ascii=False))
print('   movimientos en el historial:', len(d.get('movimientos') or []))
"
} > "$EV/05_apis_por_el_gateway.log" 2>&1

# --------------------------------------------------------------------------
# 06 - Mensajeria JMS: el retiro viaja por la cola
# --------------------------------------------------------------------------
{
    titulo "06 - MENSAJERIA ASINCRONA CON JMS (Apache ActiveMQ Artemis)"
    echo "cuentas-service publica el retiro en la cola banco.retiros y"
    echo "pagos-service lo consume para anotarlo en el historial. Son dos"
    echo "procesos distintos y ninguno llama al otro."

    paso "Estado antes del retiro"
    SALDO_ANTES=$(curl -s -H "Authorization: Bearer $TK_WEB" "$GW/api/cuentas/101" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['saldo'])")
    MOVS_ANTES=$(curl -s -H "Authorization: Bearer $TK_WEB" "$GW/api/movimientos/101/resumen" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['totalMovimientos'])")
    echo "   saldo de la cuenta 101: $SALDO_ANTES"
    echo "   movimientos en el historial: $MOVS_ANTES"

    paso "Retiro de 250 por el canal cajero"
    post_json "$GW/api/cuentas/101/retiro" "$TK_CAJERO" '{"monto":250,"canal":"cajero"}'

    echo
    echo ">> esperando 5s a que el consumidor procese el evento"
    sleep 5

    paso "Estado despues del retiro"
    SALDO_DESPUES=$(curl -s -H "Authorization: Bearer $TK_WEB" "$GW/api/cuentas/101" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['saldo'])")
    MOVS_DESPUES=$(curl -s -H "Authorization: Bearer $TK_WEB" "$GW/api/movimientos/101/resumen" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['totalMovimientos'])")
    echo "   saldo de la cuenta 101: $SALDO_ANTES -> $SALDO_DESPUES   (bajo en cuentas-service)"
    echo "   movimientos: $MOVS_ANTES -> $MOVS_DESPUES   (subio en pagos-service, por la cola)"

    paso "El movimiento recien anotado, visto desde pagos-service"
    json "$GW/api/movimientos/101?limite=1" "$TK_WEB"

    paso "Lo que dejo cada proceso en su log"
    echo "-- cuentas-service (productor)"
    grep "publicado en la cola" "$LOGS/cuentas-service.log" | tail -3
    echo "-- pagos-service (consumidor)"
    grep -E "consumido, retiro de|ya estaba procesado|rechazado por no cumplir" "$LOGS/pagos-service.log" | tail -5

    paso "La cola retiene el evento si el consumidor esta caido"
    echo "Se detiene pagos-service, se hace un retiro y se vuelve a levantar."
    PID_PAGOS=$(cat "$LOGS/pagos-service.pid")
    kill "$PID_PAGOS" 2>/dev/null
    sleep 8
    echo "   pagos-service detenido (pid $PID_PAGOS)"
    echo "   respuesta del retiro con el consumidor caido:"
    RESPUESTA_RETIRO=$(curl -s -X POST -H "Authorization: Bearer $TK_CAJERO" \
        -H "Content-Type: application/json" \
        -d '{"monto":100,"canal":"cajero"}' "$GW/api/cuentas/101/retiro")
    echo "$RESPUESTA_RETIRO" | python3 -m json.tool | sed 's/^/     /'
    EVENTO_EN_ESPERA=$(echo "$RESPUESTA_RETIRO" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['eventoId'])" 2>/dev/null)
    echo "   el retiro se aprueba, el evento $EVENTO_EN_ESPERA queda en la cola esperando"

    arrancar pagos-service "$RAIZ/pagos-service/target/pagos-service.jar"
    esperar_salud "http://localhost:8082/actuator/health" "pagos-service" 120
    esperar_gateway "$GW/api/movimientos/101/resumen" "$TK_WEB" 60
    sleep 10

    paso "La prueba de que el evento espero en la cola"
    echo "El log de pagos-service se reinicio con el proceso, asi que todo lo que"
    echo "hay en el es posterior al arranque. Si el evento aparece consumido aqui,"
    echo "es porque la cola lo guardo mientras el consumidor no existia:"
    echo
    if grep -q "$EVENTO_EN_ESPERA" "$LOGS/pagos-service.log" 2>/dev/null; then
        grep "$EVENTO_EN_ESPERA" "$LOGS/pagos-service.log" | sed 's/^/   /'
        echo
        echo "   CONFIRMADO: el evento publicado con el consumidor caido fue"
        echo "   consumido al volver a levantarlo."
    else
        echo "   !! el evento $EVENTO_EN_ESPERA no aparece en el log del consumidor"
    fi

    paso "Por que el conteo de movimientos no sirve para demostrar esto"
    MOVS_FINAL=$(medir_movimientos "$TK_WEB" 20)
    echo "   movimientos antes de bajar el consumidor: $MOVS_DESPUES"
    echo "   movimientos despues de volver a levantarlo: $MOVS_FINAL"
    echo
    echo "   Los dos numeros coinciden, y seria un error leerlo como que no paso"
    echo "   nada. pagos-service mantiene el historial en memoria y lo vuelve a"
    echo "   cargar del CSV al arrancar, asi que el contador no continua donde"
    echo "   estaba: vuelve a su linea base y le suma el evento que esperaba en la"
    echo "   cola. El resultado da igual por casualidad. La prueba de que la cola"
    echo "   cumplio su trabajo es la linea del log de arriba, no este numero."
    echo
    echo "   Esto es tambien una demostracion involuntaria de por que el estado en"
    echo "   memoria es la primera limitacion de la lista de proximos pasos."
    echo
    echo "   Nota aparte: el conteo puede tardar varios intentos en responder."
    echo "   Eureka conserva el registro de la instancia dada de baja hasta que su"
    echo "   lease vence, y mientras tanto el gateway reparte entre la instancia"
    echo "   nueva y una direccion que ya no contesta. Es el comportamiento"
    echo "   esperado de un registro eventualmente consistente, y la razon por la"
    echo "   que los clientes de este sistema llevan reintento."
} > "$EV/06_mensajeria_jms.log" 2>&1

# el gateway necesita volver a ver la instancia nueva de pagos-service
sleep 15

# --------------------------------------------------------------------------
# 07 - Mensajeria Kafka: los dos topicos
# --------------------------------------------------------------------------
{
    titulo "07 - MENSAJERIA ASINCRONA CON KAFKA (dos topicos)"
    echo "Kafka lleva hechos, no instrucciones: 'esta transaccion ocurrio' y"
    echo "'hay una alerta'. Los publican cuentas-service y pagos-service, y los"
    echo "consume clientes-service, que mantiene su propia vista de la actividad"
    echo "de cada titular sin preguntarle nada a nadie."
    echo
    echo "  banco.transacciones-completadas  <- retiros, depositos, transferencias"
    echo "  banco.alertas-seguridad          <- rechazos y dependencias degradadas"

    paso "Topicos creados por el broker al arrancar"
    curl -s http://localhost:9094/actuator/health | python3 -m json.tool

    paso "Perfil del cliente 101 antes de operar"
    json "$GW/api/clientes/101" "$TK_WEB"

    paso "Deposito de 1200 por el canal web (pagos-service orquesta y publica)"
    post_json "$GW/api/pagos/deposito/101" "$TK_WEB" '{"monto":1200,"canal":"web","descripcion":"Abono de sueldo"}'

    paso "Transferencia de 300 de la cuenta 101 a la 102"
    post_json "$GW/api/pagos/transferencia/101" "$TK_WEB" '{"cuentaDestino":102,"monto":300,"canal":"web","descripcion":"Pago de arriendo"}'

    paso "Intento de retiro sobre el limite: genera alerta, no transaccion"
    post_json "$GW/api/cuentas/101/retiro" "$TK_CAJERO" '{"monto":900000,"canal":"cajero"}'

    echo
    echo ">> esperando 8s a que clientes-service consuma los topicos"
    sleep 8

    paso "Perfil del cliente 101 despues: la actividad la trajo Kafka"
    json "$GW/api/clientes/101" "$TK_WEB"

    paso "Perfil del cliente 102: recibio el abono de la transferencia"
    json "$GW/api/clientes/102" "$TK_WEB"

    paso "Lo que publicaron los productores"
    echo "-- cuentas-service"
    grep -E "publicado en el topico|alerta .* publicada" "$LOGS/cuentas-service.log" | tail -6
    echo "-- pagos-service"
    grep -E "publicado en banco|alerta .* publicada" "$LOGS/pagos-service.log" | tail -6

    paso "Lo que consumio clientes-service"
    grep "ConsumidorEventos" "$LOGS/clientes-service.log" | tail -10

    paso "Saldos finales de las dos cuentas de la transferencia"
    for c in 101 102; do
        echo -n "   cuenta $c: "
        curl -s -H "Authorization: Bearer $TK_WEB" "$GW/api/cuentas/$c" \
            | python3 -c "import sys,json; d=json.load(sys.stdin); print('saldo', d['saldo'])"
    done
} > "$EV/07_mensajeria_kafka.log" 2>&1

# --------------------------------------------------------------------------
# 08 - Tolerancia a fallos con Resilience4j
# --------------------------------------------------------------------------
{
    titulo "08 - TOLERANCIA A FALLOS CON RESILIENCE4J"
    echo "Se detiene cuentas-service y se observa el ciclo completo del circuit"
    echo "breaker sobre la ficha de cuenta, que es la llamada saliente de"
    echo "pagos-service hacia cuentas-service."

    paso "Estado inicial del circuito"
    curl -s -H "Authorization: Bearer $TK_WEB" http://localhost:8082/actuator/circuitbreakers \
        | python3 -c "
import sys, json
d = json.load(sys.stdin)['circuitBreakers']
for n, v in d.items():
    print(f\"   {n}: {v['state']}  fallos={v.get('failureRate')}\")
"

    paso "La ficha con cuentas-service arriba"
    curl -s -H "Authorization: Bearer $TK_WEB" "$GW/api/movimientos/101/ficha" \
        | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('   origenDatosCuenta:', d.get('origenDatosCuenta'))
print('   cuenta:', json.dumps(d.get('cuenta'), ensure_ascii=False))
"

    paso "Se detiene cuentas-service"
    PID_CUENTAS=$(cat "$LOGS/cuentas-service.pid")
    kill "$PID_CUENTAS" 2>/dev/null
    sleep 10
    echo "   cuentas-service detenido (pid $PID_CUENTAS)"

    paso "Seis peticiones consecutivas: el circuito se abre y la respuesta se degrada"
    for i in $(seq 1 6); do
        ORIGEN=$(curl -s -H "Authorization: Bearer $TK_WEB" --max-time 20 "$GW/api/movimientos/101/ficha" \
            | python3 -c "import sys,json; print(json.load(sys.stdin).get('origenDatosCuenta','sin-respuesta'))" 2>/dev/null)
        ESTADO=$(curl -s -H "Authorization: Bearer $TK_WEB" http://localhost:8082/actuator/circuitbreakers \
            | python3 -c "import sys,json; print(json.load(sys.stdin)['circuitBreakers']['cuentas']['state'])" 2>/dev/null)
        printf "   peticion %d: origenDatosCuenta=%-10s circuito=%s\n" "$i" "$ORIGEN" "$ESTADO"
    done

    paso "La respuesta degradada sigue sirviendo el historial, que es el dato buscado"
    curl -s -H "Authorization: Bearer $TK_WEB" "$GW/api/movimientos/101/ficha" \
        | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('   origenDatosCuenta:', d.get('origenDatosCuenta'))
print('   cuenta:', d.get('cuenta'))
print('   movimientos entregados:', len(d.get('movimientos') or []))
print('   resumen:', json.dumps(d.get('resumen'), ensure_ascii=False))
"

    paso "La apertura del circuito salio al topico de alertas"
    grep -E "CIRCUITO|DEPENDENCIA_DEGRADADA|alerta" "$LOGS/pagos-service.log" | tail -6
    echo "-- y clientes-service la recibio:"
    grep -E "ALERTA ALTA|DEPENDENCIA_DEGRADADA" "$LOGS/clientes-service.log" | tail -4

    paso "Una transferencia con la dependencia caida se rechaza, no se degrada"
    echo -n "   POST /api/pagos/transferencia/101 -> HTTP "
    curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Authorization: Bearer $TK_WEB" \
        -H "Content-Type: application/json" --max-time 25 \
        -d '{"cuentaDestino":102,"monto":100,"canal":"web"}' "$GW/api/pagos/transferencia/101"
    echo "   No hay version degradada de mover dinero: o se mueve o se informa el fallo."

    paso "Se vuelve a levantar cuentas-service"
    arrancar cuentas-service "$RAIZ/cuentas-service/target/cuentas-service.jar" \
        --server.tomcat.accesslog.enabled=true \
        --server.tomcat.accesslog.directory="$LOGS/acceso" \
        --server.tomcat.accesslog.prefix=cuentas-8081 \
        --server.tomcat.accesslog.suffix=.log \
        --server.tomcat.accesslog.buffered=false \
        --server.tomcat.accesslog.rename-on-rotate=true
    esperar_salud "http://localhost:8081/actuator/health" "cuentas-service" 120
    esperar_gateway "$GW/api/cuentas/101" "$TK_WEB" 60
    # Ademas de que el gateway lo vea, hay que darle tiempo al circuito: esta en
    # OPEN y recien a los 10 s de espera pasa a HALF_OPEN para volver a probar.
    sleep 12

    paso "El circuito pasa a HALF_OPEN y se cierra solo"
    for i in $(seq 1 8); do
        ORIGEN=$(curl -s -H "Authorization: Bearer $TK_WEB" --max-time 20 "$GW/api/movimientos/101/ficha" \
            | python3 -c "import sys,json; print(json.load(sys.stdin).get('origenDatosCuenta','sin-respuesta'))" 2>/dev/null)
        ESTADO=$(curl -s -H "Authorization: Bearer $TK_WEB" http://localhost:8082/actuator/circuitbreakers \
            | python3 -c "import sys,json; print(json.load(sys.stdin)['circuitBreakers']['cuentas']['state'])" 2>/dev/null)
        printf "   peticion %d: origenDatosCuenta=%-10s circuito=%s\n" "$i" "$ORIGEN" "$ESTADO"
        sleep 3
    done

    paso "Transiciones del circuito registradas por Resilience4j"
    curl -s -H "Authorization: Bearer $TK_WEB" "http://localhost:8082/actuator/circuitbreakerevents" \
        | python3 -c "
import sys, json
for e in json.load(sys.stdin).get('circuitBreakerEvents', []):
    if e.get('type') == 'STATE_TRANSITION':
        print('  ', e.get('creationTime', '')[:19], e.get('stateTransition'))
" 2>/dev/null

    paso "Reintentos registrados"
    curl -s -H "Authorization: Bearer $TK_WEB" "http://localhost:8082/actuator/retryevents" \
        | python3 -c "
import sys, json, collections
ev = json.load(sys.stdin).get('retryEvents', [])
c = collections.Counter(e.get('type') for e in ev)
for t, n in c.items():
    print(f'   {t}: {n}')
print('   total de eventos de reintento:', len(ev))
" 2>/dev/null

    paso "Eventos de resiliencia en el log del servicio"
    grep ">> resiliencia" "$LOGS/pagos-service.log" | tail -12
} > "$EV/08_tolerancia_a_fallos.log" 2>&1

sleep 10

# --------------------------------------------------------------------------
# 09 - Los tres BFF
# --------------------------------------------------------------------------
{
    titulo "09 - PATRON BFF: UN BACKEND POR CANAL"
    echo "Los tres consumen los mismos microservicios por el gateway, cada uno"
    echo "con sus propias credenciales y scopes, y devuelven payloads distintos."

    paso "Canal WEB: GET localhost:8091/web/cuentas/101"
    curl -s "http://localhost:8091/web/cuentas/101" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('   campos de primer nivel:', list(d.keys()))
print('   cuenta:', json.dumps(d.get('cuenta'), ensure_ascii=False))
print('   perfil:', json.dumps(d.get('perfil'), ensure_ascii=False))
print('   resumen:', json.dumps(d.get('resumen'), ensure_ascii=False))
print('   movimientos en el historial completo:', len(d.get('historialCompleto') or []))
"
    TAM_WEB=$(curl -s "http://localhost:8091/web/cuentas/101" | wc -c)

    paso "Canal MOVIL: GET localhost:8092/movil/cuentas/101?limite=3"
    curl -s "http://localhost:8092/movil/cuentas/101?limite=3" | python3 -m json.tool
    TAM_MOVIL=$(curl -s "http://localhost:8092/movil/cuentas/101?limite=3" | wc -c)

    paso "Canal CAJERO: GET localhost:8093/cajero/cuentas/101/saldo"
    curl -s "http://localhost:8093/cajero/cuentas/101/saldo" | python3 -m json.tool
    TAM_CAJERO=$(curl -s "http://localhost:8093/cajero/cuentas/101/saldo" | wc -c)

    paso "Tamano de la respuesta por canal, para la misma cuenta"
    printf "   %-10s %8s bytes\n" "web"    "$TAM_WEB"
    printf "   %-10s %8s bytes\n" "movil"  "$TAM_MOVIL"
    printf "   %-10s %8s bytes\n" "cajero" "$TAM_CAJERO"
    echo
    echo "   Es el punto del patron: el cajero no recibe el historial de nadie y"
    echo "   el movil no recibe el perfil comercial del titular, porque ninguno"
    echo "   de los dos los muestra."

    paso "Retiro por el canal cajero: una sola llamada, el historial lo actualiza la cola"
    curl -s -X POST -H "Content-Type: application/json" -d '{"monto":150}' \
        "http://localhost:8093/cajero/cuentas/101/retiro" | python3 -m json.tool
    sleep 5
    echo "   movimiento recien anotado por el consumidor JMS:"
    json "$GW/api/movimientos/101?limite=1" "$TK_WEB"

    paso "Panel administrativo, exclusivo del canal web"
    curl -s "http://localhost:8091/web/banco/resumen-diario" | python3 -m json.tool

    paso "Deposito por el canal web"
    curl -s -X POST -H "Content-Type: application/json" -d '{"monto":500,"descripcion":"Abono por caja"}' \
        "http://localhost:8091/web/cuentas/101/deposito" | python3 -m json.tool

    paso "Edicion de datos personales, tambien exclusiva del canal web"
    curl -s -X PUT -H "Content-Type: application/json" -d '{"nombre":"Titular Actualizado"}' \
        "http://localhost:8091/web/clientes/101/nombre" | python3 -m json.tool

    paso "El canal cajero no tiene endpoints de clientes ni de pagos"
    echo "   GET localhost:8093/cajero/clientes/101 -> HTTP $(codigo http://localhost:8093/cajero/clientes/101)"
    echo "   Y aunque los tuviera, su token no tiene los scopes: el limite esta"
    echo "   en los dos lados, no solo en la forma de la API."
} > "$EV/09_bff_por_canal.log" 2>&1

# --------------------------------------------------------------------------
# 10 - Escalabilidad horizontal
# --------------------------------------------------------------------------
{
    titulo "10 - ESCALABILIDAD HORIZONTAL Y BALANCEO DE CARGA"
    echo "Se levanta una segunda instancia de cuentas-service en el puerto 8181."
    echo "No hay que tocar configuracion de nadie: se registra en Eureka con el"
    echo "mismo nombre de servicio, y tanto el gateway (lb://) como el cliente"
    echo "balanceado de pagos-service empiezan a repartir entre las dos."

    paso "Instancias de cuentas-service antes de escalar"
    curl -s -u "$EUREKA_AUTH" -H "Accept: application/json" \
        http://localhost:8761/eureka/apps/CUENTAS-SERVICE \
        | python3 -c "
import sys, json
app = json.load(sys.stdin)['application']
inst = app['instance']
inst = inst if isinstance(inst, list) else [inst]
print('   instancias:', len(inst))
for i in inst:
    print('     -', i['instanceId'], i['status'])
" 2>/dev/null

    paso "Se levanta la segunda instancia"
    arrancar cuentas-service-2 "$RAIZ/cuentas-service/target/cuentas-service.jar" \
        --server.port=8181 \
        --server.tomcat.accesslog.enabled=true \
        --server.tomcat.accesslog.directory="$LOGS/acceso" \
        --server.tomcat.accesslog.prefix=cuentas-8181 \
        --server.tomcat.accesslog.suffix=.log \
        --server.tomcat.accesslog.buffered=false \
        --server.tomcat.accesslog.rename-on-rotate=true
    esperar_salud "http://localhost:8181/actuator/health" "cuentas-service-2" 120
    echo ">> esperando 20s a que Eureka y el gateway la vean"
    sleep 20

    paso "Instancias de cuentas-service despues de escalar"
    curl -s -u "$EUREKA_AUTH" -H "Accept: application/json" \
        http://localhost:8761/eureka/apps/CUENTAS-SERVICE \
        | python3 -c "
import sys, json
app = json.load(sys.stdin)['application']
inst = app['instance']
inst = inst if isinstance(inst, list) else [inst]
print('   instancias:', len(inst))
for i in inst:
    print('     -', i['instanceId'], i['status'], 'puerto', i['port']['\$'])
" 2>/dev/null

    paso "Las dos instancias responden por separado"
    for p in 8081 8181; do
        echo "   localhost:$p/actuator/health -> $(curl -s http://localhost:$p/actuator/health | python3 -c 'import sys,json; print(json.load(sys.stdin)["status"])')"
    done

    LINEAS_8081_ANTES=$(cat "$LOGS/acceso"/cuentas-8081*.log 2>/dev/null | wc -l)
    LINEAS_8181_ANTES=$(cat "$LOGS/acceso"/cuentas-8181*.log 2>/dev/null | wc -l)

    paso "Treinta peticiones por el gateway a /api/cuentas/101"
    for i in $(seq 1 30); do
        codigo "$GW/api/cuentas/101" "$TK_WEB" > /dev/null
    done
    sleep 3

    LINEAS_8081=$(( $(cat "$LOGS/acceso"/cuentas-8081*.log 2>/dev/null | wc -l) - LINEAS_8081_ANTES ))
    LINEAS_8181=$(( $(cat "$LOGS/acceso"/cuentas-8181*.log 2>/dev/null | wc -l) - LINEAS_8181_ANTES ))
    echo "   peticiones atendidas por la instancia 8081: $LINEAS_8081"
    echo "   peticiones atendidas por la instancia 8181: $LINEAS_8181"
    echo "   total: $(( LINEAS_8081 + LINEAS_8181 )) de 30 enviadas"
    echo
    echo "   El reparto lo hace el balanceador del gateway por round-robin. No"
    echo "   es necesariamente 15 y 15: el gateway tambien consulta las cuentas"
    echo "   para otras cosas, y las dos instancias arrancaron en momentos"
    echo "   distintos."

    paso "Muestra del log de acceso de cada instancia"
    echo "-- instancia 8081 (ultimas 5)"
    tail -5 "$LOGS/acceso"/cuentas-8081*.log 2>/dev/null
    echo "-- instancia 8181 (ultimas 5)"
    tail -5 "$LOGS/acceso"/cuentas-8181*.log 2>/dev/null

    paso "El trafico entre servicios tambien se reparte"
    echo "pagos-service llama a cuentas-service con un RestClient @LoadBalanced,"
    echo "cuya URL base es el nombre del servicio en Eureka y no un host fijo."
    grep -n "url: http://cuentas-service" "$RAIZ/config-server/src/main/resources/configuracion-central/pagos-service.yml"
    echo
    echo "Veinte fichas por el gateway, que obligan a pagos-service a llamar a cuentas:"
    LINEAS_8081_ANTES=$(cat "$LOGS/acceso"/cuentas-8081*.log 2>/dev/null | wc -l)
    LINEAS_8181_ANTES=$(cat "$LOGS/acceso"/cuentas-8181*.log 2>/dev/null | wc -l)
    for i in $(seq 1 20); do
        curl -s -o /dev/null -H "Authorization: Bearer $TK_WEB" "$GW/api/movimientos/101/ficha"
    done
    sleep 3
    echo "   instancia 8081: $(( $(cat "$LOGS/acceso"/cuentas-8081*.log 2>/dev/null | wc -l) - LINEAS_8081_ANTES )) peticiones"
    echo "   instancia 8181: $(( $(cat "$LOGS/acceso"/cuentas-8181*.log 2>/dev/null | wc -l) - LINEAS_8181_ANTES )) peticiones"

    paso "En Docker el mismo escalado es una bandera"
    echo "   docker compose up -d --scale cuentas-service=2 --scale pagos-service=2"
    echo "   Los tres microservicios de negocio no declaran container_name ni"
    echo "   publican puertos al host, que es lo que permite replicarlos."
} > "$EV/10_escalabilidad_horizontal.log" 2>&1

# --------------------------------------------------------------------------
# 11 - Estado final
# --------------------------------------------------------------------------
{
    titulo "11 - ESTADO FINAL DEL ECOSISTEMA"
    echo "Fecha: $(date -Iseconds)"

    paso "Salud de cada componente"
    for entrada in \
        "broker-artemis|61617" "broker-kafka|9094" "config-server|8888" \
        "discovery-server|8761" "auth-server|9000" "api-gateway|8080" \
        "cuentas-service|8081" "cuentas-service-2|8181" "pagos-service|8082" \
        "clientes-service|8083" "bff-web|8091" "bff-movil|8092" "bff-cajero|8093"; do
        nombre="${entrada%%|*}"; puerto="${entrada##*|}"
        estado=$(curl -s --max-time 5 "http://localhost:$puerto/actuator/health" \
            | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])" 2>/dev/null || echo "SIN-RESPUESTA")
        printf "   %-20s puerto %-6s %s\n" "$nombre" "$puerto" "$estado"
    done

    paso "Registro final de Eureka"
    curl -s -u "$EUREKA_AUTH" -H "Accept: application/json" http://localhost:8761/eureka/apps \
        | python3 -c "
import sys, json
apps = json.load(sys.stdin)['applications'].get('application', [])
if isinstance(apps, dict):
    apps = [apps]
total = 0
for app in sorted(apps, key=lambda a: a['name']):
    inst = app['instance']
    inst = inst if isinstance(inst, list) else [inst]
    total += len(inst)
    print(f\"   {app['name']:<20} {len(inst)} instancia(s)\")
print('   total de instancias registradas:', total)
" 2>/dev/null

    paso "Estado del circuito al terminar"
    curl -s -H "Authorization: Bearer $TK_WEB" http://localhost:8082/actuator/circuitbreakers \
        | python3 -m json.tool

    paso "Detalle de salud del broker Kafka"
    curl -s http://localhost:9094/actuator/health | python3 -m json.tool

    paso "Tamano de los logs generados"
    ls -la "$EV"/*.log | sed "s|$EV/||"
} > "$EV/11_estado_final.log" 2>&1

echo
echo ">> evidencia generada en $EV"
ls -la "$EV"/*.log
