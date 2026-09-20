# Banco XYZ — Backend for Frontend (BFF) por canal

**Desarrollo Backend III (PBY2203) — Exp2, Semanas 4 y 5**
**Actividad formativa (S4):** *Analizando el patrón arquitectónico con Backend for Frontend (BFF)*
**Actividad sumativa (S5):** *Implementando el patrón arquitectónico Backend for Frontend (BFF)*

## 1. Objetivo del proyecto

Implementar el patrón **Backend for Frontend (BFF)** para el Banco XYZ, con un
backend dedicado por cada tipo de cliente — **web**, **móvil** y **cajero
automático** — que agrega y transforma la información de los servicios backend
según las necesidades de cada canal, en vez de exponer un único backend
genérico a todos los frontends por igual.

Se usa el mismo dataset legacy del Banco XYZ que en Exp1
([`KariVillagran/bank_legacy_data`](https://github.com/KariVillagran/bank_legacy_data),
carpeta `data/semana_3`), esta vez expuesto mediante APIs REST en lugar de
procesarse por lotes.

## 2. Continuidad respecto a la semana 4

La actividad formativa de la semana 4 pedía identificar qué estrategia de
implementación de BFF conviene al caso del Banco XYZ y esbozar la
personalización de la información por frontend. Esta entrega lleva esa base a
una implementación completa y ejecutable.

### 2.1 Estrategia de implementación elegida

Hay dos estrategias habituales para implementar BFF:

1. **Un BFF por tipo de cliente**: un servicio independiente y desplegable por
   separado para web, otro para móvil y otro para cajero.
2. **Un único BFF configurable**, que decide qué forma de respuesta entregar
   según una cabecera o parámetro que identifique el canal.

Este proyecto usa la **primera estrategia**, por tres razones:

- El enunciado pide un backend personalizado para cada tipo de cliente, que
  calza con servicios independientes más que con un servicio único lleno de
  lógica condicional.
- Los canales tienen necesidades de seguridad distintas. Con servicios
  separados, el canal cajero no solo no devuelve el historial ni los datos del
  titular: no tiene forma de pedirlos, porque ese código no está en su
  despliegue.
- Permite ajustar cada canal por separado. Un ejemplo concreto del proyecto: la
  compresión HTTP está activada en web y móvil y desactivada en cajero, porque
  a 40 bytes por respuesta el gzip agrega más de lo que ahorra (sección 7). Con
  un BFF único esa decisión no se podría tomar por canal.

El costo de esta estrategia es repetir la integración con los servicios
backend en tres lugares. Se resuelve con el módulo `common-model`, que
centraliza contratos, clientes HTTP y manejo de errores.

## 3. Requerimientos del sistema y cómo se cubren

| Requerimiento del enunciado | Cómo se implementa |
|---|---|
| APIs para acceder a los datos del dataset legacy | `core-cuentas-service` y `core-movimientos-service` |
| BFF Web: datos completos, soporte para interfaces complejas | `bff-web`: cuenta + historial completo + resumen + panel del banco |
| BFF Móvil: respuestas ligeras, datos esenciales | `bff-movil`: saldo, tipo de cuenta y últimos N movimientos; 90% menos bytes que web |
| BFF Cajero: interfaz segura y eficiente para retiros y consultas de saldo | `bff-cajero`: dos endpoints, límite de monto propio del canal, sin datos del titular |

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

- **`core-cuentas-service`** (8081): carga `intereses.csv` y expone los datos
  maestros de cuenta, más el endpoint de débito que usa el retiro de cajero.
- **`core-movimientos-service`** (8082): carga `cuentas_anuales.csv`
  (historial por cuenta) y `transacciones.csv` (log diario del banco), los
  expone y permite registrar movimientos nuevos.
- **`common-model`**: DTOs, clientes HTTP tipados, la fábrica de clientes con
  timeouts y el manejador de errores común.
- **`bff-web` / `bff-movil` / `bff-cajero`**: cada uno agrega las llamadas que
  su canal necesita y transforma el resultado a la forma que le conviene.
  Ninguno conoce la existencia de los otros dos.

Los servicios core exponen una API de dominio y no saben que existen los BFF.
Toda la lógica de "qué necesita cada canal" vive en la capa BFF.

### 4.1 Integración y agregación desde múltiples servicios

Los tres canales integran ambos servicios core, cada uno a su manera:

| Canal | Llamadas por operación | Qué agrega |
|---|---|---|
| Web | 3 llamadas a 2 servicios | cuenta + historial completo + totales |
| Móvil | 2 llamadas a 2 servicios | saldo + últimos N movimientos, recortados en origen |
| Cajero | 2 llamadas a 2 servicios en el retiro | debita el saldo y registra el movimiento |

El caso del cajero es el más completo: un retiro no es solo un débito. El BFF
debita el saldo en `core-cuentas-service` y registra el movimiento en
`core-movimientos-service`, de modo que la operación hecha en el cajero queda
visible después desde web y móvil. Está demostrado en
`evidencia/06_coherencia_entre_canales.log`.

Si el registro del movimiento falla, el retiro no se revierte: el dinero ya
salió de la cuenta. Queda registrado en el log del servicio y el comprobante
lo informa con `movimientoRegistrado: false`.

### 4.2 Estructura del código

```
Exp2_S4_S5_BFF/
├── pom.xml                          # reactor Maven con los 6 módulos
├── common-model/                    # DTOs, clientes HTTP y errores compartidos
├── core-cuentas-service/            # API de cuentas (8081)
├── core-movimientos-service/        # API de movimientos (8082)
├── bff-web/                         # 8091
├── bff-movil/                       # 8092
├── bff-cajero/                      # 8093
└── evidencia/                       # logs de ejecución + script que los genera
```

Los tres BFF comparten el mismo layout interno: `config/` construye los
clientes hacia los servicios core, `service/` tiene la agregación y las reglas
del canal, `web/` expone los endpoints y `dto/` define las respuestas propias
del canal. Agregar un cuarto canal es agregar un módulo con esa misma
estructura, reutilizando `common-model`, sin tocar los existentes.

## 5. Los tres BFF en detalle

### 5.1 BFF Web (`/web/...`, puerto 8091)

- `GET /web/cuentas/{cuentaId}`: cuenta + historial completo + totales, en un
  solo payload. Es el más pesado a propósito: está pensado para un dashboard
  de escritorio con espacio para mostrarlo todo.
- `GET /web/cuentas`: listado de cuentas.
- `GET /web/banco/resumen-diario`: panel administrativo con el agregado de
  transacciones del banco completo. Exclusivo de este canal.

### 5.2 BFF Móvil (`/movil/...`, puerto 8092)

- `GET /movil/cuentas/{cuentaId}?limite=5`: la misma agregación que hace el
  canal web, transformada a un payload más chico: sin nombre del titular (la
  app ya lo tiene de la sesión), sin descripciones y solo los últimos N
  movimientos.
- El recorte se le pide al servicio core, así que el historial completo
  tampoco viaja entre el core y el BFF (sección 7).
- `limite` se valida entre 1 y 20; fuera de rango o con un valor no numérico
  responde 400 con un mensaje claro.

### 5.3 BFF Cajero (`/cajero/...`, puerto 8093)

- `GET /cajero/cuentas/{cuentaId}/saldo`: solo el saldo. Ni nombre, ni edad,
  ni tipo de cuenta.
- `POST /cajero/cuentas/{cuentaId}/retiro` con `{"monto": 500}`: el monto pasa
  por dos validaciones en capas distintas.
  1. **Regla del canal**, en el propio BFF y configurable en
     `application.yml`: no se puede superar `$500.000` por operación. Ese
     límite no tiene sentido en web ni en móvil, así que no vive en el core.
  2. **Regla del dominio**, en `core-cuentas-service`: el saldo no puede
     quedar negativo. Esa invariante vale para cualquier canal, así que vive
     en el core y no repetida en cada BFF.

  Los dos rechazos se distinguen en la respuesta y están en
  `evidencia/05_bff_cajero.log`.

## 6. Cómo ejecutar

### 6.1 Requisitos

Java 21 (JDK) y el Maven Wrapper incluido.

### 6.2 Compilar y ejecutar los tests

En Windows (PowerShell), dentro de la carpeta `Exp2_S4_S5_BFF`:

```powershell
.\mvnw.cmd clean package
```

En macOS/Linux:

```bash
./mvnw clean package
```

Compila los 6 módulos y ejecuta los 33 tests automatizados.

### 6.3 Levantar los 5 servicios

Cada uno es un JAR Spring Boot independiente. Los servicios core deben estar
arriba antes que los BFF. En 5 terminales:

```bash
java -jar core-cuentas-service/target/core-cuentas-service.jar         # 8081
java -jar core-movimientos-service/target/core-movimientos-service.jar # 8082
java -jar bff-web/target/bff-web.jar        # 8091
java -jar bff-movil/target/bff-movil.jar    # 8092
java -jar bff-cajero/target/bff-cajero.jar  # 8093
```

### 6.4 Probar cada canal

```bash
curl http://localhost:8091/web/cuentas/103          # payload completo
curl http://localhost:8092/movil/cuentas/103        # payload liviano
curl http://localhost:8093/cajero/cuentas/103/saldo # solo saldo

curl -X POST http://localhost:8093/cajero/cuentas/103/retiro \
     -H "Content-Type: application/json" -d '{"monto":500}'
```

El script `evidencia/generar_evidencia.sh` levanta los 5 servicios, ejercita
todos los endpoints y regenera los logs de la carpeta `evidencia/`.

## 7. Optimización por canal

Medido sobre la cuenta 110, promediando 30 peticiones por endpoint
(`evidencia/07_rendimiento_y_payloads.log`):

| Canal | Endpoint | Tiempo | Bytes | Con gzip |
|---|---|---:|---:|---:|
| Web | `/web/cuentas/110` | 28,3 ms | 3.332 | 545 |
| Móvil | `/movil/cuentas/110` | 20,2 ms | 324 | 165 |
| Cajero | `/cajero/cuentas/110/saldo` | 10,6 ms | 40 | — |

- El payload móvil es **90% más chico** que el web; el del cajero, **99%**.
- Los tiempos siguen la misma lógica: el cajero hace una llamada al core, el
  móvil dos con historial recortado y el web tres con historial completo. Los
  servicios core responden entre 2 y 4 ms, así que la diferencia es el costo
  de la agregación de cada canal.
- **Compresión activada en web y móvil, desactivada en cajero.** A 40 bytes,
  el encabezado de gzip deja la respuesta del cajero más grande que sin
  comprimir. El umbral `min-response-size` no sirve para evitarlo, porque las
  respuestas se emiten con `Transfer-Encoding: chunked` y Tomcat no conoce su
  tamaño de antemano: hay que apagar la compresión en ese canal.
- **Ahorro en el tráfico interno**: el BFF móvil pide `?limite=5` al servicio
  core, que devuelve 544 bytes en vez de los 3.071 del historial completo, un
  82% menos. El ahorro no está solo en el último salto hacia la app.
- **Timeouts** de 2 s de conexión y 5 s de lectura hacia los servicios core.
  Un core que no responde hace fallar la llamada con un 502 en vez de dejar
  hilos del BFF bloqueados.

## 8. Decisiones de diseño y supuestos

- **Cuentas duplicadas en `intereses.csv`**: el dataset trae 1.000 filas (399
  válidas) para solo 50 cuentas distintas, con valores que no siempre
  coinciden entre filas de la misma cuenta. Como el archivo no tiene fecha ni
  ninguna marca para desempatar, se conserva la última fila válida de cada
  cuenta. Con datos reales, ese criterio debería reemplazarse por una fecha de
  actualización.
- **Reglas de validación heredadas de Exp1**: las de cuentas (saldo ≥ 0, edad
  entre 18 y 120, tipo dentro del dominio) y las de movimientos (fecha
  interpretable, monto > 0, corrección de "depósito" con tilde, descripción
  vacía completada) son las mismas de la migración batch de la semana 3.
- **Fechas en modo estricto**: el parser usa `ResolverStyle.STRICT`. En el modo
  por defecto, una fecha imposible como `31/02/2024` no falla: se ajusta en
  silencio al 29 de febrero. Al validar datos sucios, eso es peor que
  descartar la fila.
- **Concurrencia en el débito**: se aplica con `ConcurrentHashMap.compute`,
  que vuelve atómicas la lectura del saldo, la validación de fondos y la
  escritura. Sincronizar sobre el registro leído antes no basta, porque el
  registro es inmutable y cada débito lo reemplaza por otra instancia: dos
  retiros simultáneos pueden partir del mismo saldo y perderse uno. Hay un
  test de concurrencia con 20 hilos que cubre ese caso.
- **`totalEgresos` agrupa retiros, compras y pagos**, porque los tres sacan
  dinero de la cuenta. Se nombra así, y no "retiros", para que el dato no se
  lea como algo más estrecho de lo que es.
- **Estado en memoria**: los servicios cargan los CSV al arrancar y mantienen
  el estado (saldo y movimientos nuevos incluidos) solo en memoria. Una
  versión de producción usaría una base de datos, sin que la capa BFF
  cambiara.
- **Autenticación**: el enunciado de la semana 5 no la exige, así que no se
  implementó un esquema de autenticación por canal. La separación de seguridad
  que sí existe es de superficie expuesta: desde el canal cajero no hay forma
  de pedir el historial ni los datos del titular.

## 9. Evidencia de ejecución

La carpeta `evidencia/` contiene la salida de consola de una ejecución
completa, generada por `generar_evidencia.sh`:

- `01_arranque_y_tests.log`: arranque de los 5 servicios, con cuántas filas de
  cada CSV se cargaron y cuántas se omitieron, y el resultado de los 33 tests.
- `02_apis_core.log`: los 7 endpoints de los dos servicios core, incluidos los
  casos de error.
- `03_bff_web.log`: agregación completa, listado, panel administrativo y
  cuenta inexistente.
- `04_bff_movil.log`: payload liviano, `?limite` explícito, límite fuera de
  rango, límite no numérico y cuenta inexistente.
- `05_bff_cajero.log`: saldo, retiro aprobado, saldo actualizado, rechazo por
  límite del canal, rechazo por fondos insuficientes, monto inválido y cuenta
  inexistente.
- `06_coherencia_entre_canales.log`: un retiro hecho por el cajero baja el
  saldo y aparece en el historial que ven web y móvil.
- `07_rendimiento_y_payloads.log`: tiempos, tamaños por canal, efecto de la
  compresión y ahorro en el tráfico interno.
- `logs/`: salida completa de cada uno de los 5 procesos.

## 10. Entrega

- [x] Código fuente completo, versionable en GitHub.
- [x] Documentación (este README): objetivo, estructura y cómo ejecutar.
- [x] Evidencia de ejecución (carpeta `evidencia/`).
- [ ] Repositorio publicado en GitHub (cuenta personal).
- [ ] Carpeta de entrega comprimida como `Exp2_S5_Nombre_Apellido`.
