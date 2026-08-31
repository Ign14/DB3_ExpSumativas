# Banco XYZ — Migración de procesos batch legacy con Spring Batch

**Desarrollo Backend III (PBY2203) — Exp1, Semana 3**
**Actividad sumativa:** *Optimizando procesos batch para mejorar la resiliencia de procesos*

## 1. Objetivo del proyecto

Modernizar tres procesos batch legacy (basados en scripts COBOL/shell) del Banco XYZ,
reescribiéndolos con **Spring Batch** a partir de los datos de
[`KariVillagran/bank_legacy_data`](https://github.com/KariVillagran/bank_legacy_data)
(carpeta `data/semana_3`), garantizando integridad y consistencia de los datos,
tolerancia a fallos y buen rendimiento mediante escalado por particiones.

Se implementan tres Jobs independientes, uno por proceso de negocio:

| Job (bean) | Proceso de negocio | Archivo de entrada | Salida principal |
|---|---|---|---|
| `reporteTransaccionesDiariasJob` | Reporte de Transacciones Diarias: detecta anomalías y genera un resumen | `transacciones.csv` | `transaccion`, `resumen_transacciones_diarias` |
| `calculoInteresesMensualesJob` | Cálculo de Intereses Mensuales: aplica intereses sobre ahorro/préstamo/hipoteca y actualiza el saldo | `intereses.csv` | `interes_cuenta` |
| `estadoCuentaAnualJob` | Generación de Estados de Cuenta Anuales: compila el historial anual por cuenta para auditoría | `cuentas_anuales.csv` | `movimiento_cuenta_anual`, `estado_cuenta_anual` |

Todos los registros descartados por incumplir reglas de consistencia quedan
además auditados en la tabla común `registro_anomalia`.

## 1.1 Continuidad respecto a las semanas 1 y 2

Esta actividad pide explícitamente avanzar "en función de las actividades
formativas de la semana 1 y 2". Este proyecto incorpora esa base:

- **Semana 1** ("Analizando la arquitectura batch para procesar datos",
  formativa grupal): definió el rol de Spring Batch en la migración del
  sistema legacy del Banco XYZ y la necesidad de Jobs/Steps para leer,
  transformar y escribir los tres archivos CSV en una base de datos
  relacional. Esa arquitectura se implementa íntegramente aquí (sección 4).
- **Semana 2** ("Configurando jobs y steps en Spring Batch", formativa
  grupal): agregó procesamiento por chunks, un Step multi-hilo simple (la
  instrucción de esa semana pedía considerar 3 hilos de ejecución paralela
  con chunks de tamaño 5) y las primeras políticas de reintento/omisión y
  logging de rendimiento. Este proyecto conserva y refuerza esas tres
  capacidades, pero — tal como indica explícitamente la instrucción de la
  semana 3 ("los parámetros de cada configuración deberán ser decididos por
  ti") — evoluciona el Step multi-hilo simple hacia particionamiento
  (`PartitionStep` + `TaskExecutorPartitionHandler`) y ajusta
  `chunk-size`/`grid-size`/`thread-pool-size` en base a un benchmark propio
  (sección 5), en vez de mantener fijos los valores de ejemplo de la semana 2.

> Nota: las semanas 1 y 2 fueron actividades formativas **grupales**
> orientadas al diseño, sin un proyecto de código individual previo
> disponible; la semana 3 es la primera entrega **individual** sumativa. Por
> eso este proyecto se construyó como una implementación completa que
> materializa, en un solo repositorio, los aprendizajes acumulados de las
> tres semanas.

## 2. Estructura del proyecto

```
banco-xyz-batch/
├── pom.xml
├── docker-compose.yml                  # PostgreSQL para el perfil "postgres"
├── evidencia/                          # Logs de ejecución y benchmark (ver sección 6)
├── src/main/resources/
│   ├── application.yml                 # parámetros comunes (escalado, tolerancia a fallos, tasas)
│   ├── application-dev.yml             # perfil dev: H2 embebido
│   ├── application-postgres.yml        # perfil postgres: PostgreSQL
│   └── data/*.csv                      # datasets legacy (semana_3)
└── src/main/java/cl/duoc/bancoxyz/batch/
    ├── config/                         # infraestructura común (retry/backoff, TaskExecutor, launcher CLI)
    ├── common/                         # particionador y reader por rango, skip listener, excepción, utilidades
    ├── transacciones/                  # Job 1 (modelo, processor, tasklet de resumen, JobConfig)
    ├── intereses/                      # Job 2
    └── cuentasanuales/                 # Job 3
```

## 3. Cómo ejecutar

### 3.1 Requisitos
Solo **Java 21** (JDK, no solo JRE). El proyecto incluye el *Maven Wrapper*
(`mvnw` / `mvnw.cmd`), por lo que **no** necesitas tener Maven instalado: el
wrapper lo descarga solo la primera vez que se ejecuta. Docker es opcional,
solo para el perfil `postgres`.

### 3.2 Perfil de desarrollo (H2 embebido, sin dependencias externas)

En Windows (PowerShell), parado dentro de la carpeta `banco-xyz-batch`:

```powershell
.\mvnw.cmd clean package
java -jar target\banco-xyz-batch.jar --job=all
```

En macOS/Linux:

```bash
./mvnw clean package
java -jar target/banco-xyz-batch.jar --job=all
```

`--job` acepta `transacciones`, `intereses`, `cuentasAnuales` o `all` (por defecto).
Cada ejecución imprime en consola el resultado de cada Job y Step
(`JobMetricsListener`): estado, duración y contadores de lectura/escritura/omisión.
Puedes revisar el contenido de las tablas en `http://localhost:8080/h2-console`
si levantas el proyecto como aplicación web, o inspeccionarlas con cualquier
cliente JDBC apuntando a `jdbc:h2:mem:bancoxyz` durante la ejecución.

### 3.3 Perfil de producción (PostgreSQL)

```bash
docker compose up -d
java -jar target/banco-xyz-batch.jar --spring.profiles.active=postgres --job=all --DB_PORT=15432
```

El contenedor expone PostgreSQL en el puerto **15432** del host (no el 5432
por defecto) a propósito: es común que una máquina de desarrollo tenga una
o más instancias nativas de PostgreSQL corriendo como servicio, y los
instaladores suelen asignar automáticamente puertos "típicos" (5432, 5433,
5434, ...). Si el contenedor comparte puerto con un Postgres nativo, el
sistema operativo puede enrutar la conexión hacia ese servicio en vez del
contenedor, y la app falla con un `password authentication failed` que en
realidad no tiene nada que ver con la contraseña configurada. Se usa un
puerto no convencional (15432) para evitar por completo esa clase de
colisión. Si tu máquina no tiene ese problema, puedes usar el 5432 estándar
cambiando el mapeo de puertos en `docker-compose.yml` y quitando
`--DB_PORT=15432` del comando.

### 3.4 Ajustar el escalado por línea de comandos

```bash
java -jar target/banco-xyz-batch.jar --job=transacciones \
     --batch.partition.grid-size=8 \
     --batch.partition.thread-pool-size=8
```

## 4. Arquitectura Spring Batch

Cada uno de los tres Jobs sigue la misma arquitectura de dos Steps:

1. **Step particionado** (maestro/`PartitionStep` + `TaskExecutorPartitionHandler`):
   `RangoLineasPartitioner` cuenta las filas de datos del CSV y las reparte en
   `batch.partition.grid-size` rangos `[startLine, endLine)` de tamaño equilibrado.
   Cada partición ejecuta el mismo Step "trabajador" (lectura → validación/transformación
   → escritura), usando un `RangoLineasFlatFileItemReader` que solo lee su rango de
   líneas del archivo (mismo mecanismo enseñado en la guía de la semana: el
   `Partitioner` crea un `ExecutionContext` con `start`/`end`, y el reader lo
   respeta). Las particiones corren en paralelo sobre un `ThreadPoolTaskExecutor`
   cuyo tamaño es configurable.
2. **Step de agregación** (Tasklet): una vez escritos todos los registros válidos,
   calcula el resumen/estado de cuenta consultando la base de datos (no reprocesa
   el archivo), dejando el reporte de auditoría listo (`resumen_transacciones_diarias`
   o `estado_cuenta_anual`).

### 4.1 Procesamiento y validación (`ItemProcessor`)

Las reglas de validación replican los problemas simulados que describe el
`README.md` del dataset legacy (montos negativos/cero, fechas con formato
inconsistente, campos vacíos, tipos fuera de dominio):

- **`TransaccionItemProcessor`**: exige monto > 0, fecha interpretable en alguno
  de los 4 formatos legacy (`yyyy-MM-dd`, `yyyy/MM/dd`, `dd-MM-yyyy`, `dd/MM/yyyy`)
  y `tipo` ∈ {`credito`, `debito`} (rechaza `invalid`, `desconocido`).
- **`InteresItemProcessor`**: exige saldo ≥ 0, edad en [18, 120] y
  `tipo` ∈ {`ahorro`, `prestamo`, `hipoteca`} (rechaza `-1`, `unknown`); aplica la
  tasa mensual configurada en `application.yml` (`batch.intereses.*`) y calcula
  `saldo_final = saldo + saldo × tasa`.
  > *Supuesto de negocio documentado*: el enunciado no fija tasas concretas, por
  > lo que se definieron valores de referencia (ahorro 0,5%, préstamo 1,8%,
  > hipoteca 1,2% mensual) como parámetros configurables, no como valores fijos
  > en el código.
- **`MovimientoItemProcessor`**: combina las dos estrategias que pide el
  enunciado ("corregir **o** manejar los errores"): **corrige** el tipo de
  movimiento con tildes (`depósito` → `deposito`) y completa la descripción
  vacía con un valor por defecto; **rechaza (skip)** fecha no interpretable,
  monto ≤ 0 o tipo de movimiento fuera de dominio.

### 4.2 Tolerancia a fallos

- **Skip**: cada Step usa `faultTolerant().skip(RegistroInvalidoException.class)`
  con `skipLimit` configurable (`batch.fault-tolerance.skip-limit`), de forma que
  un registro incorrecto no detiene el Job. `AnomaliaSkipListener` deja constancia
  de cada omisión (motivo + detalle) en la tabla `registro_anomalia`, tanto para
  fallas de lectura, de procesamiento como de escritura.
- **Retry**: `retryPolicy` + `ExponentialBackOffPolicy` (100 ms, ×2, hasta 2 s)
  reintentan automáticamente errores transitorios de acceso a datos
  (`TransientDataAccessException`, p. ej. una desconexión momentánea del motor de
  base de datos), hasta `batch.fault-tolerance.retry-limit` intentos, antes de
  recién ahí aplicar la política de skip.

## 5. Comparación de configuraciones de escalado (criterio: elegir la óptima)

Se comparó el tiempo total del Job `reporteTransaccionesDiariasJob` variando
`grid-size`/`thread-pool-size` en dos escenarios: el dataset real de la
actividad (1.000 filas) y una versión ampliada a 20.000 filas (misma
distribución de datos válidos/erróneos, repetida 20 veces), para observar el
efecto del volumen de datos. Cada configuración se ejecutó 2-3 veces contra H2
embebido; se reporta el promedio (ver logs completos en `evidencia/02_benchmark_particiones_20000_filas.log`).

| grid-size = thread-pool-size | 1.000 filas (prom.) | 20.000 filas (prom.) |
|---:|---:|---:|
| 1 (secuencial) | **~794 ms** | **~3.595 ms** |
| 2 | ~1.038 ms | ~5.542 ms |
| 4 | ~1.339 ms | ~6.843 ms |
| 8 | ~1.369 ms | ~6.895 ms |
| 16 | — | ~7.780 ms |

**Lectura honesta del resultado:** en este entorno de prueba, la ejecución
secuencial fue más rápida que la particionada en ambos volúmenes. La causa no
es que particionar "no sirva", sino dónde está el cuello de botella aquí: el
`JpaItemWriter` escribe con `persist()` fila por fila y la base de datos usada
para la prueba es H2 embebido en memoria (un único motor, sin red), por lo que
varios hilos escribiendo a la vez compiten por el mismo recurso en vez de
repartir trabajo real; a eso se suma el costo fijo de orquestar Steps y
transacciones adicionales por cada partición. Con archivos de miles de filas y
validaciones simples como las de este proyecto, ese costo de coordinación pesa
más que el ahorro de paralelizar.

**Configuración elegida para el proyecto:** `grid-size=4`, `thread-pool-size=4`
(valor por defecto en `application.yml`). Se prefirió sobre el modo puramente
secuencial porque:

1. Es la técnica de escalado que exige explícitamente esta actividad
   (particiones vs. multi-thread), y el objetivo de la semana es dejar la
   arquitectura preparada para escalar, no solo medir un caso puntual.
2. La ventaja de particionar aparece cuando el cuello de botella deja de ser la
   escritura contendida en una base embebida: con **PostgreSQL real** (conexión
   por red, pool de conexiones dedicado) y con **volúmenes de producción**
   (los tres archivos legacy reales del banco, no una muestra de 1.000-20.000
   filas) o **procesamiento por ítem más pesado** (reglas de negocio más
   costosas, llamadas externas), el paralelismo sí reduce el tiempo total. Esa
   es la situación real para la que se diseñó este proyecto.
3. 4 particiones con un pool de 4 hilos es un punto intermedio razonable: no
   sobre-fragmenta el trabajo (como sí penalizó grid-size=16) ni renuncia a la
   capacidad de escalar.

**Alcance de esta comparación:** se realizó contra H2 embebido (un solo
proceso, sin acceso por red), que es el escenario más exigente para el
paralelismo por escritura contendida. Contra PostgreSQL real —con conexión
por red y pool de conexiones dedicado— el equilibrio entre el costo de
coordinar particiones y el costo de escritura cambia, por lo que los tiempos
absolutos de esta tabla no se trasladan directamente a ese motor. La
comparación se documenta como el criterio usado para elegir
`grid-size=4`/`thread-pool-size=4`, no como una medición válida para
cualquier motor de base de datos.

## 6. Evidencia de ejecución

La carpeta `evidencia/` contiene:

- `01_build_y_tests_ok.png`: `mvn clean verify` (build + suite de tests)
  terminando en `BUILD SUCCESS`.
- `02_ejecucion_tres_jobs_inicio.png` / `03_ejecucion_tres_jobs_fin_completed.png`:
  ejecución de los tres Jobs (`--job=all`) contra el perfil `dev` (H2
  embebido), desde el arranque hasta los tres Jobs en estado `COMPLETED`.
- `04_postgres_inicio.png` / `05_postgres_fin_completed.png`: la misma
  ejecución de los tres Jobs, esta vez contra una instancia real de
  PostgreSQL levantada con `docker compose up -d` (perfil `postgres`), desde
  el `docker ps` mostrando el contenedor activo hasta el cierre limpio del
  `HikariPool`. Los contadores de lectura/escritura/omisión de esta corrida
  coinciden exactamente con los de la corrida sobre H2, lo que confirma que
  la lógica de negocio y las reglas de validación son independientes del
  motor de base de datos.
- `01_ejecucion_los_tres_jobs.log`: salida de consola completa de la corrida
  contra H2, con los contadores de lectura/escritura/omisión por partición y
  los resúmenes generados por cada Tasklet.
- `02_benchmark_particiones_20000_filas.log`: corridas usadas para la
  comparación de la sección 5.

Tanto la corrida con H2 (perfil `dev`) como la corrida con PostgreSQL real
(perfil `postgres`, ver sección 3.3) quedaron documentadas con capturas
propias, tal como pide el enunciado.


