# Instrucciones para ejecutar y probar cada componente

**Evaluación Final Transversal — Desarrollo Backend III (PBY2203)**
Banco XYZ · Ignacio Miño Astorga

Este documento está escrito para que cualquier persona pueda levantar el sistema
y comprobar que cada pieza funciona, sin conocer el código. Hay dos caminos
completos: con los jar, que no necesita Docker, y con contenedores.

---

## 0. Requisitos

| Herramienta | Versión | Para qué |
|---|---|---|
| JDK | 21 o superior | Compilar y ejecutar |
| Maven | 3.9 o superior (o el wrapper `./mvnw`) | Compilar |
| `curl` | cualquiera | Probar los endpoints |
| `python3` | 3.8 o superior | Formatear las respuestas JSON de los ejemplos |
| Docker Desktop | 4.x | Sólo para el camino de contenedores |

El proyecto compila con `release 21`, así que el bytecode es idéntico en
cualquier JDK más nuevo. Si la máquina tiene JDK 24, funciona sin cambios.

Comprobar antes de empezar:

```bash
java -version
mvn -version
docker --version    # sólo si se va a usar el camino de contenedores
```

---

## 1. Compilar y correr las pruebas

```bash
cd EFT_S9_Backend_Avanzado
mvn clean package
```

Qué esperar: unos tres a cinco minutos la primera vez (descarga dependencias) y
menos de un minuto después. Al final, `BUILD SUCCESS` y quince jar en
`*/target/`.

Las pruebas corren solas dentro de ese comando. Para verlas por separado:

```bash
mvn test
```

Qué esperar: **168 pruebas, 0 fallos, 0 errores**. Si alguna falla, el detalle
queda en `*/target/surefire-reports/`.

Para correr las pruebas de un módulo:

```bash
mvn test -pl pagos-service
mvn test -pl clientes-service
```

---

## 2. Parte 1 — Los procesos batch

Los tres procesos se ejecutan y terminan. No quedan residentes y no necesitan
que nada más esté levantado.

```bash
# Los tres en secuencia
java -jar batch-migracion/target/batch-migracion.jar --job=all

# Uno por uno
java -jar batch-migracion/target/batch-migracion.jar --job=transacciones
java -jar batch-migracion/target/batch-migracion.jar --job=intereses
java -jar batch-migracion/target/batch-migracion.jar --job=cuentasAnuales
```

### Qué hay que ver

Al final de cada job, un resumen como este:

```
<< FIN job [estadoCuentaAnualJob] estado=COMPLETED duracion=766 ms
   - step [cuentasAnualesPartitionStep] leidos=1000 escritos=604 omitidos(...)=0/396/0 estado=COMPLETED
   - step [movimientoWorkerStep:particion0] leidos=250 escritos=151 ...
   - step [movimientoWorkerStep:particion1] leidos=250 escritos=161 ...
```

Lo que confirma que funciona:

- **`leidos=1000`**: leyó el archivo completo.
- **`omitidos` distinto de cero**: encontró las filas sucias del dataset y las
  omitió en vez de caerse. Cada omisión queda registrada con su motivo
  (`monto inválido: '-500'`, `fecha no interpretable`, etc.).
- **`estado=COMPLETED`**: el job terminó bien pese a las omisiones.
- **Cuatro `movimientoWorkerStep:particionN`**: el archivo se procesó en cuatro
  particiones en paralelo, no en serie.

### Probar el escalado

La misma carga con distinto paralelismo:

```bash
java -jar batch-migracion/target/batch-migracion.jar --job=transacciones \
     --batch.partition.grid-size=1 --batch.partition.thread-pool-size=1

java -jar batch-migracion/target/batch-migracion.jar --job=transacciones \
     --batch.partition.grid-size=8 --batch.partition.thread-pool-size=8
```

Comparar la `duracion` del `FIN job` entre las dos corridas, y el número de
`particionN` en el detalle.

### Probar la política de finalización

```bash
java -jar batch-migracion/target/batch-migracion.jar --job=inexistente
echo $?      # en Linux/macOS
echo $LASTEXITCODE   # en PowerShell
```

Debe imprimir **1**. Un batch que no hizo lo que se le pidió no puede terminar
con código 0, porque el código de salida es la única señal que mira un cron o un
paso de integración continua.

---

## 3. Parte 3 y 2 — El ecosistema completo, con los jar

### 3.1 La forma rápida

Un script levanta todo en el orden correcto, ejercita el sistema completo, deja
once logs de evidencia y lo baja al terminar:

```bash
bash evidencia/generar_evidencia.sh
```

Qué esperar: entre doce y veinte minutos. Al final, los logs numerados en
`evidencia/` y la salida de cada proceso en `evidencia/logs/`.

En Windows, desde Git Bash o WSL. PowerShell no ejecuta este script.

### 3.2 La forma manual, paso a paso

El orden importa: un microservicio que arranca antes que el Config Server no
obtiene su configuración, y uno que arranca antes que Eureka no se registra.

Abrir una terminal por servicio, o lanzarlos en segundo plano. **Esperar a que
cada grupo responda `UP` antes de pasar al siguiente.**

```bash
# --- Grupo 1: los brokers ---
java -jar broker-artemis/target/broker-artemis.jar &
java -jar broker-kafka/target/broker-kafka.jar &

# comprobar (el de Kafka tarda más, hasta un minuto)
curl -s localhost:61617/actuator/health
curl -s localhost:9094/actuator/health

# --- Grupo 2: configuración y descubrimiento ---
java -jar config-server/target/config-server.jar &
java -jar discovery-server/target/discovery-server.jar &

curl -s localhost:8888/actuator/health
curl -s localhost:8761/actuator/health

# --- Grupo 3: autorización y gateway ---
java -jar auth-server/target/auth-server.jar &
java -jar api-gateway/target/api-gateway.jar &

curl -s localhost:9000/actuator/health
curl -s localhost:8080/actuator/health

# --- Grupo 4: los tres microservicios de negocio ---
java -jar cuentas-service/target/cuentas-service.jar &
java -jar pagos-service/target/pagos-service.jar &
java -jar clientes-service/target/clientes-service.jar &

curl -s localhost:8081/actuator/health
curl -s localhost:8082/actuator/health
curl -s localhost:8083/actuator/health

# --- Grupo 5: los tres BFF ---
java -jar bff-web/target/bff-web.jar &
java -jar bff-movil/target/bff-movil.jar &
java -jar bff-cajero/target/bff-cajero.jar &

curl -s localhost:8091/actuator/health
curl -s localhost:8092/actuator/health
curl -s localhost:8093/actuator/health
```

Cada respuesta debe ser `{"status":"UP",...}`.

Después del grupo 4, **esperar unos quince segundos** antes de probar el
gateway: el gateway necesita que Eureka le informe los servicios nuevos.

Para bajar todo:

```bash
pkill -f "target/.*\.jar"
```

---

## 4. Comprobar la configuración centralizada y el descubrimiento

```bash
# El Config Server pide credenciales: sin ellas, 401
curl -s -o /dev/null -w "%{http_code}\n" localhost:8888/cuentas-service/default

# Con credenciales, devuelve la configuración
curl -s -u banco-config:banco-config-secret \
     localhost:8888/cuentas-service/default | python3 -m json.tool | head -30

# Eureka también pide credenciales
curl -s -o /dev/null -w "%{http_code}\n" localhost:8761/eureka/apps

# Servicios registrados
curl -s -u banco-eureka:banco-eureka-secret -H "Accept: application/json" \
     localhost:8761/eureka/apps | python3 -m json.tool | grep -E '"name"|instanceId'
```

La consola de Eureka también se puede abrir en el navegador:
<http://localhost:8761> (usuario `banco-eureka`, clave `banco-eureka-secret`).
Deben aparecer `API-GATEWAY`, `AUTH-SERVER`, `CUENTAS-SERVICE`,
`PAGOS-SERVICE` y `CLIENTES-SERVICE`.

---

## 5. Comprobar OAuth 2.0 y el control de acceso

### 5.1 Pedir un token por canal

```bash
# Canal web: los seis scopes
TOKEN_WEB=$(curl -s -u banco-web-client:banco-web-secret \
  -d grant_type=client_credentials \
  --data-urlencode "scope=cuentas.read cuentas.write movimientos.read movimientos.write clientes.read clientes.write" \
  localhost:9000/oauth2/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

# Canal móvil: sólo lectura
TOKEN_MOVIL=$(curl -s -u banco-movil-client:banco-movil-secret \
  -d grant_type=client_credentials \
  --data-urlencode "scope=cuentas.read movimientos.read clientes.read" \
  localhost:9000/oauth2/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

# Canal cajero: saldo y retiro
TOKEN_CAJERO=$(curl -s -u cajero-client:cajero-secret \
  -d grant_type=client_credentials \
  --data-urlencode "scope=cuentas.read cuentas.write" \
  localhost:9000/oauth2/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

echo "${#TOKEN_WEB} ${#TOKEN_MOVIL} ${#TOKEN_CAJERO}"   # tres números de ~800
```

Para ver lo que lleva dentro un token:

```bash
echo "$TOKEN_WEB" | cut -d. -f2 | tr '_-' '/+' | python3 -c \
"import sys,base64,json; s=sys.stdin.read().strip(); s+='='*(-len(s)%4); print(json.dumps(json.loads(base64.b64decode(s)),indent=2))"
```

Debe mostrar `iss`, `aud`, `exp` y la lista de `scope`.

### 5.2 Lo que debe fallar

```bash
# Sin token
curl -s -o /dev/null -w "sin token: %{http_code}\n" localhost:8080/api/cuentas/101

# Token alterado
curl -s -o /dev/null -w "token alterado: %{http_code}\n" \
     -H "Authorization: Bearer ${TOKEN_WEB}x" localhost:8080/api/cuentas/101

# Secreto incorrecto
curl -s -o /dev/null -w "secreto malo: %{http_code}\n" \
     -u cajero-client:equivocado -d grant_type=client_credentials \
     --data-urlencode "scope=cuentas.read" localhost:9000/oauth2/token

# Un cliente pidiendo un scope que no tiene registrado
curl -s -u cajero-client:cajero-secret -d grant_type=client_credentials \
     --data-urlencode "scope=clientes.read" localhost:9000/oauth2/token
```

Las tres primeras deben devolver **401**, y la última un error
`invalid_scope`.

### 5.3 Mínimo privilegio por canal

Esta es la comprobación que más vale la pena hacer: el mismo recurso, tres
tokens válidos, tres resultados distintos.

```bash
for recurso in /api/cuentas/101 /api/movimientos/101 /api/clientes/101; do
  for par in "web:$TOKEN_WEB" "movil:$TOKEN_MOVIL" "cajero:$TOKEN_CAJERO"; do
    canal="${par%%:*}"; tk="${par#*:}"
    printf "%-22s %-8s %s\n" "$recurso" "$canal" \
      "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $tk" "localhost:8080$recurso")"
  done
done
```

Lo que debe salir:

| Recurso | web | móvil | cajero |
|---|---|---|---|
| `/api/cuentas/101` | 200 | 200 | 200 |
| `/api/movimientos/101` | 200 | 200 | **403** |
| `/api/clientes/101` | 200 | 200 | **403** |

El cajero recibe 403 con un token de firma perfectamente válida. La autorización
por scope la aplica cada microservicio, no sólo el gateway.

Y la escritura:

```bash
# El canal móvil no puede editar datos personales
curl -s -o /dev/null -w "movil escribiendo: %{http_code}\n" -X PUT \
  -H "Authorization: Bearer $TOKEN_MOVIL" -H "Content-Type: application/json" \
  -d '{"nombre":"Intento"}' localhost:8080/api/clientes/101/nombre

# Ningún canal puede liquidar saldo directamente
curl -s -o /dev/null -w "web liquidando: %{http_code}\n" -X POST \
  -H "Authorization: Bearer $TOKEN_WEB" -H "Content-Type: application/json" \
  -d '{"monto":100}' localhost:8080/api/cuentas/101/abono
```

Ambas deben devolver **403**.

---

## 6. Comprobar los tres microservicios por el gateway

Todas las rutas pasan por `localhost:8080`.

```bash
# Gestión de cuentas
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/cuentas/101 | python3 -m json.tool

# Gestión de clientes
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/clientes/101 | python3 -m json.tool

# Movimientos
curl -s -H "Authorization: Bearer $TOKEN_WEB" "localhost:8080/api/movimientos/101?limite=3" | python3 -m json.tool
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/movimientos/101/resumen | python3 -m json.tool

# Reporte de transacciones diarias (la salida del proceso batch 1)
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/transacciones-diarias/resumen | python3 -m json.tool

# Vista agregada con tolerancia a fallos
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/movimientos/101/ficha | python3 -m json.tool
```

En la ficha, el campo **`origenDatosCuenta`** debe decir `SERVICIO`. Es el que
después va a decir `DEGRADADO` cuando se pruebe la tolerancia a fallos.

---

## 7. Comprobar la mensajería JMS

La prueba consiste en ver que un retiro baja el saldo en un microservicio y
aparece en el historial de otro, sin que uno llame al otro.

```bash
# 1. Estado antes
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/cuentas/101 \
  | python3 -c "import sys,json;print('saldo:',json.load(sys.stdin)['saldo'])"
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/movimientos/101/resumen \
  | python3 -c "import sys,json;print('movimientos:',json.load(sys.stdin)['totalMovimientos'])"

# 2. Retiro
curl -s -X POST -H "Authorization: Bearer $TOKEN_CAJERO" -H "Content-Type: application/json" \
  -d '{"monto":250,"canal":"cajero"}' localhost:8080/api/cuentas/101/retiro | python3 -m json.tool

# 3. Esperar unos segundos y volver a mirar
sleep 5
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/cuentas/101 \
  | python3 -c "import sys,json;print('saldo:',json.load(sys.stdin)['saldo'])"
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/movimientos/101/resumen \
  | python3 -c "import sys,json;print('movimientos:',json.load(sys.stdin)['totalMovimientos'])"
```

El saldo baja en 250 y los movimientos suben en 1. El comprobante del retiro
trae `"eventoPublicado": true`.

### La cola retiene el evento si el consumidor está caído

```bash
# Detener pagos-service (el consumidor)
pkill -f "pagos-service.jar"

# Retirar con el consumidor caído: el retiro se aprueba igual
curl -s -X POST -H "Authorization: Bearer $TOKEN_CAJERO" -H "Content-Type: application/json" \
  -d '{"monto":100,"canal":"cajero"}' localhost:8080/api/cuentas/101/retiro | python3 -m json.tool

# Volver a levantarlo
java -jar pagos-service/target/pagos-service.jar &
sleep 45

# El movimiento aparece: el evento esperaba en la cola
curl -s -H "Authorization: Bearer $TOKEN_WEB" "localhost:8080/api/movimientos/101?limite=2" | python3 -m json.tool
```

---

## 8. Comprobar la mensajería Kafka

La prueba es que `clientes-service` sabe de la actividad de un titular sin
preguntarle nada a nadie: se la cuentan los eventos.

```bash
# 1. Perfil antes de operar: operacionesRegistradas y alertasRegistradas en 0
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/clientes/101 | python3 -m json.tool

# 2. Un depósito
curl -s -X POST -H "Authorization: Bearer $TOKEN_WEB" -H "Content-Type: application/json" \
  -d '{"monto":1200,"canal":"web","descripcion":"Abono de sueldo"}' \
  localhost:8080/api/pagos/deposito/101 | python3 -m json.tool

# 3. Una transferencia a otra cuenta
curl -s -X POST -H "Authorization: Bearer $TOKEN_WEB" -H "Content-Type: application/json" \
  -d '{"cuentaDestino":102,"monto":300,"canal":"web","descripcion":"Pago de arriendo"}' \
  localhost:8080/api/pagos/transferencia/101 | python3 -m json.tool

# 4. Un intento de retiro sobre el límite: genera una alerta, no una transacción
curl -s -X POST -H "Authorization: Bearer $TOKEN_CAJERO" -H "Content-Type: application/json" \
  -d '{"monto":900000,"canal":"cajero"}' localhost:8080/api/cuentas/101/retiro | python3 -m json.tool

# 5. Esperar y volver a mirar los dos perfiles
sleep 8
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/clientes/101 | python3 -m json.tool
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/clientes/102 | python3 -m json.tool
```

Qué debe pasar:

- El cliente 101 queda con `operacionesRegistradas: 2` (el depósito y la
  transferencia) y `alertasRegistradas: 1` (el intento sobre el límite).
- `ultimaOperacion` dice `TRANSFERENCIA de 300 el <fecha>`.
- El cliente **102 también** queda con una operación: recibió el abono de la
  transferencia.
- El `saldoReferencial` **no** cambia, y eso es correcto: es un dato comercial
  del archivo legacy, no el saldo real, que vive en `cuentas-service`.

---

## 9. Comprobar la tolerancia a fallos

```bash
# 1. Estado inicial del circuito: debe decir CLOSED
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8082/actuator/circuitbreakers | python3 -m json.tool

# 2. La ficha con cuentas-service arriba: origenDatosCuenta = SERVICIO
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/movimientos/101/ficha \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['origenDatosCuenta'])"

# 3. Detener cuentas-service
pkill -f "cuentas-service.jar"
sleep 5

# 4. Seis peticiones seguidas: el circuito se abre
for i in $(seq 1 6); do
  ORIGEN=$(curl -s -H "Authorization: Bearer $TOKEN_WEB" --max-time 20 \
    localhost:8080/api/movimientos/101/ficha \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['origenDatosCuenta'])")
  ESTADO=$(curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8082/actuator/circuitbreakers \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['circuitBreakers']['cuentas']['state'])")
  echo "$i: origen=$ORIGEN circuito=$ESTADO"
done
```

Qué debe pasar: las primeras respuestas tardan un par de segundos y devuelven
`DEGRADADO`; después de cuatro llamadas el circuito pasa a **`OPEN`** y las
siguientes responden de inmediato, sin esperar a la dependencia.

La respuesta degradada sigue sirviendo el historial completo, con el campo
`cuenta` en `null`. Es el punto del fallback: para quien consulta movimientos, el
nombre del titular es un adorno.

```bash
# 5. Una transferencia con la dependencia caída NO se degrada: se informa el fallo
curl -s -o /dev/null -w "transferencia con dependencia caída: %{http_code}\n" \
  -X POST -H "Authorization: Bearer $TOKEN_WEB" -H "Content-Type: application/json" \
  --max-time 25 -d '{"cuentaDestino":102,"monto":100,"canal":"web"}' \
  localhost:8080/api/pagos/transferencia/101

# 6. Volver a levantar cuentas-service
java -jar cuentas-service/target/cuentas-service.jar &
sleep 45

# 7. El circuito pasa a HALF_OPEN y se cierra solo
for i in $(seq 1 8); do
  ORIGEN=$(curl -s -H "Authorization: Bearer $TOKEN_WEB" --max-time 20 \
    localhost:8080/api/movimientos/101/ficha \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['origenDatosCuenta'])")
  ESTADO=$(curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8082/actuator/circuitbreakers \
    | python3 -c "import sys,json;print(json.load(sys.stdin)['circuitBreakers']['cuentas']['state'])")
  echo "$i: origen=$ORIGEN circuito=$ESTADO"
  sleep 3
done

# 8. Las transiciones registradas
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8082/actuator/circuitbreakerevents \
  | python3 -c "
import sys,json
for e in json.load(sys.stdin)['circuitBreakerEvents']:
    if e['type']=='STATE_TRANSITION': print(e['creationTime'][:19], e['stateTransition'])"
```

La secuencia completa debe ser `CLOSED → OPEN → HALF_OPEN → CLOSED`.

Y una comprobación que une resiliencia con mensajería: la apertura del circuito
se publica en el tópico de alertas. Buscarla en el log del consumidor:

```bash
# Si el ecosistema se levantó con el script de evidencia:
grep "DEPENDENCIA_DEGRADADA" evidencia/logs/clientes-service.log

# Si se levantó a mano con "java -jar ... &", el log está en la terminal donde
# corre clientes-service. Para poder buscarlo, conviene lanzarlo redirigido:
#   java -jar clientes-service/target/clientes-service.jar > /tmp/clientes.log 2>&1 &
#   grep "DEPENDENCIA_DEGRADADA" /tmp/clientes.log
```

---

## 10. Comprobar los tres BFF

```bash
# Canal web: payload completo, cuatro llamadas por detrás
curl -s localhost:8091/web/cuentas/101 | python3 -m json.tool

# Canal móvil: payload liviano
curl -s "localhost:8092/movil/cuentas/101?limite=3" | python3 -m json.tool

# Canal cajero: sólo el saldo
curl -s localhost:8093/cajero/cuentas/101/saldo | python3 -m json.tool
```

Los BFF no piden token: cada uno tiene sus propias credenciales y pide el suyo
internamente. Comparar el tamaño de las tres respuestas:

```bash
for par in "web:8091/web/cuentas/101" "movil:8092/movil/cuentas/101?limite=3" "cajero:8093/cajero/cuentas/101/saldo"; do
  printf "%-8s %6s bytes\n" "${par%%:*}" "$(curl -s "localhost:${par#*:}" | wc -c)"
done
```

### Operaciones por canal

```bash
# Retiro por cajero: una sola llamada
curl -s -X POST -H "Content-Type: application/json" -d '{"monto":150}' \
  localhost:8093/cajero/cuentas/101/retiro | python3 -m json.tool

# Depósito por el canal web
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"monto":500,"descripcion":"Abono por caja"}' \
  localhost:8091/web/cuentas/101/deposito | python3 -m json.tool

# Transferencia por el canal web
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"cuentaDestino":102,"monto":200,"descripcion":"Pago de servicios"}' \
  localhost:8091/web/cuentas/101/transferencia | python3 -m json.tool

# Edición de datos personales, exclusiva del canal web
curl -s -X PUT -H "Content-Type: application/json" -d '{"nombre":"Titular Actualizado"}' \
  localhost:8091/web/clientes/101/nombre | python3 -m json.tool

# Panel administrativo, exclusivo del canal web
curl -s localhost:8091/web/banco/resumen-diario | python3 -m json.tool

# El cajero no tiene estos endpoints: 404
curl -s -o /dev/null -w "cajero/clientes: %{http_code}\n" localhost:8093/cajero/clientes/101
```

---

## 11. Comprobar la escalabilidad horizontal

Con el ecosistema arriba, levantar una segunda instancia de `cuentas-service` en
otro puerto:

```bash
java -jar cuentas-service/target/cuentas-service.jar --server.port=8181 &
sleep 40
```

No hay que tocar configuración de nadie. Comprobar que Eureka ve las dos:

```bash
curl -s -u banco-eureka:banco-eureka-secret -H "Accept: application/json" \
  localhost:8761/eureka/apps/CUENTAS-SERVICE | python3 -c "
import sys,json
inst = json.load(sys.stdin)['application']['instance']
inst = inst if isinstance(inst, list) else [inst]
print('instancias:', len(inst))
for i in inst: print('  -', i['instanceId'], i['status'])"
```

Y que el gateway reparte entre ellas. La forma de verlo es contar las peticiones
que atendió cada una, para lo cual las instancias se levantan con el log de
acceso de Tomcat activado:

```bash
mkdir -p /tmp/acceso

java -jar cuentas-service/target/cuentas-service.jar \
  --server.tomcat.accesslog.enabled=true \
  --server.tomcat.accesslog.directory=/tmp/acceso \
  --server.tomcat.accesslog.prefix=inst-8081 \
  --server.tomcat.accesslog.buffered=false &

java -jar cuentas-service/target/cuentas-service.jar --server.port=8181 \
  --server.tomcat.accesslog.enabled=true \
  --server.tomcat.accesslog.directory=/tmp/acceso \
  --server.tomcat.accesslog.prefix=inst-8181 \
  --server.tomcat.accesslog.buffered=false &

sleep 45

for i in $(seq 1 30); do
  curl -s -o /dev/null -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/cuentas/101
done
sleep 3

echo "instancia 8081: $(cat /tmp/acceso/inst-8081*.log | wc -l) peticiones"
echo "instancia 8181: $(cat /tmp/acceso/inst-8181*.log | wc -l) peticiones"
```

Las dos cifras deben ser distintas de cero. No tienen que ser exactamente 15 y
15: el gateway consulta las cuentas también para otras cosas y las instancias
arrancaron en momentos distintos.

---

## 12. El ecosistema completo, en Docker

### 12.1 Construir las imágenes

```bash
cd EFT_S9_Backend_Avanzado
docker compose build
```

Qué esperar: **entre cinco y doce minutos la primera vez**. El build es
multi-etapa y compila el reactor completo dentro de la imagen, así que no hace
falta tener Java ni Maven instalados. La etapa de compilación es idéntica para
los once servicios, de modo que Docker la reutiliza de su caché y el proyecto se
compila una vez, no once.

Al terminar:

```bash
docker image ls | grep banco-xyz
```

Deben aparecer **once** imágenes `banco-xyz/*:1.0.0`: los tres BFF, el gateway,
los tres microservicios de dominio, los tres componentes de infraestructura y el
broker de Artemis. Kafka no aparece porque usa la imagen oficial, y
`batch-migracion` tampoco porque no es un servicio del compose.

### 12.2 Levantar la orquestación

```bash
docker compose up -d
docker compose ps
```

Qué esperar: **entre dos y cuatro minutos** hasta que todos reporten `healthy`.
El orden de arranque lo encadena `docker-compose.yml` con
`condition: service_healthy`, así que no hay que esperar a mano entre servicios.

El contenedor `kafka-init` aparece como `Exited (0)` y eso es correcto: es un
contenedor de un solo uso que crea los dos tópicos y termina.

Para seguir el arranque:

```bash
docker compose logs -f api-gateway
docker compose logs kafka-init          # debe terminar en ">> kafka-init: topicos creados"
```

### 12.3 Verificar

Las mismas pruebas de las secciones 5 a 10 funcionan igual, porque los puertos
publicados son los mismos. La única diferencia está en el **issuer** de los
tokens: dentro de la red de Docker es `http://auth-server:9000`, y los
microservicios lo exigen en el claim `iss`. Eso significa que el token hay que
pedirlo al `auth-server` publicado en `localhost:9000`, que es lo que hacen todos
los comandos de este documento, y sigue funcionando sin cambios.

```bash
# Salud de todos los contenedores
docker compose ps

# Eureka con los servicios registrados
curl -s -u banco-eureka:banco-eureka-secret -H "Accept: application/json" \
  localhost:8761/eureka/apps | python3 -m json.tool | grep '"name"'

# Un token y una consulta
TOKEN=$(curl -s -u banco-web-client:banco-web-secret -d grant_type=client_credentials \
  --data-urlencode "scope=cuentas.read movimientos.read clientes.read" \
  localhost:9000/oauth2/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/cuentas/101 | python3 -m json.tool

# Los tópicos de Kafka
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

### 12.4 Escalar en contenedores

```bash
docker compose up -d --scale cuentas-service=2 --scale pagos-service=2
docker compose ps
```

Deben aparecer `eft-banco-xyz-cuentas-service-1` y `-2`. Comprobar en Eureka:

```bash
curl -s -u banco-eureka:banco-eureka-secret -H "Accept: application/json" \
  localhost:8761/eureka/apps/CUENTAS-SERVICE | python3 -m json.tool | grep instanceId
```

### 12.5 Bajar todo

```bash
docker compose down
docker compose down -v --remove-orphans   # si además se quiere limpiar volúmenes
```

---

## 13. Problemas conocidos y qué hacer

**Un microservicio no arranca y el log dice que no pudo conectar con el Config
Server.** Es lo esperado: los servicios tienen `fail-fast: true`. Levantar
primero el `config-server`, esperar a que responda `UP` y volver a intentar.

**El gateway devuelve 503 justo después de levantar un servicio.** El gateway
necesita que Eureka le informe la instancia nueva. Esperar quince segundos. Si
persiste, comprobar que el servicio aparece en `localhost:8761`.

**El broker de Kafka tarda en estar `UP`.** Es normal: hasta un minuto en la
ejecución local. El log muestra algunas líneas de `Connection refused` y
`EOFException` durante el arranque; no son fallas, son el cliente de
administración intentando conectarse antes de que el puerto acepte conexiones.

**`docker compose up` se queda mucho tiempo creando la red.** Suele ser el
demonio de Docker en mal estado, no el proyecto. En Windows:
`wsl --shutdown`, luego `docker compose down --remove-orphans` y
`docker network prune -f`, y volver a levantar.

**Un token deja de funcionar después de reiniciar el `auth-server`.** La clave
RSA de firma se genera al arrancar, así que los tokens emitidos antes dejan de
validar. Pedir uno nuevo.

**Los puertos están ocupados.** El sistema usa 8080, 8081, 8082, 8083, 8091,
8092, 8093, 8181, 8761, 8888, 9000, 9092, 9094, 61616 y 61617. Para liberar los
que haya dejado una corrida anterior: `pkill -f "target/.*\.jar"`.

**En Windows, `git clone` falla con rutas demasiado largas.** Activar el soporte
de rutas largas: `git config --global core.longpaths true`.
