# Banco XYZ — Microservicios resilientes y seguros en la nube con Spring Cloud

**Desarrollo Backend III (PBY2203) — Exp3, Semanas 6, 7 y 8**
**Actividad formativa (S6):** *Implementando microservicios y seguridad en la nube con Spring Cloud*
**Actividad formativa (S7):** *Configurando tolerancia a fallos y arquitectura de eventos con microservicios en la nube*
**Actividad sumativa (S8):** *Desarrollando microservicios y resiliencia en la nube con Spring Cloud*

## 1. Objetivo del proyecto

Llevar el sistema del Banco XYZ a una arquitectura de **microservicios
distribuidos, seguros y resilientes**, lista para desplegarse en un entorno
cloud con un solo comando.

El proyecto está construido sobre el mismo dataset legacy de las experiencias
anteriores ([`KariVillagran/bank_legacy_data`](https://github.com/KariVillagran/bank_legacy_data)),
y agrega las cinco capacidades que piden las semanas 6 a 8:

1. **Configuración centralizada** con Spring Cloud Config Server.
2. **Service discovery** con Netflix Eureka.
3. **Seguridad con OAuth 2.0 y JWT**, con un servidor de autorización propio y
   cada microservicio funcionando como Resource Server.
4. **Tolerancia a fallos con Resilience4j**: circuit breaker, reintentos,
   límite de tiempo y respuesta degradada.
5. **Mensajería asíncrona con JMS** sobre Apache ActiveMQ Artemis, para una
   arquitectura orientada a eventos.

Todo el ecosistema está dockerizado: siete imágenes y un `docker-compose.yml`
que las orquesta con control de orden de arranque.

## 2. Continuidad respecto a las semanas 6 y 7

### 2.1 La base de la semana 6

La actividad formativa de la semana 6 pedía tres cosas mínimas: un config
server consumido por al menos un microservicio, un service discovery con al
menos un microservicio registrado, y un microservicio con tolerancia a fallos y
autenticación. Esta entrega mantiene esa base y la extiende a todo el
ecosistema, porque el valor de estos patrones se ve recién cuando hay más de un
servicio detrás:

| Requisito de la semana 6 | Mínimo pedido | Lo que hay en esta entrega |
|---|---|---|
| Config server | Consumido por 1 microservicio | Consumido por 4: `auth-server`, `api-gateway`, `cuentas-service` y `movimientos-service` |
| Service discovery | 1 microservicio registrado | 4 registrados en Eureka, y el gateway resuelve las rutas por nombre (`lb://`) en vez de host y puerto |
| Microservicio con tolerancia a fallos y autenticación | 1 microservicio | 2 microservicios de negocio como Resource Server, y `movimientos-service` con las tres políticas de Resilience4j sobre su dependencia |

### 2.2 La decisión de la semana 7

La semana 7 pedía elegir un patrón de arquitectura de eventos, diagramarlo e
implementarlo. El patrón elegido es **event-carried state transfer** sobre una
cola punto a punto, y la decisión está argumentada junto al diagrama y al
catálogo de mensajes en [`docs/arquitectura_eventos.md`](docs/arquitectura_eventos.md).

El resumen: el retiro de dinero se modela como un evento
(`RetiroRealizadoEvento`) que `cuentas-service` publica en la cola
`banco.retiros` y que `movimientos-service` consume para ampliar el historial.
El evento lleva dentro todos los datos que el consumidor necesita, así que no
hay una llamada HTTP de vuelta.

### 2.3 Lo que agrega la semana 8

Sobre esa base, la actividad sumativa pide tres cosas concretas: implementar
OAuth 2.0, crear imágenes Docker de los microservicios y orquestarlas con
`docker-compose`. Las tres están en las secciones 5 y 8.

## 3. Cómo se cubre cada criterio de la evaluación

| Criterio | Dónde está | Evidencia |
|---|---|---|
| Implementa OAuth 2.0 con flujo funcional que asegura la protección de datos y servicios | `auth-server` emite JWT por `client_credentials`; gateway y ambos microservicios validan firma, issuer y scopes | `evidencia/03_oauth2_y_control_de_acceso.log` |
| Crea imágenes Docker funcionales para todos los microservicios | `Dockerfile` multi-etapa parametrizado por módulo; una imagen por cada uno de los 7 componentes | sección 8.2 |
| Configura `docker-compose.yaml` orquestando todos los componentes | `docker-compose.yml`: 7 servicios, red propia, healthchecks y orden de arranque por dependencias | sección 8.2 |
| Configura mecanismos de tolerancia a fallos con Resilience4j | `CuentasClienteResiliente` con circuit breaker, retry y time limiter, y respuesta degradada | `evidencia/06_tolerancia_a_fallos.log` |
| Integra mensajería asíncrona con Kafka o JMS | JMS sobre Artemis: productor en `cuentas-service`, consumidor en `movimientos-service` | `evidencia/05_mensajeria_asincrona.log` y `07_cola_retiene_eventos.log` |
| Entrega código fuente, documentación y evidencia de ejecución | Este repositorio, este README y la carpeta `evidencia/` | sección 11 |

## 4. Arquitectura

```
                   ┌──────────────────┐     ┌──────────────────────┐
                   │  config-server   │     │   discovery-server   │
                   │      :8888       │     │   Eureka  :8761      │
                   │ configuración    │     │  registro de         │
                   │ de los 4 servicios│    │  instancias          │
                   └────────┬─────────┘     └──────────┬───────────┘
                            │ al arrancar              │ se registran
          ┌─────────────────┼──────────────────────────┼─────────────────┐
          │                 │                          │                 │
   ┌──────┴───────┐  ┌──────┴────────┐        ┌────────┴────────┐  ┌─────┴────────┐
   │ auth-server  │  │  api-gateway  │        │ cuentas-service │  │ movimientos- │
   │    :9000     │  │     :8080     │        │      :8081      │  │ service :8082│
   │ OAuth2 + JWT │  │ valida JWT y  │        │ Resource Server │  │ Resource     │
   │ 3 clientes   │  │ enruta lb://  │        │ dominio cuentas │  │ Server       │
   └──────┬───────┘  └───────┬───────┘        └────────┬────────┘  └──────┬───────┘
          │                  │                         │                  │
          │ clave pública    ├── /api/cuentas/**  ─────▶│                  │
          │ para validar     └── /api/movimientos/** ───┼─────────────────▶│
          │                                            │                  │
          └────────────────────────────────────────────┴──────────────────┘
                                                        │                  │
                              publica evento            │                  │ consume evento
                                                        ▼                  ▼
                                            ┌──────────────────────────────────┐
                                            │  broker-artemis :61616           │
                                            │  cola banco.retiros (JMS)        │
                                            └──────────────────────────────────┘

        movimientos-service ──HTTP con JWT propio + Resilience4j──▶ cuentas-service
```

### 4.1 Los siete componentes

| Componente | Puerto | Rol |
|---|---|---|
| `config-server` | 8888 | Sirve la configuración de los cuatro servicios que la consumen. Perfil `native`: la configuración viaja en el propio repositorio |
| `discovery-server` | 8761 | Eureka. Los servicios se registran al arrancar y el gateway los resuelve por nombre |
| `broker-artemis` | 61616 (JMS) / 61617 (salud) | Broker Apache ActiveMQ Artemis con la cola `banco.retiros` |
| `auth-server` | 9000 | Spring Authorization Server. Único componente que conoce credenciales |
| `api-gateway` | 8080 | Puerta de entrada única. Valida el JWT y enruta por `lb://` |
| `cuentas-service` | 8081 | Dominio de cuentas: saldo, titular y retiros. Publica el evento de retiro |
| `movimientos-service` | 8082 | Dominio de movimientos: historial, totales y la vista agregada con tolerancia a fallos. Consume el evento |

Hay un octavo módulo, `common-events`, que no es un servicio: es el contrato
compartido (los eventos y los DTO). Es lo único que los dos microservicios de
negocio tienen en común, y está a propósito reducido a tipos de datos, sin
lógica.

### 4.2 Estructura del código

```
Exp3_S6_S8_Microservicios/
├── pom.xml                      # reactor Maven con los 8 módulos
├── Dockerfile                   # imagen común, parametrizada por módulo
├── docker-compose.yml           # orquestación de los 7 componentes
├── docs/
│   └── arquitectura_eventos.md  # decisión y diagrama de la semana 7
├── common-events/               # eventos, DTOs, excepciones y manejador de errores
├── config-server/
│   └── src/main/resources/configuracion-central/   # la configuración de todo el ecosistema
├── discovery-server/
├── broker-artemis/
├── auth-server/
├── api-gateway/
├── cuentas-service/
│   └── src/main/.../{config,domain,service,mensajeria,web}/
├── movimientos-service/
│   └── src/main/.../{config,domain,cliente,service,mensajeria,util,web}/
├── evidencia/                   # logs de ejecución + script que los genera
├── RESUMEN_APRENDIZAJES_S1_S8.md
└── README.md
```

Los dos microservicios de negocio comparten el mismo layout interno:
`domain/` tiene el estado y las invariantes, `service/` las reglas que
coordinan varias piezas, `web/` los endpoints, `config/` el cableado
(seguridad, mensajería, clientes HTTP) y `mensajeria/` los extremos de la cola.
`movimientos-service` agrega `cliente/`, que es su integración con el otro
microservicio.

### 4.3 Qué sabe cada servicio del otro

Poco a propósito, y es lo que permite desplegarlos por separado:

- `cuentas-service` **no sabe** que `movimientos-service` existe. Publica un
  evento en una cola y termina.
- `movimientos-service` sí conoce a `cuentas-service`, pero solo por su URL
  configurable y a través de un único punto (`CuentasGatewayHttp`), protegido
  por el circuit breaker.
- Ninguno de los dos sabe bajo qué ruta pública queda expuesto: el prefijo
  `/api` lo pone y lo quita el gateway.
- Ninguno sabe autenticar a nadie: solo validan un token firmado por el
  `auth-server`.

## 5. Seguridad con OAuth 2.0

### 5.1 El flujo elegido y por qué

Se implementa `client_credentials`. En este sistema quien pide un token no es
una persona frente a una pantalla de login, sino una aplicación —el canal web,
el cajero automático— o otro microservicio. No hay un usuario final que
autorice el acceso a sus datos, que es el problema que resuelven
`authorization_code` y PKCE. Lo que hay que autenticar es la aplicación, y para
eso `client_credentials` es el flujo correcto; usar `authorization_code` aquí
agregaría un paso de consentimiento sin nadie que lo diera.

### 5.2 Clientes registrados y mínimo privilegio

Cada cliente recibe solo los scopes que necesita:

| Cliente | Secreto por defecto | Scopes | Por qué |
|---|---|---|---|
| `banco-web-client` | `banco-web-secret` | `cuentas.read`, `cuentas.write`, `movimientos.read`, `movimientos.write` | Canal administrativo: ve y modifica los dos dominios |
| `cajero-client` | `cajero-secret` | `cuentas.read`, `cuentas.write` | Consulta saldo y retira. **No puede leer el historial de nadie** |
| `movimientos-service` | `movimientos-secret` | `cuentas.read` | Tráfico entre servicios, solo lectura |

Los secretos salen de la configuración central (`WEB_CLIENT_SECRET`,
`CAJERO_CLIENT_SECRET`, `MOVIMIENTOS_CLIENT_SECRET`), no del código: los valores
de la tabla son los que valen cuando no se define la variable, para que el
ecosistema se levante sin preparar nada. El de `movimientos-service` tiene que
coincidir en dos lugares, porque el `auth-server` lo registra y
`movimientos-service` lo presenta.

Ese reparto no es decorativo: un token del cajero recibe **403** al pedir
`/api/movimientos/103`, y pedir un scope no registrado para el cliente devuelve
`invalid_scope` sin emitir token. Las dos cosas están en
`evidencia/03_oauth2_y_control_de_acceso.log`, y hay tests que fallan si alguien
amplía esos scopes sin darse cuenta (`AuthorizationServerConfigTest`).

### 5.3 Dos barreras, no una

El JWT se valida en el gateway **y** en cada microservicio. La razón: si la
validación viviera solo en el gateway, cualquier proceso dentro de la red
interna podría llamar a `cuentas-service:8081` directamente y operar sin token.
El gateway sería una puerta con un muro de un metro al lado.

La división de responsabilidades es:

- El **gateway** verifica que el token exista y esté bien firmado, y rechaza
  antes de gastar recursos del microservicio.
- Cada **microservicio** vuelve a verificar la firma y el issuer, y además
  comprueba el scope concreto que exige cada endpoint (`cuentas.read` para
  consultar, `cuentas.write` para retirar).

En la evidencia se ve que ambos responden 401 sin token, y que un token con un
carácter alterado tampoco pasa.

### 5.4 La infraestructura también pide credenciales

Las dos barreras de arriba hablan del tráfico de negocio. Auditando el proyecto
contra la pauta me encontré con que había una tercera puerta, y esa sí estaba
abierta: los tres componentes de infraestructura no pedían nada.

El caso más grave era el **Config Server**. Devuelve la configuración completa de
todos los microservicios, y ahí dentro viaja el secreto del cliente OAuth2 de
`movimientos-service`. Es decir que un `GET` anónimo a
`/movimientos-service/default` entregaba una credencial con la que cualquiera
podía pedirle al `auth-server` un JWT perfectamente válido. Todo el esquema de
scopes quedaba neutralizado por la puerta de al lado, y lo peor es que mi propia
evidencia lo mostraba sin que yo lo notara.

**Eureka** era el segundo. El registro no es informativo: es quien decide a dónde
va el tráfico. Con la API abierta, cualquiera podía desregistrar una instancia
—y dejar al gateway respondiendo 503 con tokens válidos— o registrar un host
propio bajo el nombre de un microservicio y recibir las peticiones *con el Bearer
token real en la cabecera*. Ninguna de las dos cosas la detiene el JWT, porque
ocurren antes de que el JWT entre en juego.

El **broker** era el tercero. Spring Boot, al configurar Artemis embebido, llama
a `setSecurityEnabled(false)`, y con un acceptor TCP abierto eso deja un camino
de escritura al dominio que no pasa por ningún token: publicar un evento en la
cola de retiros modifica el historial de una cuenta igual que una petición
autenticada. Peor todavía, las propiedades `spring.artemis.user` y
`spring.artemis.password` estaban ahí, en la configuración de los dos
microservicios, haciendo creer que había autenticación: el broker simplemente
las ignoraba.

Los tres están cerrados ahora:

| Componente | Cómo se protege | Qué queda abierto |
|---|---|---|
| Config Server | Autenticación básica (`CONFIG_USER` / `CONFIG_PASSWORD`) | Solo `/actuator/health`, que es lo que consulta el healthcheck de Docker |
| Eureka | Autenticación básica (`EUREKA_USER` / `EUREKA_PASSWORD`); los clientes la envían en la URL del registro | Solo `/actuator/health` |
| Broker Artemis | `setSecurityEnabled(true)` con un usuario y un rol con permiso de enviar y consumir | Nada; el puerto 61616 ya no se publica al host |
| Actuator de los microservicios | `/actuator/health` e `/info` abiertos; el resto exige un scope | La salud, porque el healthcheck no tiene token |

Dos detalles que salieron de ahí y vale la pena nombrar. El primero:
`show-details: always` en el endpoint de salud hacía que un `curl` sin
credenciales devolviera el árbol completo de componentes, con la URL del Config
Server, los property sources en uso y el inventario de servicios de Eureka. Ahora
es `when-authorized`, y el healthcheck sigue funcionando porque solo necesita el
estado. El segundo: los endpoints de Resilience4j
(`/actuator/circuitbreakerevents`) publican los mensajes de las excepciones
internas del servicio, así que quedaron detrás del token.

### 5.5 El detalle del issuer con Docker

Hay una trampa clásica al dockerizar un servidor OAuth2, y conviene dejarla
documentada porque está resuelta a propósito.

Dentro de la red de Docker el `auth-server` se alcanza como
`http://auth-server:9000`, pero desde la máquina del desarrollador se alcanza
como `http://localhost:9000`. Si el issuer se dejara en automático, un token
pedido desde el host llevaría `iss: http://localhost:9000` y los microservicios,
que esperan `http://auth-server:9000`, lo rechazarían.

La solución es fijar el issuer explícitamente con una sola variable,
`AUTH_ISSUER_URI`, que el `auth-server` usa para firmar y los Resource Servers
para validar. En `docker-compose.yml` vale `http://auth-server:9000` para los
cuatro servicios; en local, `http://localhost:9000`. Un token pedido desde el
host sigue llevando el issuer interno, y la validación queda estricta en los dos
entornos sin tener que aflojarla.

Por el mismo motivo los Resource Servers declaran también `jwk-set-uri`: con
solo `issuer-uri`, Spring consulta los metadatos del `auth-server` **durante el
arranque**, lo que vuelve crítico el orden de arranque. Declarando el
`jwk-set-uri` la clave se busca recién al validar el primer token, sin perder la
validación del issuer.

### 5.6 Lo que no se hizo y por qué

- **Todo el tráfico va por HTTP, no HTTPS.** Un sistema real necesita TLS,
  porque un bearer token en claro es una credencial reutilizable por quien la
  intercepte. Se omite porque certificados autofirmados en siete contenedores
  habrían agregado complejidad sin mostrar nada nuevo del tema de la semana.
- **La clave RSA de firma se genera al arrancar.** No hay un keystore
  versionado en el repositorio, que es lo correcto, pero tiene una consecuencia:
  al reiniciar el `auth-server` los tokens emitidos antes dejan de validar.
- **Los secretos vienen de variables de entorno con valores por defecto.** Los
  del `auth-server` se cifran con BCrypt antes de guardarse, pero los valores por
  defecto están en el repositorio para que el proyecto se pueda levantar sin
  preparar nada. En producción vendrían de un gestor de secretos y no tendrían
  default.
- **La contraseña de Eureka viaja en la URL del registro.** Es la forma
  documentada de darle autenticación básica al cliente de Eureka
  (`http://usuario:clave@host:8761/eureka/`), y tiene una consecuencia incómoda:
  cuando el cliente no puede conectarse, escribe en su log la URL completa,
  credencial incluida. Lo preferí igual a dejar el registro abierto, pero la forma
  correcta sería un interceptor que ponga la cabecera `Authorization` en vez de
  llevar la credencial en la URL.

## 6. Tolerancia a fallos con Resilience4j

El escenario protegido es la llamada de `movimientos-service` a
`cuentas-service` para armar la ficha de una cuenta (`/api/movimientos/{id}/ficha`).

### 6.1 Las tres políticas y qué problema resuelve cada una

| Política | Configuración | Problema que resuelve |
|---|---|---|
| **TimeLimiter** | 2 s | Un servicio que acepta la conexión y nunca responde es peor que uno caído: consume hilos sin dar señales de fallo |
| **Retry** | 2 intentos, 200 ms de espera | El fallo transitorio: un reinicio, un paquete perdido |
| **CircuitBreaker** | ventana de 10, mínimo 4 llamadas, umbral 50%, 10 s abierto | El fallo sostenido: deja de intentar y responde de inmediato. Protege a las dos partes, a nosotros de acumular esperas y al otro servicio de recibir tráfico mientras arranca |

Los números no son independientes entre sí: forman un **presupuesto de tiempo**
que tiene que ser coherente con los timeouts del cliente HTTP, y la primera
versión que escribí no lo era. Tenía el TimeLimiter en 3 s y el cliente HTTP en
2 s de conexión más 4 s de lectura: el TimeLimiter siempre ganaba, así que los
timeouts del cliente no acotaban nada y cada corte dejaba la petición HTTP viva
ocupando un hilo. Con tres reintentos encima, la respuesta degradada tardaba casi
diez segundos.

Así quedó:

| Capa | Límite | Por qué ese valor |
|---|---|---|
| Cliente HTTP | 1 s de conexión + 1 s de lectura | El peor caso del HTTP (2 s) tiene que quedar **por debajo** del TimeLimiter, o el TimeLimiter pasa a ser el único freno real |
| TimeLimiter | 2 s | Acota la latencia que ve el usuario por intento |
| Retry | 2 intentos, 200 ms entre ellos | Un tercer intento contra un servicio caído agrega más de dos segundos de espera para muy poca probabilidad extra de éxito |
| **Peor caso total** | **≈ 4,2 s** | 2 s + 0,2 s + 2 s antes de devolver la respuesta degradada |

Ese peor caso es un servicio que **acepta la conexión y no contesta**. Un servicio
caído es el caso fácil: el sistema operativo rechaza la conexión de inmediato y
cada intento falla en milisegundos. En la evidencia se ve así —algo más de dos décimas de segundo con el circuito
cerrado, que es casi toda la espera entre reintentos, y unas centésimas con el
circuito abierto—, y conviene no confundir una medición con la otra: el
presupuesto acota el peor caso, no describe el caso medido.

Lo que compra el circuit breaker se ve mejor por el otro lado: con el circuito
abierto la consulta no sale a la red, así que además de ahorrar la espera, deja de
molestar a un servicio que está intentando arrancar.

Hay una cuarta decisión que no es una política de Resilience4j pero que pertenece
a este tema: las llamadas salen en un **pool de hilos propio y acotado**, no en el
`ForkJoinPool` común. `CompletableFuture.supplyAsync` sin executor usa ese pool
común, cuyo paralelismo es el número de núcleos menos uno y que está pensado para
trabajo de CPU, no para esperar en un socket. Y hay un detalle que lo vuelve
grave: el TimeLimiter cancela el futuro a los 2 s, pero cancelar un
`CompletableFuture` **no interrumpe** el hilo que ya está ejecutando la tarea, así
que la llamada bloqueada sigue ocupando su hilo hasta que el socket se rinda. Con
el pool común, unas pocas llamadas a un servicio colgado agotan el paralelismo del
proceso completo y el circuito se abre por inanición del pool, no porque la
dependencia esté caída.

### 6.2 El orden de las capas y dónde va el fallback

Resilience4j envuelve de afuera hacia adentro: `Retry` → `CircuitBreaker` →
`TimeLimiter`. Por eso el `fallbackMethod` está declarado en `@Retry`, la capa
más externa.

Es un detalle que parece menor y no lo es: si el fallback estuviera en
`@CircuitBreaker`, el primer fallo se convertiría en un valor de retorno válido,
`Retry` vería un éxito y **no reintentaría nunca**. El fallback tiene que ser lo
último que ocurre, no lo primero.

### 6.3 Qué cuenta como fallo

Solo lo que es un fallo de verdad. La configuración registra únicamente
`ServicioNoDisponibleException` y `TimeoutException`.

Un **404** de `cuentas-service` no cuenta: significa que el servicio está sano y
la cuenta no existe. Si contara como fallo, un cliente consultando cuentas
inexistentes abriría el circuito y dejaría sin datos de cuenta a las consultas
de cuentas que sí existen. Hay un test que recorre 20 consultas de cuentas
inexistentes y verifica que el circuito sigue cerrado.

Un **401 o un 403** tampoco cuentan. Significan que nuestro token no sirve —el
secreto está mal, o el `auth-server` se reinició y cambió su clave de firma— y eso
no se arregla reintentando ni esperando: es un problema de configuración que va a
seguir ahí. Si contara como fallo, el circuito se abriría cada diez segundos y el
log diría "respuesta degradada" cuando lo que hace falta es que alguien revise las
credenciales. Por eso tienen su propia excepción, fuera de `record-exceptions` y
de `retry-exceptions`, y el fallback los registra en nivel `ERROR` con el mensaje
de qué revisar, en vez del `WARN` de una dependencia caída.

Tampoco se reintenta contra un circuito abierto: `CallNotPermittedException`
está en `ignore-exceptions`, así que la llamada va directo al fallback en vez de
gastar intentos en algo que ya se sabe que va a fallar.

### 6.4 Degradación parcial, no caída total

El fallback no inventa datos: devuelve la ficha con el historial completo y los
datos de cuenta vacíos, marcando el campo `origenDatosCuenta` como
`DEGRADADO`.

Esa marca es parte del contrato a propósito. Para quien consulta movimientos, el
nombre del titular es un adorno y el historial es el dato que vino a buscar; pero
nadie debe recibir una ficha degradada creyendo que los datos de cuenta son
reales. Por eso el cliente distingue tres resultados y no dos: la cuenta existe,
la cuenta no existe, y no sabemos si existe porque el otro servicio no
respondió.

Hay un caso que sale de ahí y vale la pena nombrar: si `cuentas-service` no
respondió, **no se devuelve 404** aunque tampoco haya historial. No se puede
concluir que la cuenta no exista a partir de un servicio que no contestó.

### 6.5 Demostración del ciclo completo

`evidencia/06_tolerancia_a_fallos.log` recorre el ciclo entero:

```
>> resiliencia: REINTENTO 1 hacia cuentas tras ServicioNoDisponibleException
>> resiliencia: CIRCUITO cuentas paso de CLOSED a OPEN
>> resiliencia: se agotaron los 2 intentos hacia cuentas
>> resiliencia: CIRCUITO cuentas paso de OPEN a HALF_OPEN      (10 s despues)
>> resiliencia: CIRCUITO cuentas paso de HALF_OPEN a CLOSED    (volvio cuentas-service)
```

Esas líneas salen del log del propio servicio, no de un endpoint: los endpoints de
Actuator muestran el estado actual, pero no sirven para reconstruir después lo que
pasó, y un reintento o una transición del circuito son justo las cosas que uno
quiere encontrar en el log al revisar un incidente al día siguiente.

Durante la ventana abierta, las siete consultas responden `DEGRADADO` con los 29
movimientos del historial intactos, y el endpoint `/actuator/circuitbreakers`
cuenta en `notPermittedCalls` las llamadas que no salieron a la red.

### 6.6 Una limitación honesta del TimeLimiter

El `TimeLimiter` corta la espera del llamador, pero no interrumpe la llamada HTTP
que quedó bloqueada en el hilo de abajo. Los dos límites hacen cosas distintas y
los dos hacen falta: el `TimeLimiter` acota la **latencia que ve el usuario**, y
los timeouts del cliente HTTP acotan el **recurso consumido**. De ahí que el peor
caso del cliente tenga que quedar por debajo del `TimeLimiter`, y no al revés.

El cliente que pide el token tiene el mismo problema y es fácil pasarlo por alto:
Spring Security lo construye con un `RestTemplate` sin timeouts, así que un
`auth-server` que acepta la conexión y no responde bloquearía la llamada para
siempre, antes incluso de que la petición a `cuentas-service` empiece. Se le
pasan los mismos timeouts explícitamente.

## 7. Mensajería asíncrona con JMS

La decisión, el diagrama y el catálogo de mensajes están en
[`docs/arquitectura_eventos.md`](docs/arquitectura_eventos.md). Lo esencial aquí:

### 7.1 Por qué JMS y Artemis, y no Kafka

El enunciado permite Kafka o JMS. Se eligió JMS sobre Apache ActiveMQ Artemis
por dos razones:

1. **El caso de uso es una cola de trabajo, no un log de eventos.** El retiro
   tiene un consumidor y debe procesarse una vez. Las garantías que distinguen a
   Kafka —retención del log, relectura desde un offset, múltiples grupos de
   consumidores independientes— no se usarían aquí.
2. **Artemis se puede ejecutar embebido en un proceso Java.** Eso permite que el
   proyecto corra completo sin Docker (sección 8.3), lo que hace la evidencia
   reproducible en cualquier máquina con solo un JDK.

### 7.2 Por qué el retiro va por la cola y no por HTTP

Es la decisión de diseño central de la semana 7. Un retiro afecta a dos
dominios: baja el saldo en `cuentas-service` y agrega un movimiento al historial
en `movimientos-service`.

Si eso se hiciera con una llamada HTTP sincrónica, un `movimientos-service`
caído haría **fallar el retiro** — una operación que no necesita su opinión para
aprobarse. Con la cola, el retiro se aprueba, el evento espera y se procesa
cuando el consumidor vuelve. Está demostrado en
`evidencia/07_cola_retiene_eventos.log`: con `movimientos-service` apagado el
retiro se aprueba, el saldo baja, y al volver el servicio el movimiento aparece
en el historial sin intervención.

El precio es **consistencia eventual**: hay una ventana de milisegundos en que el
saldo ya bajó y el historial todavía no lo refleja. Es un precio aceptable para
un historial de consulta, y no lo sería para, por ejemplo, un control de límite
diario que dependiera de ese historial para autorizar el retiro siguiente.

### 7.3 El consumidor es idempotente

JMS garantiza entrega **al menos una vez**, no exactamente una vez: si el
procesamiento lanza una excepción, el broker reentrega el mensaje. Registrar dos
veces el mismo retiro falsearía el historial y el resumen dejaría de cuadrar con
el saldo.

`ConsumidorRetiros` recuerda los `eventoId` ya procesados y descarta la
reentrega. El conjunto está **acotado** a los últimos 10.000 identificadores: sin
tope sería una fuga de memoria monótona en un proceso de larga vida, y la única
forma de limpiarlo sería reiniciar, que es justamente lo que borra la idempotencia
entera. Con el broker sin persistencia y un solo consumidor esto alcanza; con
varias instancias habría que llevarlo a un almacén compartido, porque cada
instancia solo conoce lo que ella vio.

El identificador se marca **antes** de procesar, así que si algo falla hay que
liberarlo. Si no se liberara, el broker reentregaría el mensaje, el guard lo
descartaría como "ya procesado" y un retiro real desaparecería del historial sin
dejar rastro del error.

Un evento con datos que no cumplen las reglas del dominio **no se reintenta**: se
descarta y queda en el log. Reintentarlo sería un bucle infinito por un dato que
no va a mejorar. Pero su identificador se libera, para que una reposición
corregida sí pueda procesarse.

### 7.4 Si el broker no acepta el evento

El débito ya se aplicó, así que el retiro **no se revierte**: el dinero salió. La
respuesta trae `eventoPublicado: false`, el log queda con el `eventoId` para
poder reponer el movimiento a mano, y el cliente puede notar que su comprobante
tardará en aparecer en el historial. Hay un test que cubre exactamente este caso.

## 8. Cómo ejecutar

### 8.1 Requisitos

Para el camino con Docker: **Docker Desktop** (o Docker Engine con el plugin
Compose). No hace falta tener Java ni Maven instalados: la imagen los trae.

Para el camino local: **Java 21 o superior** (JDK) y el Maven Wrapper incluido.
El proyecto compila con `release 21` y se verificó con JDK 21. Los tests no usan
librerías de mocks —que son las que suelen romperse al cambiar de versión del
JDK—: los dobles de prueba están escritos a mano.

### 8.2 Con docker-compose (camino recomendado)

Desde la carpeta `Exp3_S6_S8_Microservicios`:

```bash
docker compose build        # construye las 7 imágenes
docker compose up -d        # las levanta en el orden correcto
docker compose ps           # estado y healthchecks
```

La primera construcción descarga las dependencias de Maven y toma unos minutos;
las siguientes reutilizan la caché. La etapa de compilación es idéntica para los
siete servicios, así que Docker la ejecuta una vez y la reutiliza.

`docker compose up` respeta el orden de arranque, y cada servicio espera solo lo
que de verdad necesita:

| Servicio | Espera a que estén `healthy` | Por qué |
|---|---|---|
| `auth-server` | `config-server`, `discovery-server` | Toma su configuración y se registra; no usa la cola |
| `cuentas-service`, `movimientos-service` | `config-server`, `discovery-server`, `broker-artemis` | Además publican y consumen eventos |
| `api-gateway` | `config-server`, `discovery-server`, `auth-server` | Valida JWT, así que necesita al emisor arriba |

Sin esto, un microservicio que arranca antes que el Config Server no obtiene su
configuración —y con `fail-fast` activado, no arranca—, y uno que arranca antes
que Eureka no se registra. `depends_on` por sí solo no alcanza: espera a que el
contenedor exista, no a que el servicio dentro esté listo. De ahí el
`condition: service_healthy` contra el endpoint de salud real de cada uno.

Queda disponible:

| URL | Qué es |
|---|---|
| http://localhost:8080 | API pública (gateway) |
| http://localhost:8761 | Consola de Eureka: los 4 servicios registrados (pide usuario `banco-eureka` / clave `banco-eureka-secret`) |
| http://localhost:9000/.well-known/oauth-authorization-server | Metadatos del servidor OAuth2 |
| http://localhost:8888/cuentas-service/default | La configuración que el Config Server entrega (pide usuario `banco-config` / clave `banco-config-secret`) |
| http://localhost:8082/actuator/circuitbreakers | Estado del circuit breaker (exige un JWT con `movimientos.read`) |

Para bajar todo: `docker compose down`.

El script `evidencia\generar_evidencia_docker.ps1` hace todo esto solo: construye
las imágenes, levanta la orquestación, espera a que los siete contenedores
reporten `healthy`, ejercita el ecosistema completo incluidas las caídas, y lo baja
al terminar, dejando seis logs en `evidencia/docker/`.

```powershell
powershell -ExecutionPolicy Bypass -File evidencia\generar_evidencia_docker.ps1
```

> **Nota sobre los primeros segundos.** El gateway resuelve las rutas `lb://`
> desde su copia local del registro de Eureka, no consultando a Eureka en cada
> petición. Hasta que esa copia incluya al microservicio, la ruta responde
> **503** aunque el servicio ya esté arriba. La configuración central baja el
> intervalo de refresco a 5 segundos (sección 9), así que la espera es de unos
> segundos y no del medio minuto que viene por defecto. Es comportamiento normal
> de Eureka, no un error de configuración.

### 8.3 Sin Docker, con los jar

Útil para desarrollar y para reproducir la evidencia en una máquina sin Docker.

```bash
./mvnw clean package          # compila los 8 módulos y corre los 68 tests
```

En Windows (PowerShell): `.\mvnw.cmd clean package`

Luego, en siete terminales y **en este orden** (los tres primeros son
infraestructura y deben estar arriba antes que el resto):

```bash
java -jar config-server/target/config-server.jar             # 8888
java -jar discovery-server/target/discovery-server.jar       # 8761
java -jar broker-artemis/target/broker-artemis.jar           # 61616
java -jar auth-server/target/auth-server.jar                 # 9000
java -jar cuentas-service/target/cuentas-service.jar         # 8081
java -jar movimientos-service/target/movimientos-service.jar # 8082
java -jar api-gateway/target/api-gateway.jar                 # 8080
```

Sin variables de entorno, todos los servicios usan `localhost` por defecto, así
que no hay nada más que configurar.

El script `evidencia/generar_evidencia.sh` hace todo esto solo —compila, levanta
los siete componentes en orden, ejercita el ecosistema completo incluidas las
caídas, y los baja al terminar—. Regenera los logs de la carpeta `evidencia/`.

### 8.4 Probar el ecosistema

Primero, un token. El `-u` son las credenciales del cliente OAuth2:

```bash
curl -s -u banco-web-client:banco-web-secret \
     -d grant_type=client_credentials \
     -d 'scope=cuentas.read cuentas.write movimientos.read movimientos.write' \
     http://localhost:9000/oauth2/token
```

Guardando el `access_token` en una variable:

```bash
TOKEN=$(curl -s -u banco-web-client:banco-web-secret \
  -d grant_type=client_credentials \
  -d 'scope=cuentas.read cuentas.write movimientos.read movimientos.write' \
  http://localhost:9000/oauth2/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/cuentas/103
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/movimientos/103/resumen
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/movimientos/103/ficha
```

En PowerShell:

```powershell
$body = @{ grant_type = 'client_credentials'; scope = 'cuentas.read cuentas.write movimientos.read movimientos.write' }
$cred = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('banco-web-client:banco-web-secret'))
$token = (Invoke-RestMethod -Method Post -Uri http://localhost:9000/oauth2/token `
          -Headers @{ Authorization = "Basic $cred" } -Body $body).access_token

# UTF-8 explícito: Invoke-RestMethod de PowerShell 5.1 asume ISO-8859-1 cuando la
# respuesta no declara charset, y las descripciones con tilde se verían mal.
curl.exe -s -H "Authorization: Bearer $token" http://localhost:8080/api/cuentas/103
```

Un retiro, que es lo que dispara el evento:

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"monto":500,"canal":"cajero"}' \
     http://localhost:8080/api/cuentas/103/retiro
```

El retiro necesita el scope `cuentas.write`. Después de unos segundos, el
movimiento aparece en `GET /api/movimientos/103?limite=1` sin que nadie haya
llamado a `movimientos-service`: llegó por la cola.

### 8.5 Provocar el fallo para ver el circuit breaker

```bash
docker compose stop cuentas-service            # o cerrar su terminal

curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/movimientos/103/ficha
# → origenDatosCuenta: DEGRADADO, con el historial completo

curl -H "Authorization: Bearer $TOKEN" http://localhost:8082/actuator/circuitbreakers     # state: OPEN
curl -H "Authorization: Bearer $TOKEN" http://localhost:8082/actuator/circuitbreakerevents

docker compose start cuentas-service
# a los ~10 s el circuito pasa a HALF_OPEN y se cierra solo
```

### 8.6 Endpoints disponibles

Todos pasan por el gateway en `http://localhost:8080` y exigen un JWT válido.

| Método y ruta | Scope | Qué devuelve |
|---|---|---|
| `GET /api/cuentas` | `cuentas.read` | Las 50 cuentas cargadas |
| `GET /api/cuentas/{id}` | `cuentas.read` | Una cuenta |
| `POST /api/cuentas/{id}/retiro` | `cuentas.write` | Comprobante del retiro; publica el evento |
| `GET /api/movimientos/{id}` | `movimientos.read` | Historial completo, o los últimos N con `?limite=N` (1 a 20) |
| `GET /api/movimientos/{id}/resumen` | `movimientos.read` | Totales de depósitos y egresos |
| `GET /api/movimientos/{id}/ficha` | `movimientos.read` | Vista agregada de los dos microservicios, con tolerancia a fallos |
| `POST /api/movimientos` | `movimientos.write` | Registra un movimiento nuevo |

## 9. Decisiones de diseño y supuestos

- **El broker se empaqueta como un componente del proyecto.** `broker-artemis`
  es Apache ActiveMQ Artemis hospedado en un proceso Spring Boot, con un
  *acceptor* TCP en 61616. Artemis embebido, tal como lo configura Spring Boot
  por defecto, solo acepta conexiones dentro de la misma JVM y no serviría para
  que dos microservicios se hablen; con el acceptor es un componente de red como
  cualquier otro. La ventaja de empaquetarlo así, en vez de usar una imagen
  oficial, es que el entorno local y el de Docker son idénticos y los
  microservicios tienen exactamente la misma configuración en los dos. La
  desventaja es que en producción se usaría un broker gestionado, con
  persistencia y autenticación propias.
- **El broker exige autenticación, y eso hubo que forzarlo.** Spring Boot, al
  configurar Artemis embebido, llama a `setSecurityEnabled(false)`, y no ofrece un
  punto de extensión para inyectar un gestor de seguridad en el servidor que crea.
  Se enciende la seguridad desde el `ArtemisConfigurationCustomizer` y el gestor se
  inyecta con un `BeanPostProcessor` justo antes de que el bean arranque, en vez de
  reemplazar el bean de Boot: así se conserva todo lo que Boot hace por nosotros
  —declarar las colas, aplicar los personalizadores— y solo se agrega lo que falta.
  El indicador de salud JMS de Spring Boot hubo que reemplazarlo por uno propio,
  porque usa la factoría de conexiones embebida, que no lleva credenciales, y
  reportaría `DOWN` con el broker perfectamente sano.
- **La configuración se sirve en perfil `native`, desde el classpath del Config
  Server.** Lo habitual en producción es un backend Git versionado. Se eligió
  `native` para que quien clone el repositorio levante el ecosistema completo
  sin depender de credenciales ni de un repositorio externo.
- **El refresco del registro de Eureka está en 5 segundos y sin deltas.** Por
  defecto el cliente refresca cada 30 segundos y pide solo los cambios. Eso
  tiene dos efectos molestos en un ecosistema chico: tras levantar un servicio,
  el gateway tarda hasta medio minuto en poder enrutar hacia él, y si una
  instancia se reinicia con el mismo identificador, el delta puede traer la baja
  de la instancia anterior *después* del alta de la nueva y dejarla fuera del
  caché del cliente hasta el refresco siguiente. Lo encontré justamente así: al
  reiniciar `cuentas-service` durante la demostración del circuit breaker, el
  gateway lo perdió por un ciclo y la sección siguiente de la evidencia falló
  con un 503 que no tenía nada que ver con lo que estaba probando. Con refresco
  corto y registro completo en cada consulta el problema desaparece, y el costo
  para siete componentes es despreciable. En un despliegue con cientos de
  instancias esa decisión se revisaría.
- **El límite de retiro por operación vive en la configuración central, no en el
  código.** Son `$500.000` y se pueden cambiar sin recompilar ni volver a
  desplegar. Hay dos niveles de rechazo que conviene no confundir: ese límite es
  una regla de negocio configurable, mientras que "el saldo no puede quedar
  negativo" es una invariante del dominio y vive dentro de la operación atómica
  que aplica el débito, porque ninguna configuración debería poder desactivarla.
  (En Exp2 el límite vivía en el BFF del cajero porque era una regla de ese
  canal; aquí no hay capa BFF, así que pasa a ser una regla del dominio de
  cuentas, configurable.)
- **Reglas de validación heredadas de Exp1 y Exp2.** Las de cuentas (saldo ≥ 0,
  edad entre 18 y 120, tipo dentro del dominio) y las de movimientos (fecha
  interpretable, monto > 0, corrección de "depósito" con tilde, descripción
  vacía completada) son las mismas de la migración batch de la semana 3. Del
  dataset de 1.000 filas quedan 399 filas válidas para 50 cuentas distintas, y
  686 movimientos válidos repartidos en 20 cuentas.
- **Las reglas de movimientos están en un solo punto.** `construir()` es el
  único lugar donde se normaliza y valida un movimiento, y por ahí pasan las
  **tres** puertas de entrada: la carga del CSV, el `POST /api/movimientos` y el
  consumidor de la cola. Es lo que impide que una sea más permisiva que las
  otras.
- **Fechas en modo estricto.** El parser usa `ResolverStyle.STRICT`. En el modo
  por defecto, una fecha imposible como `31/02/2024` no falla: se ajusta en
  silencio al 29 de febrero. Al validar datos sucios, inventar una fecha
  plausible es peor que descartar la fila.
- **Concurrencia en el débito.** Se aplica con `ConcurrentHashMap.compute`, que
  vuelve atómicas la lectura del saldo, la validación de fondos y la escritura.
  Sincronizar sobre el registro leído antes no basta, porque el registro es
  inmutable y cada débito lo reemplaza por otra instancia: dos retiros
  simultáneos pueden partir del mismo saldo y perderse uno. Hay un test de
  concurrencia con 20 hilos que cubre ese caso.
- **Cuentas duplicadas en `intereses.csv`.** El dataset repite la misma cuenta en
  varias filas con valores que no siempre coinciden, y no trae fecha para
  desempatar: se conserva la última fila válida de cada cuenta. Con datos reales,
  ese criterio debería reemplazarse por una fecha de actualización.
- **Estado en memoria.** Los servicios cargan los CSV al arrancar y mantienen el
  estado solo en memoria. Una versión de producción usaría una base de datos sin
  que cambiara nada de la arquitectura distribuida.
- **Un retiro rechazado responde 200, no 4xx.** La petición estaba bien formada y
  el servicio la evaluó: el rechazo es información de negocio. Los 4xx quedan
  reservados para peticiones mal hechas (monto nulo, cero o negativo responden
  400).

## 10. Evidencia de ejecución

La carpeta `evidencia/` contiene la salida de consola de una ejecución completa,
generada por `generar_evidencia.sh`:

- `01_compilacion_y_pruebas.log`: compilación de los 8 módulos y los 68 tests.
- `02_configuracion_y_discovery.log`: la configuración que el Config Server
  entrega a cada microservicio, la confirmación en los logs de que la tomaron de
  ahí, y los 4 servicios registrados en Eureka.
- `03_oauth2_y_control_de_acceso.log`: metadatos del servidor OAuth2, emisión del
  token, claims decodificados, clave pública, y los rechazos: credenciales
  incorrectas, scope no registrado, sin token (en el gateway y en el
  microservicio directo), token manipulado, y el 403 del cajero contra
  movimientos. Incluye la comprobación de que el Config Server, Eureka y el
  actuator de resiliencia responden 401 sin credenciales y 200 con ellas.
- `04_apis_por_el_gateway.log`: los 7 endpoints a través del gateway, la carga
  del dataset, un registro de movimiento aceptado con las tres normalizaciones
  aplicadas, y las validaciones de parámetros y de cuerpo que se rechazan.
- `05_mensajeria_asincrona.log`: un retiro baja el saldo en un microservicio y
  aparece en el historial del otro por la cola, con las trazas de los dos
  extremos; más los rechazos de negocio, que no generan evento.
- `06_tolerancia_a_fallos.log`: el ciclo completo del circuit breaker
  (`CLOSED → OPEN → HALF_OPEN → CLOSED`), las siete respuestas degradadas, las
  llamadas no permitidas, las trazas del fallback y la comparación de cuánto tarda
  una respuesta degradada antes y después de que el circuito se abra.
- `07_cola_retiene_eventos.log`: con el consumidor caído, el retiro se aprueba
  igual, el evento espera en la cola y se procesa al volver el servicio.
- `08_estado_final.log`: salud de los siete componentes y registro final de
  Eureka.
- `logs/`: salida completa de cada uno de los procesos.

Los ocho logs de arriba se generaron con los jar (sección 8.3) para que esa
evidencia sea reproducible en cualquier máquina con un JDK y sin depender del
demonio de Docker. La evidencia de la ejecución **sobre contenedores** está en
`evidencia/docker/`, generada por `generar_evidencia_docker.ps1`: la construcción
de las siete imágenes, los siete contenedores en `healthy`, la resolución por
nombre dentro de la red, y los mismos escenarios de OAuth 2.0, mensajería y
circuit breaker ejecutados contra la orquestación. El `LEEME.md` de esa carpeta
dice qué criterio de la pauta cubre cada log.

Una advertencia sobre los logs incluidos: contienen la URL de Eureka con su
contraseña en claro (sección 5.6). Con los valores por defecto del repositorio es
inocuo, pero si alguna vez se despliega con credenciales reales, los logs no se
publican tal cual.

## 11. Entrega

- [x] Código fuente completo, versionable en GitHub.
- [x] Documentación (este README): objetivo, estructura del código e
      instrucciones para ejecutar el proyecto.
- [x] Evidencia de ejecución (carpeta `evidencia/`).
- [x] Imágenes Docker de los 7 componentes (`Dockerfile`).
- [x] Orquestación completa (`docker-compose.yml`).
- [x] Repositorio publicado en GitHub (cuenta personal).
- [x] Carpeta de entrega comprimida como `Exp3_S8_Nombre_Apellido`.
- [x] 68 pruebas automatizadas, sin librerías de mocks.
- [x] Auditoría del proyecto contra la pauta, con los hallazgos corregidos
      (secciones 5.4, 6.1 y 6.3).

## 12. Resumen de las ocho semanas

El documento [`RESUMEN_APRENDIZAJES_S1_S8.md`](RESUMEN_APRENDIZAJES_S1_S8.md)
recorre lo aprendido en las tres experiencias del curso —Spring Batch, el patrón
BFF y los microservicios en la nube— y cómo cada una se apoya en la anterior
sobre el mismo dataset del Banco XYZ.
