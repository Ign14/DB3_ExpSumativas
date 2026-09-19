# Banco XYZ — Backend for Frontend (BFF) por canal

**Desarrollo Backend III (PBY2203) — Exp2, Semanas 4 y 5**
**Actividad formativa (S4):** *Analizando el patrón arquitectónico con Backend for Frontend (BFF)*
**Actividad sumativa (S5):** *Implementando el patrón arquitectónico Backend for Frontend (BFF)*

## 1. Objetivo del proyecto

Implementar el patrón **Backend for Frontend (BFF)** para el Banco XYZ, creando un
backend dedicado por cada tipo de cliente — **web**, **móvil** y **cajero
automático** — que agrega y transforma la información expuesta por los
servicios backend según las necesidades reales de cada canal, en vez de
exponer un único backend genérico a todos los frontends por igual.

Se usa el mismo dataset legacy del Banco XYZ que en Exp1
([`KariVillagran/bank_legacy_data`](https://github.com/KariVillagran/bank_legacy_data),
carpeta `data/semana_3`), esta vez expuesto mediante APIs REST en lugar de
procesarse por lotes.

## 2. Continuidad respecto a la semana 4

La actividad formativa de la semana 4 ("Analizando el patrón arquitectónico
con Backend for Frontend (BFF)") pedía identificar qué estrategia de
implementación de BFF conviene al caso del Banco XYZ y esbozar la
personalización de la información por frontend. Esta entrega (semana 5)
avanza esa base hacia una implementación completa y ejecutable.

### 2.1 Estrategia de implementación elegida

Existen, en términos generales, dos estrategias para implementar BFF:

1. **Un BFF por tipo de cliente** (uno para web, uno para móvil, uno para
   cajero), cada uno como servicio independiente y desplegable por
   separado.
2. **Un único BFF configurable**, que decide qué forma de respuesta entregar
   según una cabecera o parámetro que indique el canal (`X-Client-Type`,
   por ejemplo).

Este proyecto usa la **primera estrategia (un servicio por canal)**, por
tres razones concretas para este caso:

- El enunciado pide explícitamente "un backend personalizado para cada tipo
  de cliente", lo que calza directamente con servicios independientes en
  vez de un único servicio con lógica condicional por canal.
- Los tres canales tienen necesidades de seguridad muy distintas (un cajero
  no debería siquiera *poder* alcanzar código que devuelve historial o
  datos del titular), y esa separación es más difícil de garantizar dentro
  de un solo proceso que compila y despliega junto todo el código de los
  tres canales.
- Permite optimizar y escalar cada canal por separado: si el tráfico móvil
  crece mucho más que el de cajeros, se escala solo `bff-movil` sin tocar
  los demás.

El costo de esta estrategia es la duplicación de la lógica de invocación a
los servicios backend entre los tres BFF — se resuelve con el módulo
`common-model` (ver sección 4), que centraliza esa integración en un solo
lugar reutilizado por los tres.

## 3. Requerimientos del sistema y cómo se cubren

| Requerimiento del enunciado | Cómo se implementa |
|---|---|
| APIs para acceder a los datos del dataset legacy | `core-cuentas-service` y `core-movimientos-service` (sección 4) |
| BFF Web: datos completos, soporte para interfaces complejas | `bff-web`: agrega cuenta + historial completo + resumen + panel del banco |
| BFF Móvil: respuestas ligeras, datos esenciales | `bff-movil`: solo saldo, tipo de cuenta y los últimos 5 movimientos, sin nombre ni descripciones |
| BFF Cajero: interfaz segura y eficiente para retiros/saldo | `bff-cajero`: solo 2 endpoints (saldo, retiro), con límite máximo propio del canal |

## 4. Arquitectura

```
Cliente Web  ──────▶  bff-web (8091)   ─┐
Cliente Móvil ─────▶  bff-movil (8092) ─┼──▶  core-cuentas-service (8081)
Cliente Cajero ────▶  bff-cajero (8093)─┘         │
                              │                    ▼  (dato de cuenta/saldo)
                              └──────────▶  core-movimientos-service (8082)
                                                   (historial de movimientos +
                                                    resumen de transacciones)
```

- **`core-cuentas-service`** (puerto 8081): carga `intereses.csv` en memoria
  al arrancar y expone los datos maestros de cuenta (titular, edad, tipo,
  saldo), además del endpoint de débito usado por el retiro de cajero.
- **`core-movimientos-service`** (puerto 8082): carga `cuentas_anuales.csv`
  (historial de movimientos por cuenta) y `transacciones.csv` (log de
  transacciones diarias del banco, no asociado a una cuenta) y expone
  ambos.
- **`common-model`**: DTOs compartidos (`CuentaDTO`, `MovimientoDTO`, etc.)
  y los clientes HTTP tipados (`CuentasApiClient`, `MovimientosApiClient`)
  que usan los tres BFF para hablar con los servicios core. Evita que cada
  BFF reimplemente su propia llamada REST.
- **`bff-web` / `bff-movil` / `bff-cajero`**: cada uno agrega las llamadas a
  los servicios core que su canal necesita y transforma el resultado a la
  forma que le conviene a ese canal (ver sección 5). Ninguno de los tres
  conoce la existencia de los otros dos.

Ningún servicio core sabe que existen BFF: expone una API de dominio pura.
Toda la lógica de "qué necesita cada canal" vive exclusivamente en la capa
BFF, que es justamente el punto del patrón.

### 4.1 Por qué dos servicios core en vez de uno

El enunciado exige explícitamente que cada BFF "integre y agregue
información desde servicios backend" (plural). Partir los datos en dos
servicios con responsabilidades distintas (cuentas vs. movimientos) permite
demostrar una agregación real: `bff-web` y `bff-movil`, por ejemplo, cada
uno hace una llamada a `core-cuentas-service` y otra a
`core-movimientos-service` y combina ambas respuestas en un único payload
para su cliente. `bff-cajero`, en cambio, solo declara un cliente hacia
`core-cuentas-service`: el historial de movimientos no forma parte de
ninguna operación de cajero, así que ni siquiera tiene la posibilidad de
alcanzarlo.

### 4.2 Estructura del código

```
Exp2_S4_S5_BFF/
├── pom.xml                          # reactor Maven (agrega los 6 módulos)
├── common-model/                    # DTOs + clientes HTTP reutilizables
│   └── src/main/java/.../bff/common/{dto,client,exception}/
├── core-cuentas-service/            # API de cuentas (puerto 8081)
│   └── src/main/{java,resources}/   # dominio + data/intereses.csv
├── core-movimientos-service/        # API de movimientos (puerto 8082)
│   └── src/main/{java,resources}/   # dominio + data/{cuentas_anuales,transacciones}.csv
├── bff-web/                         # puerto 8091
├── bff-movil/                       # puerto 8092
├── bff-cajero/                      # puerto 8093
└── evidencia/                       # logs de arranque y de ejecución (ver sección 7)
```

Cada módulo BFF sigue el mismo layout interno (`config/` arma los clientes
hacia los core, `web/` tiene los controladores, `dto/` las formas de
respuesta propias del canal, `error/` el manejo de errores), lo que hace
fácil agregar un cuarto canal en el futuro sin reorganizar nada existente.

## 5. Los tres BFF en detalle

### 5.1 BFF Web (`/web/...`, puerto 8091)

- `GET /web/cuentas/{cuentaId}`: agrega cuenta completa + **historial
  completo** de movimientos + resumen, en un solo payload. Es el más
  "pesado" a propósito: está pensado para un dashboard de escritorio con
  espacio para mostrar todo.
- `GET /web/cuentas`: lista todas las cuentas.
- `GET /web/banco/resumen-diario`: panel administrativo exclusivo de este
  canal, con el agregado de transacciones diarias del banco completo (no
  tiene sentido en móvil ni en cajero).

### 5.2 BFF Móvil (`/movil/...`, puerto 8092)

- `GET /movil/cuentas/{cuentaId}?limite=5`: la **misma agregación** de dos
  servicios core que hace el BFF Web, pero transformada a un payload
  deliberadamente más chico: sin nombre del titular (la app ya lo tiene de
  la sesión activa), sin descripciones de movimiento, y solo los últimos N
  movimientos en vez del historial completo.

### 5.3 BFF Cajero (`/cajero/...`, puerto 8093)

- `GET /cajero/cuentas/{cuentaId}/saldo`: lo mínimo indispensable para
  mostrar en pantalla — ni nombre, ni edad, ni tipo de cuenta.
- `POST /cajero/cuentas/{cuentaId}/retiro` `{"monto": 500}`: aplica **dos**
  validaciones en capas distintas, a propósito:
  1. **Regla del canal** (en el propio `bff-cajero`): el monto no puede
     superar `$500.000` por operación — un límite que no tiene ningún
     sentido para el canal web o móvil, así que no vive en el servicio
     core sino en el BFF.
  2. **Regla del dominio** (en `core-cuentas-service`): el saldo no puede
     quedar negativo — esa invariante es la misma sin importar qué canal
     pida el débito, así que vive en el core, no repetida en cada BFF.

## 6. Cómo ejecutar

### 6.1 Requisitos

Java 21 (JDK) y el Maven Wrapper incluido (no hace falta tener Maven
instalado).

### 6.2 Compilar todo el reactor

En Windows (PowerShell), parado dentro de la carpeta `Exp2_S4_S5_BFF`:

```powershell
.\mvnw.cmd clean package
```

En macOS/Linux:

```bash
./mvnw clean package
```

### 6.3 Levantar los 5 servicios

Cada uno es un JAR Spring Boot independiente. Deben levantarse los dos
servicios core antes que los BFF (los BFF no reintentan la conexión si el
core aún no está arriba). En 5 terminales distintas:

```bash
java -jar core-cuentas-service/target/core-cuentas-service.jar        # puerto 8081
java -jar core-movimientos-service/target/core-movimientos-service.jar # puerto 8082
java -jar bff-web/target/bff-web.jar        # puerto 8091
java -jar bff-movil/target/bff-movil.jar    # puerto 8092
java -jar bff-cajero/target/bff-cajero.jar  # puerto 8093
```

### 6.4 Probar cada canal

```bash
# Cuenta de ejemplo usada en toda la evidencia: 103
curl http://localhost:8091/web/cuentas/103          # BFF Web: payload completo
curl http://localhost:8092/movil/cuentas/103        # BFF Movil: payload liviano
curl http://localhost:8093/cajero/cuentas/103/saldo # BFF Cajero: solo saldo

curl -X POST http://localhost:8093/cajero/cuentas/103/retiro \
     -H "Content-Type: application/json" -d '{"monto":500}'
```

## 7. Decisiones de diseño y supuestos documentados

- **Cuentas duplicadas en `intereses.csv`**: el dataset legacy trae 1.000
  filas para solo 50 `cuenta_id` distintos (algunas cuentas se repiten más
  de 30 veces, con valores que no siempre coinciden entre sí). Como el
  archivo no trae fecha ni ninguna marca de cuál fila es la más reciente,
  `core-cuentas-service` conserva la **última fila válida leída** para cada
  cuenta. Es una decisión arbitraria pero documentada: con un archivo real
  de producción, este criterio debería reemplazarse por una fecha de
  actualización real.
- **Reglas de validación reutilizadas de Exp1**: tanto la validación de
  cuentas (saldo ≥ 0, edad en [18,120], tipo ∈ {ahorro, préstamo,
  hipoteca}) como la de movimientos (fecha interpretable en los 4 formatos
  del dataset, monto > 0, corrección de "depósito" con tilde, descripción
  vacía reemplazada) son las mismas reglas ya validadas en la migración
  batch de la semana 3, para mantener consistencia de criterio entre ambas
  entregas del curso.
- **Estado en memoria, no persistente**: los tres servicios cargan los CSV
  una sola vez al arrancar y mantienen el estado (incluido el saldo, que
  cambia con cada retiro) solo en memoria. Es una simplificación adecuada
  para demostrar el patrón BFF; una versión de producción reemplazaría esto
  por una base de datos real, sin que la capa BFF tuviera que cambiar.
- **Autenticación**: el enunciado de la semana 5 no la exige (a diferencia
  del de la semana 4, que sí la mencionaba), así que no se implementó un
  esquema de autenticación/autorización por canal en esta entrega.

## 8. Evidencia de ejecución

La carpeta `evidencia/` contiene:

- `01_arranque_5_servicios.log`: arranque de los 5 servicios, incluyendo
  cuántas filas de cada CSV se cargaron como válidas y cuántas se
  omitieron por datos inconsistentes.
- `02_bff_web.log`: `GET /web/cuentas/103` (agregación completa),
  `GET /web/banco/resumen-diario`, y el caso de cuenta inexistente (404).
- `03_bff_movil.log`: `GET /movil/cuentas/103` (payload liviano) y el caso
  de cuenta inexistente.
- `04_bff_cajero.log`: consulta de saldo, un retiro válido, el saldo
  actualizado tras ese retiro, un retiro que excede el límite del canal, un
  retiro dentro del límite del canal pero sin fondos suficientes en la
  cuenta, y el caso de cuenta inexistente.
- `05_comparacion_payloads.log`: tamaño real en bytes de la misma cuenta
  (103) devuelto por cada canal — la respuesta móvil es **90,7% más chica**
  que la web para la misma cuenta en el mismo momento.
- `logs/`: la salida de consola completa de cada uno de los 5 procesos.

## 9. Entrega (checklist según instrucciones específicas)

- [x] Código fuente completo, versionable en GitHub.
- [x] Documentación (este README): objetivo, estructura y cómo ejecutar.
- [x] Evidencia de ejecución (carpeta `evidencia/`).
- [ ] Repositorio en GitHub (cuenta personal) — pendiente de publicar.
- [ ] Carpeta de entrega comprimida con el nombre `Exp2_S5_Nombre_Apellido` —
      pendiente.
