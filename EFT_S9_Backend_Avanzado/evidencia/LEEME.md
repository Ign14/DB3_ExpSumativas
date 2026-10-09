# Evidencia de ejecución — índice

El sistema se ejecutó y se registró en **tres entornos distintos**, porque cada
uno demuestra algo que los otros no pueden demostrar. Este archivo dice qué hay
en cada carpeta y qué prueba cada cosa, para que nada quede escondido en un
subdirectorio.

| Carpeta | Entorno | Qué demuestra | Archivos |
|---|---|---|---|
| [`local/`](local/) | Los jar, con un JDK y sin Docker | Que la **lógica del sistema** funciona y que la evidencia es reproducible en cualquier máquina | 11 logs |
| [`docker/`](docker/) | Contenedores en el equipo de desarrollo | Que las **imágenes se construyen** y que la **orquestación** levanta el ecosistema completo | 8 logs + capturas |
| [`nube/`](nube/) | Una instancia EC2 en AWS | Que el sistema **se despliega y corre en la nube**, con los microservicios y los brokers fuera del equipo local | 7 logs + capturas |

> Cada carpeta se genera con su propio script, y los tres están al final de este
> archivo. Si alguna contiene únicamente su `LEEME.md`, esa ejecución todavía no
> se corrió: no es que la evidencia falte, es que ese entorno no se ha levantado
> aún.

Si sólo va a mirar una cosa de cada carpeta:

- `local/01_compilacion_y_pruebas.log` — los quince módulos y las 168 pruebas.
- `docker/01_construccion_de_imagenes.log` — `docker compose build` con las once
  imágenes, y `docker image ls` al final.
- `nube/02_orquestacion_en_ec2.log` — `docker compose ps` dentro de la instancia
  EC2, con todos los contenedores en `healthy`.

---

## 1. `local/` — ejecución con los jar

Generada por `generar_evidencia.sh`, que levanta los trece procesos en el orden
correcto, ejercita el sistema completo y lo baja al terminar.

Existe porque es la única evidencia **reproducible en cualquier máquina con un
JDK**, sin depender del demonio de Docker ni de una cuenta de AWS. Si algo de lo
que este proyecto afirma se quiere comprobar de nuevo, es por aquí.

| Archivo | Qué demuestra |
|---|---|
| `01_compilacion_y_pruebas.log` | Los quince módulos compilan y las 168 pruebas pasan |
| `02_batch_migracion.log` | Los tres jobs sobre el dataset legacy, el mismo resultado con una, cuatro y ocho particiones, la reejecución automática ante un fallo provocado y el código de salida distinto de cero |
| `03_configuracion_y_discovery.log` | Config Server y Eureka pidiendo credenciales, y los cinco servicios registrados |
| `04_oauth2_y_control_de_acceso.log` | Token por `client_credentials`, claims, clave pública, y los rechazos: sin token, token alterado, secreto incorrecto, scope no registrado y mínimo privilegio por canal |
| `05_apis_por_el_gateway.log` | Los tres microservicios respondiendo por el gateway |
| `06_mensajeria_jms.log` | Un retiro que baja el saldo en un servicio y aparece en el historial de otro por la cola, y la cola reteniendo el evento con el consumidor caído |
| `07_mensajeria_kafka.log` | Los dos tópicos con productores y consumidores reales, y la actividad del titular actualizada por eventos |
| `08_tolerancia_a_fallos.log` | El ciclo completo del circuit breaker `CLOSED → OPEN → HALF_OPEN → CLOSED`, con respuesta degradada |
| `09_bff_por_canal.log` | Los tres canales devolviendo payloads de tamaños muy distintos para la misma cuenta |
| `10_escalabilidad_horizontal.log` | Dos instancias de `cuentas-service` y el reparto de peticiones entre ellas |
| `11_estado_final.log` | Salud de los doce componentes y de la réplica, y el registro final de Eureka |
| `logs/` | Salida completa de cada proceso y los logs de acceso de las instancias escaladas |

## 2. `docker/` — ejecución en contenedores

Generada por `generar_evidencia_docker.ps1`. Demuestra lo que la carpeta
anterior no puede: que las imágenes se construyen y que `docker-compose` levanta
el ecosistema tal como está declarado. El índice detallado está en
[`docker/LEEME.md`](docker/LEEME.md).

## 3. `nube/` — ejecución en AWS EC2

Generada por `generar_evidencia_nube.sh`, que corre **dentro de la instancia**.
Demuestra que el sistema completo —los tres microservicios, los dos brokers y
toda la infraestructura— se despliega y funciona fuera del equipo de desarrollo.
El índice detallado está en [`nube/LEEME.md`](nube/LEEME.md), y los pasos para
reproducir el despliegue, en la sección 11 de
[`../despliegue.md`](../despliegue.md).

---

## Cómo regenerar cada una

```bash
# 1. Con los jar, sin Docker
bash evidencia/generar_evidencia.sh

# 2. En contenedores, con Docker Desktop corriendo
powershell -ExecutionPolicy Bypass -File evidencia\generar_evidencia_docker.ps1

# 3. En la nube, desde dentro de la instancia EC2
bash evidencia/generar_evidencia_nube.sh
```

## Una advertencia sobre los logs

Contienen la URL de Eureka con su contraseña en claro. Con los valores por
defecto del repositorio es inocuo, pero si alguna vez se despliega con
credenciales reales, los logs no se publican tal cual.
