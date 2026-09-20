#!/usr/bin/env bash
# Mide tiempos de respuesta y tamanos de payload por canal.
# Requiere los 5 servicios levantados.
# Uso:  bash evidencia/medir_rendimiento.sh

REPETICIONES=30
CUENTA=103
DIR="$(cd "$(dirname "$0")" && pwd)"

# Promedio de tiempo total (ms) sobre N peticiones, descartando la primera
# (calentamiento de la JVM y del pool de conexiones).
tiempo_medio() {
  local url=$1
  curl -s -o /dev/null "$url"   # calentamiento
  local total=0
  for _ in $(seq $REPETICIONES); do
    local t
    t=$(curl -s -o /dev/null -w "%{time_total}" "$url")
    total=$(echo "$total + $t" | bc -l)
  done
  # Se multiplica por 1000 ANTES de dividir: con scale bajo, dividir primero
  # trunca el resultado a cero antes de pasarlo a milisegundos.
  echo "scale=2; ($total * 1000) / $REPETICIONES" | bc -l
}

bytes() { curl -s -o /dev/null -w "%{size_download}" "$1"; }
bytes_gzip() { curl -s -o /dev/null -H "Accept-Encoding: gzip" -w "%{size_download}" "$1"; }

{
echo "================================================================"
echo " Rendimiento y consumo de recursos por canal"
echo " Cuenta de prueba: $CUENTA | promedio de $REPETICIONES peticiones"
echo " (se descarta una peticion previa de calentamiento de JVM)"
echo "================================================================"
echo

WEB_URL="http://localhost:8091/web/cuentas/$CUENTA"
MOVIL_URL="http://localhost:8092/movil/cuentas/$CUENTA"
CAJERO_URL="http://localhost:8093/cajero/cuentas/$CUENTA/saldo"

echo "### 1. Tiempo de respuesta y tamano por canal (endpoint principal)"
echo
printf "%-10s %-38s %12s %12s %12s\n" "Canal" "Endpoint" "Tiempo(ms)" "Bytes" "Bytes gzip"
printf "%-10s %-38s %12s %12s %12s\n" "----------" "--------------------------------------" "------------" "------------" "------------"
printf "%-10s %-38s %12s %12s %12s\n" "Web"    "/web/cuentas/$CUENTA"          "$(tiempo_medio "$WEB_URL")"    "$(bytes "$WEB_URL")"    "$(bytes_gzip "$WEB_URL")"
printf "%-10s %-38s %12s %12s %12s\n" "Movil"  "/movil/cuentas/$CUENTA"        "$(tiempo_medio "$MOVIL_URL")"  "$(bytes "$MOVIL_URL")"  "$(bytes_gzip "$MOVIL_URL")"
printf "%-10s %-38s %12s %12s %12s\n" "Cajero" "/cajero/cuentas/$CUENTA/saldo" "$(tiempo_medio "$CAJERO_URL")" "$(bytes "$CAJERO_URL")" "$(bytes_gzip "$CAJERO_URL")"
echo

WEB_B=$(bytes "$WEB_URL"); MOVIL_B=$(bytes "$MOVIL_URL"); CAJERO_B=$(bytes "$CAJERO_URL")
echo "Reduccion de payload movil respecto de web:  $(echo "scale=1; 100 - ($MOVIL_B*100/$WEB_B)" | bc)%"
echo "Reduccion de payload cajero respecto de web: $(echo "scale=1; 100 - ($CAJERO_B*100/$WEB_B)" | bc)%"
echo
WEB_GZ=$(bytes_gzip "$WEB_URL"); MOVIL_GZ=$(bytes_gzip "$MOVIL_URL")
echo "Efecto de la compresion:"
echo "  Web:   $WEB_B -> $WEB_GZ bytes ($(echo "scale=1; 100 - ($WEB_GZ*100/$WEB_B)" | bc)% menos)"
echo "  Movil: $MOVIL_B -> $MOVIL_GZ bytes ($(echo "scale=1; 100 - ($MOVIL_GZ*100/$MOVIL_B)" | bc)% menos)"
echo "  Cajero: compresion DESACTIVADA a proposito en este canal."
echo "    A 39 bytes, el encabezado fijo de gzip agrega mas bytes de los que"
echo "    ahorra (medido: 39 -> 65 bytes comprimido). Como las respuestas se"
echo "    emiten con Transfer-Encoding: chunked, Tomcat no puede aplicar el"
echo "    umbral min-response-size, asi que no basta con subirlo: la decision"
echo "    correcta para este canal es apagar la compresion."
echo

echo "### 2. Trafico interno BFF -> core (el recorte ocurre en el core)"
echo
FULL="http://localhost:8082/api/movimientos/$CUENTA"
CORTADO="http://localhost:8082/api/movimientos/$CUENTA?limite=5"
printf "%-52s %12s\n" "Peticion del BFF al servicio core" "Bytes"
printf "%-52s %12s\n" "----------------------------------------------------" "------------"
printf "%-52s %12s\n" "GET /api/movimientos/$CUENTA          (canal web)" "$(bytes "$FULL")"
printf "%-52s %12s\n" "GET /api/movimientos/$CUENTA?limite=5 (canal movil)" "$(bytes "$CORTADO")"
FULL_B=$(bytes "$FULL"); CORT_B=$(bytes "$CORTADO")
echo
echo "El canal movil ahorra $(echo "scale=1; 100 - ($CORT_B*100/$FULL_B)" | bc)% tambien en el trafico interno,"
echo "no solo en el payload que ve la app: el servicio core nunca llega a"
echo "serializar el historial completo para una peticion movil."
echo

echo "### 3. Tiempo de respuesta de los servicios core (referencia)"
echo
printf "%-52s %12s\n" "Endpoint core" "Tiempo(ms)"
printf "%-52s %12s\n" "----------------------------------------------------" "------------"
printf "%-52s %12s\n" "GET /api/cuentas/$CUENTA" "$(tiempo_medio "http://localhost:8081/api/cuentas/$CUENTA")"
printf "%-52s %12s\n" "GET /api/movimientos/$CUENTA" "$(tiempo_medio "$FULL")"
printf "%-52s %12s\n" "GET /api/movimientos/$CUENTA/resumen" "$(tiempo_medio "http://localhost:8082/api/movimientos/$CUENTA/resumen")"
echo
echo "Los tiempos del BFF incluyen sus llamadas a los servicios core; la"
echo "diferencia entre el tiempo de un BFF y el de los core que consume es"
echo "el costo de la agregacion y transformacion que agrega el patron."
} > "$DIR/07_rendimiento_y_payloads.log" 2>&1

cat "$DIR/07_rendimiento_y_payloads.log"
