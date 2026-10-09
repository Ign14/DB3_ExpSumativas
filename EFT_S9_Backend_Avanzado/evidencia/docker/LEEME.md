# Evidencia de la ejecución con Docker

Ésta es la segunda de las tres evidencias del proyecto; el índice de las tres
está en [`../LEEME.md`](../LEEME.md).

Los logs de `../local/` se generaron ejecutando los jar directamente
(`generar_evidencia.sh`), para que esa evidencia sea reproducible en cualquier
máquina con un JDK y sin depender del demonio de Docker, y los de `../nube/`
corresponden al despliegue en una instancia EC2. Cada conjunto cubre algo
distinto: aquél demuestra la lógica del sistema, éste demuestra que las imágenes
se construyen y que la orquestación declarada funciona, y el de la nube
demuestra que todo eso ocurre fuera del equipo de desarrollo.

## Cómo generarla

Con Docker Desktop corriendo, desde la carpeta `EFT_S9_Backend_Avanzado`:

```powershell
powershell -ExecutionPolicy Bypass -File evidencia\generar_evidencia_docker.ps1
```

El script construye las once imágenes del repositorio, levanta la orquestación completa —que
incluye Kafka y el contenedor de un solo uso que crea los tópicos—, espera a que
todos los contenedores reporten `healthy`, ejercita el ecosistema entero y lo
baja al terminar. Deja ocho logs en esta carpeta:

| Archivo | Qué demuestra |
|---|---|
| `01_construccion_de_imagenes.log` | Las once imágenes se construyen y existen |
| `02_orquestacion.log` | Los contenedores en `healthy`, la red propia, la resolución por nombre dentro de la red, los tópicos creados y el registro de Eureka |
| `03_oauth2.log` | Token por `client_credentials`, claims, clave pública y los rechazos: sin token, token alterado, secreto incorrecto, scope no registrado y mínimo privilegio por canal |
| `04_apis_y_mensajeria.log` | Los tres microservicios por el gateway, un retiro que viaja por la cola JMS entre dos contenedores, y los dos tópicos de Kafka con productores y consumidores reales |
| `05_tolerancia_a_fallos.log` | El ciclo completo del circuit breaker sobre contenedores: `CLOSED → OPEN → HALF_OPEN → CLOSED`, con respuesta degradada y la alerta publicada en el tópico |
| `06_bff_por_canal.log` | Los tres canales devolviendo payloads de tamaños muy distintos para la misma cuenta |
| `07_escalabilidad_horizontal.log` | `--scale cuentas-service=2 --scale pagos-service=2`, las réplicas registradas en Eureka y cuántas peticiones atendió cada una, contadas en su propio log de acceso |
| `08_estado_final.log` | Salud de cada componente consultada dentro de la red y registro final de Eureka |

La primera ejecución tarda bastante: la construcción descarga las dependencias de
Maven dentro de la imagen, y el escalado del paso 7 espera a que las réplicas
nuevas estén sanas. En total, entre veinte y treinta minutos.

## Tres cosas que en los logs se leen raro y no son fallas

Quedan anotadas aquí en vez de editarse en los logs, porque una evidencia
retocada a mano deja de ser evidencia.

**`kafka-init` aparece como `Exited (0)`.** Es correcto y es a propósito. Es un
contenedor de un solo uso que crea los dos tópicos con sus particiones y
termina. Los microservicios que los consumen dependen de que este contenedor haya
terminado bien (`service_completed_successfully`), no sólo de que Kafka esté
sano, de modo que ningún consumidor arranca contra un tópico inexistente.

**En `01_construccion_de_imagenes.log` pueden aparecer bloques con
`NativeCommandError` y `CategoryInfo`.** No falló nada: Docker escribe el
progreso de la construcción en la salida de error, y PowerShell envuelve esas
líneas en registros de error. La prueba de que la construcción terminó bien está
unas líneas más abajo, en los `naming to docker.io/banco-xyz/...  done`, y en la
sección siguiente, que lista las imágenes con su tamaño.

**En `05_tolerancia_a_fallos.log`, el circuito puede pasar por `OPEN` más de una
vez antes de cerrarse.** La secuencia esperada es `CLOSED → OPEN → HALF_OPEN →
CLOSED`, pero es normal ver `HALF_OPEN → OPEN → HALF_OPEN → CLOSED`: cuando el
circuito prueba en medio abierto, el contenedor que acaba de arrancar puede
todavía estar descargando su configuración y registrándose en Eureka, así que la
prueba falla y el circuito vuelve a abrirse unos segundos. Es exactamente lo que
pasaría en un incidente real, y es mejor evidencia que un ciclo limpio.

## Capturas de pantalla

Los logs prueban el comportamiento, pero conviene acompañarlos con capturas de
pantalla. Las cinco que más valen:

1. `docker compose ps` con todos los contenedores en `healthy`.
2. `docker image ls` con las once imágenes `banco-xyz/*`.
3. La consola de Eureka en <http://localhost:8761> con los cinco servicios
   registrados (usuario `banco-eureka`, clave `banco-eureka-secret`).
4. La ficha respondiendo `origenDatosCuenta: DEGRADADO` con `cuentas-service`
   detenido, y volviendo a `SERVICIO` después de levantarlo.
5. `docker compose ps` después de `--scale cuentas-service=2`, con las dos
   réplicas visibles.

Los comandos exactos están en la sección 12 de `instrucciones.md`.
