# Evidencia de ejecución

- `01_build_y_tests_ok.png`: `mvn clean verify` (build + suite de tests)
  terminando en `BUILD SUCCESS`.
- `02_ejecucion_tres_jobs_inicio.png` / `03_ejecucion_tres_jobs_fin_completed.png`:
  ejecución de los tres Jobs (`reporteTransaccionesDiariasJob`,
  `calculoInteresesMensualesJob`, `estadoCuentaAnualJob`) con `--job=all`
  contra el perfil `dev` (H2 embebido), desde el arranque hasta los tres
  Jobs en estado `COMPLETED`.
- `04_postgres_inicio.png` / `05_postgres_fin_completed.png`: la misma
  ejecución de los tres Jobs, esta vez contra una instancia real de
  PostgreSQL levantada con `docker compose up -d` (perfil `postgres`,
  puerto 15432 — ver README.md del proyecto, sección 3.3). La primera
  captura muestra `docker ps` con el contenedor activo y el arranque del
  jar con el perfil `postgres` activo; la segunda muestra el cierre de los
  tres Jobs en estado `COMPLETED` y el `HikariPool-1 - Shutdown completed`.
  Los contadores de lectura/escritura/omisión de esta corrida coinciden
  exactamente con los de la corrida sobre H2, confirmando que la lógica de
  negocio y las reglas de validación son independientes del motor de base
  de datos.
- `01_ejecucion_los_tres_jobs.log`: salida de consola de una ejecución
  completa (`--job=all`) con `grid-size=4`, `thread-pool-size=4` contra H2,
  mostrando los tres Jobs terminando en estado `COMPLETED`, con sus
  contadores de lectura/escritura/omisión por partición.
- `02_benchmark_particiones_20000_filas.log`: resultados de las pruebas de
  comparación de configuraciones de particionado (grid-size 1/2/4/8/16)
  sobre un archivo ampliado a 20.000 filas, usadas para justificar la
  configuración óptima elegida (ver README.md del proyecto, sección
  "Comparación de configuraciones de escalado").

Con esto queda documentada tanto la ejecución con H2 (perfil `dev`) como la
ejecución contra PostgreSQL real (perfil `postgres`), tal como pide el
enunciado ("evidencia de ejecución... con su propia cuenta").
