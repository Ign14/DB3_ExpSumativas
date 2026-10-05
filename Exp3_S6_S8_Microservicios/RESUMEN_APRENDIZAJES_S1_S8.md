# Desarrollo Backend III (PBY2203) — Resumen de aprendizajes, semanas 1 a 8

Este documento recorre las ocho semanas del curso y las tres experiencias
sumativas que las acompañan. No es un resumen de contenidos de las guías: es lo
que quedó después de implementarlas, incluidas las cosas que resultaron
distintas de lo que esperaba.

Las tres experiencias se construyeron sobre **el mismo dataset legacy del Banco
XYZ** ([`KariVillagran/bank_legacy_data`](https://github.com/KariVillagran/bank_legacy_data)):
tres archivos CSV con datos sucios a propósito —montos negativos, fechas en
cuatro formatos distintos, campos vacíos, tipos fuera de dominio—. Eso hace que
las tres entregas se puedan comparar de verdad, porque el problema de negocio no
cambió: lo que cambió fue la arquitectura con la que se resolvió.

## 1. El recorrido completo

| Semana | Tema | Tipo | Qué construí |
|---|---|---|---|
| 1 | Introducción a Spring Batch y sus componentes | Formativa grupal | Diseño de la arquitectura batch: Jobs y Steps para leer, transformar y escribir los tres CSV |
| 2 | Configuración de jobs y steps | Formativa grupal | Procesamiento por chunks, Step multi-hilo, primeras políticas de reintento y omisión |
| 3 | Escalado y procesamiento paralelo | **Sumativa (Exp1)** | Tres Jobs con particionamiento (`PartitionStep`), tolerancia a fallos con skip/retry y auditoría de anomalías; benchmark de configuraciones de escalado |
| 4 | Análisis del patrón BFF | Formativa grupal | Elección de la estrategia de implementación de BFF y diseño de la personalización por canal |
| 5 | Implementación del patrón BFF | **Sumativa (Exp2)** | Seis módulos: dos servicios core con APIs REST y tres BFF (web, móvil, cajero), con medición de payloads por canal |
| 6 | Microservicios y seguridad en la nube con Spring Cloud | Formativa grupal | Config Server, Service Discovery y el primer microservicio con autenticación |
| 7 | Tolerancia a fallos y arquitectura de eventos | Formativa grupal | Elección del patrón de eventos, diagrama de la topología de mensajería e implementación con JMS |
| 8 | Microservicios y resiliencia en la nube | **Sumativa (Exp3)** | OAuth 2.0 con JWT, Resilience4j, mensajería asíncrona, siete imágenes Docker y orquestación con docker-compose |

## 2. Experiencia 1 — Spring Batch (semanas 1 a 3)

### Lo que aprendí del framework

Spring Batch no es "un programa que recorre un archivo". Es un modelo con piezas
que tienen responsabilidades separadas, y entender esa separación es lo que
permite que la tolerancia a fallos funcione:

- El **`ItemReader`** lee, el **`ItemProcessor`** valida y transforma, el
  **`ItemWriter`** escribe. Que la validación viva en el processor y no en el
  reader es lo que permite que una fila mala se omita sin detener el Job.
- El **chunk** es la unidad transaccional. No se escribe fila por fila: se
  acumula un bloque y se confirma completo. Eso cambia el costo de un error, y
  por eso existen las políticas de skip y retry.
- El **`Partitioner`** reparte el trabajo en rangos y cada partición ejecuta el
  mismo Step trabajador en su propio hilo. Es la diferencia entre "escalar" y
  "poner más hilos": las particiones tienen límites explícitos y reanudables.

Implementé tres Jobs, uno por proceso de negocio (reporte de transacciones
diarias, cálculo de intereses mensuales y estados de cuenta anuales), cada uno
con un Step particionado y un Step de agregación por Tasklet.

### Lo que no esperaba

El benchmark de escalado me dio el resultado contrario al que buscaba: la
ejecución **secuencial fue más rápida** que la particionada, en 1.000 y en 20.000
filas. Mi primera reacción fue pensar que había configurado algo mal.

No estaba mal configurado: el cuello de botella no era el procesamiento, era la
escritura. El `JpaItemWriter` escribe fila por fila contra H2 embebido, un único
motor en memoria sin red, así que varios hilos escribiendo a la vez compiten por
el mismo recurso en lugar de repartir trabajo real; a eso se suma el costo fijo
de orquestar un Step y una transacción por partición.

La lección me sirvió para las tres experiencias: **paralelizar solo ayuda si el
cuello de botella está donde se paraleliza.** Y lo segundo, que documentar un
resultado que contradice la expectativa vale más que esconderlo: dejé el
benchmark en el README con la explicación, y elegí `grid-size=4` justificando que
la arquitectura queda preparada para escalar cuando el cuello de botella sea otro
(PostgreSQL real, volúmenes de producción, procesamiento más costoso).

## 3. Experiencia 2 — El patrón BFF (semanas 4 y 5)

### La decisión de arquitectura

Hay dos formas de implementar BFF: un servicio independiente por canal, o un
único servicio que decide la forma de la respuesta según una cabecera. Elegí la
primera, y las tres razones que escribí entonces siguen pareciéndome correctas:

1. El enunciado pedía un backend personalizado por tipo de cliente, que calza con
   servicios independientes mejor que con un servicio lleno de condicionales.
2. La superficie de seguridad se audita de una mirada. El canal del cajero expone
   dos endpoints y ninguno devuelve el historial ni los datos del titular; eso es
   una propiedad del despliegue, no la esperanza de que un `if` acierte el canal.
3. Permite ajustar cada canal por separado — y encontré un caso concreto donde
   eso importó.

El costo es repetir la integración en tres lugares, resuelto con un módulo
compartido para contratos, clientes HTTP y manejo de errores.

### El caso de la compresión

Activé compresión HTTP en los tres canales, por reflejo. Al medir, la respuesta
del cajero —40 bytes, solo el saldo— **creció a 65 bytes** comprimida: el
encabezado de gzip pesa más que lo que ahorra a ese tamaño.

Intenté arreglarlo con `min-response-size`, que es justamente el umbral para
evitar comprimir respuestas chicas, y no funcionó. La razón: las respuestas se
emiten con `Transfer-Encoding: chunked`, así que Tomcat no conoce el tamaño de
antemano y el umbral nunca se aplica. La única solución era apagar la compresión
en ese canal.

Dos cosas me quedaron de ahí. Una, que una optimización sin medición puede ser
una desoptimización. Otra, que esa decisión —compresión sí en web y móvil, no en
cajero— **solo era posible por haber elegido un BFF por canal**. Con un servicio
único, los tres canales habrían compartido la configuración de Tomcat.

### El ahorro que no estaba donde lo buscaba

El payload móvil es 90% más chico que el web y el del cajero 98,8%. Pero el
ahorro que más me interesó fue otro: el BFF móvil le pide `?limite=5` al servicio
core, que devuelve 544 bytes en vez de los 3.071 del historial completo. El
tráfico interno entre servicios también cuenta, no solo el último salto hacia la
app. Recortar en el BFF habría dejado ese 82% de ahorro sobre la mesa.

## 4. Experiencia 3 — Microservicios en la nube (semanas 6 a 8)

### Configuración centralizada y service discovery

Lo que entendí de estos dos patrones es que resuelven el mismo problema desde
dos lados: **sacar de los jar la información que depende del entorno.**

El Config Server saca los valores (puertos, URLs, umbrales, secretos) y Eureka
saca las direcciones. El efecto combinado es que los cuatro microservicios de
esta entrega tienen en su propio jar exactamente dos datos: su nombre y dónde
está el Config Server. Todo lo demás se descarga al arrancar.

Un detalle que al principio me pareció un tecnicismo y resultó importante:
configuré `fail-fast: true` en el cliente de configuración. Sin eso, un Config
Server caído no hace fallar el arranque: el servicio arranca con los valores por
defecto de Spring —puerto 8080, sin seguridad, sin registrarse en Eureka— y
parece estar funcionando. Prefiero que no arranque.

### OAuth 2.0 y JWT

Lo central fue entender la diferencia entre **autenticar** y **validar**. El
`auth-server` es el único componente que conoce credenciales; los microservicios
no saben autenticar a nadie, solo verifican una firma con una clave pública. Eso
es lo que permite agregar un microservicio nuevo sin tocar el servidor de
autorización.

Elegí `client_credentials` porque en este sistema quien pide un token es una
aplicación o otro microservicio, no una persona: no hay un usuario final que
autorice el acceso a sus datos, que es el problema que resuelven
`authorization_code` y PKCE.

Tres cosas que aprendí a golpes:

- **La validación tiene que estar en cada microservicio, no solo en el gateway.**
  Si estuviera solo en el gateway, cualquier proceso dentro de la red interna
  podría llamar al microservicio directamente y operar sin token.
- **Los scopes no son decorativos.** Darle al cliente del cajero solo
  `cuentas.read` y `cuentas.write` significa que un token robado de un cajero no
  sirve para leer el historial de nadie. Le puse tests a eso, porque es una
  afirmación de seguridad y las afirmaciones de seguridad se rompen en silencio.
- **El issuer es una trampa al dockerizar.** Dentro de la red de Docker el
  servidor se alcanza como `auth-server:9000` y desde la máquina como
  `localhost:9000`. Si el issuer se deja en automático, un token pedido desde el
  host llega con un issuer que los microservicios rechazan. La tentación es
  aflojar la validación; la solución correcta es fijar el issuer con una variable
  única que usen el emisor y los validadores.

### Tolerancia a fallos: lo que de verdad cuesta

Configurar Resilience4j es escribir unas líneas de YAML. Lo difícil fue decidir
**qué cuenta como fallo**.

Mi primera versión trataba cualquier respuesta sin datos como un fallo. Eso
significaba que un cliente consultando cuentas inexistentes abría el circuito y
dejaba sin datos a las consultas de cuentas válidas: un 404 no es un fallo del
servicio, es el servicio funcionando y contestando que no existe. Terminé con un
tipo de retorno que distingue **tres** resultados donde yo tenía dos: la cuenta
existe, la cuenta no existe, y no sabemos si existe porque el otro servicio no
respondió. Esa tercera posibilidad es la que hace falta nombrar para poder
degradar con honestidad.

De ahí salió la segunda lección: **el fallback no debe inventar datos.** La ficha
degradada devuelve el historial completo y los datos de cuenta vacíos, con un
campo que dice `DEGRADADO`. Nadie debe recibir una respuesta degradada creyendo
que es real. Y el corolario: si el otro servicio no respondió, no se puede
responder 404 aunque no haya datos locales; no se concluye una ausencia a partir
de un silencio.

La tercera fue el orden de las capas. Resilience4j envuelve
`Retry → CircuitBreaker → TimeLimiter`, así que el `fallbackMethod` tiene que
estar en `@Retry`, la capa más externa. Si lo pongo en `@CircuitBreaker`, el
primer fallo se convierte en un valor de retorno válido, `Retry` ve un éxito y
no reintenta nunca: el fallback queda silenciosamente desactivando el reintento.
Es un error que no da ningún síntoma hasta que se necesita.

### Arquitectura de eventos

La decisión que más me enseñó de las tres experiencias fue **por qué el retiro va
por una cola y no por HTTP.**

Un retiro afecta a dos dominios: baja el saldo y agrega un movimiento al
historial. Con una llamada HTTP sincrónica, un servicio de movimientos caído
haría fallar el retiro — una operación que no necesita su opinión para
aprobarse. Con la cola, el retiro se aprueba, el evento espera y se procesa
cuando el consumidor vuelve.

El precio es explícito y hay que saber nombrarlo: **consistencia eventual**. Hay
una ventana de milisegundos en que el saldo ya bajó y el historial no lo refleja.
Es aceptable para un historial de consulta, y no lo sería si ese mismo historial
alimentara un control de límite diario que autoriza el retiro siguiente. La
asincronía no es gratis: se paga en garantías, y hay que saber qué garantía se
está vendiendo.

Lo otro que aprendí es que **"al menos una vez" no es "exactamente una vez"**. JMS
reentrega el mensaje si el procesamiento falla, así que el consumidor tiene que
ser idempotente o el historial se duplica. Resolverlo fue fácil (recordar los
identificadores de evento procesados); darme cuenta de que había que resolverlo
fue lo que costó.

### Docker

De la dockerización lo que más me sirvió fue el build multi-etapa con la etapa de
compilación compartida: los siete servicios usan el mismo `Dockerfile`
parametrizado, así que Docker compila el reactor una vez y reutiliza esa capa
para los siete. Sin eso serían siete compilaciones completas.

Y en `docker-compose`, que `depends_on` por sí solo no sirve: espera a que el
contenedor exista, no a que el servicio dentro esté listo. Con
`condition: service_healthy` y un healthcheck real contra el endpoint de salud,
el orden de arranque se cumple de verdad. Esto importa concretamente aquí: un
microservicio que arranca antes que el Config Server no obtiene su configuración.

## 5. El hilo conductor: el mismo problema, tres arquitecturas

| | Exp1 (S3) | Exp2 (S5) | Exp3 (S8) |
|---|---|---|---|
| **Arquitectura** | Monolito batch | 6 servicios, patrón BFF | 7 componentes distribuidos |
| **Comunicación** | Ninguna (un proceso) | HTTP sincrónico | HTTP + mensajería asíncrona |
| **Interacción** | Por lotes, sin usuario | Petición/respuesta | Petición/respuesta + eventos |
| **Configuración** | En el jar, por perfiles | En cada jar | Centralizada en el Config Server |
| **Direcciones** | No aplica | Host y puerto fijos | Service discovery con Eureka |
| **Seguridad** | No pedida | Separación de superficie expuesta | OAuth 2.0, JWT y scopes por cliente |
| **Tolerancia a fallos** | Skip y retry en el Job | Timeouts hacia los servicios core | Circuit breaker, retry, time limiter y degradación |
| **Despliegue** | Un jar | 5 procesos | `docker compose up` |
| **Qué pasa si falla una parte** | El Job omite la fila y sigue | El BFF responde 502 | El circuito se abre y se responde degradado; el evento espera en la cola |

Lo que se ve leyendo la tabla de izquierda a derecha es que **cada capacidad
nueva paga un precio**. Centralizar la configuración agrega un componente del que
todos dependen al arrancar. Distribuir agrega latencia de red y modos de falla
que antes no existían. La asincronía agrega consistencia eventual. Ninguna de
las tres arquitecturas es mejor en abstracto: la última es la más compleja, y
esa complejidad se justifica solo por lo que compra —desplegar y escalar cada
dominio por separado, y seguir funcionando cuando una parte se cae—.

## 6. Lo que me llevo, más allá del framework

Hay cinco cosas que aparecieron en las tres experiencias y que me parecen el
aprendizaje real del curso.

**Un solo punto de validación.** En Exp2 encontré, auditando mi propio código,
que `POST /api/movimientos` no aplicaba ninguna validación: aceptaba fechas
imposibles y tipos fuera de dominio que la carga del CSV sí rechazaba. Era un
agujero contable silencioso. Lo resolví extrayendo un único método por donde
pasan todas las puertas de entrada. En Exp3 esa decisión se pagó sola: cuando
agregué el consumidor de la cola, ya había un lugar por donde entrar, y la
tercera puerta nació validada sin que tuviera que acordarme.

**Distinguir un fallo de un resultado.** Aparece en los tres proyectos con
distinta cara: una fila inválida no es un error del Job, un retiro rechazado no
es un error del cliente, un 404 no es una caída del servicio. Cada vez que
confundí las dos cosas, el sistema reaccionó de más: abortando, respondiendo 500
o abriendo un circuito que no correspondía.

**La concurrencia no se arregla poniendo `synchronized` cerca.** Mi primera
versión del débito sincronizaba sobre el registro leído del mapa, y perdía
actualizaciones: el registro es inmutable y cada débito lo reemplaza por otra
instancia, así que dos retiros simultáneos partían del mismo saldo. Dos retiros
de 500 sobre un saldo de 6.500 dejaban 6.000 en vez de 5.500. Lo encontró un
test con 20 hilos, no una lectura del código. La solución fue hacer atómica la
operación completa (leer, validar, escribir) con `ConcurrentHashMap.compute`.

**Los modos permisivos mienten.** `DateTimeFormatter` en modo por defecto acepta
`31/02/2024` y lo convierte en 29 de febrero, sin avisar. Mi parser "funcionaba" y
estaba corrompiendo datos. `ResolverStyle.STRICT` lo rechaza. Cuando el trabajo
es validar datos sucios, un framework que arregla la basura por su cuenta es
peor que uno que falla.

**Auditar lo propio contra la pauta, no contra la intención.** Lo hice en Exp2 y
lo repetí en Exp3, y las dos veces encontré cosas que no habría visto leyendo el
código con la cabeza de quien lo escribió. En Exp3 el hallazgo que más me
sorprendió fue este: tenía OAuth 2.0 bien implementado, con scopes por cliente y
doble validación, y al mismo tiempo el Config Server servía la configuración
completa a cualquiera que la pidiera, con el secreto del cliente OAuth2 dentro. Un
`GET` anónimo entregaba la credencial con la que pedir un JWT válido. Mi propia
evidencia lo mostraba y yo lo había leído sin verlo, porque estaba leyendo para
confirmar que la configuración se servía bien, no para preguntarme quién podía
pedirla. La lección es que la pregunta útil no es "¿funciona lo que construí?"
sino "¿qué puede hacer alguien que no soy yo?".

De la misma auditoría salió algo más sutil y que me gustó entender: tenía
`spring.artemis.user` y `spring.artemis.password` configuradas en los dos
microservicios, y el broker las ignoraba porque Spring Boot deshabilita la
seguridad de Artemis embebido. Era configuración muerta que *parecía* seguridad.
Eso es peor que no tenerla, porque quien lea el proyecto —incluido yo mismo en
tres meses— va a creer que ese camino está cerrado.

**Probar lo que se afirma.** Las pruebas que más valor tuvieron no son las que
confirman el camino feliz: son el test de concurrencia que encontró la pérdida de
actualizaciones, el de fechas imposibles que encontró el modo permisivo, y los de
scopes que fallarían si alguien amplía los permisos de un cliente. Si escribo en
un README que el cajero no puede leer el historial, tiene que haber algo que falle
cuando eso deje de ser verdad.

Hay una categoría de test que aprendí a desconfiar en esta última auditoría: el
que pasa siempre. Tenía uno que decía verificar que un 404 no abriera el circuito,
y el doble que usaba *devolvía* un resultado en vez de lanzar una excepción — así
que el circuit breaker no tenía nada que registrar y el test pasaba con cualquier
configuración, incluso borrando la línea que decía qué cuenta como fallo. El
nombre del test afirmaba algo que el test no comprobaba, que es la peor
combinación posible: da confianza sin darla. Lo reemplacé por uno que levanta un
servidor HTTP de verdad y responde 404, y por otro que arranca un contexto de
Spring mínimo para comprobar que las anotaciones de Resilience4j están realmente
cableadas: sin contexto, esas anotaciones son inertes y un test que construya la
clase con `new` pasaría igual aunque alguien las borrara.

Un detalle práctico de las pruebas: no uso librerías de mocks. Los dobles están
escritos a mano. La razón es concreta y la aprendí en Exp2, cuando el proyecto
compiló en mi entorno y falló en el equipo donde se revisaba con un error de
instrumentación de bytecode por la versión del JDK. Una subclase de tres líneas
no tiene ese problema y además se lee sin conocer la API de ninguna librería.

## 7. Lo que falta

Para no cerrar con una lista de logros, las tres cosas que este proyecto no tiene
y que un sistema real necesitaría:

1. **Persistencia.** Los microservicios mantienen el estado en memoria y el
   broker corre sin journal en disco. Un reinicio pierde los retiros de la sesión
   y los eventos pendientes.
2. **TLS.** Todo el tráfico va en claro. Un bearer token interceptado es una
   credencial reutilizable.
3. **Observabilidad.** Hay endpoints de Actuator y logs por servicio, pero no hay
   trazabilidad distribuida: seguir un retiro a través del gateway, dos
   microservicios y una cola requiere leer cuatro logs y cruzarlos a mano. Es lo
   primero que agregaría a continuación.
