#!/usr/bin/env bash
# Genera toda la evidencia de ejecución: levanta los 5 servicios, ejercita
# cada endpoint de los 2 servicios core y de los 3 BFF, mide tiempos y
# tamaños de respuesta, y comprueba la coherencia entre canales.
#
# Uso (desde la raíz del proyecto, con el proyecto ya compilado):
#   bash evidencia/generar_evidencia.sh

set -u

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$RAIZ/evidencia"
LOGS="$DIR/logs"

CORE_CUENTAS=http://localhost:8081
CORE_MOV=http://localhost:8082
WEB=http://localhost:8091
MOVIL=http://localhost:8092
CAJERO=http://localhost:8093

CUENTA=103          # cuenta usada para las consultas
CUENTA_DEBITO=101   # cuenta usada para el débito directo al core
CUENTA_COHERENCIA=107
REPETICIONES=30

# --- 1. Arranque limpio de los 5 servicios -------------------------------
levantar_servicios() {
  echo "Deteniendo servicios previos..."
  for pid in $(jps 2>/dev/null | grep -v Jps | awk '{print $1}'); do kill "$pid" 2>/dev/null; done
  sleep 3

  rm -rf "$LOGS"; mkdir -p "$LOGS"
  cd "$RAIZ"

  echo "Levantando servicios core..."
  nohup java -jar core-cuentas-service/target/core-cuentas-service.jar > "$LOGS/core-cuentas-service.log" 2>&1 &
  nohup java -jar core-movimientos-service/target/core-movimientos-service.jar > "$LOGS/core-movimientos-service.log" 2>&1 &
  esperar "$CORE_CUENTAS/api/cuentas/$CUENTA"
  esperar "$CORE_MOV/api/movimientos/$CUENTA/resumen"

  echo "Levantando los 3 BFF..."
  nohup java -jar bff-web/target/bff-web.jar > "$LOGS/bff-web.log" 2>&1 &
  nohup java -jar bff-movil/target/bff-movil.jar > "$LOGS/bff-movil.log" 2>&1 &
  nohup java -jar bff-cajero/target/bff-cajero.jar > "$LOGS/bff-cajero.log" 2>&1 &
  esperar "$WEB/web/cuentas/$CUENTA"
  esperar "$MOVIL/movil/cuentas/$CUENTA"
  esperar "$CAJERO/cajero/cuentas/$CUENTA/saldo"
}

# Espera hasta que una URL responda 200, con tope de 60 segundos.
esperar() {
  for _ in $(seq 60); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "$1")" = "200" ]; then return 0; fi
    sleep 1
  done
  echo "TIMEOUT esperando $1" >&2
  return 1
}

# Ejecuta una petición mostrando el comando, el código HTTP y el cuerpo.
req() {
  local metodo=$1 url=$2 datos=${3:-}
  if [ -n "$datos" ]; then
    echo "\$ curl -X $metodo '$url' -d '$datos'"
    curl -s -w "\n[HTTP %{http_code} | %{time_total}s | %{size_download} bytes]\n" \
         -X "$metodo" "$url" -H "Content-Type: application/json" -d "$datos" | head -60
  else
    echo "\$ curl '$url'"
    curl -s -w "\n[HTTP %{http_code} | %{time_total}s | %{size_download} bytes]\n" "$url" | head -60
  fi
  echo
}

bytes() { curl -s -o /dev/null -w "%{size_download}" "$1"; }
bytes_gzip() { curl -s -o /dev/null -H "Accept-Encoding: gzip" -w "%{size_download}" "$1"; }

# Promedio en milisegundos, descartando una petición previa de calentamiento.
tiempo_medio() {
  local url=$1 total=0 t
  curl -s -o /dev/null "$url"
  for _ in $(seq $REPETICIONES); do
    t=$(curl -s -o /dev/null -w "%{time_total}" "$url")
    total=$(echo "$total + $t" | bc -l)
  done
  echo "scale=2; ($total * 1000) / $REPETICIONES" | bc -l
}

levantar_servicios

# --- 01. Arranque y suite de tests ---------------------------------------
{
echo "================================================================"
echo " Arranque de los 5 servicios (2 core + 3 BFF)"
echo "================================================================"
echo
for f in core-cuentas-service core-movimientos-service bff-web bff-movil bff-cajero; do
  echo "--- $f ---"
  grep -E "Starting [A-Z]|filas leídas|cuentas distintas|historial cargado|transacciones diarias|Tomcat started|Started [A-Z]" "$LOGS/$f.log"
  echo
done
echo "================================================================"
echo " Suite de tests automatizados (mvn test)"
echo "================================================================"
echo
cd "$RAIZ" && mvn test 2>&1 | grep -E "Running cl|Tests run:|BUILD"
} > "$DIR/01_arranque_y_tests.log" 2>&1

# --- 02. APIs de los servicios core --------------------------------------
{
echo "================================================================"
echo " APIs de los servicios core"
echo " core-cuentas-service :8081  |  core-movimientos-service :8082"
echo "================================================================"
echo
echo "### core-cuentas-service"
echo
echo "--- Listado de cuentas (total y las 2 primeras) ---"
echo "\$ curl '$CORE_CUENTAS/api/cuentas'"
curl -s "$CORE_CUENTAS/api/cuentas" | jq '{total: length, primeras: .[0:2]}'
echo
req GET "$CORE_CUENTAS/api/cuentas/$CUENTA"
req GET "$CORE_CUENTAS/api/cuentas/999999"
echo "--- Débito directo sobre el core (cuenta $CUENTA_DEBITO) ---"
req PATCH "$CORE_CUENTAS/api/cuentas/$CUENTA_DEBITO/debitar" '{"monto":1000}'
echo "--- Débito con monto inválido ---"
req PATCH "$CORE_CUENTAS/api/cuentas/$CUENTA_DEBITO/debitar" '{"monto":-5}'
echo
echo "### core-movimientos-service"
echo
echo "--- Historial completo (total y los 2 primeros) ---"
echo "\$ curl '$CORE_MOV/api/movimientos/$CUENTA'"
curl -s "$CORE_MOV/api/movimientos/$CUENTA" | jq '{total: length, primeros: .[0:2]}'
echo
echo "--- Historial recortado en origen (?limite=3) ---"
req GET "$CORE_MOV/api/movimientos/$CUENTA?limite=3"
echo "--- Limite fuera de rango ---"
req GET "$CORE_MOV/api/movimientos/$CUENTA?limite=-1"
req GET "$CORE_MOV/api/movimientos/$CUENTA/resumen"
req GET "$CORE_MOV/api/banco/transacciones-diarias/resumen"
echo "--- Registro de un movimiento nuevo ---"
req POST "$CORE_MOV/api/movimientos" '{"cuentaId":103,"fecha":"2025-02-01","tipoMovimiento":"deposito","monto":1000,"descripcion":"Prueba de registro"}'
echo "--- Registro rechazado: datos incompletos ---"
req POST "$CORE_MOV/api/movimientos" '{"cuentaId":103,"monto":1000}'
echo "--- Registro rechazado: fecha no interpretable ---"
req POST "$CORE_MOV/api/movimientos" '{"cuentaId":103,"fecha":"no-es-una-fecha","tipoMovimiento":"retiro","monto":1000,"descripcion":"x"}'
echo "--- Registro rechazado: tipo fuera de dominio ---"
req POST "$CORE_MOV/api/movimientos" '{"cuentaId":103,"fecha":"2025-02-01","tipoMovimiento":"transferencia","monto":1000,"descripcion":"x"}'
echo "--- Registro normalizado: tipo con tilde y fecha dd/MM/yyyy se guardan normalizados ---"
req POST "$CORE_MOV/api/movimientos" '{"cuentaId":103,"fecha":"01/02/2025","tipoMovimiento":"Dep\u00f3sito","monto":1000,"descripcion":"  "}'
} > "$DIR/02_apis_core.log" 2>&1

# --- 03. BFF Web ----------------------------------------------------------
{
echo "================================================================"
echo " BFF Web (:8091) - payload completo para interfaces complejas"
echo " Agrega 3 llamadas a 2 servicios core en una sola respuesta"
echo "================================================================"
echo
echo "--- Cuenta completa: datos + historial entero + resumen ---"
echo "\$ curl '$WEB/web/cuentas/$CUENTA'"
curl -s "$WEB/web/cuentas/$CUENTA" | jq '{cuenta, movimientos_en_historial: (.historialCompleto|length), primeros_movimientos: .historialCompleto[0:3], resumen}'
echo
echo "--- Listado de cuentas (total y las 2 primeras) ---"
echo "\$ curl '$WEB/web/cuentas'"
curl -s "$WEB/web/cuentas" | jq '{total: length, primeras: .[0:2]}'
echo
echo "--- Panel administrativo, exclusivo de este canal ---"
req GET "$WEB/web/banco/resumen-diario"
echo "--- Cuenta inexistente: el 404 del core se propaga como 404 del BFF ---"
req GET "$WEB/web/cuentas/999999"
} > "$DIR/03_bff_web.log" 2>&1

# --- 04. BFF Móvil --------------------------------------------------------
{
echo "================================================================"
echo " BFF Movil (:8092) - payload liviano"
echo " Sin nombre del titular, sin descripciones, solo los ultimos N"
echo "================================================================"
echo
req GET "$MOVIL/movil/cuentas/$CUENTA"
echo "--- Con limite explicito: el recorte lo hace el servicio core ---"
req GET "$MOVIL/movil/cuentas/$CUENTA?limite=3"
echo "--- Limite fuera de rango ---"
req GET "$MOVIL/movil/cuentas/$CUENTA?limite=-1"
req GET "$MOVIL/movil/cuentas/$CUENTA?limite=999"
echo "--- Limite con tipo incorrecto ---"
req GET "$MOVIL/movil/cuentas/$CUENTA?limite=abc"
echo "--- Cuenta inexistente ---"
req GET "$MOVIL/movil/cuentas/999999"
} > "$DIR/04_bff_movil.log" 2>&1

# --- 05. BFF Cajero -------------------------------------------------------
{
echo "================================================================"
echo " BFF Cajero (:8093) - solo operaciones criticas"
echo " El retiro integra los dos servicios core: debita el saldo y"
echo " registra el movimiento resultante"
echo "================================================================"
echo
req GET "$CAJERO/cajero/cuentas/$CUENTA/saldo"
echo "--- Retiro valido ---"
req POST "$CAJERO/cajero/cuentas/$CUENTA/retiro" '{"monto":500}'
echo "--- Saldo despues del retiro ---"
req GET "$CAJERO/cajero/cuentas/$CUENTA/saldo"
echo "--- Rechazo por regla del CANAL: excede el limite por operacion ---"
req POST "$CAJERO/cajero/cuentas/$CUENTA/retiro" '{"monto":600000}'
echo "--- Rechazo por regla del DOMINIO: dentro del limite, pero sin fondos ---"
req POST "$CAJERO/cajero/cuentas/$CUENTA/retiro" '{"monto":400000}'
echo "--- Rechazo por monto invalido ---"
req POST "$CAJERO/cajero/cuentas/$CUENTA/retiro" '{"monto":0}'
echo "--- Cuenta inexistente ---"
req GET "$CAJERO/cajero/cuentas/999999/saldo"
} > "$DIR/05_bff_cajero.log" 2>&1

# --- 06. Coherencia entre canales ----------------------------------------
{
echo "================================================================"
echo " Coherencia entre canales"
echo " Un retiro hecho en el CAJERO queda visible en los canales WEB y"
echo " MOVIL, porque el BFF de cajero registra el movimiento en el"
echo " servicio de movimientos ademas de debitar el saldo."
echo "================================================================"
echo
echo "--- ANTES del retiro ---"
echo -n "Saldo (cajero):                  "; curl -s "$CAJERO/cajero/cuentas/$CUENTA_COHERENCIA/saldo" | jq -c .
echo -n "Movimientos en historial (web):  "; curl -s "$WEB/web/cuentas/$CUENTA_COHERENCIA" | jq '.historialCompleto | length'
echo -n "Resumen (web):                   "; curl -s "$WEB/web/cuentas/$CUENTA_COHERENCIA" | jq -c '.resumen | {cantidadMovimientos, totalEgresos}'
echo
echo "--- RETIRO de 2500 por cajero ---"
curl -s -X POST "$CAJERO/cajero/cuentas/$CUENTA_COHERENCIA/retiro" -H "Content-Type: application/json" -d '{"monto":2500}' | jq .
echo
echo "--- DESPUES del retiro ---"
echo -n "Saldo (cajero):                  "; curl -s "$CAJERO/cajero/cuentas/$CUENTA_COHERENCIA/saldo" | jq -c .
echo -n "Movimientos en historial (web):  "; curl -s "$WEB/web/cuentas/$CUENTA_COHERENCIA" | jq '.historialCompleto | length'
echo -n "Resumen (web):                   "; curl -s "$WEB/web/cuentas/$CUENTA_COHERENCIA" | jq -c '.resumen | {cantidadMovimientos, totalEgresos}'
echo
echo "El mismo retiro visto desde el canal MOVIL:"
curl -s "$MOVIL/movil/cuentas/$CUENTA_COHERENCIA?limite=1" | jq .
} > "$DIR/06_coherencia_entre_canales.log" 2>&1

# --- 07. Rendimiento ------------------------------------------------------
# Se mide al final y sobre una cuenta que ninguna prueba anterior modificó,
# para que las cifras sean reproducibles.
CUENTA_PERF=110
WEB_URL="$WEB/web/cuentas/$CUENTA_PERF"
MOVIL_URL="$MOVIL/movil/cuentas/$CUENTA_PERF"
CAJERO_URL="$CAJERO/cajero/cuentas/$CUENTA_PERF/saldo"
MOV_COMPLETO="$CORE_MOV/api/movimientos/$CUENTA_PERF"
MOV_CORTADO="$CORE_MOV/api/movimientos/$CUENTA_PERF?limite=5"

{
echo "================================================================"
echo " Rendimiento y consumo de recursos por canal"
echo " Cuenta $CUENTA_PERF | promedio de $REPETICIONES peticiones"
echo "================================================================"
echo
echo "### 1. Tiempo de respuesta y tamano por canal"
echo
printf "%-10s %-34s %12s %12s %12s\n" "Canal" "Endpoint" "Tiempo(ms)" "Bytes" "Bytes gzip"
printf "%-10s %-34s %12s %12s %12s\n" "----------" "----------------------------------" "------------" "------------" "------------"
printf "%-10s %-34s %12s %12s %12s\n" "Web"    "/web/cuentas/$CUENTA_PERF"          "$(tiempo_medio "$WEB_URL")"    "$(bytes "$WEB_URL")"    "$(bytes_gzip "$WEB_URL")"
printf "%-10s %-34s %12s %12s %12s\n" "Movil"  "/movil/cuentas/$CUENTA_PERF"        "$(tiempo_medio "$MOVIL_URL")"  "$(bytes "$MOVIL_URL")"  "$(bytes_gzip "$MOVIL_URL")"
printf "%-10s %-34s %12s %12s %12s\n" "Cajero" "/cajero/cuentas/$CUENTA_PERF/saldo" "$(tiempo_medio "$CAJERO_URL")" "$(bytes "$CAJERO_URL")" "$(bytes_gzip "$CAJERO_URL")"
echo

WEB_B=$(bytes "$WEB_URL"); MOVIL_B=$(bytes "$MOVIL_URL"); CAJERO_B=$(bytes "$CAJERO_URL")
WEB_GZ=$(bytes_gzip "$WEB_URL"); MOVIL_GZ=$(bytes_gzip "$MOVIL_URL")
echo "Reduccion de payload movil respecto de web:  $(echo "scale=1; 100 - ($MOVIL_B*100/$WEB_B)" | bc)%"
echo "Reduccion de payload cajero respecto de web: $(echo "scale=1; 100 - ($CAJERO_B*100/$WEB_B)" | bc)%"
echo
echo "Compresion:"
echo "  Web:    $WEB_B -> $WEB_GZ bytes ($(echo "scale=1; 100 - ($WEB_GZ*100/$WEB_B)" | bc)% menos)"
echo "  Movil:  $MOVIL_B -> $MOVIL_GZ bytes ($(echo "scale=1; 100 - ($MOVIL_GZ*100/$MOVIL_B)" | bc)% menos)"
echo "  Cajero: desactivada. A este tamano de respuesta el encabezado de gzip"
echo "          agrega mas bytes de los que ahorra."
echo
echo "### 2. Trafico interno BFF -> core"
echo
printf "%-50s %12s\n" "Peticion del BFF al servicio core" "Bytes"
printf "%-50s %12s\n" "--------------------------------------------------" "------------"
printf "%-50s %12s\n" "GET /api/movimientos/$CUENTA_PERF (canal web)" "$(bytes "$MOV_COMPLETO")"
printf "%-50s %12s\n" "GET /api/movimientos/$CUENTA_PERF?limite=5 (canal movil)" "$(bytes "$MOV_CORTADO")"
FULL_B=$(bytes "$MOV_COMPLETO"); CORT_B=$(bytes "$MOV_CORTADO")
echo
echo "El canal movil ahorra $(echo "scale=1; 100 - ($CORT_B*100/$FULL_B)" | bc)% tambien en el trafico interno:"
echo "el servicio core nunca serializa el historial completo para una"
echo "peticion movil."
echo
echo "### 3. Tiempo de respuesta de los servicios core (referencia)"
echo
printf "%-50s %12s\n" "Endpoint core" "Tiempo(ms)"
printf "%-50s %12s\n" "--------------------------------------------------" "------------"
printf "%-50s %12s\n" "GET /api/cuentas/$CUENTA_PERF" "$(tiempo_medio "$CORE_CUENTAS/api/cuentas/$CUENTA_PERF")"
printf "%-50s %12s\n" "GET /api/movimientos/$CUENTA_PERF" "$(tiempo_medio "$MOV_COMPLETO")"
printf "%-50s %12s\n" "GET /api/movimientos/$CUENTA_PERF/resumen" "$(tiempo_medio "$CORE_MOV/api/movimientos/$CUENTA_PERF/resumen")"
echo
echo "La diferencia entre el tiempo de un BFF y el de los servicios core que"
echo "consume es el costo de la agregacion y transformacion por canal."
} > "$DIR/07_rendimiento_y_payloads.log" 2>&1

echo "Evidencia generada en $DIR"
ls -1 "$DIR"/*.log
