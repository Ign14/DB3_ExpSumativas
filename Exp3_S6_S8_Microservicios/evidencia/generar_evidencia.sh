#!/usr/bin/env bash
# Genera la evidencia de ejecucion de Exp3: compila, levanta los siete
# componentes en el orden correcto y ejercita el ecosistema completo, dejando la
# salida en los archivos numerados de esta carpeta.
#
# Pensado para ejecutarse sin Docker, con los jar directamente, para que la
# evidencia sea reproducible tambien en una maquina sin el demonio de Docker
# disponible. El equivalente con Docker esta documentado en el README (seccion de
# ejecucion con docker-compose).
#
# Uso:  bash evidencia/generar_evidencia.sh        (desde la raiz del proyecto)

set -u

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EV="$RAIZ/evidencia"
LOGS="$EV/logs"
mkdir -p "$LOGS"
cd "$RAIZ"

SERVICIOS_INFRA="config-server discovery-server broker-artemis"
# Credenciales de la infraestructura: las mismas que la configuracion central usa
# por defecto cuando no hay variables de entorno.
CRED_CONFIG="banco-config:banco-config-secret"
CRED_EUREKA="banco-eureka:banco-eureka-secret"
SERVICIOS_APP="auth-server cuentas-service movimientos-service api-gateway"
PIDS=""

titulo() { printf '\n========== %s ==========\n' "$1"; }

levantar() {
  local modulo="$1"; shift
  java "$@" -jar "$RAIZ/$modulo/target/$modulo.jar" > "$LOGS/$modulo.log" 2>&1 &
  PIDS="$PIDS $!"
  echo "$!" > "$LOGS/$modulo.pid"
  echo "  $modulo levantado (pid $!)"
}

esperar_salud() {
  local nombre="$1" url="$2" intentos=60
  printf '  esperando a %s ' "$nombre"
  while [ $intentos -gt 0 ]; do
    if curl -fsS "$url" > /dev/null 2>&1; then echo " listo"; return 0; fi
    printf '.'; sleep 2; intentos=$((intentos - 1))
  done
  echo " NO respondio"; return 1
}

# Tras levantar o reiniciar un microservicio, el gateway necesita un ciclo de
# refresco para verlo en su copia del registro de Eureka: hasta entonces la ruta
# lb:// responde 503. Se espera a que enrute antes de seguir.
esperar_ruta_gateway() {
  local url="$1" token="$2" intentos=30 codigo
  printf '  esperando a que el gateway vuelva a enrutar '
  while [ $intentos -gt 0 ]; do
    codigo=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "$url" 2>/dev/null)
    if [ "$codigo" = "200" ]; then echo " listo"; return 0; fi
    printf '.'; sleep 3; intentos=$((intentos - 1))
  done
  echo " sigue respondiendo $codigo"; return 1
}

bajar_todo() {
  echo
  echo "Bajando los servicios..."
  for pid in $PIDS; do kill "$pid" 2>/dev/null; done
  sleep 3
  for pid in $PIDS; do kill -9 "$pid" 2>/dev/null; done
}
trap bajar_todo EXIT

json() { python3 -m json.tool 2>/dev/null || cat; }

# curl devuelve 000 cuando no hay nadie escuchando: se traduce a algo legible.
codigo_http() {
  local codigo
  codigo=$(curl -s -o /dev/null -w '%{http_code}' "$1" 2>/dev/null)
  if [ "$codigo" = "000" ] || [ -z "$codigo" ]; then echo "sin respuesta (puerto cerrado)"; else echo "$codigo"; fi
}

# --------------------------------------------------------------------------
# 01 - Compilacion, pruebas y arranque
# --------------------------------------------------------------------------
{
titulo "Version de Java y Maven"
java -version 2>&1 | grep -v "JAVA_TOOL_OPTIONS"
./mvnw -v 2>&1 | grep -v "JAVA_TOOL_OPTIONS" | head -2

titulo "Compilacion del reactor y ejecucion de las pruebas"
./mvnw -B clean package 2>&1 | grep -E "Tests run|Building |BUILD|Reactor Summary|^\[INFO\] [a-z-]+ \.+ (SUCCESS|FAILURE)" | grep -v "Building jar"
} > "$EV/01_compilacion_y_pruebas.log" 2>&1
echo "01_compilacion_y_pruebas.log listo"

echo "Levantando infraestructura (config, discovery, broker)..."
for s in $SERVICIOS_INFRA; do levantar "$s"; done
esperar_salud "config-server"    "http://localhost:8888/actuator/health"
esperar_salud "discovery-server" "http://localhost:8761/actuator/health"
esperar_salud "broker-artemis"   "http://localhost:61617/actuator/health"

echo "Levantando auth-server y microservicios..."
for s in $SERVICIOS_APP; do levantar "$s"; done
esperar_salud "auth-server"         "http://localhost:9000/actuator/health"
esperar_salud "cuentas-service"     "http://localhost:8081/actuator/health"
esperar_salud "movimientos-service" "http://localhost:8082/actuator/health"
esperar_salud "api-gateway"         "http://localhost:8080/actuator/health"

# Los tokens se piden una sola vez y se reutilizan en todas las secciones.
TOKEN_WEB=$(curl -s -u banco-web-client:banco-web-secret \
  -d grant_type=client_credentials \
  -d 'scope=cuentas.read cuentas.write movimientos.read movimientos.write' \
  http://localhost:9000/oauth2/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
TOKEN_CAJERO=$(curl -s -u cajero-client:cajero-secret \
  -d grant_type=client_credentials -d 'scope=cuentas.read cuentas.write' \
  http://localhost:9000/oauth2/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
AUTH_WEB="Authorization: Bearer $TOKEN_WEB"
AUTH_CAJERO="Authorization: Bearer $TOKEN_CAJERO"

# El gateway resuelve las rutas lb:// desde su copia local del registro de
# Eureka. La configuracion central baja ese refresco a 5 segundos, asi que la
# espera es corta; aun asi se verifica consultando la ruta en vez de confiar en
# un sleep.
esperar_ruta_gateway "http://localhost:8080/api/cuentas/103" "$TOKEN_WEB"
esperar_ruta_gateway "http://localhost:8080/api/movimientos/103" "$TOKEN_WEB"

# --------------------------------------------------------------------------
# 02 - Configuracion centralizada y service discovery
# --------------------------------------------------------------------------
{
titulo "Configuracion que el Config Server entrega a cada microservicio"
for s in cuentas-service movimientos-service api-gateway auth-server; do
  echo "--- $s"
  curl -s -u "$CRED_CONFIG" "http://localhost:8888/$s/default" | json
done

titulo "Confirmacion en los logs de que la configuracion vino del Config Server"
grep -h "Fetching config from server\|Located environment" "$LOGS"/cuentas-service.log "$LOGS"/movimientos-service.log | head -6

titulo "Servicios registrados en Eureka"
curl -s -u "$CRED_EUREKA" -H "Accept: application/json" http://localhost:8761/eureka/apps | python3 -c "
import sys, json
apps = json.load(sys.stdin)['applications'].get('application', [])
apps = apps if isinstance(apps, list) else [apps]
for app in sorted(apps, key=lambda a: a['name']):
    inst = app['instance']
    for i in (inst if isinstance(inst, list) else [inst]):
        print(f\"{app['name']:<22} {i['ipAddr']}:{i['port']['\$']:<6} {i['status']}\")"

titulo "El gateway resuelve por nombre de servicio, no por host y puerto"
echo "Las rutas que el Config Server le entrega al gateway:"
curl -s -u "$CRED_CONFIG" http://localhost:8888/api-gateway/default | python3 -c "
import sys, json
fuentes = json.load(sys.stdin)['propertySources']
for fuente in fuentes:
    for clave, valor in sorted(fuente['source'].items()):
        if 'gateway.routes' in clave:
            print(f'{clave} = {valor}')"
echo
echo "El balanceador de carga del gateway resolviendo esos nombres:"
grep -hE "LoadBalancer|RoundRobin" "$LOGS"/api-gateway.log | tail -3
echo "(si no hay lineas, es que no hubo nada que reportar: la resolucion por nombre"
echo " solo deja traza cuando falla. Se ve funcionando en la seccion 04, donde las"
echo " peticiones a /api/** llegan a los microservicios sin que el gateway conozca"
echo " su host ni su puerto.)"
echo
curl -s http://localhost:8080/actuator/health | json
} > "$EV/02_configuracion_y_discovery.log" 2>&1
echo "02_configuracion_y_discovery.log listo"

# --------------------------------------------------------------------------
# 03 - OAuth2: emision del token y control de acceso
# --------------------------------------------------------------------------
{
titulo "Metadatos publicados por el servidor de autorizacion"
curl -s http://localhost:9000/.well-known/oauth-authorization-server | json

titulo "Token por client_credentials para el canal web"
RESPUESTA_TOKEN=$(curl -s -u banco-web-client:banco-web-secret -d grant_type=client_credentials \
  -d 'scope=cuentas.read cuentas.write movimientos.read movimientos.write' \
  http://localhost:9000/oauth2/token)
echo "$RESPUESTA_TOKEN" | json

titulo "Contenido de ese mismo JWT (claims decodificados)"
python3 - "$(echo "$RESPUESTA_TOKEN" | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")" <<'PY'
import base64, json, sys
carga = sys.argv[1].split('.')[1]
carga += '=' * (-len(carga) % 4)
print(json.dumps(json.loads(base64.urlsafe_b64decode(carga)), indent=2))
PY

titulo "Clave publica con la que los microservicios validan la firma"
curl -s http://localhost:9000/oauth2/jwks | json

titulo "Credenciales incorrectas: no se emite token"
curl -s -u banco-web-client:clave-equivocada -d grant_type=client_credentials \
  -d scope=cuentas.read http://localhost:9000/oauth2/token
echo

titulo "Scope no registrado para el cliente: se rechaza"
curl -s -u cajero-client:cajero-secret -d grant_type=client_credentials \
  -d scope=movimientos.read http://localhost:9000/oauth2/token
echo

titulo "La infraestructura tampoco esta abierta"
printf 'Config Server sin credenciales      -> %s\n' "$(codigo_http http://localhost:8888/movimientos-service/default)"
printf 'Config Server con credenciales      -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -u "$CRED_CONFIG" http://localhost:8888/movimientos-service/default)"
printf 'Eureka sin credenciales             -> %s\n' "$(codigo_http http://localhost:8761/eureka/apps)"
printf 'Eureka con credenciales             -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -u "$CRED_EUREKA" http://localhost:8761/eureka/apps)"
printf 'Actuator de resiliencia sin token   -> %s\n' "$(codigo_http http://localhost:8082/actuator/circuitbreakers)"
printf 'Actuator de resiliencia con token   -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH_WEB" http://localhost:8082/actuator/circuitbreakers)"
printf 'Salud (abierta para el healthcheck) -> %s\n' "$(codigo_http http://localhost:8082/actuator/health)"

titulo "Sin token: 401 tanto en el gateway como en el microservicio directo"
printf 'gateway  /api/cuentas/103            -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/cuentas/103)"
printf 'gateway  /api/movimientos/103        -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/movimientos/103)"
printf 'directo  cuentas-service:8081        -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/cuentas/103)"
printf 'directo  movimientos-service:8082    -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8082/movimientos/103)"

titulo "HEAD funciona igual que GET con el mismo scope"
printf 'HEAD /api/cuentas/103 -> %s\n' "$(curl -s -o /dev/null -I -w '%{http_code}' -H "$AUTH_WEB" http://localhost:8080/api/cuentas/103)"

titulo "Token manipulado: la firma no valida"
printf 'token alterado -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${TOKEN_WEB}xx" http://localhost:8080/api/cuentas/103)"

titulo "Token valido del canal web: acceso permitido"
printf 'GET /api/cuentas/103     -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH_WEB" http://localhost:8080/api/cuentas/103)"
printf 'GET /api/movimientos/103 -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH_WEB" http://localhost:8080/api/movimientos/103)"

titulo "Minimo privilegio: el token del cajero no sirve para leer movimientos"
printf 'cajero GET /api/cuentas/103     -> %s (tiene cuentas.read)\n' "$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH_CAJERO" http://localhost:8080/api/cuentas/103)"
printf 'cajero GET /api/movimientos/103 -> %s (no tiene movimientos.read)\n' "$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH_CAJERO" http://localhost:8080/api/movimientos/103)"
} > "$EV/03_oauth2_y_control_de_acceso.log" 2>&1
echo "03_oauth2_y_control_de_acceso.log listo"

# --------------------------------------------------------------------------
# 04 - APIs de los microservicios a traves del gateway
# --------------------------------------------------------------------------
{
titulo "Carga del dataset legacy en cada microservicio"
grep -h "filas leidas\|cuentas distintas\|historial cargado" "$LOGS"/cuentas-service.log "$LOGS"/movimientos-service.log

titulo "GET /api/cuentas (primeras 3 cuentas de las cargadas)"
curl -s -H "$AUTH_WEB" http://localhost:8080/api/cuentas | python3 -c "
import sys, json
cuentas = json.load(sys.stdin)
print(f'total de cuentas: {len(cuentas)}')
print(json.dumps(cuentas[:3], indent=2))"

titulo "GET /api/cuentas/103"
curl -s -H "$AUTH_WEB" http://localhost:8080/api/cuentas/103 | json

titulo "POST /api/cuentas/103/retiro (el septimo endpoint; el flujo completo esta en la seccion 05)"
curl -s -H "$AUTH_CAJERO" -H 'Content-Type: application/json' -d '{"monto":100,"canal":"web"}' \
  http://localhost:8080/api/cuentas/103/retiro | json

titulo "GET /api/cuentas/999999 (cuenta inexistente)"
curl -s -w '\nHTTP %{http_code}\n' -H "$AUTH_WEB" http://localhost:8080/api/cuentas/999999

titulo "GET /api/movimientos/103?limite=3"
curl -s -H "$AUTH_WEB" "http://localhost:8080/api/movimientos/103?limite=3" | json

titulo "GET /api/movimientos/103/resumen"
curl -s -H "$AUTH_WEB" http://localhost:8080/api/movimientos/103/resumen | json

titulo "GET /api/movimientos/103/ficha (agregacion entre los dos microservicios)"
curl -s -H "$AUTH_WEB" http://localhost:8080/api/movimientos/103/ficha | python3 -c "
import sys, json
f = json.load(sys.stdin)
f['movimientos'] = f['movimientos'][:2] + ['... recortado para la evidencia']
print(json.dumps(f, indent=2))"

titulo "Validaciones de parametros: limite fuera de rango y no numerico"
curl -s -w '\nHTTP %{http_code}\n' -H "$AUTH_WEB" "http://localhost:8080/api/movimientos/103?limite=-1"
curl -s -w '\nHTTP %{http_code}\n' -H "$AUTH_WEB" "http://localhost:8080/api/movimientos/103?limite=99"
curl -s -w '\nHTTP %{http_code}\n' -H "$AUTH_WEB" "http://localhost:8080/api/movimientos/103?limite=abc"

titulo "POST /api/movimientos: un registro valido se acepta con 201"
curl -s -w '\nHTTP %{http_code}\n' -X POST -H "$AUTH_WEB" -H 'Content-Type: application/json' \
  -d '{"cuentaId":103,"fecha":"15/06/2024","tipoMovimiento":"Dep\u00f3sito","monto":1200,"descripcion":"   "}' \
  http://localhost:8080/api/movimientos
echo "(la fecha llego en formato legacy y el tipo con tilde y mayuscula; la"
echo " descripcion venia en blanco. Los tres se normalizaron al registrar.)"

titulo "POST /api/movimientos con datos invalidos (misma validacion que la carga del CSV)"
curl -s -w '\nHTTP %{http_code}\n' -X POST -H "$AUTH_WEB" -H 'Content-Type: application/json' \
  -d '{"cuentaId":103,"fecha":"31/02/2024","tipoMovimiento":"deposito","monto":100,"descripcion":"fecha imposible"}' \
  http://localhost:8080/api/movimientos
curl -s -w '\nHTTP %{http_code}\n' -X POST -H "$AUTH_WEB" -H 'Content-Type: application/json' \
  -d '{"cuentaId":103,"fecha":"2024-05-05","tipoMovimiento":"transferencia","monto":100,"descripcion":"tipo fuera del dominio"}' \
  http://localhost:8080/api/movimientos
} > "$EV/04_apis_por_el_gateway.log" 2>&1
echo "04_apis_por_el_gateway.log listo"

# --------------------------------------------------------------------------
# 05 - Mensajeria asincrona: el retiro viaja por la cola
# --------------------------------------------------------------------------
{
titulo "Estado inicial de la cuenta 110"
echo "saldo:"; curl -s -H "$AUTH_WEB" http://localhost:8080/api/cuentas/110 | json
echo "resumen de movimientos:"; curl -s -H "$AUTH_WEB" http://localhost:8080/api/movimientos/110/resumen | json

titulo "POST /api/cuentas/110/retiro con 500 (canal cajero)"
curl -s -H "$AUTH_CAJERO" -H 'Content-Type: application/json' \
  -d '{"monto":500,"canal":"cajero"}' \
  http://localhost:8080/api/cuentas/110/retiro | json

echo
echo "esperando a que el consumidor procese el evento..."
sleep 3

titulo "Despues del retiro: el saldo bajo en cuentas-service"
curl -s -H "$AUTH_WEB" http://localhost:8080/api/cuentas/110 | json

titulo "Y el movimiento aparecio en movimientos-service, que nunca fue llamado por HTTP"
curl -s -H "$AUTH_WEB" "http://localhost:8080/api/movimientos/110?limite=1" | json
curl -s -H "$AUTH_WEB" http://localhost:8080/api/movimientos/110/resumen | json

titulo "Trazas de los dos extremos de la cola"
grep -h "evento" "$LOGS"/cuentas-service.log | tail -3
grep -h "evento" "$LOGS"/movimientos-service.log | tail -3

titulo "Rechazos de negocio: sobre el limite por operacion y sin fondos"
curl -s -H "$AUTH_CAJERO" -H 'Content-Type: application/json' \
  -d '{"monto":500001,"canal":"cajero"}' http://localhost:8080/api/cuentas/110/retiro | json
curl -s -H "$AUTH_CAJERO" -H 'Content-Type: application/json' \
  -d '{"monto":400000,"canal":"cajero"}' http://localhost:8080/api/cuentas/110/retiro | json

titulo "Un retiro rechazado no genera evento: el historial no cambio"
curl -s -H "$AUTH_WEB" http://localhost:8080/api/movimientos/110/resumen | json

titulo "Monto invalido: error del cliente, no rechazo de negocio"
curl -s -w '\nHTTP %{http_code}\n' -H "$AUTH_CAJERO" -H 'Content-Type: application/json' \
  -d '{"monto":-100,"canal":"cajero"}' http://localhost:8080/api/cuentas/110/retiro
} > "$EV/05_mensajeria_asincrona.log" 2>&1
echo "05_mensajeria_asincrona.log listo"

# --------------------------------------------------------------------------
# 06 - Tolerancia a fallos con Resilience4j
# --------------------------------------------------------------------------
{
titulo "Estado del circuito con cuentas-service arriba"
curl -s -H "$AUTH_WEB" http://localhost:8082/actuator/circuitbreakers | json

titulo "Ficha completa: los datos de cuenta vienen del servicio"
curl -s -H "$AUTH_WEB" http://localhost:8080/api/movimientos/110/ficha | python3 -c "
import sys, json
f = json.load(sys.stdin)
print('origenDatosCuenta:', f['origenDatosCuenta'])
print('cuenta:', json.dumps(f['cuenta']))
print('movimientos en el historial:', f['resumen']['totalMovimientos'])"

titulo "Se detiene cuentas-service para provocar el fallo"
kill "$(cat "$LOGS/cuentas-service.pid")" 2>/dev/null
sleep 4
printf 'cuentas-service responde: %s\n' "$(codigo_http http://localhost:8081/actuator/health)"

titulo "Lo que cuesta responder degradado, antes y despues de que el circuito se abra"
echo "El presupuesto de tiempo acota el PEOR caso: 2 s de TimeLimiter + 0,2 s de"
echo "espera + 2 s del reintento = 4,2 s. Ese peor caso es un servicio que acepta"
echo "la conexion y no contesta. Un servicio caido, como este, es el caso facil:"
echo "el sistema operativo rechaza la conexion de inmediato y cada intento falla"
echo "en milisegundos, asi que lo que se mide es basicamente la espera entre"
echo "reintentos. Con el circuito abierto se ahorra tambien esa espera, y sobre"
echo "todo se deja de molestar a un servicio que esta intentando arrancar."
printf 'circuito cerrado, reintentando: '
curl -s -o /dev/null -w '%{time_total} s\n' -H "$AUTH_WEB" http://localhost:8080/api/movimientos/110/ficha

titulo "Siete consultas seguidas con la dependencia caida"
for i in 1 2 3 4 5 6 7; do
  printf 'intento %s: ' "$i"
  curl -s -H "$AUTH_WEB" http://localhost:8080/api/movimientos/110/ficha | python3 -c "
import sys, json
f = json.load(sys.stdin)
print('origen =', f['origenDatosCuenta'], '| movimientos =', f['resumen']['totalMovimientos'],
      '| cuenta =', f['cuenta'])"
done

titulo "Con el circuito ya abierto, la misma consulta vuelve de inmediato"
printf 'circuito abierto, directo al fallback: '
curl -s -o /dev/null -w '%{time_total} s\n' -H "$AUTH_WEB" http://localhost:8080/api/movimientos/110/ficha

titulo "El circuito quedo abierto y las llamadas ya no salen a la red"
curl -s -H "$AUTH_WEB" http://localhost:8082/actuator/circuitbreakers | json

titulo "Transiciones registradas por Resilience4j"
curl -s -H "$AUTH_WEB" http://localhost:8082/actuator/circuitbreakerevents | python3 -c "
import sys, json
for e in json.load(sys.stdin)['circuitBreakerEvents']:
    if e.get('stateTransition'):
        print(e['creationTime'][11:19], e['stateTransition'])"

titulo "El historial propio sigue respondiendo normal: la degradacion es parcial"
curl -s -w '\nHTTP %{http_code}\n' -H "$AUTH_WEB" "http://localhost:8080/api/movimientos/110?limite=2"

titulo "Trazas del fallback"
grep -h "modo degradado" "$LOGS"/movimientos-service.log | tail -5

titulo "Se vuelve a levantar cuentas-service"
levantar cuentas-service
esperar_salud "cuentas-service" "http://localhost:8081/actuator/health"
echo "esperando el paso a HALF_OPEN (wait-duration-in-open-state = 10 s)..."
sleep 12

titulo "El circuito prueba, le resulta y se cierra solo"
for i in 1 2 3 4; do
  printf 'intento %s: ' "$i"
  curl -s -H "$AUTH_WEB" http://localhost:8080/api/movimientos/110/ficha | python3 -c "
import sys, json; print('origen =', json.load(sys.stdin)['origenDatosCuenta'])"
done
curl -s -H "$AUTH_WEB" http://localhost:8082/actuator/circuitbreakers | json

titulo "El gateway vuelve a enrutar hacia la instancia reiniciada"
esperar_ruta_gateway "http://localhost:8080/api/cuentas/110" "$TOKEN_WEB"
curl -s -o /dev/null -w 'GET /api/cuentas/110 -> %{http_code}\n' -H "$AUTH_WEB" http://localhost:8080/api/cuentas/110

titulo "Ciclo completo de transiciones"
curl -s -H "$AUTH_WEB" http://localhost:8082/actuator/circuitbreakerevents | python3 -c "
import sys, json
for e in json.load(sys.stdin)['circuitBreakerEvents']:
    if e.get('stateTransition'):
        print(e['creationTime'][11:19], e['stateTransition'])"

titulo "Reintentos y transiciones, tal como quedan en el log del servicio"
grep -hE "resiliencia: (REINTENTO|CIRCUITO|se agotaron)" "$LOGS"/movimientos-service.log | tail -12
} > "$EV/06_tolerancia_a_fallos.log" 2>&1
echo "06_tolerancia_a_fallos.log listo"

# --------------------------------------------------------------------------
# 07 - Resiliencia de la mensajeria: la cola retiene el evento
# --------------------------------------------------------------------------
{
titulo "Resumen de la cuenta 114 antes de nada"
curl -s -H "$AUTH_WEB" http://localhost:8080/api/movimientos/114/resumen | json

titulo "Se detiene movimientos-service, el consumidor de la cola"
kill "$(cat "$LOGS/movimientos-service.pid")" 2>/dev/null
sleep 4
printf 'movimientos-service responde: %s\n' "$(codigo_http http://localhost:8082/actuator/health)"

titulo "Un retiro con el consumidor caido: se aprueba igual y el evento se publica"
curl -s -H "$AUTH_CAJERO" -H 'Content-Type: application/json' \
  -d '{"monto":300,"canal":"cajero-sin-consumidor"}' \
  http://localhost:8080/api/cuentas/114/retiro | json

titulo "El saldo ya bajo, aunque el historial todavia no se puede consultar"
curl -s -H "$AUTH_WEB" http://localhost:8080/api/cuentas/114 | json

titulo "Vuelve movimientos-service y procesa el evento que estaba en la cola"
levantar movimientos-service
esperar_salud "movimientos-service" "http://localhost:8082/actuator/health"
sleep 5
curl -s -H "$AUTH_WEB" http://localhost:8080/api/movimientos/114/resumen | json
curl -s -H "$AUTH_WEB" "http://localhost:8080/api/movimientos/114?limite=1" | json
grep -h "evento" "$LOGS"/movimientos-service.log | tail -3
} > "$EV/07_cola_retiene_eventos.log" 2>&1
echo "07_cola_retiene_eventos.log listo"

# --------------------------------------------------------------------------
# 08 - Resumen final
# --------------------------------------------------------------------------
{
titulo "Estado de salud de los siete componentes"
for par in "config-server 8888" "discovery-server 8761" "broker-artemis 61617" \
           "auth-server 9000" "cuentas-service 8081" "movimientos-service 8082" "api-gateway 8080"; do
  set -- $par
  printf '%-22s %s\n' "$1" "$(curl -s "http://localhost:$2/actuator/health" | python3 -c "
import sys, json
try: print(json.load(sys.stdin)['status'])
except Exception: print('sin respuesta')")"
done

titulo "Registro final de Eureka"
curl -s -u "$CRED_EUREKA" -H "Accept: application/json" http://localhost:8761/eureka/apps | python3 -c "
import sys, json
apps = json.load(sys.stdin)['applications'].get('application', [])
apps = apps if isinstance(apps, list) else [apps]
for app in sorted(apps, key=lambda a: a['name']):
    inst = app['instance']
    for i in (inst if isinstance(inst, list) else [inst]):
        print(f\"{app['name']:<22} {i['ipAddr']}:{i['port']['\$']:<6} {i['status']}\")"

titulo "Broker: la cola de eventos"
grep -h "banco.retiros" "$LOGS"/broker-artemis.log | head -4
} > "$EV/08_estado_final.log" 2>&1
echo "08_estado_final.log listo"

echo
echo "Evidencia generada en $EV"
