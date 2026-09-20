# Banco XYZ — Backend for Frontend (BFF) por canal

**Desarrollo Backend III (PBY2203) — Exp2, Semanas 4 y 5**
**Actividad formativa (S4):** *Analizando el patrón arquitectónico con Backend for Frontend (BFF)*
**Actividad sumativa (S5):** *Implementando el patrón arquitectónico Backend for Frontend (BFF)*

## 1. Objetivo del proyecto

Implementar el patrón **Backend for Frontend (BFF)** para el Banco XYZ, creando un
backend dedicado por cada tipo de cliente — **web**, **móvil** y **cajero
automático** — que agrega y transforma la información expuesta por los
servicios backend según las necesidades reales de cada canal, en vez de
exponer un único backend genérico a todos los frontends por igual.

Se usa el mismo dataset legacy del Banco XYZ que en Exp1
([`KariVillagran/bank_legacy_data`](https://github.com/KariVillagran/bank_legacy_data),
carpeta `data/semana_3`), esta vez expuesto mediante APIs REST en lugar de
procesarse por lotes.

## 2. Continuidad respecto a la semana 4

La actividad formativa de la semana 4 ("Analizando el patrón arquitectónico
con Backend for Frontend (BFF)") pedía identificar qué estrategia de
implementación de BFF conviene al caso del Banco XYZ y esbozar la
personalización de la información por frontend. Esta entrega (semana 5)
avanza esa base hacia una implementación completa y ejecutable.

### 2.1 Estrategia de implementación elegida

Existen, en términos generales, dos estrategias para implementar BFF:

1. **Un BFF por tipo de cliente** (uno para web, uno para móvil, uno para
   cajero), cada uno como servicio independiente y desplegable por
   separado.
2. **Un único BFF configurable**, que decide qué forma de respuesta entregar
   según una cabecera o parámetro que indique el canal (`X-Client-Type`,
   por ejemplo).

Este proyecto usa la **primera estrategia (un servicio por canal)**, por
tres razones concretas para este caso:

- El enunciado pide explícitamente "un backend personalizado para cada tipo
  de cliente", lo que calza directamente con servicios independientes en
  vez de un único servicio con lógica condicional por canal.
- Los tres canales tienen necesidades de seguridad muy distintas (un cajero
  no debería siquiera *poder* alcanzar código que devuelve historial
  completo o datos del titular), y esa separación es más difícil de
  garantizar dentro de un solo proceso que compila y despliega junto todo
  el código de los tres canales.
- Permite optimizar y escalar cada canal por separado. Esto no es teórico
  en este proyecto: la compresión HTTP está activada en web y móvil y
  **desactivada** en cajero, porque a 39 bytes por respuesta el gzip agrega
  más bytes de los que ahorra (ver sección 7). Con un BFF único, esa
  decisión no se podría tomar por canal.

El costo de esta estrategia es la duplicación de la lógica de invocación a
los servicios backend entre los tres BFF — se resuelve con el módulo
`common-model` (ver sección 4), que centraliza esa integración, los
contratos de datos y el manejo de errores en un solo lugar reutilizado por
los tres.

## 3. Requerimientos del sistema y cómo se cubren

| Requerimiento del enunciado | Cómo se implementa |
|---|---|
| APIs para acceder a los datos del dataset legacy | `core-cuentas-service` y `core-movimientos-service` (sección 4) |
| BFF Web: datos completos, soporte para interfaces complejas | `bff-web`: agrega cuenta + historial completo + resumen + panel del banco |
| BFF Móvil: respuestas ligeras, datos esenciales | `bff-movil`: solo saldo, tipo de cuenta y los últimos N movimientos; 91% menos bytes que web |
| BFF Cajero: interfaz segura y eficiente para retiros/saldo | `bff-cajero`: solo 2 endpoints, con límite de monto propio del canal y sin exponer datos del titular |

## 4. Arquitectura

```
Cliente Web  ──────▶  bff-web (8091)   ─┐
Cliente Móvil ─────▶  bff-movil (8092) ─┼──▶  core-cuentas-service (8081)
Cliente Cajero ────▶  bff-cajero (8093)─┘         │
                              │                    ▼  (cuenta, saldo, débito)
                              └──────────▶  core-movimientos-service (8082)
                                                   (historial, resumen,
                                                    registro de movimientos)
```

- **`core-cuentas-service`** (puerto 8081): carga `intereses.csv` en memoria
  al arrancar y expone los datos maestros de cuenta (titular, edad, tipo,
  saldo), además del endpoint de débito que usa el retiro de cajero.
- **`core-movimientos-service`** (puerto 8082): carga `cuentas_anuales.csv`
  (historial de movimientos por cuenta) y `transacciones.csv` (log de
  transacciones diarias del banco, no asociado a una cuenta), los expone, y
  permite registrar movimientos nuevos.
- **`common-model`**: DTOs compartidos, los clientes HTTP tipados
  (`CuentasApiClient`, `MovimientosApiClient`), la fábrica de `RestClient`
  con timeouts (`CoreRestClientFactory`) y el manejo de errores común
  (`BffExceptionHandler`). Evita que cada BFF reimplemente lo mismo tres
  veces.
- **`bff-web` / `bff-movil` / `bff-cajero`**: cada uno agrega las llamadas a
  los servicios core que su canal necesita y transforma el resultado a la
  forma que le conviene a ese canal (ver sección 5). Ninguno de los tres
  conoce la existencia de los otros dos.

Ningún servicio core sabe que existen BFF: expone una API de dominio pura.
Toda la lógica de "qué necesita cada canal" vive exclusivamente en la capa
BFF, que es justamente el punto del patrón.

### 4.1 Integración y agregación desde múltiples servicios

Los tres canales integran **ambos** servicios core, cada uno a su manera:

| Canal | Llamadas por operación | Qué agrega |
|---|---|---|
| Web | 3 llamadas a 2 servicios | cuenta + historial completo + resumen, en un solo payload |
| Móvil | 2 llamadas a 2 servicios | saldo + últimos N movimientos, recortados en origen |
| Cajero | 2 llamadas a 2 servicios (en el retiro) | debita el saldo y registra el movimiento resultante |

El caso del cajero es el más interesante: un retiro no es solo un débito.
El BFF de cajero **debita el saldo** en `core-cuentas-service` y luego
**registra el movimiento** en `core-movimientos-service`, de modo que el
retiro hecho en el cajero queda visible después desde los canales web y
móvil. Esa coherencia entre canales está demostrada en
`evidencia/06_coherencia_entre_canales.log`.

Si el registro del movimiento falla, el retiro **no** se revierte: el dinero
ya salió de la cuenta, así que devolver un error sería mentirle al cajero
sobre algo que ya ocurrió. En su lugar se deja constancia en el log y el
comprobante lo informa con `movimientoRegistrado: false`.

### 4.2 Estructura del código

```
Exp2_S4_S5_BFF/
├── pom.xml                          # reactor Maven (agrega los 6 módulos)
├── common-model/                    # DTOs + clientes HTTP + errores, reutilizables
│   └── src/main/java/.../bff/common/{dto,client,exception,error}/
├── core-cuentas-service/            # API de cuentas (puerto 8081)
│   └── src/{main,test}/             # dominio + data/intereses.csv + tests
├── core-movimientos-service/        # API de movimientos (puerto 8082)
│   └── src/{main,test}/             # dominio + CSVs + tests
├── bff-web/                         # puerto 8091
├── bff-movil/                       # puerto 8092
├── bff-cajero/                      # puerto 8093
└── evidencia/                       # logs de ejecución + scripts que los generan
```

Cada módulo BFF sigue el mismo layout interno (`config/` arma los clientes
hacia los core, `web/` tiene los controladores, `dto/` las formas de
respuesta propias del canal), lo que hace fácil agregar un cuarto canal sin
reorganizar nada existente: basta un módulo nuevo que reutilice
`common-model`.

## 5. Los tres BFF en detalle

### 5.1 BFF Web (`/web/...`, puerto 8091)

- `GET /web/cuentas/{cuentaId}`: agrega cuenta completa + **historial
  completo** de movimientos + resumen, en un solo payload. Es el más
  "pesado" a propósito: está pensado para un dashboard de escritorio con
  espacio para mostrar todo.
- `GET /web/cuentas`: lista todas las cuentas.
- `GET /web/banco/resumen-diario`: panel administrativo exclusivo de este
  canal, con el agregado de transacciones diarias del banco completo (no
  tiene sentido en móvil ni en cajero).

### 5.2 BFF Móvil (`/movil/...`, puerto 8092)

- `GET /movil/cuentas/{cuentaId}?limite=5`: la **misma agregación** de dos
  servicios core que hace el BFF Web, pero transformada a un payload
  deliberadamente más chico: sin nombre del titular (la app ya lo tiene de
  la sesión activa), sin descripciones de movimiento, y solo los últimos N
  movimientos.
- El recorte se le **pide al servicio core** (`?limite=N`) en vez de traer
  el historial completo y descartarlo en el BFF, de modo que el ahorro
  exista también en el tráfico interno: 83% menos bytes entre el BFF y el
  core (sección 7).
- `limite` se valida entre 1 y 20: fuera de rango responde 400 con un
  mensaje claro, no un error 500.

### 5.3 BFF Cajero (`/cajero/...`, puerto 8093)

- `GET /cajero/cuentas/{cuentaId}/saldo`: lo mínimo indispensable para
  mostrar en pantalla — ni nombre, ni edad, ni tipo de cuenta.
- `POST /cajero/cuentas/{cuentaId}/retiro` `{"monto": 500}`: aplica **dos**
  validaciones en capas distintas, a propósito:
  1. **Regla del canal** (en el propio `bff-cajero`, configurable en
     `application.yml`): el monto no puede superar `$500.000` por
     operación — un límite que no tiene sentido para el canal web o móvil,
     así que no vive en el servicio core sino en el BFF.
  2. **Regla del dominio** (en `core-cuentas-service`): el saldo no puede
     quedar negativo — esa invariante es la misma sin importar qué canal
     pida el débito, así que vive en el core, no repetida en cada BFF.

  Los dos rechazos son distinguibles en la respuesta
  (`"Excede el limite maximo por operacion en cajero"` vs
  `"Fondos insuficientes"`), como se ve en
  `evidencia/05_bff_cajero.log`.

## 6. Cómo ejecutar

### 6.1 Requisitos

Java 21 (JDK) y el Maven Wrapper incluido (no hace falta tener Maven
instalado).

### 6.2 Compilar y ejecutar los tests

En Windows (PowerShell), parado dentro de la carpeta `Exp2_S4_S5_BFF`:

```powershell
.\mvnw.cmd clean package
```

En macOS/Linux:

```bash
./mvnw clean package
```

Esto compila los 6 módulos y ejecuta los 15 tests automatizados.

### 6.3 Levantar los 5 servicios

Cada uno es un JAR Spring Boot independiente. Deben levantarse los dos
servicios core antes que los BFF. En 5 terminales distintas:

```bash
java -jar core-cuentas-service/target/core-cuentas-service.jar         # 8081
java -jar core-movimientos-service/target/core-movimientos-service.jar # 8082
java -jar bff-web/target/bff-web.jar        # 8091
java -jar bff-movil/target/bff-movil.jar    # 8092
java -jar bff-cajero/target/bff-cajero.jar  # 8093
```

### 6.4 Probar cada canal

```bash
# Cuenta de ejemplo usada en toda la evidencia: 103
curl http://localhost:8091/web/cuentas/103          # BFF Web: payload completo
curl http://localhost:8092/movil/cuentas/103        # BFF Movil: payload liviano
curl http://localhost:8093/cajero/cuentas/103/saldo # BFF Cajero: solo saldo

curl -X POST http://localhost:8093/cajero/cuentas/103/retiro \
     -H "Content-Type: application/json" -d '{"monto":500}'
```

Los scripts `evidencia/generar_evidencia.sh` y
`evidencia/medir_rendimiento.sh` ejercitan todos los endpoints y regeneran
los logs de la carpeta `evidencia/`.

## 7. Optimización por canal (medida, no declarada)

Medido sobre la misma cuenta y en el mismo momento, promediando 30
peticiones por endpoint (ver `evidencia/07_rendimiento_y_payloads.log`):

| Canal | Endpoint | Tiempo | Bytes | Bytes con gzip |
|---|---|---:|---:|---:|
| Web | `/web/cuentas/103` | 27,9 ms | 3.552 | 566 |
| Móvil | `/movil/cuentas/103` | 19,4 ms | 318 | 158 |
| Cajero | `/cajero/cuentas/103/saldo` | 9,7 ms | 39 | — |

- El payload móvil es **91% más chico** que el web; el del cajero, **99%**.
- Los tiempos siguen la misma lógica: el cajero hace una sola llamada al
  core, el móvil dos (con historial recortado) y el web tres (con historial
  completo).
- **Compresión activada en web y móvil, desactivada en cajero.** No es un
  descuido: a 39 bytes, el encabezado fijo de gzip hace que la respuesta
  del cajero *crezca* a 65 bytes. Como las respuestas se emiten con
  `Transfer-Encoding: chunked`, Tomcat no puede aplicar el umbral
  `min-response-size`, así que no basta con subirlo — hay que apagar la
  compresión en ese canal.
- **Ahorro en el tráfico interno**: el BFF móvil pide `?limite=5` al
  servicio core, que devuelve 562 bytes en vez de los 3.296 del historial
  completo (83% menos). El ahorro no está solo en el último salto hacia la
  app.
- **Timeouts** de 2 s de conexión y 5 s de lectura en todas las llamadas a
  los servicios core (`CoreRestClientFactory`): un core colgado hace fallar
  la llamada rápido con un 502, en vez de dejar hilos del BFF bloqueados
  indefinidamente.

## 8. Decisiones de diseño y supuestos documentados

- **Cuentas duplicadas en `intereses.csv`**: el dataset legacy trae 1.000
  filas (399 válidas) para solo 50 `cuenta_id` distintos; algunas cuentas se
  repiten más de 30 veces con valores que no siempre coinciden. Como el
  archivo no trae fecha ni ninguna marca de cuál fila es la más reciente,
  `core-cuentas-service` conserva la **última fila válida leída** de cada
  cuenta. Es una decisión arbitraria pero documentada: con datos reales,
  este criterio debería reemplazarse por una fecha de actualización.
- **Reglas de validación reutilizadas de Exp1**: tanto la validación de
  cuentas (saldo ≥ 0, edad en [18,120], tipo ∈ {ahorro, préstamo,
  hipoteca}) como la de movimientos (fecha interpretable en los 4 formatos
  del dataset, monto > 0, corrección de "depósito" con tilde, descripción
  vacía reemplazada) son las mismas ya validadas en la migración batch de
  la semana 3, para mantener consistencia de criterio entre ambas entregas.
- **Fechas en modo estricto**: el parser de fechas usa `ResolverStyle.STRICT`
  y no el modo SMART por defecto de Java. En modo SMART, una fecha imposible
  como `31/02/2024` no falla: se ajusta silenciosamente al 29 de febrero.
  Para datos legacy que justamente se están validando, convertir basura en
  una fecha plausible es peor que rechazarla. Este caso lo detectó un test
  (`FechaLegacyParserTest`).
- **Concurrencia en el débito**: `core-cuentas-service` aplica el débito con
  `ConcurrentHashMap.compute`, que hace atómicas la lectura del saldo, la
  validación de fondos y la escritura del nuevo saldo. Un bloque
  `synchronized` sobre el registro leído previamente no basta, porque el
  registro es inmutable y cada débito lo reemplaza por otra instancia: dos
  retiros simultáneos podían partir del mismo saldo y perderse uno. Hay un
  test de concurrencia (`debitosConcurrentesNoPierdenActualizaciones`) que
  cubre exactamente ese escenario.
- **Estado en memoria, no persistente**: los servicios cargan los CSV una
  sola vez al arrancar y mantienen el estado (incluidos el saldo y los
  movimientos nuevos) solo en memoria. Es una simplificación adecuada para
  demostrar el patrón BFF; una versión de producción reemplazaría esto por
  una base de datos, sin que la capa BFF tuviera que cambiar.
- **Autenticación**: el enunciado de la semana 5 no la exige (a diferencia
  del de la semana 4, que sí la mencionaba), así que no se implementó un
  esquema de autenticación/autorización por canal. La separación de
  seguridad que sí existe es de superficie expuesta: el canal cajero no
  tiene forma de pedir el historial ni los datos del titular.

## 9. Evidencia de ejecución

La carpeta `evidencia/` contiene la salida de consola real de una ejecución
completa, más los dos scripts que la regeneran:

- `01_arranque_5_servicios.log`: arranque de los 5 servicios (cuántas filas
  de cada CSV se cargaron como válidas y cuántas se omitieron) y la salida
  de los 15 tests automatizados.
- `02_apis_core.log`: los 6 endpoints de los dos servicios core, incluidos
  los casos de error (404 y 400).
- `03_bff_web.log`: agregación completa, listado de cuentas, panel
  administrativo y cuenta inexistente.
- `04_bff_movil.log`: payload liviano, `?limite` explícito, límite fuera de
  rango (400) y cuenta inexistente.
- `05_bff_cajero.log`: saldo, retiro aprobado, saldo actualizado, rechazo
  por límite del canal, rechazo por fondos insuficientes, monto inválido y
  cuenta inexistente.
- `06_coherencia_entre_canales.log`: un retiro hecho por el cajero baja el
  saldo **y** aparece en el historial que ven los canales web y móvil.
- `07_rendimiento_y_payloads.log`: tiempos de respuesta, tamaños de payload
  por canal, efecto de la compresión y ahorro en el tráfico interno.
- `logs/`: salida de consola completa de cada uno de los 5 procesos.
- `generar_evidencia.sh` y `medir_rendimiento.sh`: los scripts que producen
  todo lo anterior, para poder reproducirlo.

## 10. Entrega (checklist según instrucciones específicas)

- [x] Código fuente completo, versionable en GitHub.
- [x] Documentación (este README): objetivo, estructura y cómo ejecutar.
- [x] Evidencia de ejecución (carpeta `evidencia/`).
- [ ] Repositorio en GitHub (cuenta personal) — pendiente de publicar.
- [ ] Carpeta de entrega comprimida con el nombre `Exp2_S5_Nombre_Apellido` —
      pendiente.
