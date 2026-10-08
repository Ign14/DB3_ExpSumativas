# Guion del video de presentación

**Evaluación Final Transversal — Desarrollo Backend III (PBY2203)**
Duración objetivo: **6 minutos** (el rango pedido es de 5 a 7)
Formato: MP4, con grabación de webcam mientras se presenta

---

## Antes de grabar

**Qué dejar listo en pantalla**, cada cosa en su propia pestaña o ventana, para
no buscar nada en vivo:

1. El repositorio en GitHub, en la carpeta `EFT_S9_Backend_Avanzado`
2. Una terminal con el sistema **ya levantado** (`docker compose ps` mostrando
   todo `healthy`). Levantarlo en vivo se come tres minutos del video.
3. El diagrama `informe/diagramas/01_arquitectura.png` abierto en un visor
4. Estos cuatro archivos de evidencia abiertos, cada uno en una pestaña del
   editor:
   - `evidencia/02_batch_migracion.log`
   - `evidencia/09_bff_por_canal.log`
   - `evidencia/08_tolerancia_a_fallos.log`
   - `evidencia/10_escalabilidad_horizontal.log`
5. Una terminal limpia, lista para pegar comandos

**Comprobar antes de grabar:** que el micrófono se escuche, que la webcam esté
encendida y visible, y que el texto de la pantalla se lea al tamaño al que vas a
grabar. Un log con letra de 9 puntos no se lee en un video comprimido; subir el
editor a 16 o 18 puntos.

**Dos consejos sobre el tiempo.** Cronometrar un ensayo completo antes de la toma
buena: lo que parecen seis minutos leyendo suelen ser ocho. Y si algo se
sobrepasa, recortar del punto 1, que es el que más fácil se alarga.

---

## Minuto 0:00 – 0:40 · Presentación y resumen ejecutivo

**En pantalla:** tú en cámara, pantalla completa de webcam.

> Hola, soy Ignacio Miño Astorga, de Analista Programador Computacional en Duoc
> UC. Esta es mi evaluación final de Desarrollo Backend III.
>
> El caso es el Banco XYZ: una institución con más de treinta años operando sobre
> COBOL y scripts Shell en un mainframe. El sistema funciona, y ese es parte del
> problema: funciona lo suficiente como para que cambiarlo parezca caro, y lo
> justo como para que cada cambio tome meses.
>
> Lo que construí es el reemplazo. Son quince módulos que cubren tres frentes:
> los procesos batch migrados a Spring Batch, un backend por canal con el patrón
> BFF, y tres microservicios seguros y resilientes con Spring Cloud. Todo se
> levanta con un comando y escala horizontalmente.

---

## Minuto 0:40 – 1:40 · La arquitectura

**En pantalla:** el diagrama de arquitectura, con la webcam en una esquina.
Ir señalando con el cursor mientras hablas.

> Esta es la arquitectura completa. Tiene cuatro capas y una sola puerta de
> entrada.
>
> *(señalar arriba)* Los tres canales —navegador, móvil y cajero— no hablan con
> el dominio. Cada uno habla con su propio backend, su BFF, que agrega lo que ese
> canal necesita y recorta el resto.
>
> *(señalar el gateway)* Todo cruza por el api-gateway, que valida el token antes
> de enrutar y resuelve el destino por nombre de servicio en Eureka, no por host
> y puerto. Esa segunda parte es la que hace que replicar un microservicio sea
> gratis.
>
> *(señalar el dominio)* Abajo están los tres microservicios de dominio: cuentas,
> pagos y clientes. Cada uno es dueño de sus datos y de sus reglas.
>
> *(señalar la mensajería)* Y a la derecha, la mensajería. Uso dos brokers a
> propósito, y en un minuto explico por qué.
>
> *(señalar el batch)* Arriba a la izquierda, separado de todo lo demás, el
> proceso batch: no es un servicio, se ejecuta y termina.

---

## Minuto 1:40 – 2:30 · Resultados, parte 1: el batch y los canales

**En pantalla:** `evidencia/02_batch_migracion.log`, buscando el bloque del
`FIN job`.

> Primer resultado: los tres procesos batch. Este es el log de una ejecución
> real sobre el dataset legacy.
>
> *(señalar las cifras)* Mil filas leídas, 604 escritas, 396 omitidas. Y esas 396
> no son un fallo: son exactamente los errores que el dataset trae sembrados
> —montos negativos, fechas imposibles, tipos que no existen— y cada una quedó
> registrada con su motivo. El proceso no se detuvo.
>
> *(señalar las particiones)* Y acá se ve que corrió en cuatro particiones en
> paralelo, no en serie.

**Cambiar a:** `evidencia/09_bff_por_canal.log`, en el bloque de tamaños.

> Segundo resultado: el patrón BFF. Esta es la misma cuenta pedida por los tres
> canales. El canal web devuelve 4.918 caracteres, con el historial completo y el
> perfil del titular. El móvil, 215. El cajero, 39.
>
> Dos órdenes de magnitud de diferencia, para el mismo dato de negocio. Eso es lo
> que el patrón resuelve: el cajero no necesita el historial de nadie, y pedirlo
> sería gastar red y exponer datos que esa pantalla no muestra.

---

## Minuto 2:30 – 3:30 · Resultados, parte 2: seguridad y resiliencia

**En pantalla:** terminal. Pegar y ejecutar este bloque, que ya debería estar
copiado:

```bash
for recurso in /api/cuentas/101 /api/movimientos/101 /api/clientes/101; do
  for par in "web:$TOKEN_WEB" "movil:$TOKEN_MOVIL" "cajero:$TOKEN_CAJERO"; do
    canal="${par%%:*}"; tk="${par#*:}"
    printf "%-22s %-8s %s\n" "$recurso" "$canal" \
      "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $tk" "localhost:8080$recurso")"
  done
done
```

> Tercer resultado: la seguridad. Cada canal es un cliente OAuth 2.0 distinto, con
> sus propios scopes.
>
> *(mientras sale la salida)* Acá se ve en vivo. El canal web accede a los tres
> recursos. El móvil también, porque sólo está leyendo. Pero el cajero recibe 403
> en movimientos y en clientes, con un token de firma perfectamente válida.
>
> Eso es lo importante: el permiso no lo decide el gateway, lo decide cada
> microservicio mirando el scope. Si estuviera sólo en el gateway, cualquier
> proceso dentro de la red podría saltárselo.

**Cambiar a:** `evidencia/08_tolerancia_a_fallos.log`, en el bloque de las seis
peticiones.

> Cuarto resultado: tolerancia a fallos. Acá detuve cuentas-service y pedí la
> ficha de una cuenta seis veces seguidas.
>
> *(señalar)* La primera responde degradada y el circuito sigue cerrado. A la
> segunda, el circuito se abre, y de ahí en adelante responde de inmediato sin
> siquiera intentar la llamada.
>
> Y fíjense en qué devuelve degradado: el historial completo, con los datos de la
> cuenta en nulo. Para quien consulta movimientos, el nombre del titular es un
> adorno; el historial es lo que vino a buscar.
>
> *(bajar hasta las transiciones)* Acá está el ciclo completo: cerrado, abierto,
> medio abierto, abierto otra vez porque la primera prueba falló mientras el
> servicio arrancaba, y finalmente cerrado solo.

---

## Minuto 3:30 – 4:10 · Comparación con el sistema legacy

**En pantalla:** tú en cámara, o la tabla de comparación del informe técnico.

> Comparado con el sistema legacy, los cambios que más importan son cuatro.
>
> El batch pasó de secuencial a particionado, y de "se cae o no se cae" a tres
> niveles distintos de respuesta al fallo: una fila mala se omite y se registra,
> un fallo transitorio se reintenta, y un fallo crítico relanza el job solo.
>
> Los tres canales pasaron de compartir un backend a tener uno cada uno, con
> permisos distintos.
>
> La caída de un módulo pasó de afectar al sistema completo a abrir un circuito y
> degradar una respuesta.
>
> Y escalar pasó de ser un proyecto a ser una bandera: `--scale cuentas-service=2`
> y listo.
>
> Hay una dimensión, eso sí, en la que el sistema legacy es mejor hoy: persiste
> sus datos y el mío no. Mis microservicios mantienen el estado en memoria. Lo
> digo porque es la primera cosa de mi lista de próximos pasos.

---

## Minuto 4:10 – 5:10 · Desafíos y soluciones

**En pantalla:** tú en cámara. Este es el bloque más personal; mirarlo a la
cámara y no leerlo.

> De los problemas que enfrenté, dos me parecen los que de verdad enseñaron
> algo, y los dos aparecieron de la misma manera: auditando el proyecto cuando
> ya lo creía terminado.
>
> **El primero fue de seguridad, y lo encontré auditando el proyecto cuando ya lo
> creía terminado.** Tenía OAuth 2.0 bien implementado, con scopes por canal y
> doble validación. Y al mismo tiempo, el Config Server servía la configuración
> completa a cualquiera que la pidiera, sin credenciales. Dentro de esa
> configuración viaja el secreto del cliente OAuth 2.0 de uno de mis servicios.
>
> O sea: un GET anónimo entregaba una credencial con la que pedir un token
> perfectamente válido. Todo mi esquema de permisos quedaba neutralizado por la
> puerta de al lado. Lo mismo pasaba con Eureka y con el broker de mensajería.
>
> Lo arreglé poniéndoles autenticación a los tres. Pero lo que me llevo no es la
> solución, es cómo apareció: no lo encontré probando lo que había construido,
> sino preguntándome qué me faltaba.
>
> La misma auditoría encontró otras dos cosas que habían pasado por delante de
> ciento treinta y seis pruebas en verde: un parser de fechas que mi propia
> documentación describía como estricto y no lo era, y un DTO duplicado que hacía
> que el canal web mostrara cero movimientos sobre un historial de cuarenta. Las
> dos las arreglé, y la suite quedó en ciento sesenta y ocho pruebas porque
> agregué las que habrían detectado cada una.
>
> **El segundo fue de consistencia.** Al agregar las transferencias me topé con
> que son dos llamadas remotas y no hay una transacción que cubra a las dos. Si
> abono primero y falla el cargo, creé dinero. Si cargo primero y falla el abono,
> descontré dinero que no llegó.
>
> La segunda es reparable y la primera no, así que el orden quedó fijo: cargar,
> después abonar, y si el abono falla, devolver el monto al origen. Y si la
> compensación también falla, publicar una alerta de severidad alta con todos los
> datos para reponerlo a mano.
>
> Lo incómodo fue tener que escribir en el código que la solución está
> incompleta: falta una clave de idempotencia por operación, y con estado en
> memoria no se puede sostener. Me costó dejarlo escrito, y creo que es lo más
> honesto del proyecto.

---

## Minuto 5:10 – 5:50 · Propuestas de mejora y próximos pasos

**En pantalla:** la sección 9 del informe técnico, o tú en cámara.

> Para terminar, qué le falta a este sistema antes de ver tráfico real. Son cinco
> cosas y están todas escritas en el código, donde corresponde.
>
> **Primera: persistencia.** Es la más importante, y por una razón que no es
> obvia. Yo demuestro escalado horizontal con dos instancias repartiéndose las
> peticiones quince y quince. Eso es real. Pero como cada instancia tiene su
> propia copia del saldo en memoria, apenas alguien retira, las dos divergen. El
> reparto de tráfico funciona; la corrección del sistema escalado, no. Es el
> primer caso en que una demostración que funciona no prueba lo que parece
> probar.
>
> **Segunda: sacar la clave de firma del auth-server del proceso.** Hoy se genera
> al arrancar, así que dos instancias firmarían con claves distintas. Mientras
> siga así, ese servicio corre con una sola instancia y es el único punto único de
> falla del sistema.
>
> **Y las otras tres:** la clave de idempotencia que mencioné, TLS con
> autenticación del usuario final, y trazabilidad distribuida, porque hoy seguir
> un retiro a través del gateway, dos servicios, una cola y un tópico son cinco
> logs cruzados a mano.

---

## Minuto 5:50 – 6:00 · Cierre

**En pantalla:** tú en cámara.

> Eso es el proyecto. El código, los tres documentos y toda la evidencia de
> ejecución están en el repositorio. Gracias.

---

## Notas de edición

**Lo que no hay que hacer:** levantar el sistema en vivo, compilar en vivo, o
buscar un archivo mientras hablas. Todo lo que tarde más de cinco segundos en
pantalla sin que pase nada, va cortado.

**Lo que sí vale la pena mostrar en vivo:** el bloque de los códigos 200 y 403
del minuto 2:30. Es rápido, es visualmente claro y demuestra que el sistema está
corriendo de verdad mientras grabas.

**Si el video queda largo:** el recorte más barato es el minuto 0:40 – 1:40, la
explicación de la arquitectura. Se puede bajar a treinta segundos diciendo sólo
las cuatro capas y dejando que el diagrama hable.

**Si queda corto:** hay dos opciones. La primera, agregar en el minuto 2:30 la
demostración en vivo de un retiro que baja el saldo en un servicio y aparece en
el historial de otro por la cola; son dos comandos y se ve muy bien. La segunda,
contar en el minuto 4:10 el tercer hallazgo de la auditoría —el DTO duplicado
que mostraba cero— porque es el más fácil de explicar en treinta segundos y el
que mejor ilustra que una serialización no falla, rellena.

```bash
curl -s -H "Authorization: Bearer $TOKEN_WEB" localhost:8080/api/cuentas/101
curl -s -X POST -H "Authorization: Bearer $TOKEN_CAJERO" -H "Content-Type: application/json" \
  -d '{"monto":250,"canal":"cajero"}' localhost:8080/api/cuentas/101/retiro
sleep 5
curl -s -H "Authorization: Bearer $TOKEN_WEB" "localhost:8080/api/movimientos/101?limite=1"
```
