# Evidencia de la ejecución en AWS EC2

Esta carpeta contiene la ejecución del sistema **fuera del equipo de
desarrollo**: una instancia EC2 de AWS con los trece contenedores corriendo,
incluidos los dos brokers de mensajería.

Es la tercera de las tres evidencias del proyecto, y demuestra lo que las otras
dos no pueden. `local/` demuestra que la lógica funciona en cualquier máquina
con un JDK; `docker/` demuestra que las imágenes se construyen y la orquestación
levanta; **ésta demuestra que todo eso ocurre en la nube**, con los
microservicios y los brokers fuera del portátil.

## Cómo reproducirla

Los pasos completos —crear la instancia, subir el proyecto, construir y
capturar— están en la **sección 11 de [`../../despliegue.md`](../../despliegue.md)**.
En resumen son cuatro comandos en el equipo local y uno dentro de la instancia.

El script que genera estos logs corre **dentro de la instancia**:

```bash
bash evidencia/generar_evidencia_nube.sh
```

## Qué demuestra cada archivo

| Archivo | Qué demuestra |
|---|---|
| `01_instancia_ec2.log` | **Que esto es EC2 y no un equipo local.** Identidad de la instancia, tipo, zona de disponibilidad, IP pública y AMI, leídos del servicio de metadatos de AWS, que sólo responde desde dentro de una instancia. Incluye los recursos de la máquina y la comprobación de que no hay JDK ni Maven instalados: la compilación ocurre dentro de la imagen |
| `02_orquestacion_en_ec2.log` | `docker compose build` con las once imágenes construidas en la instancia, `docker compose ps` con todos los contenedores en `healthy`, la red privada con las IP de cada contenedor, el consumo de memoria y los servicios registrados en Eureka |
| `03_oauth2_en_ec2.log` | El token emitido por el `auth-server` desplegado, sus claims, y el mínimo privilegio por canal: el cajero recibe 403 en movimientos y en clientes con un token de firma válida |
| `04_mensajeria_en_ec2.log` | **Los dos brokers corriendo en la nube.** Un retiro que baja el saldo en un contenedor y aparece en el historial de otro por la cola JMS, y los dos tópicos de Kafka con productores y consumidores reales actualizando la actividad de dos titulares distintos |
| `05_tolerancia_a_fallos_en_ec2.log` | El ciclo completo del circuit breaker sobre contenedores reales: `CLOSED → OPEN → HALF_OPEN → CLOSED`, con respuesta degradada, y la alerta publicada en el tópico |
| `06_escalabilidad_en_ec2.log` | `docker compose up -d --scale cuentas-service=2`, las dos réplicas registradas en Eureka y el conteo de peticiones que atendió cada una, tomado de sus propios logs de acceso |
| `07_estado_final_en_ec2.log` | Salud de cada componente consultada dentro de la red, registro final de Eureka, las imágenes construidas y el uso de recursos de la instancia |

## La identidad de la instancia

El primer log empieza con los metadatos de EC2 a propósito. Un `docker compose
ps` es idéntico en cualquier máquina, así que sin esa cabecera no habría forma
de distinguir esta evidencia de la de la carpeta `docker/`. Los metadatos se
leen de `169.254.169.254`, una dirección que sólo responde desde dentro de una
instancia de AWS, usando IMDSv2 —que exige pedir un token antes de consultar—
porque es la versión que impide que una petición hecha desde fuera alcance los
metadatos de la máquina.

## Capturas de pantalla

Los logs prueban el comportamiento; conviene acompañarlos con capturas. Las
cinco que más valen:

1. La consola de EC2 con la instancia en estado `running`, mostrando su tipo y
   su IP pública.
2. La sesión SSH con `docker compose ps` y todos los contenedores `healthy`.
3. `docker image ls` dentro de la instancia, con las once imágenes.
4. La consola de Eureka abierta en el navegador contra la IP pública
   (`http://<IP>:8761`), con los cinco servicios registrados.
5. `docker compose ps` después de `--scale cuentas-service=2`, con las dos
   réplicas.

## Después de capturar

**Terminar la instancia.** EC2 se cobra por hora encendida, y una instancia
olvidada es la forma más común de que una demostración de veinte centavos
termine costando sesenta dólares al mes. El comando está al final de la sección 11 de
`despliegue.md`.
