#!/usr/bin/env bash
# Genera la evidencia de la ejecucion del sistema EN LA NUBE.
#
# Este script corre DENTRO de la instancia EC2, no en el equipo de desarrollo.
# Lo primero que registra es la identidad de la maquina —instancia, tipo, zona,
# IP publica— porque sin eso un log de contenedores es indistinguible del que
# sale en cualquier portatil, y lo que hay que demostrar aqui es justamente que
# el sistema corre fuera del equipo local.
#
# Uso, dentro de la instancia y desde la carpeta EFT_S9_Backend_Avanzado:
#     bash evidencia/generar_evidencia_nube.sh
#
# Deja siete logs en evidencia/nube/. Los pasos para llegar hasta aqui estan en
# la seccion 11 de despliegue.md.

set -u

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EV="$RAIZ/evidencia/nube"
mkdir -p "$EV"
rm -f "$EV"/*.log
cd "$RAIZ"

COMPOSE="docker compose -f docker-compose.yml -f docker-compose.nube.yml"
GW="http://localhost:8080"
AUTH="http://localhost:9000"
EUREKA_AUTH="banco-eureka:banco-eureka-secret"

titulo() { echo; echo "=============================================================="; echo "  $*"; echo "=============================================================="; }
paso()   { echo; echo "--- $* ---"; }

codigo() {
    local url="$1" tk="${2:-}"
    if [[ -n "$tk" ]]; then
        curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $tk" --max-time 20 "$url"
    else
        curl -s -o /dev/null -w "%{http_code}" --max-time 20 "$url"
    fi
}
json() { curl -s -H "Authorization: Bearer $2" --max-time 20 "$1" | python3 -m json.tool 2>/dev/null; }
post_json() {
    curl -s -X POST -H "Authorization: Bearer $2" -H "Content-Type: application/json" \
        -d "$3" --max-time 30 "$1" | python3 -m json.tool 2>/dev/null
}
token() {
    local id="$1" secreto="$2"; shift 2
    curl -s -u "$id:$secreto" -d "grant_type=client_credentials" \
        --data-urlencode "scope=$*" "$AUTH/oauth2/token" \
        | python3 -c "import sys,json; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null
}

# Metadatos de la instancia, por IMDSv2 (el servicio de metadatos de EC2).
# IMDSv2 exige pedir un token primero: es lo que impide que una peticion hecha
# desde fuera, por ejemplo a traves de un servidor mal configurado, alcance los
# metadatos de la maquina.
meta() {
    local ruta="$1"
    local tk
    tk=$(curl -s -X PUT "http://169.254.169.254/latest/api/token" \
            -H "X-aws-ec2-metadata-token-ttl-seconds: 120" --max-time 5 2>/dev/null)
    [[ -z "$tk" ]] && { echo "(no disponible)"; return; }
    curl -s -H "X-aws-ec2-metadata-token: $tk" \
        "http://169.254.169.254/latest/meta-data/$ruta" --max-time 5 2>/dev/null || echo "(no disponible)"
}

# Que un contenedor este healthy no basta para pedirle algo POR EL GATEWAY: el
# gateway enruta con la copia del registro de Eureka que tiene en memoria, y
# hasta refrescarla responde 503. Dormir un numero fijo de segundos funciona
# hasta que la maquina esta mas cargada; por eso se pregunta hasta que conteste.
esperar_gateway() {
    local url="$1" tk="$2" intentos="${3:-60}" i
    for ((i = 1; i <= intentos; i++)); do
        [[ "$(codigo "$url" "$tk")" == "200" ]] && { echo "$i"; return 0; }
        sleep 1
    done
    echo "-1"
    return 1
}

esperar_sanos() {
    local intentos="${1:-40}" i sanos total
    for ((i = 1; i <= intentos; i++)); do
        total=$($COMPOSE ps --services 2>/dev/null | grep -v kafka-init | wc -l)
        sanos=$($COMPOSE ps --format '{{.Service}} {{.Health}}' 2>/dev/null \
                | grep -v '^kafka-init' | grep -c healthy)
        echo "   intento $i: $sanos de $total contenedores healthy"
        [[ "$total" -gt 0 && "$sanos" -eq "$total" ]] && return 0
        sleep 15
    done
    return 1
}

# --------------------------------------------------------------------------
# 01 - La instancia
# --------------------------------------------------------------------------
{
    titulo "01 - LA INSTANCIA EN LA QUE CORRE TODO ESTO"
    echo "Fecha: $(date -Iseconds)"
    echo
    echo "Lo que sigue sale del servicio de metadatos de EC2 (169.254.169.254),"
    echo "que solo responde desde dentro de una instancia de AWS. Es la forma de"
    echo "distinguir este log del que saldria en cualquier equipo de desarrollo."

    paso "Identidad de la instancia"
    printf "   %-22s %s\n" "instance-id:"   "$(meta instance-id)"
    printf "   %-22s %s\n" "tipo:"          "$(meta instance-type)"
    printf "   %-22s %s\n" "zona:"          "$(meta placement/availability-zone)"
    printf "   %-22s %s\n" "region:"        "$(meta placement/region)"
    printf "   %-22s %s\n" "IP privada:"    "$(meta local-ipv4)"
    printf "   %-22s %s\n" "IP publica:"    "$(meta public-ipv4)"
    printf "   %-22s %s\n" "AMI:"           "$(meta ami-id)"

    paso "Recursos de la maquina"
    echo "   CPU:"
    lscpu 2>/dev/null | grep -E "^(Architecture|CPU\(s\)|Model name):" | sed 's/^/     /'
    echo "   Memoria:"
    free -h | sed 's/^/     /'
    echo "   Disco:"
    df -h / | sed 's/^/     /'

    paso "Sistema operativo y herramientas"
    cat /etc/os-release 2>/dev/null | grep -E "^(NAME|VERSION)=" | sed 's/^/   /'
    echo "   docker:  $(docker --version 2>/dev/null)"
    echo "   compose: $(docker compose version 2>/dev/null)"
    echo
    echo "   Nota: no hay JDK ni Maven instalados en esta maquina. La"
    echo "   compilacion ocurre dentro de la imagen, en la etapa de build del"
    echo "   Dockerfile multi-etapa:"
    which java mvn 2>/dev/null || echo "     (ni java ni mvn en el PATH, como se esperaba)"
} > "$EV/01_instancia_ec2.log" 2>&1

# --------------------------------------------------------------------------
# 02 - Construccion y orquestacion
# --------------------------------------------------------------------------
echo ">> 02 construyendo las imagenes en la instancia (tarda varios minutos)"
{
    titulo "02 - CONSTRUCCION Y ORQUESTACION EN LA NUBE"

    paso "Construccion de las imagenes dentro de la instancia"
    $COMPOSE build 2>&1 | tail -80

    paso "Imagenes construidas"
    docker image ls --filter "reference=banco-xyz/*" 2>&1

    paso "Levantando la orquestacion completa"
    $COMPOSE up -d 2>&1

    paso "Esperando a que todos reporten healthy"
    esperar_sanos 45 || echo "   !! no todos llegaron a healthy"

    paso "El gateway enrutando hacia cada microservicio"
    echo "Estar healthy no es lo mismo que ser alcanzable por el gateway: el"
    echo "gateway enruta con su copia del registro de Eureka, y hasta refrescarla"
    echo "responde 503. Se pregunta por una ruta de cada microservicio hasta que"
    echo "conteste 200."
    echo
    TK_SONDA=$(token banco-web-client banco-web-secret "cuentas.read movimientos.read clientes.read")
    for par in "cuentas-service|$GW/api/cuentas/101" \
               "pagos-service|$GW/api/movimientos/101/resumen" \
               "clientes-service|$GW/api/clientes/101"; do
        nombre="${par%%|*}"; ruta="${par#*|}"
        s=$(esperar_gateway "$ruta" "$TK_SONDA")
        if [[ "$s" == "-1" ]]; then
            printf "   %-18s !! el gateway sigue sin enrutar\n" "$nombre"
        else
            printf "   %-18s enrutando tras %ss\n" "$nombre" "$s"
        fi
    done

    paso "docker compose ps"
    $COMPOSE ps 2>&1

    paso "El contenedor de un solo uso que crea los topicos de Kafka"
    $COMPOSE logs kafka-init 2>&1 | tail -20

    paso "Consumo de recursos de la instancia con todo arriba"
    docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}" 2>&1
    echo
    free -h | sed 's/^/   /'

    paso "La red privada de la orquestacion"
    docker network ls --filter name=eft-banco-xyz 2>&1
    docker network inspect eft-banco-xyz_banco \
        --format '{{range .Containers}}{{.Name}} {{.IPv4Address}}{{println}}{{end}}' 2>&1

    paso "Servicios registrados en Eureka, consultado dentro de la red"
    $COMPOSE exec -T api-gateway curl -s -u "$EUREKA_AUTH" -H "Accept: application/json" \
        http://discovery-server:8761/eureka/apps 2>/dev/null \
        | python3 -c "
import sys, json
apps = json.load(sys.stdin)['applications'].get('application', [])
apps = [apps] if isinstance(apps, dict) else apps
for a in sorted(apps, key=lambda x: x['name']):
    inst = a['instance'] if isinstance(a['instance'], list) else [a['instance']]
    print('  ', a['name'], ':', len(inst), 'instancia(s)')
" 2>/dev/null
} > "$EV/02_orquestacion_en_ec2.log" 2>&1

TK_WEB=$(token banco-web-client banco-web-secret "cuentas.read cuentas.write movimientos.read movimientos.write clientes.read clientes.write")
TK_MOVIL=$(token banco-movil-client banco-movil-secret "cuentas.read movimientos.read clientes.read")
TK_CAJERO=$(token cajero-client cajero-secret "cuentas.read cuentas.write")

# --------------------------------------------------------------------------
# 03 - OAuth2 en la nube
# --------------------------------------------------------------------------
echo ">> 03 OAuth2 y control de acceso"
{
    titulo "03 - OAUTH 2.0 Y CONTROL DE ACCESO, SOBRE LA INSTANCIA EC2"

    paso "Token por client_credentials"
    curl -s -u "banco-web-client:banco-web-secret" -d "grant_type=client_credentials" \
        --data-urlencode "scope=cuentas.read cuentas.write movimientos.read movimientos.write clientes.read clientes.write" \
        "$AUTH/oauth2/token" | python3 -m json.tool

    paso "Contenido del token"
    echo "$TK_WEB" | cut -d. -f2 | tr '_-' '/+' | python3 -c "
import sys, base64, json
s = sys.stdin.read().strip(); s += '=' * (-len(s) % 4)
print(json.dumps(json.loads(base64.b64decode(s)), indent=2, sort_keys=True))"

    paso "Sin token, el gateway rechaza antes de enrutar"
    for r in /api/cuentas/101 /api/movimientos/101 /api/clientes/101; do
        echo "   GET $r sin token -> HTTP $(codigo "$GW$r")"
    done

    paso "Minimo privilegio por canal"
    printf "   %-10s %-28s %s\n" "CANAL" "RECURSO" "HTTP"
    for par in "web:$TK_WEB" "movil:$TK_MOVIL" "cajero:$TK_CAJERO"; do
        canal="${par%%:*}"; tk="${par#*:}"
        for r in /api/cuentas/101 /api/movimientos/101 /api/clientes/101; do
            printf "   %-10s %-28s %s\n" "$canal" "GET $r" "$(codigo "$GW$r" "$tk")"
        done
    done
    echo
    echo "   El cajero recibe 403 en movimientos y clientes con un token de firma"
    echo "   valida: la autorizacion por scope la aplica cada microservicio."

    paso "Las primitivas de liquidacion exigen un scope que ningun canal tiene"
    echo -n "   POST /api/cuentas/101/abono con token web -> HTTP "
    curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Authorization: Bearer $TK_WEB" \
        -H "Content-Type: application/json" -d '{"monto":100}' "$GW/api/cuentas/101/abono"
} > "$EV/03_oauth2_en_ec2.log" 2>&1

# --------------------------------------------------------------------------
# 04 - APIs, JMS y Kafka con los brokers en la nube
# --------------------------------------------------------------------------
echo ">> 04 APIs, JMS y Kafka"
{
    titulo "04 - APIS, MENSAJERIA JMS Y KAFKA, CON LOS BROKERS EN LA NUBE"
    echo "Los dos brokers corren como contenedores de esta instancia, no en"
    echo "ningun equipo local. Los microservicios se conectan a ellos por la red"
    echo "privada de la orquestacion."

    paso "Los brokers, vistos desde dentro de la red"
    $COMPOSE exec -T api-gateway curl -s http://broker-artemis:61617/actuator/health 2>/dev/null \
        | python3 -m json.tool 2>/dev/null
    echo "   topicos de Kafka:"
    $COMPOSE exec -T kafka /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 --describe 2>&1 | sed 's/^/     /'

    paso "Gestion de cuentas"
    json "$GW/api/cuentas/101" "$TK_WEB"

    paso "Reporte de transacciones diarias (salida del proceso batch 1)"
    json "$GW/api/transacciones-diarias/resumen" "$TK_WEB"

    paso "Estado antes del retiro"
    SALDO_ANTES=$(curl -s -H "Authorization: Bearer $TK_WEB" "$GW/api/cuentas/101" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['saldo'])")
    MOVS_ANTES=$(curl -s -H "Authorization: Bearer $TK_WEB" "$GW/api/movimientos/101/resumen" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['totalMovimientos'])")
    echo "   saldo: $SALDO_ANTES   movimientos: $MOVS_ANTES"

    paso "Retiro por el canal cajero: viaja por la cola JMS entre dos contenedores"
    post_json "$GW/api/cuentas/101/retiro" "$TK_CAJERO" '{"monto":250,"canal":"cajero"}'
    sleep 8
    SALDO_DESPUES=$(curl -s -H "Authorization: Bearer $TK_WEB" "$GW/api/cuentas/101" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['saldo'])")
    MOVS_DESPUES=$(curl -s -H "Authorization: Bearer $TK_WEB" "$GW/api/movimientos/101/resumen" \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['totalMovimientos'])")
    echo "   saldo:       $SALDO_ANTES -> $SALDO_DESPUES   (bajo en cuentas-service)"
    echo "   movimientos: $MOVS_ANTES -> $MOVS_DESPUES   (subio en pagos-service, por la cola)"

    paso "Perfil del cliente antes de las operaciones de Kafka"
    json "$GW/api/clientes/101" "$TK_WEB"

    paso "Deposito y transferencia: los dos publican en Kafka"
    post_json "$GW/api/pagos/deposito/101" "$TK_WEB" '{"monto":1200,"canal":"web","descripcion":"Abono de sueldo"}'
    post_json "$GW/api/pagos/transferencia/101" "$TK_WEB" '{"cuentaDestino":102,"monto":300,"canal":"web","descripcion":"Pago de arriendo"}'

    paso "Intento de retiro sobre el limite: genera alerta, no transaccion"
    post_json "$GW/api/cuentas/101/retiro" "$TK_CAJERO" '{"monto":900000,"canal":"cajero"}'

    sleep 10
    paso "Perfil del cliente 101 despues: la actividad la trajo Kafka"
    json "$GW/api/clientes/101" "$TK_WEB"
    paso "Perfil del cliente 102: recibio el abono de la transferencia"
    json "$GW/api/clientes/102" "$TK_WEB"

    paso "Lo que dejaron los contenedores en sus logs"
    echo "-- cuentas-service (productor JMS y Kafka)"
    $COMPOSE logs --no-log-prefix cuentas-service 2>&1 | grep "publicado en" | tail -4
    echo "-- pagos-service (consumidor JMS, productor Kafka)"
    $COMPOSE logs --no-log-prefix pagos-service 2>&1 | grep -E "consumido, retiro de|publicado en banco" | tail -4
    echo "-- clientes-service (consumidor Kafka)"
    $COMPOSE logs --no-log-prefix clientes-service 2>&1 | grep "ConsumidorEventos" | tail -6
} > "$EV/04_mensajeria_en_ec2.log" 2>&1

# --------------------------------------------------------------------------
# 05 - Tolerancia a fallos
# --------------------------------------------------------------------------
echo ">> 05 tolerancia a fallos (detiene y levanta un contenedor)"
{
    titulo "05 - TOLERANCIA A FALLOS SOBRE CONTENEDORES EN LA NUBE"

    paso "La ficha con cuentas-service arriba"
    curl -s -H "Authorization: Bearer $TK_WEB" "$GW/api/movimientos/101/ficha" \
        | python3 -c "import sys,json; d=json.load(sys.stdin); print('   origenDatosCuenta:', d['origenDatosCuenta']); print('   cuenta:', json.dumps(d.get('cuenta')))"

    paso "Se detiene el contenedor de cuentas-service"
    $COMPOSE stop cuentas-service 2>&1
    sleep 5

    paso "Seis peticiones: el circuito se abre y la respuesta se degrada"
    for i in $(seq 1 6); do
        ORIGEN=$(curl -s -H "Authorization: Bearer $TK_WEB" --max-time 25 "$GW/api/movimientos/101/ficha" \
            | python3 -c "import sys,json; print(json.load(sys.stdin).get('origenDatosCuenta','sin-respuesta'))" 2>/dev/null)
        ESTADO=$($COMPOSE exec -T pagos-service curl -s -H "Authorization: Bearer $TK_WEB" \
            http://localhost:8082/actuator/circuitbreakers 2>/dev/null \
            | python3 -c "import sys,json; print(json.load(sys.stdin)['circuitBreakers']['cuentas']['state'])" 2>/dev/null)
        printf "   peticion %d: origenDatosCuenta=%-10s circuito=%s\n" "$i" "$ORIGEN" "$ESTADO"
    done

    paso "La respuesta degradada sigue sirviendo el historial"
    curl -s -H "Authorization: Bearer $TK_WEB" "$GW/api/movimientos/101/ficha" \
        | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('   origenDatosCuenta:', d['origenDatosCuenta'])
print('   cuenta:', d.get('cuenta'))
print('   movimientos entregados:', len(d.get('movimientos') or []))"

    paso "Una transferencia con la dependencia caida se rechaza, no se degrada"
    echo -n "   POST /api/pagos/transferencia/101 -> HTTP "
    curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Authorization: Bearer $TK_WEB" \
        -H "Content-Type: application/json" --max-time 35 \
        -d '{"cuentaDestino":102,"monto":100,"canal":"web"}' "$GW/api/pagos/transferencia/101"
    echo "   No hay version degradada de mover dinero."

    paso "La apertura del circuito salio al topico de alertas"
    $COMPOSE logs --no-log-prefix clientes-service 2>&1 | grep "DEPENDENCIA_DEGRADADA" | tail -3

    paso "Se vuelve a levantar el contenedor"
    $COMPOSE start cuentas-service 2>&1
    sleep 60

    paso "El circuito pasa a HALF_OPEN y se cierra solo"
    for i in $(seq 1 8); do
        ORIGEN=$(curl -s -H "Authorization: Bearer $TK_WEB" --max-time 25 "$GW/api/movimientos/101/ficha" \
            | python3 -c "import sys,json; print(json.load(sys.stdin).get('origenDatosCuenta','sin-respuesta'))" 2>/dev/null)
        ESTADO=$($COMPOSE exec -T pagos-service curl -s -H "Authorization: Bearer $TK_WEB" \
            http://localhost:8082/actuator/circuitbreakers 2>/dev/null \
            | python3 -c "import sys,json; print(json.load(sys.stdin)['circuitBreakers']['cuentas']['state'])" 2>/dev/null)
        printf "   peticion %d: origenDatosCuenta=%-10s circuito=%s\n" "$i" "$ORIGEN" "$ESTADO"
        sleep 3
    done

    paso "Transiciones registradas por Resilience4j"
    $COMPOSE exec -T pagos-service curl -s -H "Authorization: Bearer $TK_WEB" \
        http://localhost:8082/actuator/circuitbreakerevents 2>/dev/null \
        | python3 -c "
import sys, json
for e in json.load(sys.stdin).get('circuitBreakerEvents', []):
    if e.get('type') == 'STATE_TRANSITION':
        print('  ', e.get('creationTime','')[:19], e.get('stateTransition'))" 2>/dev/null
} > "$EV/05_tolerancia_a_fallos_en_ec2.log" 2>&1

# --------------------------------------------------------------------------
# 06 - Escalabilidad horizontal en la nube
# --------------------------------------------------------------------------
echo ">> 06 escalado horizontal"
{
    titulo "06 - ESCALABILIDAD HORIZONTAL EN LA NUBE"

    paso "Instancias antes de escalar"
    $COMPOSE ps cuentas-service 2>&1

    paso "Escalando cuentas-service a dos replicas"
    $COMPOSE up -d --scale cuentas-service=2 2>&1
    echo "   esperando a que la replica nueva este sana y registrada"
    sleep 90

    paso "Contenedores despues de escalar"
    $COMPOSE ps 2>&1

    paso "Instancias registradas en Eureka"
    $COMPOSE exec -T api-gateway curl -s -u "$EUREKA_AUTH" -H "Accept: application/json" \
        http://discovery-server:8761/eureka/apps/CUENTAS-SERVICE 2>/dev/null \
        | python3 -c "
import sys, json
app = json.load(sys.stdin)['application']
inst = app['instance'] if isinstance(app['instance'], list) else [app['instance']]
print('   instancias:', len(inst))
for i in inst:
    print('     -', i['instanceId'], i['status'])" 2>/dev/null

    # Se mide el DELTA y no el acumulado: el log de acceso empieza con el
    # contenedor, y la replica original ya venia atendiendo peticiones de los
    # pasos anteriores. Sumar acumulados daria mas de treinta y el log se
    # leeria como si las cuentas no cuadraran.
    declare -A ANTES
    for c in $($COMPOSE ps -q cuentas-service); do
        ANTES[$c]=$(docker logs "$c" 2>&1 | grep -c "GET /cuentas/101")
    done

    paso "Treinta peticiones por el gateway, repartidas entre las replicas"
    for i in $(seq 1 30); do codigo "$GW/api/cuentas/101" "$TK_WEB" > /dev/null; done
    sleep 3
    echo "De esas treinta, cuantas atendio cada replica segun su log de acceso:"
    total=0
    for c in $($COMPOSE ps -q cuentas-service); do
        nombre=$(docker inspect --format '{{.Name}}' "$c" | sed 's|^/||')
        ahora=$(docker logs "$c" 2>&1 | grep -c "GET /cuentas/101")
        n=$(( ahora - ${ANTES[$c]:-0} ))
        total=$(( total + n ))
        printf "   %-45s %s peticiones\n" "$nombre" "$n"
    done
    echo "   total repartido entre las replicas: $total  (de 30 enviadas)"
    echo
    echo "   El reparto lo hace el balanceador del gateway sobre el registro de"
    echo "   Eureka. No hubo que tocar configuracion de ningun componente: basto"
    echo "   levantar la replica."

    paso "Consumo de la instancia con la replica adicional"
    docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" 2>&1
    free -h | sed 's/^/   /'

    paso "Volviendo a una replica"
    $COMPOSE up -d --scale cuentas-service=1 2>&1
} > "$EV/06_escalabilidad_en_ec2.log" 2>&1

# --------------------------------------------------------------------------
# 07 - Estado final
# --------------------------------------------------------------------------
echo ">> 07 estado final"
{
    titulo "07 - ESTADO FINAL DEL DESPLIEGUE EN LA NUBE"
    echo "Fecha: $(date -Iseconds)"
    echo "Instancia: $(meta instance-id)  tipo: $(meta instance-type)  zona: $(meta placement/availability-zone)"

    paso "Contenedores"
    $COMPOSE ps 2>&1

    paso "Salud de cada componente, consultada dentro de la red"
    for par in "config-server:8888" "discovery-server:8761" "auth-server:9000" \
               "api-gateway:8080" "cuentas-service:8081" "pagos-service:8082" \
               "clientes-service:8083" "broker-artemis:61617" \
               "bff-web:8091" "bff-movil:8092" "bff-cajero:8093"; do
        n="${par%%:*}"; p="${par##*:}"
        r=$($COMPOSE exec -T api-gateway curl -s --max-time 8 "http://$n:$p/actuator/health" 2>/dev/null)
        printf "   %-20s %s\n" "$n" "${r:-(sin respuesta)}"
    done

    paso "Registro final de Eureka"
    $COMPOSE exec -T api-gateway curl -s -u "$EUREKA_AUTH" -H "Accept: application/json" \
        http://discovery-server:8761/eureka/apps 2>/dev/null \
        | python3 -c "
import sys, json
apps = json.load(sys.stdin)['applications'].get('application', [])
apps = [apps] if isinstance(apps, dict) else apps
total = 0
for a in sorted(apps, key=lambda x: x['name']):
    inst = a['instance'] if isinstance(a['instance'], list) else [a['instance']]
    total += len(inst)
    print('  ', a['name'], ':', len(inst), 'instancia(s)')
print('   total de instancias registradas:', total)" 2>/dev/null

    paso "Imagenes construidas en esta instancia"
    docker image ls --filter "reference=banco-xyz/*" 2>&1

    paso "Uso de recursos al terminar"
    docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}" 2>&1
    echo
    free -h | sed 's/^/   /'
    df -h / | sed 's/^/   /'
} > "$EV/07_estado_final_en_ec2.log" 2>&1

echo
echo ">> evidencia de la nube generada en $EV"
ls -la "$EV"
echo
echo ">> El sistema queda corriendo. Para bajarlo:   $COMPOSE down"
echo ">> No olvide TERMINAR la instancia EC2 al final: se cobra por hora encendida."
