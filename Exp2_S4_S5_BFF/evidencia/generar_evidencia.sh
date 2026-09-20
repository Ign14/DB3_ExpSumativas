#!/usr/bin/env bash
# Genera la evidencia de ejecucion del proyecto: ejercita todos los endpoints
# de los 2 servicios core y de los 3 BFF, mide tiempos de respuesta y tamanos
# de payload, y demuestra la coherencia entre canales.
#
# Requiere los 5 servicios levantados (ver README, seccion "Como ejecutar").
# Uso:  bash evidencia/generar_evidencia.sh

CORE_CUENTAS=http://localhost:8081
CORE_MOV=http://localhost:8082
WEB=http://localhost:8091
MOVIL=http://localhost:8092
CAJERO=http://localhost:8093

DIR="$(cd "$(dirname "$0")" && pwd)"

# Ejecuta una peticion mostrando el comando, el codigo HTTP y el cuerpo.
req() {
  local metodo=$1 url=$2 datos=$3
  if [ -n "$datos" ]; then
    echo "\$ curl -X $metodo '$url' -d '$datos'"
    curl -s -w "\n[HTTP %{http_code} | %{time_total}s | %{size_download} bytes]\n" \
         -X "$metodo" "$url" -H "Content-Type: application/json" -d "$datos" | head -60
  else
    echo "\$ curl '$url'"
    curl -s -w "\n[HTTP %{http_code} | %{time_total}s | %{size_download} bytes]\n" \
         "$url" | head -60
  fi
  echo
}

# ---------------------------------------------------------------------------
{
echo "================================================================"
echo " APIs de los servicios core (capa de datos del dataset legacy)"
echo " core-cuentas-service :8081  |  core-movimientos-service :8082"
echo "================================================================"
echo
echo "### core-cuentas-service"
echo
echo "--- Listado de cuentas (se muestra solo el total y las 2 primeras) ---"
echo "\$ curl '$CORE_CUENTAS/api/cuentas'"
curl -s "$CORE_CUENTAS/api/cuentas" | jq '{total: length, primeras: .[0:2]}'
echo
req GET "$CORE_CUENTAS/api/cuentas/103"
req GET "$CORE_CUENTAS/api/cuentas/999999"
echo "--- Debito directo sobre el core (cuenta 101, para no interferir con la demo de cajero) ---"
req PATCH "$CORE_CUENTAS/api/cuentas/101/debitar" '{"monto":1000}'
echo "--- Debito rechazado por monto invalido ---"
req PATCH "$CORE_CUENTAS/api/cuentas/101/debitar" '{"monto":-5}'
echo
echo "### core-movimientos-service"
echo
echo "--- Historial completo (se muestra el total y los 2 primeros) ---"
echo "\$ curl '$CORE_MOV/api/movimientos/103'"
curl -s "$CORE_MOV/api/movimientos/103" | jq '{total: length, primeros: .[0:2]}'
echo
echo "--- Historial recortado en origen (?limite=3) ---"
req GET "$CORE_MOV/api/movimientos/103?limite=3"
echo "--- Limite fuera de rango: se rechaza con 400, no con 500 ---"
req GET "$CORE_MOV/api/movimientos/103?limite=-1"
req GET "$CORE_MOV/api/movimientos/103/resumen"
req GET "$CORE_MOV/api/banco/transacciones-diarias/resumen"
} > "$DIR/02_apis_core.log" 2>&1

# ---------------------------------------------------------------------------
{
echo "================================================================"
echo " BFF Web (:8091) - payload completo para interfaces complejas"
echo " Agrega 3 llamadas a 2 servicios core en una sola respuesta"
echo "================================================================"
echo
echo "--- Cuenta completa: datos + historial entero + resumen ---"
echo "\$ curl '$WEB/web/cuentas/103'"
curl -s "$WEB/web/cuentas/103" | jq '{cuenta, movimientos_en_historial: (.historialCompleto|length), primeros_movimientos: .historialCompleto[0:3], resumen}'
echo
echo "--- Listado de cuentas (se muestra el total y las 2 primeras) ---"
echo "\$ curl '$WEB/web/cuentas'"
curl -s "$WEB/web/cuentas" | jq '{total: length, primeras: .[0:2]}'
echo
echo "--- Panel administrativo: resumen diario del banco (exclusivo del canal web) ---"
req GET "$WEB/web/banco/resumen-diario"
echo "--- Cuenta inexistente: el 404 del core se propaga como 404 del BFF ---"
req GET "$WEB/web/cuentas/999999"
} > "$DIR/03_bff_web.log" 2>&1

# ---------------------------------------------------------------------------
{
echo "================================================================"
echo " BFF Movil (:8092) - payload liviano para ahorrar ancho de banda"
echo " Sin nombre del titular, sin descripciones, solo los ultimos N"
echo "================================================================"
echo
req GET "$MOVIL/movil/cuentas/103"
echo "--- Con limite explicito (?limite=3): el recorte lo hace el core, no el BFF ---"
req GET "$MOVIL/movil/cuentas/103?limite=3"
echo "--- Limite fuera de rango: se rechaza con 400 y mensaje claro, no con un 500 ---"
req GET "$MOVIL/movil/cuentas/103?limite=-1"
req GET "$MOVIL/movil/cuentas/103?limite=999"
echo "--- Cuenta inexistente ---"
req GET "$MOVIL/movil/cuentas/999999"
} > "$DIR/04_bff_movil.log" 2>&1

# ---------------------------------------------------------------------------
{
echo "================================================================"
echo " BFF Cajero (:8093) - solo operaciones criticas"
echo " El retiro integra LOS DOS servicios core: debita el saldo en"
echo " core-cuentas-service y registra el movimiento en core-movimientos"
echo "================================================================"
echo
req GET "$CAJERO/cajero/cuentas/103/saldo"
echo "--- Retiro valido: se aprueba, debita y registra el movimiento ---"
req POST "$CAJERO/cajero/cuentas/103/retiro" '{"monto":500}'
echo "--- Saldo despues del retiro (refleja el debito) ---"
req GET "$CAJERO/cajero/cuentas/103/saldo"
echo "--- Rechazo por regla del CANAL: excede el limite por operacion (\$500.000) ---"
req POST "$CAJERO/cajero/cuentas/103/retiro" '{"monto":600000}'
echo "--- Rechazo por regla del DOMINIO: dentro del limite del canal, pero sin fondos ---"
req POST "$CAJERO/cajero/cuentas/103/retiro" '{"monto":400000}'
echo "--- Rechazo por monto invalido ---"
req POST "$CAJERO/cajero/cuentas/103/retiro" '{"monto":0}'
echo "--- Cuenta inexistente ---"
req GET "$CAJERO/cajero/cuentas/999999/saldo"
} > "$DIR/05_bff_cajero.log" 2>&1

# ---------------------------------------------------------------------------
{
echo "================================================================"
echo " Coherencia entre canales"
echo " Un retiro hecho en el CAJERO queda visible en los canales WEB y"
echo " MOVIL, porque el BFF de cajero registra el movimiento en el otro"
echo " servicio core ademas de debitar el saldo."
echo "================================================================"
echo
echo "--- ANTES del retiro ---"
echo -n "Saldo (cajero):                 "; curl -s "$CAJERO/cajero/cuentas/107/saldo" | jq -c .
echo -n "Movimientos en historial (web): "; curl -s "$WEB/web/cuentas/107" | jq '.historialCompleto | length'
echo -n "Resumen de retiros (web):       "; curl -s "$WEB/web/cuentas/107" | jq -c '.resumen | {cantidadMovimientos, totalRetiros}'
echo
echo "--- RETIRO de \$2.500 por cajero ---"
curl -s -X POST "$CAJERO/cajero/cuentas/107/retiro" -H "Content-Type: application/json" -d '{"monto":2500}' | jq .
echo
echo "--- DESPUES del retiro ---"
echo -n "Saldo (cajero):                 "; curl -s "$CAJERO/cajero/cuentas/107/saldo" | jq -c .
echo -n "Movimientos en historial (web): "; curl -s "$WEB/web/cuentas/107" | jq '.historialCompleto | length'
echo -n "Resumen de retiros (web):       "; curl -s "$WEB/web/cuentas/107" | jq -c '.resumen | {cantidadMovimientos, totalRetiros}'
echo
echo "Ultimo movimiento visto desde el canal MOVIL:"
curl -s "$MOVIL/movil/cuentas/107?limite=1" | jq .
} > "$DIR/06_coherencia_entre_canales.log" 2>&1

echo "Evidencia generada en $DIR"
