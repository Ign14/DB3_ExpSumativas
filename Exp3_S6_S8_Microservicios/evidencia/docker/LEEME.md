# Evidencia de la ejecución con Docker

Los logs numerados de `evidencia/` se generaron ejecutando los jar directamente
(`generar_evidencia.sh`), para que esa evidencia sea reproducible en cualquier
máquina con un JDK y sin depender del demonio de Docker. Los dos conjuntos de
evidencia cubren cosas distintas: aquel demuestra la lógica del sistema, y este
demuestra que las imágenes y la orquestación funcionan.

## Cómo generarla

Con Docker Desktop corriendo, desde la carpeta `Exp3_S6_S8_Microservicios`:

```powershell
powershell -ExecutionPolicy Bypass -File evidencia\generar_evidencia_docker.ps1
```

El script construye las siete imágenes, levanta la orquestación, espera a que los
siete contenedores reporten `healthy`, ejercita el ecosistema completo —token,
control de acceso, APIs por el gateway, un retiro que viaja por la cola, la caída
de `cuentas-service` con el circuito abriéndose y cerrándose solo— y baja todo al
terminar. Deja seis logs en esta carpeta:

| Archivo | Qué demuestra | Criterio de la pauta |
|---|---|---|
| `01_construccion_de_imagenes.log` | Las siete imágenes se construyen y existen | 2 |
| `02_orquestacion.log` | Los siete contenedores en `healthy`, la red propia, la resolución por nombre dentro de la red y el registro de Eureka | 3 |
| `03_oauth2.log` | Token por `client_credentials`, claims, clave pública, y los rechazos (sin token, token alterado, scope no registrado, mínimo privilegio del cajero) | 1 |
| `04_apis_y_mensajeria.log` | Los endpoints por el gateway y un retiro que baja el saldo en un microservicio y aparece en el historial del otro por la cola | 5 |
| `05_tolerancia_a_fallos.log` | El ciclo completo del circuit breaker sobre contenedores: `CLOSED → OPEN → HALF_OPEN → CLOSED`, con respuesta degradada | 4 |
| `06_estado_final.log` | Salud final de los siete contenedores y registro de Eureka | 3 |

La primera ejecución tarda varios minutos porque la construcción descarga las
dependencias de Maven dentro de la imagen.

## Dos cosas que en los logs se leen raro y no son fallas

Quedan anotadas aquí en vez de editarse en los logs, porque una evidencia
retocada a mano deja de ser evidencia.

En `01_construccion_de_imagenes.log` aparecen dos bloques con
`NativeCommandError`, `CategoryInfo` y un subrayado bajo `docker compose build`.
No falló nada: Docker escribe el progreso de la construcción en la salida de
error, y PowerShell envuelve esas líneas en registros de error y las muestra con
ese formato. La prueba de que la construcción terminó bien está unas líneas más
abajo, en los siete `naming to docker.io/banco-xyz/...  done`, y en la sección
siguiente, que lista las siete imágenes con su tamaño.

En `04_apis_y_mensajeria.log`, el historial de movimientos sale envuelto como
`{"value": [...], "Count": 1}`. La API devuelve un arreglo JSON a secas; el
envoltorio lo agrega `ConvertTo-Json` de PowerShell al recibir un arreglo por
tubería. El contenido es el que devolvió el servicio.

## Capturas de pantalla

Los logs prueban el comportamiento, pero conviene acompañarlos con capturas, que
es lo que el enunciado pide explícitamente. Las cuatro que más valen:

1. `docker compose ps` con los siete contenedores en `healthy`.
2. `docker images` con las siete imágenes `banco-xyz/*`.
3. La consola de Eureka en `http://localhost:8761` con los cuatro servicios
   registrados (usuario `banco-eureka`, clave `banco-eureka-secret`).
4. La ficha respondiendo `origenDatosCuenta: DEGRADADO` con `cuentas-service`
   detenido, y volviendo a `SERVICIO` después de levantarlo.

Los comandos exactos están en las secciones 8.2, 8.4 y 8.5 del README.
