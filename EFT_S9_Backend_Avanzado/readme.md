# Banco XYZ — Modernización del backend legacy

**Evaluación Final Transversal — Desarrollo Backend III (PBY2203)**
Ignacio Miño Astorga · Analista Programador Computacional · Duoc UC

Repositorio: <https://github.com/Ign14/DB3_ExpSumativas> — carpeta `EFT_S9_Backend_Avanzado`

---

## 0. Qué se entrega y dónde está cada cosa

| Entregable | Archivo |
|---|---|
| Documentación del proyecto | `readme.md` (este archivo) |
| Informe técnico | `informe_tecnico.pdf` — su fuente y los diagramas, en `informe/` |
| Instrucciones para ejecutar y probar cada componente | `instrucciones.md` |
| Pasos para desplegar en la nube | `despliegue.md` — la sección 11 es el despliegue que se ejecutó, en una instancia EC2 |
| Video de la presentación | `video/` — el guion, con los tiempos de cada tramo, en `informe/guion_video.md` |
| Código fuente | los quince módulos de esta misma carpeta |
| Evidencia de ejecución | `evidencia/` — tres entornos: `local/` (los jar), `docker/` (contenedores) y `nube/` (una instancia EC2 en AWS). Índice en `evidencia/LEEME.md` |
| Recorrido de las nueve semanas | `RESUMEN_APRENDIZAJES.md` |

---

## 1. Qué es esto

El Banco XYZ opera desde hace más de treinta años sobre una plataforma COBOL y
scripts Shell en mainframe. Ese sistema procesa transacciones, calcula intereses
y genera estados de cuenta, y hoy es caro de mantener, difícil de escalar e
imposible de integrar con nada moderno.

Este repositorio es la solución backend que reemplaza esa plataforma. Son
quince módulos Maven que, juntos, cubren las tres piezas del problema:

**Los procesos batch legacy, reescritos en Spring Batch.** Los tres informes
clave —el reporte de transacciones diarias, el cálculo de intereses y los
estados de cuenta anuales— leen los mismos archivos que el sistema antiguo,
con los mismos datos sucios, y los transforman con particionamiento, políticas
de omisión y reintento, y reejecución automática ante fallos críticos.

**Un backend por canal, con el patrón BFF.** El navegador, la aplicación móvil
y el cajero automático dejaron de pedirle lo mismo a un monolito. Cada uno tiene
su propio backend, que agrega lo que ese canal necesita y nada más, con sus
propias credenciales y sus propios permisos.

**Microservicios seguros y resilientes con Spring Cloud.** El dominio quedó
partido en tres servicios —cuentas, pagos y clientes— con configuración
centralizada, descubrimiento dinámico, un gateway como única puerta de entrada,
OAuth 2.0 con JWT en cada salto, tolerancia a fallos con Resilience4j y una
arquitectura de eventos sobre Kafka y JMS.

Todo se levanta con un comando, se despliega en contenedores y escala
horizontalmente sin cambiar una línea de configuración.

### Los datos

El origen es el repositorio que la actividad entrega,
[`KariVillagran/fin_legacy_data`](https://github.com/KariVillagran/fin_legacy_data),
con tres archivos de mil filas cada uno y errores sembrados a propósito: montos
negativos, fechas en tres formatos distintos, saldos vacíos, edades imposibles,
tipos de transacción inexistentes y cuentas duplicadas con valores que no
coinciden. Esa suciedad no es un accidente del ejercicio: es exactamente lo que
aparece al migrar un sistema de treinta años, y la mitad de las decisiones de
este proyecto existen por ella.

| Archivo | Filas | Alimenta |
|---|---|---|
| `movimientos_financieros_diarios.csv` | 1.000 | Reporte de transacciones diarias (batch) y el panel administrativo del canal web |
| `intereses_trimestrales.csv` | 1.000 | Cálculo de intereses (batch), el padrón de cuentas y el padrón de clientes |
| `estados_financieros_anuales.csv` | 1.000 | Estados de cuenta anuales (batch) y el historial de movimientos |

---

## 2. Arquitectura

```
    CANALES                    ENTRADA              DOMINIO                EVENTOS
  ┌───────────┐
  │ navegador │──┐
  └───────────┘  │   ┌─────────┐
  ┌───────────┐  ├──▶│ bff-web │──┐
  │   móvil   │──┤   └─────────┘  │
  └───────────┘  │   ┌───────────┐│   ┌─────────────┐   ┌──────────────────┐
  ┌───────────┐  ├──▶│ bff-movil │┼──▶│ api-gateway │──▶│ cuentas-service  │──┐
  │  cajero   │──┘   └───────────┘│   │   :8080     │   │     :8081        │  │
  └───────────┘  │   ┌────────────┤   │             │   └──────────────────┘  │
                 └──▶│ bff-cajero │   │  lb:// por  │   ┌──────────────────┐  │
                     └────────────┘   │   Eureka    │──▶│  pagos-service   │◀─┤
                                      │             │   │     :8082        │  │
                                      │  valida JWT │   └──────────────────┘  │
                                      └─────────────┘   ┌──────────────────┐  │
                                                     ┌─▶│ clientes-service │◀─┤
                                                     │  │     :8083        │  │
                                                     │  └──────────────────┘  │
   INFRAESTRUCTURA                                   │                        │
  ┌────────────────┐  ┌──────────────────┐           │   ┌─────────────────┐  │
  │ config-server  │  │ discovery-server │           └───│ Kafka  :9092    │◀─┘
  │     :8888      │  │  (Eureka) :8761  │               │ 2 tópicos       │
  └────────────────┘  └──────────────────┘               └─────────────────┘
  ┌────────────────┐                                     ┌─────────────────┐
  │  auth-server   │                                     │ Artemis  :61616 │
  │     :9000      │                                     │ cola de retiros │
  └────────────────┘                                     └─────────────────┘

   PROCESO BATCH (se ejecuta y termina, no queda residente)
  ┌──────────────────────────────────────────────────────────────────────┐
  │ batch-migracion : 3 jobs particionados sobre los CSV legacy          │
  └──────────────────────────────────────────────────────────────────────┘
```

### Los quince módulos

| Módulo | Puerto | Qué hace |
|---|---|---|
| `common-dominio` | — | Los contratos que comparten los servicios: DTOs de las APIs y los tres eventos |
| `batch-migracion` | — | Los tres procesos batch migrados desde COBOL |
| `config-server` | 8888 | Sirve la configuración de todo el ecosistema, con autenticación básica |
| `discovery-server` | 8761 | Eureka: registro y descubrimiento de servicios, con autenticación básica |
| `auth-server` | 9000 | Spring Authorization Server: emite los JWT por `client_credentials` |
| `api-gateway` | 8080 | Única puerta de entrada; valida el JWT y enruta por nombre de servicio |
| `cuentas-service` | 8081 | Gestión de cuentas: saldo, titular, retiros y las primitivas de liquidación |
| `pagos-service` | 8082 | Procesamiento de pagos: depósitos, transferencias e historial de movimientos |
| `clientes-service` | 8083 | Gestión de clientes: datos personales y la proyección de actividad desde Kafka |
| `broker-artemis` | 61616 | Broker JMS de la cola de retiros |
| `broker-kafka` | 9092 | Broker Kafka para la ejecución local sin Docker |
| `bff-common` | — | Los clientes HTTP y el gestor de tokens que comparten los tres canales |
| `bff-web` | 8091 | Canal web: payload completo para una consola administrativa |
| `bff-movil` | 8092 | Canal móvil: payload mínimo, pensado para ancho de banda escaso |
| `bff-cajero` | 8093 | Canal cajero: saldo y retiro, con el límite físico del dispositivo |

---

## 3. Parte 1 — Los procesos batch

El sistema legacy generaba tres informes y los tres se reescribieron como Jobs de
Spring Batch independientes, lanzables por separado:

```
--job=transacciones    -> reporteTransaccionesDiariasJob
--job=intereses        -> calculoInteresesMensualesJob
--job=cuentasAnuales   -> estadoCuentaAnualJob
--job=all              -> los tres en secuencia
```

### Cómo se procesan mil filas sucias sin detenerse

Cada Job tiene la misma forma: un `PartitionStep` divide el archivo en rangos de
líneas, un pool de hilos procesa los rangos en paralelo, y cada partición lee,
valida, transforma y escribe por chunks. El tamaño de la grilla y del pool se
cambian por línea de comandos, lo que permitió medir configuraciones distintas
sobre la misma carga.

La parte interesante es qué pasa con una fila mala. Hay tres niveles de
respuesta y confundirlos es el error clásico de una migración:

Una **fila inválida** —un monto negativo, una fecha que no existe, un tipo de
transacción desconocido— no detiene nada. Se omite, queda registrada como
anomalía con el motivo exacto, y el proceso sigue. El límite de omisiones es
configurable: si se superara, el Step falla, porque mil omisiones no son datos
sucios sino un archivo equivocado.

Ese límite tiene un detalle que cuesta ver y que apareció calibrándolo: Spring
Batch lo aplica **por ejecución de Step, no por archivo**, así que con
particionamiento cada partición lleva su propia cuenta. Con el límite en 200, el
archivo de movimientos diarios —que trae 608 filas inválidas de 1.000— fallaba
con una partición y pasaba con cuatro: el mismo archivo, el mismo límite y dos
resultados distintos según el paralelismo. Está calibrado en 700 justamente para
que el resultado no dependa del grado de partición, y `evidencia/local/02` muestra las
tres configuraciones dando lo mismo.

Un **fallo transitorio** —la base de datos que rechaza una conexión por un
instante— se reintenta a nivel de chunk, hasta el límite configurado.

Un **fallo crítico** —la base de datos que se cae a mitad del proceso— hace
fallar el Job entero, y ahí entra la política de reejecución automática: el
proceso relanza el Job hasta dos veces, esperando entre intentos, antes de
rendirse. Cada intento parte de cero y no reanuda la ejecución anterior, y eso
es deliberado: reanudar aprovecharía el avance de los Steps que ya completaron,
pero con escritura por chunks sobre claves generadas, una reanudación puede
duplicar filas. Partir de cero es más lento y es correcto.

Y por encima de todo eso está la **política de finalización**: el proceso termina
con código de salida cero sólo si todos los Jobs completaron. Si alguno quedó
fallido tras agotar los reintentos, sale con uno. Sin esa línea, un batch que
falló entero terminaría "correctamente" para el cron que lo invoca y nadie se
enteraría hasta que faltaran los datos.

### Las reglas de validación

Son las mismas en los tres Jobs porque los tres archivos traen la misma clase de
basura:

- **Fechas**: el dataset mezcla `yyyy-MM-dd`, `dd-MM-yyyy`, `yyyy/MM/dd` y
  `dd/MM/yyyy`. Se intentan los cuatro formatos, y siempre con
  `ResolverStyle.STRICT`. Esto último importa más de lo que parece: en el modo
  por defecto, `DateTimeFormatter` acepta `31/02/2024` y lo convierte
  silenciosamente en el 29 de febrero. Un parser que "funciona" corrompiendo
  datos es peor que uno que falla.
- **Montos**: mayores que cero. Un monto negativo o cero en un archivo de
  movimientos es un error de origen, no un movimiento válido.
- **Edades**: entre 18 y 120. El dataset trae edades de 100 y de 5.
- **Tipos**: dentro del dominio conocido (`ahorro`, `prestamo`, `hipoteca` para
  cuentas; `compra`, `deposito`, `pago`, `retiro` para movimientos). El archivo
  trae `-1` como tipo de cuenta y `depósito` con tilde donde el resto dice
  `deposito`.
- **Cuentas duplicadas**: el archivo de intereses repite la misma cuenta con
  valores distintos y no trae fecha para desempatar. Se conserva la última fila
  válida. Con datos reales, ese criterio debería reemplazarse por una fecha de
  actualización, y así está anotado.

---

## 4. Parte 2 — El patrón BFF

El problema original era que los tres frontends pedían lo mismo al mismo
backend. El navegador necesita el historial completo y los totales; la
aplicación móvil necesita el saldo y los últimos movimientos; el cajero necesita
el saldo y poder retirar. Servir a los tres con una API única significa que dos
de ellos reciben datos que no van a mostrar, y que ninguno puede evolucionar sin
coordinarse con los otros.

La solución es un backend por canal. Cada uno expone la API que su frontend
quiere, agrega por detrás los microservicios que hagan falta, y recorta la
respuesta a lo que ese canal muestra.

### Qué devuelve cada canal para la misma cuenta

| Canal | Endpoint | Contenido | Llamadas al dominio |
|---|---|---|---|
| Web | `GET :8091/web/cuentas/{id}` | Cuenta, perfil del titular, historial completo y totales | 4 |
| Móvil | `GET :8092/movil/cuentas/{id}?limite=3` | Saldo, tipo de cuenta y los N últimos movimientos, con tres campos cada uno | 2 |
| Cajero | `GET :8093/cajero/cuentas/{id}/saldo` | Número de cuenta y saldo. Nada más | 1 |

La diferencia de tamaño entre el payload del canal web y el del cajero está
medida en `evidencia/local/09_bff_por_canal.log`, y es de dos órdenes de magnitud.

### Autenticación y autorización por canal

Cada canal es un cliente OAuth 2.0 distinto, con su propio secreto y sus propios
scopes. Eso no es burocracia: es lo que hace que un canal comprometido no sirva
para hacer lo que ese canal no hace.

| Canal | Cliente OAuth 2.0 | Scopes |
|---|---|---|
| Web | `banco-web-client` | `cuentas.read`, `cuentas.write`, `movimientos.read`, `movimientos.write`, `clientes.read`, `clientes.write` |
| Móvil | `banco-movil-client` | `cuentas.read`, `movimientos.read`, `clientes.read` |
| Cajero | `cajero-client` | `cuentas.read`, `cuentas.write` |

El canal móvil no tiene ningún scope de escritura, y la razón es concreta: una
aplicación que vive en un teléfono que se pierde, se presta o se roba no debería
poder mover dinero con el token que lleva guardado. El cajero no puede leer el
historial de movimientos ni los datos personales del titular: un cajero
comprometido no debe convertirse en una fuente de información personal. Y el
canal web, que es la consola administrativa, es el único que puede editar datos
del cliente o transferir.

Esto se puede comprobar en la evidencia: el mismo token del cajero, con firma
perfectamente válida, recibe 200 en `/api/cuentas/101` y 403 en
`/api/movimientos/101` y en `/api/clientes/101`.

### Lo que el patrón de eventos le quitó al canal

En la implementación anterior, un retiro por cajero eran dos llamadas que el BFF
tenía que orquestar: debitar el saldo en un servicio y registrar el movimiento en
el otro. El problema era evidente y no tenía buena solución desde el canal: si la
segunda llamada falla, el dinero ya salió.

Ahora el canal hace una sola llamada. `cuentas-service` aplica el débito y
publica el evento en la cola; `pagos-service` lo consume y anota el movimiento en
el historial. La cola garantiza que el evento espere si el consumidor está caído.
El BFF se quedó sólo con lo que le corresponde: su límite por operación y la
forma del comprobante.

---

## 5. Parte 3 — Microservicios con Spring Cloud

### 5.1 Configuración centralizada

Cada microservicio sabe únicamente su nombre y dónde está el Config Server.
Puerto, seguridad, broker, umbrales de Resilience4j y reglas de negocio se
descargan al arrancar y viven versionadas en un solo lugar
(`config-server/src/main/resources/configuracion-central/`).

Dos detalles que no son obvios:

El cliente arranca con `fail-fast: true`. Sin eso, un Config Server caído deja al
servicio arrancando con los valores por defecto de Spring: puerto 8080, sin
seguridad y sin registrarse en Eureka. Es mejor que no arranque.

Y el Config Server pide credenciales. Esto se agregó después de una revisión, y
el motivo está en la sección 5.6.

### 5.2 Descubrimiento y enrutamiento

Los servicios se registran en Eureka al arrancar. El gateway enruta por nombre de
servicio (`lb://cuentas-service`) y no por host y puerto, lo que significa que
levantar una segunda instancia basta para que empiece a recibir tráfico.

El refresco del registro está en cinco segundos y con el registro completo en
cada consulta, en vez de los treinta segundos y el delta que trae por defecto.
Con cinco servicios registrados el costo es despreciable, y resuelve dos
molestias reales:
tras levantar un servicio, el gateway tardaba hasta medio minuto en poder
enrutar hacia él, y si una instancia se reiniciaba con el mismo identificador, el
delta podía traer la baja de la instancia anterior después del alta de la nueva y
dejarla fuera del caché hasta el refresco siguiente.

### 5.3 OAuth 2.0 con JWT

El flujo es `client_credentials`, y la elección se explica por quién pide el
token: en este sistema no hay una persona frente a una pantalla de login, sino
aplicaciones —un canal, un microservicio— que se autentican a sí mismas. No hay
usuario final que autorice nada, así que ni `authorization_code` ni PKCE
aportarían algo.

Hay dos barreras y ninguna reemplaza a la otra:

El **gateway** valida firma e issuer antes de enrutar, de modo que un token
inválido no consume recursos de ningún microservicio.

Cada **microservicio** vuelve a validar firma e issuer, y además comprueba el
scope concreto que exige cada endpoint. Si la autorización viviera sólo en el
gateway, cualquier proceso dentro de la red interna podría llamar a los
servicios sin token: el gateway sería una puerta con un muro de un metro al lado.

La clave RSA de firma se genera al arrancar. Es lo correcto para una entrega que
no debe llevar un keystore versionado, y tiene una consecuencia que conviene
nombrar: al reiniciar el `auth-server`, los tokens emitidos antes dejan de
validar. En producción la clave vendría de un almacén externo y rotaría con
solapamiento.

### 5.4 Un scope para liquidar, distinto del de escribir

`cuentas-service` expone dos clases de operación sobre el saldo y las separa a
propósito.

Un **retiro** (`POST /cuentas/{id}/retiro`) es una operación de negocio del
dominio de cuentas: la pide un canal, pasa por un límite configurable y publica
el hecho de que ocurrió. Exige `cuentas.write`.

Un **cargo** o un **abono** (`POST /cuentas/{id}/cargo`, `/abono`) son la
liquidación de una operación cuyo dueño es otro servicio: `pagos-service` decide
que hay una transferencia y le pide a `cuentas-service` que mueva las dos puntas.
No publican ningún evento —el que publica es el que sabe qué pasó— y exigen un
scope propio, `cuentas.liquidar`, que no tiene ningún canal.

La diferencia es práctica: un cajero puede retirar, y eso queda sujeto al límite
por operación y registrado en el tópico. No debería poder mover saldo sin dejar
ese rastro llamando directo a la primitiva.

### 5.5 Consistencia en una transferencia

Una transferencia son dos llamadas remotas y no existe una transacción que
abarque a las dos. Lo que se hace al respecto, en orden de importancia:

**Se carga antes de abonar.** Si falla la primera punta, no hay nada que
deshacer. Al revés —abonar y después cargar— un fallo en la segunda dejaría
dinero creado, que es el error más caro de los dos.

**Se compensa la punta aplicada.** Si el abono al destino falla, se devuelve el
monto a la cuenta de origen y queda un movimiento de reverso en el historial. Es
una compensación de negocio, no un rollback: las tres operaciones dejan rastro.

**Si la compensación también falla, se grita.** Ahí hay un descalce contable
real, y lo único honesto es registrarlo con todos los datos necesarios para
reponerlo a mano y publicarlo en el tópico de alertas con severidad alta, en vez
de que el sistema siga como si nada.

Lo que falta para cerrarlo del todo está dicho sin adornos: una clave de
idempotencia por operación, que permitiría reintentar sin duplicar, y un registro
durable de operaciones en curso para poder retomar la compensación si el proceso
se cae en el peor momento. Con estado en memoria no se puede sostener ninguna de
las dos, y no se finge que sí. Los doce tests de `PagoServiceTest` cubren estos
caminos, incluidos los cuatro de compensación.

### 5.6 La infraestructura también pide credenciales

Las dos barreras de la sección 5.3 hablan del tráfico de negocio. Revisando el
proyecto terminado apareció una tercera puerta, y esa sí estaba abierta: los
componentes de infraestructura no pedían nada.

El caso más grave era el **Config Server**. Devuelve la configuración completa de
todos los microservicios, y ahí dentro viaja el secreto del cliente OAuth 2.0 de
`pagos-service`. Es decir que un `GET` anónimo entregaba una credencial con la
que cualquiera podía pedirle al `auth-server` un JWT perfectamente válido. Todo
el esquema de scopes quedaba neutralizado por la puerta de al lado.

**Eureka** era el segundo. El registro no es informativo: es quien decide a dónde
va el tráfico. Sin autenticación, cualquiera podía dar de baja un servicio o
registrar uno propio con el nombre de `cuentas-service` y recibir las peticiones
del gateway.

**Artemis**, tercero. Spring Boot configura el broker embebido llamando a
`setSecurityEnabled(false)`, y con un acceptor TCP abierto eso deja un camino de
escritura al dominio que no pasa por ningún token: publicar un evento en la cola
de retiros modifica el historial de una cuenta igual que una petición
autenticada.

Los tres quedaron con autenticación. El Config Server y Eureka con autenticación
básica; Artemis con un único usuario, un rol acotado y la seguridad del broker
encendida a mano, porque Spring Boot no ofrece un punto de extensión para
ponerla.

### 5.7 Tolerancia a fallos con Resilience4j

La llamada saliente de `pagos-service` hacia `cuentas-service` lleva las tres
políticas, y el orden en que se aplican no es casual. Resilience4j envuelve de
afuera hacia adentro como `Retry`, `CircuitBreaker`, `TimeLimiter`, así que el
método de fallback va declarado en `@Retry`, la capa más externa. Si estuviera en
`@CircuitBreaker`, el primer fallo se convertiría en un valor de retorno válido,
`Retry` vería éxito y no reintentaría nunca: el fallback tiene que ser lo último
que ocurre, no lo primero.

Por qué cada una:

El **TimeLimiter** acota cuánto espera el cliente. Un servicio que acepta la
conexión y nunca contesta es peor que uno caído, porque consume hilos sin dar
señales de fallo.

El **Retry** cubre el fallo transitorio: un reinicio, un paquete perdido. Un solo
reintento, 200 ms después. Un tercer intento contra un servicio caído agrega más
de dos segundos a la espera del usuario para muy poca probabilidad extra de
éxito.

El **CircuitBreaker** cubre el fallo sostenido. Cuando la mitad de las llamadas
de la ventana falla, deja de intentar y responde de inmediato con el fallback.
Eso protege a las dos partes: a nosotros de acumular esperas, y a
`cuentas-service` de recibir tráfico mientras arranca.

Los números forman un presupuesto de tiempo que tiene que ser coherente con los
timeouts del cliente HTTP, o las capas se estorban:

```
por intento:  1 s de conexión + 1 s de lectura = 2 s, acotados por el TimeLimiter en 2 s
peor caso:    2 s + 0,2 s de espera + 2 s = 4,2 s hasta la respuesta degradada
```

Si el TimeLimiter fuera más corto que el peor caso del HTTP, cortaría la espera
del cliente pero dejaría la petición viva ocupando un hilo; si fuera más largo,
el que acotaría de verdad sería el cliente HTTP y el TimeLimiter no serviría de
nada.

Y sólo se cuenta como fallo lo que es un fallo de verdad. Un 404 significa que el
servicio respondió bien y la cuenta no existe: si contara como fallo, consultar
cuentas inexistentes abriría el circuito de las válidas. Un 401 o un 403 son un
problema de configuración permanente, y abrir el circuito sólo lo disfrazaría de
caída pasajera.

**Dónde hay fallback y dónde no.** La consulta de la ficha lo tiene: se entrega
el historial sin los datos de la cuenta, marcado como `DEGRADADO`, porque para
quien consulta movimientos el nombre del titular es un adorno y el historial es
el dato que vino a buscar. Las primitivas de liquidación no lo tienen, y eso es
la decisión importante: un cargo que no se pudo aplicar no tiene versión
degradada. O el dinero se movió o no se movió, y devolver "aprobado en modo
degradado" sería decirle al cliente que su transferencia salió cuando el saldo no
cambió.

### 5.8 Arquitectura de eventos: dos brokers, a propósito

El sistema usa Kafka y JMS a la vez, y no por acumular tecnologías. Son dos
formas distintas de mensaje:

**La cola JMS lleva instrucciones con destinatario.** `pagos-service` tiene que
anotar un retiro en el historial, exactamente una vez, y si está caído el mensaje
debe esperarlo. Eso es una cola, y JMS la modela bien.

**Los tópicos de Kafka llevan hechos sin destinatario.** "Esta transacción
ocurrió", "hay una alerta". El productor no sabe quién lo necesita ni cuántos
son. Hoy los consume `clientes-service`; mañana puede sumarse analítica o
notificaciones sin que el productor cambie una línea. Para eso sirve un tópico
con retención y consumidores independientes.

Mandar los dos por el mismo canal obligaría a elegir: con una cola, el segundo
consumidor que apareciera le robaría mensajes al primero; con un tópico, habría
que inventar la garantía de procesamiento único que la cola ya da.

| Canal | Destino | Qué viaja | Productor | Consumidor |
|---|---|---|---|---|
| JMS | cola `banco.retiros` | Retiro aprobado | `cuentas-service` | `pagos-service` |
| Kafka | tópico `banco.transacciones-completadas` | Retiros, depósitos y transferencias aplicados | `cuentas-service`, `pagos-service` | `clientes-service` |
| Kafka | tópico `banco.alertas-seguridad` | Rechazos sospechosos, circuito abierto, compensación fallida | `cuentas-service`, `pagos-service` | `clientes-service` |

Tres detalles de la configuración que vale nombrar:

**La clave del mensaje es el número de cuenta.** Kafka garantiza el orden dentro
de una partición y una misma clave cae siempre en la misma partición, así que un
consumidor nunca ve el segundo retiro de una cuenta antes que el primero, aunque
el tópico tenga tres particiones y varios consumidores.

**El consumidor declara a qué tipo deserializar, en vez de leerlo del
encabezado.** Lo habitual es dejar que el productor escriba el nombre de su clase
y que el consumidor lo use; eso acopla los servicios por el nombre de sus
paquetes y, peor, le pediría a este proceso instanciar una clase cualquiera del
classpath si alguien manipulara el encabezado.

**Cada deserializador va envuelto en `ErrorHandlingDeserializer`.** Sin eso, un
mensaje con JSON inválido hace fallar la deserialización antes de que el listener
exista, el contenedor reintenta el mismo offset para siempre y el consumo del
tópico se detiene: un solo mensaje mal formado basta para dejar de procesar todo
lo que venga detrás.

### 5.9 Escalabilidad horizontal

Los tres microservicios de negocio se pueden replicar sin tocar configuración de
nadie. En Docker es una bandera:

```bash
docker compose up -d --scale cuentas-service=2 --scale pagos-service=2
```

Esto funciona porque esos tres servicios no declaran `container_name` ni publican
puertos al host. Un nombre de contenedor fijo impide levantar una segunda
instancia y un puerto publicado chocaría entre réplicas.

El reparto ocurre en dos lugares y los dos usan Eureka:

El **gateway** enruta con `lb://`, así que el tráfico que entra desde afuera se
distribuye entre las instancias registradas.

El **cliente de `pagos-service`** hacia `cuentas-service` es un `RestClient`
marcado con `@LoadBalanced`, cuya URL base es el nombre del servicio y no un host
fijo. Eso hace que el tráfico entre servicios también se reparta, en vez de ir
todo a la instancia que estuviera configurada. Y se resuelve en el cliente en vez
de mandar el tráfico interno por el gateway, que también balancea: el gateway es
la puerta de entrada desde afuera, y hacer pasar por él las llamadas entre
servicios agrega un salto de red y convierte la puerta en un cuello de botella.

`evidencia/local/10_escalabilidad_horizontal.log` muestra las dos instancias
registradas en Eureka y el conteo de peticiones que atendió cada una, tomado de
sus propios logs de acceso.

---

## 6. Cómo ejecutar

### 6.1 Requisitos

- JDK 21 o superior. El proyecto compila con `release 21`, así que el bytecode es
  el mismo en cualquier JDK más nuevo. El equipo de desarrollo corre JDK 24.
- Maven 3.9 o superior (o el wrapper `./mvnw` incluido).
- Docker Desktop, sólo para la orquestación en contenedores.

### 6.2 Compilar y probar

```bash
cd EFT_S9_Backend_Avanzado
mvn clean package
```

Compila los quince módulos y corre las 168 pruebas automatizadas. No hay
librerías de mocks: los dobles de prueba están escritos a mano, porque las
librerías de mocks instrumentan bytecode y se rompen al cambiar de versión del
JDK, y este proyecto tiene que compilar igual donde se desarrolló y donde se
revisa.

### 6.3 Los procesos batch

Se ejecutan y terminan; no quedan residentes.

```bash
java -jar batch-migracion/target/batch-migracion.jar --job=all
java -jar batch-migracion/target/batch-migracion.jar --job=transacciones
java -jar batch-migracion/target/batch-migracion.jar --job=transacciones \
     --batch.partition.grid-size=8 --batch.partition.thread-pool-size=8
```

### 6.4 El ecosistema completo, en local

El script de evidencia levanta todo en el orden correcto, ejercita el sistema y
lo baja al terminar:

```bash
bash evidencia/generar_evidencia.sh
```

Para levantarlo a mano, el orden importa y está detallado en
[`instrucciones.md`](instrucciones.md).

### 6.5 El ecosistema completo, en Docker

```bash
docker compose build
docker compose up -d
docker compose ps
```

El detalle, los tiempos esperados y la verificación están en
[`instrucciones.md`](instrucciones.md); el despliegue en la nube, en
[`despliegue.md`](despliegue.md).

---

## 7. Decisiones de diseño y supuestos

**Estado en memoria.** Los tres microservicios cargan los CSV al arrancar y
mantienen el estado sólo en memoria. Una versión de producción usaría una base de
datos sin que cambiara nada de la arquitectura distribuida, y es el primer
cambio que haría falta para sostener la idempotencia de la sección 5.5.

**Un retiro rechazado responde 200, no 4xx.** La petición estaba bien formada y
el servicio la evaluó. Se devuelve el motivo en el comprobante y se reservan los
4xx para peticiones mal hechas. Si un rechazo por fondos fuera un 4xx, el cliente
resiliente de quien llama lo contaría como un error de la dependencia y acabaría
abriendo el circuito por rechazos de negocio.

**El saldo de referencia del cliente no es el saldo de la cuenta.**
`clientes-service` expone un `saldoReferencial` que sale del archivo legacy y
sirve para segmentar comercialmente. La verdad del dinero vive en
`cuentas-service`. Que no coincidan tras unas operaciones es correcto, no un bug.

**Las invariantes viven dentro de la operación atómica.** El saldo que no puede
quedar negativo y el monto que debe ser positivo se comprueban dentro del
`compute` del repositorio, no en la capa de servicio. Una invariante que vive
fuera de la operación atómica no es una invariante: el registro es inmutable y
cada débito lo reemplaza por otra instancia, así que dos retiros simultáneos
pueden partir del mismo saldo y perderse uno. Hay tests de concurrencia con
veinte hilos que cubren ese caso en los tres repositorios.

**El canal tiene límites propios, más estrictos que el dominio.** El cajero
rechaza sobre 500.000 antes de llamar a nadie; `cuentas-service` tiene su propio
límite configurable. No es redundancia: un cajero dispensa billetes y tiene
restricciones físicas que la cuenta no conoce, y validar en el canal ahorra una
llamada remota.

**Los logs incluidos contienen la URL de Eureka con su contraseña en claro.** Con
los valores por defecto del repositorio es inocuo, pero si alguna vez se
despliega con credenciales reales, los logs no se publican tal cual.

---

## 8. Evidencia de ejecución

El sistema se ejecutó y se registró en **tres entornos**, cada uno porque
demuestra algo que los otros no pueden. El índice de los tres está en
[`evidencia/LEEME.md`](evidencia/LEEME.md), y conviene empezar por ahí:

| Carpeta | Entorno | Qué demuestra |
|---|---|---|
| [`evidencia/local/`](evidencia/local/) | Los jar, con un JDK y sin Docker | Que la lógica funciona y que la evidencia es reproducible en cualquier máquina |
| [`evidencia/docker/`](evidencia/docker/) | Contenedores en el equipo de desarrollo | Que las imágenes se construyen y la orquestación levanta el ecosistema completo |
| [`evidencia/nube/`](evidencia/nube/) | Una instancia EC2 en AWS | Que el sistema se despliega y corre en la nube, con los microservicios y los dos brokers fuera del equipo local |

Los once registros de `evidencia/local/`:

| Archivo | Qué demuestra |
|---|---|
| `01_compilacion_y_pruebas.log` | Compilación de los quince módulos y las 168 pruebas |
| `02_batch_migracion.log` | Los tres jobs sobre el dataset legacy, el mismo resultado con una, cuatro y ocho particiones, la reejecución automática ante un fallo provocado y el código de salida distinto de cero |
| `03_configuracion_y_discovery.log` | Config Server y Eureka autenticados, y los servicios registrados |
| `04_oauth2_y_control_de_acceso.log` | Token, claims, clave pública y los rechazos por canal y por scope |
| `05_apis_por_el_gateway.log` | Los tres microservicios respondiendo por el gateway |
| `06_mensajeria_jms.log` | Un retiro que baja el saldo en un servicio y aparece en el historial del otro por la cola, y la cola reteniendo el evento con el consumidor caído |
| `07_mensajeria_kafka.log` | Los dos tópicos con productores y consumidores reales, y la proyección de actividad en `clientes-service` |
| `08_tolerancia_a_fallos.log` | El ciclo completo del circuit breaker `CLOSED → OPEN → HALF_OPEN → CLOSED`, con respuesta degradada y la alerta en el tópico |
| `09_bff_por_canal.log` | Los tres canales devolviendo payloads distintos para la misma cuenta |
| `10_escalabilidad_horizontal.log` | Dos instancias de `cuentas-service` y el reparto de peticiones entre ellas |
| `11_estado_final.log` | Salud de los doce componentes y de la réplica levantada al escalar, más el registro final de Eureka |
| `logs/` | Salida completa de cada proceso y los logs de acceso de las instancias escaladas |

Las otras dos carpetas tienen su propio índice: `evidencia/docker/LEEME.md`
—nueve registros, con `docker compose build`, `docker image ls` y los
contenedores en ejecución— y `evidencia/nube/LEEME.md` —siete registros tomados
dentro de la instancia EC2, empezando por sus metadatos de AWS, que es lo que
distingue esa ejecución de la del equipo de desarrollo—. Los pasos para
reproducir el despliegue en la nube están en la sección 11 de
[`despliegue.md`](despliegue.md).

---

## 9. Las nueve semanas del curso

El documento [`RESUMEN_APRENDIZAJES.md`](RESUMEN_APRENDIZAJES.md) recorre lo
aprendido en las tres experiencias y cómo cada una se apoya en la anterior hasta
llegar a este sistema: Spring Batch en las semanas 1 a 3, el patrón BFF en las
semanas 4 y 5, y los microservicios en la nube en las semanas 6 a 8.
