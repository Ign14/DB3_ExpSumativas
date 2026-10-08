# Desarrollo Backend III (PBY2203) — Resumen de aprendizajes, semanas 1 a 9

Este documento recorre las nueve semanas del curso: las tres experiencias
sumativas y la Evaluación Final Transversal que las integra. No es un resumen de
contenidos de las guías: es lo que quedó después de implementarlas, incluidas
las cosas que resultaron distintas de lo que esperaba.

Las tres experiencias se construyeron sobre **el mismo dataset legacy del Banco
XYZ** ([`KariVillagran/bank_legacy_data`](https://github.com/KariVillagran/bank_legacy_data)):
tres archivos CSV con datos sucios a propósito —montos negativos, fechas en
cuatro formatos distintos, campos vacíos, tipos fuera de dominio—. Eso hace que
las tres entregas se puedan comparar de verdad, porque el problema de negocio no
cambió: lo que cambió fue la arquitectura con la que se resolvió. La evaluación
final cambia el dataset por
[`fin_legacy_data`](https://github.com/KariVillagran/fin_legacy_data), que trae
los mismos problemas con otros nombres de archivo, y pide integrar las tres
arquitecturas en un solo sistema.

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
| 9 | Desarrollo Backend Avanzado: Spring Cloud y Batch | **Evaluación Final Transversal** | Las tres arquitecturas integradas en un sistema de quince módulos sobre el dataset `fin_legacy_data`: batch, tres BFF, tres microservicios, Kafka junto a JMS, compensación de transferencias y escalado horizontal |

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

## 5. La Evaluación Final Transversal (semana 9)

La EFT no agrega una tecnología nueva: pide poner las tres arquitecturas a
funcionar juntas, y ahí aparecieron los aprendizajes que ninguna experiencia por
separado podía dar.

### Integrar no es juntar

Mi primera idea fue que integrar era poner los tres proyectos en una carpeta y
escribir un documento que los explicara. No es eso. Al unirlos aparecieron
decisiones que antes no existían porque cada experiencia vivía sola:

Los BFF de la semana 5 hablaban con dos servicios "core" propios. En el sistema
integrado esos servicios core ya no tienen razón de existir: los microservicios
de la semana 8 cubren el mismo dominio, mejor. Los BFF pasaron a consumir el
gateway, y de paso tuvieron que volverse clientes OAuth 2.0 con credenciales
propias, algo que en la semana 5 no necesitaban porque no había nada que les
pidiera un token.

El retiro por cajero cambió de forma. En la semana 5 el BFF orquestaba dos
llamadas —debitar y registrar— y cargaba con el problema de que la segunda
fallara después de que el dinero ya había salido. Con la cola de la semana 7 esa
orquestación dejó de ser suya. El canal hace una sola llamada y el evento se
encarga del resto. **Es la primera vez que vi una capacidad de arquitectura
eliminar código de otra capa en vez de agregarlo.**

### Dos brokers no son un adorno

El caso pide Kafka y el sistema ya tenía JMS funcionando. Lo cómodo era migrar
todo a Kafka y quedarse con uno; lo que hice fue quedarme con los dos, y
entender por qué:

Una **cola** lleva una instrucción con un destinatario: alguien tiene que anotar
este retiro en el historial, exactamente una vez, y si está caído el mensaje debe
esperarlo.

Un **tópico** lleva un hecho sin destinatario: esta transacción ocurrió. El
productor no sabe quién lo necesita. Hoy lo lee un servicio; mañana pueden leerlo
tres sin que el productor cambie.

Mandar los dos por el mismo canal obliga a elegir: con una cola, el segundo
consumidor le roba mensajes al primero; con un tópico, hay que inventar la
garantía de procesamiento único que la cola ya da. Lo que aprendí no fue a
configurar Kafka: fue que la pregunta "¿cola o tópico?" se responde mirando si el
mensaje tiene un destinatario, no comparando tecnologías.

### El problema que no sabía que tenía: la transferencia

Agregar `pagos-service` con depósitos y transferencias trajo el primer caso del
curso en que **no hay una respuesta correcta y hay que elegir cuál equivocación
es más barata**. Una transferencia son dos llamadas remotas y no existe una
transacción que abarque a las dos.

Si abono primero y falla el cargo, creé dinero. Si cargo primero y falla el
abono, descontré dinero que no llegó. La segunda es reparable y la primera no, así
que el orden quedó fijo: cargar, después abonar, y compensar si la segunda falla.
Y si la compensación también falla, publicar una alerta de severidad alta con
todos los datos para reponerlo a mano, en vez de que el sistema siga como si
nada.

Lo incómodo del ejercicio fue tener que escribir en el código que la solución
está incompleta: falta una clave de idempotencia por operación, y con estado en
memoria no se puede sostener. Me costó dejarlo escrito y creo que es lo más
valioso del documento.

### Un scope nuevo que apareció solo

Al agregar las transferencias, `pagos-service` necesitaba mover saldo en
`cuentas-service`. Lo rápido era darle `cuentas.write`, el scope que ya existía
para los retiros. Pero `cuentas.write` autoriza a pedir un retiro, que es una
operación de canal con su límite y su evento, y este servicio no tiene por qué
poder pedirla: lo que necesita es cargar y abonar las puntas de una operación que
él mismo orquesta.

De ahí salió `cuentas.liquidar`, un scope que no tiene ningún canal. La lección
es sobre cómo se diseña el mínimo privilegio: no se reparte el permiso que ya
existe porque alcanza, se crea el permiso que describe lo que esa parte hace.

### Escalar horizontalmente es más fácil y más tramposo de lo que parece

Replicar un microservicio resultó ser casi gratis: quitar el nombre de contenedor
fijo y el puerto publicado del `docker-compose.yml`, y Eureka más el balanceador
hacen el resto. La evidencia muestra 15 y 15 peticiones repartidas entre dos
instancias sin tocar configuración de nadie.

Lo tramposo es que eso sólo es correcto mientras los servicios no tengan estado
propio. Mis tres microservicios cargan los CSV en memoria, así que dos instancias
de `cuentas-service` tienen cada una su copia del saldo y divergen en cuanto
alguien retira. El reparto de tráfico es real; la corrección del sistema
escalado, no. Es el primer caso del curso en que una demostración que funciona
no prueba lo que parece probar, y por eso quedó dicho en `despliegue.md` antes de
los comandos y no después.

## 6. El hilo conductor: el mismo problema, tres arquitecturas

| | Exp1 (S3) | Exp2 (S5) | Exp3 (S8) | EFT (S9) |
|---|---|---|---|---|
| **Arquitectura** | Monolito batch | 6 servicios, patrón BFF | 7 componentes distribuidos | 15 módulos: batch, 3 BFF, 3 microservicios e infraestructura |
| **Comunicación** | Ninguna (un proceso) | HTTP sincrónico | HTTP + mensajería asíncrona | HTTP balanceado + cola + dos tópicos |
| **Interacción** | Por lotes, sin usuario | Petición/respuesta | Petición/respuesta + eventos | Las tres a la vez |
| **Configuración** | En el jar, por perfiles | En cada jar | Centralizada en el Config Server | Centralizada y autenticada |
| **Direcciones** | No aplica | Host y puerto fijos | Service discovery con Eureka | Eureka, con balanceo en el gateway y entre servicios |
| **Seguridad** | No pedida | Separación de superficie expuesta | OAuth 2.0, JWT y scopes por cliente | Seis scopes, uno por canal y uno sólo para liquidar |
| **Tolerancia a fallos** | Skip y retry en el Job | Timeouts hacia los servicios core | Circuit breaker, retry, time limiter y degradación | Lo anterior, más compensación de operaciones a medio aplicar |
| **Despliegue** | Un jar | 5 procesos | `docker compose up` | `docker compose up`, escalable con `--scale` |
| **Qué pasa si falla una parte** | El Job omite la fila y sigue | El BFF responde 502 | El circuito se abre y se responde degradado; el evento espera en la cola | Lo anterior, y una transferencia a medio aplicar se compensa o se grita |

Lo que se ve leyendo la tabla de izquierda a derecha es que **cada capacidad
nueva paga un precio**. Centralizar la configuración agrega un componente del que
todos dependen al arrancar. Distribuir agrega latencia de red y modos de falla
que antes no existían. La asincronía agrega consistencia eventual. Y la última
columna agrega el precio más caro de todos: una operación que puede quedar a
medio aplicar y que hay que compensar a mano si la compensación falla.

Ninguna de las cuatro arquitecturas es mejor en abstracto. La última es la más
compleja, y esa complejidad se justifica sólo por lo que compra —desplegar y
escalar cada dominio por separado, y seguir funcionando cuando una parte se
cae—. Si el Banco XYZ tuviera cien clientes y un canal, el monolito de la
primera columna sería la respuesta correcta y las otras tres, un error caro.

## 7. Lo que me llevo, más allá del framework

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

**Revisar lo propio contra lo que exige el sistema, no contra la intención con que se escribió.** Lo hice en Exp2 y
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

## 8. Lo que falta

Para no cerrar con una lista de logros, las cinco cosas que este sistema no tiene
y que necesitaría antes de ver tráfico real. Están en orden de importancia y
todas aparecen nombradas en el código, donde corresponde:

1. **Persistencia.** Los microservicios mantienen el estado en memoria y el
   broker corre sin journal en disco. Un reinicio pierde los movimientos de la
   sesión, y dos instancias del mismo servicio divergen en cuanto alguien
   retira. Es lo que convierte el escalado horizontal en algo correcto y no sólo
   en algo que funciona.
2. **La clave de firma del `auth-server` fuera del proceso.** Se genera al
   arrancar, así que cada reinicio invalida los tokens vigentes y dos instancias
   firman con claves distintas. Mientras siga así, ese servicio corre con una
   sola instancia y es el único punto único de falla del sistema.
3. **Clave de idempotencia por operación de pago.** Es lo que permitiría
   reintentar una transferencia sin duplicarla y retomar una compensación
   interrumpida.
4. **TLS y autenticación del usuario final.** Todo el tráfico interno va en
   claro, y nada verifica a la persona detrás de un canal: los canales se
   autentican a sí mismos, no a quien los usa.
5. **Trazabilidad distribuida.** Hay endpoints de Actuator y logs por servicio,
   pero seguir un retiro a través del gateway, dos microservicios, una cola y un
   tópico requiere leer cinco logs y cruzarlos a mano.
