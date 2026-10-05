# Genera la evidencia de ejecucion sobre Docker: construye las siete imagenes,
# levanta la orquestacion completa y ejercita el ecosistema, incluidas las
# caidas, dejando la salida en evidencia/docker/.
#
# Es el equivalente de generar_evidencia.sh, que hace lo mismo con los jar
# directamente. Los dos existen porque demuestran cosas distintas:
# aquel demuestra la logica del sistema sin depender de Docker, y este demuestra
# que las imagenes y la orquestacion funcionan.
#
# Uso, desde la carpeta Exp3_S6_S8_Microservicios y con Docker Desktop corriendo:
#     powershell -ExecutionPolicy Bypass -File evidencia\generar_evidencia_docker.ps1
#
# Escrito para PowerShell 5.1, que es el que viene con Windows. Dos decisiones que
# vienen de ahi: se usa curl.exe en vez de Invoke-RestMethod, porque
# Invoke-RestMethod de 5.1 asume ISO-8859-1 cuando la respuesta no declara charset
# y las descripciones con tilde se verian mal en la evidencia; y los comandos se
# invocan como bloques de PowerShell en vez de pasarlos por cmd /c, para no pelear
# con el anidamiento de comillas.

$ErrorActionPreference = 'Continue'

$raiz = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $raiz
$dirEvidencia = Join-Path $raiz 'evidencia\docker'
New-Item -ItemType Directory -Force -Path $dirEvidencia | Out-Null

$script:logActual = $null
# UTF-8 sin BOM: el -Encoding UTF8 de PowerShell 5.1 escribe BOM, que ensucia la
# primera linea de cada log.
$script:utf8 = New-Object System.Text.UTF8Encoding($false)

function Abrir-Log([string]$nombre) {
    $script:logActual = Join-Path $dirEvidencia $nombre
    [IO.File]::WriteAllText($script:logActual, '', $script:utf8)
    Write-Host "  -> $nombre"
}

function Escribir([string]$texto) {
    [IO.File]::AppendAllText($script:logActual, $texto + "`r`n", $script:utf8)
}

function Titulo([string]$texto) {
    Escribir ''
    Escribir ("========== " + $texto + " ==========")
}

# Ejecuta un bloque y deja su salida en el log. La etiqueta es lo que se muestra
# como comando; el bloque es lo que de verdad se ejecuta.
# Ejecuta un bloque y deja su salida en el log. Con -EnVivo la va mostrando
# tambien en pantalla, que es lo que hace falta en los pasos largos: si se acumula
# todo para escribirlo al final, la construccion de las imagenes se pasa varios
# minutos sin imprimir nada y parece colgada.
function Correr([string]$etiqueta, [scriptblock]$bloque, [switch]$EnVivo) {
    Escribir ("`$ " + $etiqueta)
    $acumulado = New-Object System.Collections.Generic.List[string]
    # Docker escribe su progreso en stderr, y PowerShell envuelve esas lineas en
    # ErrorRecord: al renderizarlas aparecen con el bloque "CategoryInfo /
    # FullyQualifiedErrorId", que en el log parece un fallo cuando no hubo
    # ninguno. Se aplanan a texto plano.
    & $bloque 2>&1 | ForEach-Object {
        $linea = if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.ToString() } else { [string]$_ }
        $acumulado.Add($linea) | Out-Null
        if ($EnVivo) { Write-Host ("    " + $linea) }
    }
    if ($acumulado.Count -gt 0) { Escribir (($acumulado -join "`r`n").TrimEnd()) }
}

function Json([string]$texto) {
    if (-not $texto) { return '(sin respuesta)' }
    try {
        $objeto = $texto | ConvertFrom-Json
        # -InputObject y no la tuberia: al pasar un arreglo por tuberia,
        # ConvertTo-Json lo envuelve en {"value": [...], "Count": n} y la
        # respuesta deja de parecerse a lo que la API devolvio.
        return (ConvertTo-Json -InputObject $objeto -Depth 8)
    } catch { return $texto }
}

function Codigo([string]$url, [string]$token) {
    if ($token) {
        return (curl.exe -s -o NUL -w "%{http_code}" -H "Authorization: Bearer $token" $url)
    }
    return (curl.exe -s -o NUL -w "%{http_code}" $url)
}

# Los cuerpos JSON van en archivos temporales: pasar JSON entre comillas a un
# ejecutable nativo desde PowerShell 5.1 es una fuente conocida de sorpresas.
function Postear([string]$url, [string]$token, [string]$json) {
    $archivo = Join-Path $env:TEMP ("banco-" + [guid]::NewGuid().ToString('N') + ".json")
    Set-Content -Path $archivo -Value $json -Encoding ASCII
    try {
        return (curl.exe -s -H "Authorization: Bearer $token" -H "Content-Type: application/json" --data "@$archivo" $url)
    } finally {
        Remove-Item $archivo -ErrorAction SilentlyContinue
    }
}

function Token([string]$cliente, [string]$secreto, [string]$scopes) {
    $respuesta = curl.exe -s -u "${cliente}:${secreto}" `
        --data "grant_type=client_credentials" `
        --data-urlencode "scope=$scopes" `
        http://localhost:9000/oauth2/token
    try { return ($respuesta | ConvertFrom-Json).access_token } catch { return $null }
}

function Buscar-EnLogs([string]$servicio, [string[]]$patrones) {
    $lineas = docker compose logs $servicio --no-log-prefix 2>&1 | Select-String -SimpleMatch -Pattern $patrones
    if ($lineas) { Escribir (($lineas | ForEach-Object { $_.Line }) -join "`n") }
    else { Escribir ('(sin coincidencias en el log de ' + $servicio + ')') }
}

# Se consulta con "docker ps" filtrando por la etiqueta del proyecto, y no con
# "docker compose ps --format", porque el soporte de plantillas Go en ese
# subcomando depende de la version de Compose, mientras que en docker ps es
# estable desde siempre.
$script:proyecto = 'banco-xyz-microservicios'

function Estado-Contenedores() {
    return @(docker ps --all --filter "label=com.docker.compose.project=$script:proyecto" `
        --format "{{.Names}}  {{.Status}}" 2>&1 | Where-Object { $_ -match '\S' })
}

function Esperar-Saludables([int]$segundos = 300) {
    Write-Host "  esperando a que los 7 contenedores reporten healthy..." -NoNewline
    $limite = (Get-Date).AddSeconds($segundos)
    while ((Get-Date) -lt $limite) {
        $lineas = Estado-Contenedores
        $sanos = @($lineas | Where-Object { $_ -match '\(healthy\)' })
        if ($lineas.Count -ge 7 -and $sanos.Count -eq $lineas.Count) {
            Write-Host " listo"
            return $true
        }
        Write-Host "." -NoNewline
        Start-Sleep -Seconds 5
    }
    Write-Host " se agoto la espera"
    return $false
}

# El gateway resuelve las rutas lb:// desde su copia del registro de Eureka, que
# se refresca cada 5 segundos: hasta que la incluya, la ruta responde 503.
function Esperar-Ruta([string]$url, [string]$token, [int]$intentos = 40) {
    Write-Host "  esperando a que el gateway enrute..." -NoNewline
    for ($i = 0; $i -lt $intentos; $i++) {
        if ((Codigo $url $token) -eq '200') { Write-Host " listo"; return $true }
        Write-Host "." -NoNewline
        Start-Sleep -Seconds 3
    }
    Write-Host " sin exito"
    return $false
}

Write-Host ''
Write-Host 'Generando la evidencia de ejecucion con Docker...'
Write-Host ''

# ---------------------------------------------------------------------------
# 01 - Construccion de las imagenes
# ---------------------------------------------------------------------------
Abrir-Log '01_construccion_de_imagenes.log'
Titulo 'Version de Docker'
Correr 'docker --version' { docker --version }
Correr 'docker compose version' { docker compose version }

Titulo 'Construccion de las siete imagenes'
Escribir 'La etapa de compilacion del Dockerfile es identica para los siete'
Escribir 'servicios, asi que Docker la ejecuta una vez y reutiliza esa capa para los'
Escribir 'demas. La primera construccion descarga las dependencias de Maven y toma'
Escribir 'varios minutos.'
Write-Host '  construyendo las imagenes (esto toma varios minutos la primera vez):'
Correr 'docker compose build' { docker compose build } -EnVivo

Titulo 'Imagenes resultantes'
Correr 'docker images --filter reference=banco-xyz/*' {
    docker images --filter "reference=banco-xyz/*" --format "{{.Repository}}:{{.Tag}}  {{.Size}}"
}

# ---------------------------------------------------------------------------
# 02 - Orquestacion
# ---------------------------------------------------------------------------
Abrir-Log '02_orquestacion.log'
Titulo 'Levantando la orquestacion completa'
Correr 'docker compose up -d' { docker compose up -d }

$saludables = Esperar-Saludables

Titulo 'Estado de los contenedores'
Escribir 'La columna de salud es la que importa: docker-compose encadena el orden de'
Escribir 'arranque con condition: service_healthy contra el endpoint de salud real de'
Escribir 'cada servicio, no solo con depends_on.'
Correr 'docker compose ps' { docker compose ps }

Titulo 'Estado y puertos publicados de cada contenedor'
Correr 'docker ps --filter label=com.docker.compose.project=banco-xyz-microservicios' {
    docker ps --all --filter "label=com.docker.compose.project=$script:proyecto" `
        --format "{{.Names}}  {{.Status}}  {{.Ports}}"
}
Correr 'docker network ls --filter name=banco' { docker network ls --filter "name=banco" }

Titulo 'La resolucion por nombre funciona dentro de la red'
Escribir 'Se consulta el Config Server desde dentro del contenedor del gateway, usando'
Escribir 'el nombre de servicio como host. No hay IPs ni puertos escritos a mano.'
Correr 'docker compose exec -T api-gateway curl -s -u *** http://config-server:8888/api-gateway/default' {
    docker compose exec -T api-gateway curl -s -u banco-config:banco-config-secret http://config-server:8888/api-gateway/default
}

Titulo 'Servicios registrados en Eureka'
Correr 'curl -u *** http://localhost:8761/eureka/apps' {
    curl.exe -s -u banco-eureka:banco-eureka-secret -H "Accept: application/json" http://localhost:8761/eureka/apps
}

if (-not $saludables) {
    Titulo 'ATENCION'
    Escribir 'No todos los contenedores llegaron a healthy, asi que las secciones'
    Escribir 'siguientes pueden fallar. Revisar con: docker compose logs'
}

# ---------------------------------------------------------------------------
# 03 - OAuth 2.0 sobre los contenedores
# ---------------------------------------------------------------------------
Abrir-Log '03_oauth2.log'
$tokenWeb = Token 'banco-web-client' 'banco-web-secret' 'cuentas.read cuentas.write movimientos.read movimientos.write'
$tokenCajero = Token 'cajero-client' 'cajero-secret' 'cuentas.read cuentas.write'

Titulo 'Metadatos del servidor de autorizacion'
Escribir 'El issuer es el nombre interno de la red, no localhost: es la direccion con'
Escribir 'la que los microservicios alcanzan al auth-server para bajar la clave'
Escribir 'publica. El token pedido desde el host por el puerto publicado lleva ese'
Escribir 'mismo issuer, asi que los Resource Servers lo validan sin aflojar la'
Escribir 'comprobacion.'
Correr 'curl http://localhost:9000/.well-known/oauth-authorization-server' {
    curl.exe -s http://localhost:9000/.well-known/oauth-authorization-server
}

Titulo 'Token por client_credentials'
$respuestaToken = curl.exe -s -u "banco-web-client:banco-web-secret" `
    --data "grant_type=client_credentials" `
    --data-urlencode "scope=cuentas.read cuentas.write movimientos.read movimientos.write" `
    http://localhost:9000/oauth2/token
Escribir (Json $respuestaToken)

Titulo 'Claims de ese mismo JWT'
if ($tokenWeb) {
    $carga = $tokenWeb.Split('.')[1].Replace('-', '+').Replace('_', '/')
    while ($carga.Length % 4 -ne 0) { $carga += '=' }
    Escribir (Json ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($carga))))
} else {
    Escribir 'No se pudo obtener el token: revisar que auth-server este arriba.'
}

Titulo 'Clave publica con la que los microservicios validan la firma'
Correr 'curl http://localhost:9000/oauth2/jwks' { curl.exe -s http://localhost:9000/oauth2/jwks }

Titulo 'Sin token: 401 en el gateway y tambien en el microservicio directo'
Escribir ('gateway  /api/cuentas/103     -> ' + (Codigo 'http://localhost:8080/api/cuentas/103' $null))
Escribir ('directo  cuentas-service:8081 -> ' + (Codigo 'http://localhost:8081/cuentas/103' $null))

Titulo 'Token manipulado: la firma no valida'
Escribir ('token alterado -> ' + (Codigo 'http://localhost:8080/api/cuentas/103' ($tokenWeb + 'xx')))

Titulo 'La infraestructura tampoco esta abierta'
Escribir ('Config Server sin credenciales -> ' + (Codigo 'http://localhost:8888/movimientos-service/default' $null))
Escribir ('Config Server con credenciales -> ' + (curl.exe -s -o NUL -w "%{http_code}" -u banco-config:banco-config-secret http://localhost:8888/movimientos-service/default))
Escribir ('Eureka sin credenciales        -> ' + (Codigo 'http://localhost:8761/eureka/apps' $null))
Escribir ('Eureka con credenciales        -> ' + (curl.exe -s -o NUL -w "%{http_code}" -u banco-eureka:banco-eureka-secret http://localhost:8761/eureka/apps))
Escribir ('Actuator de resiliencia sin token -> ' + (Codigo 'http://localhost:8082/actuator/circuitbreakers' $null))
Escribir ('Actuator de resiliencia con token -> ' + (Codigo 'http://localhost:8082/actuator/circuitbreakers' $tokenWeb))

Titulo 'Minimo privilegio: el cajero no puede leer movimientos'
Escribir ('cajero GET /api/cuentas/103     -> ' + (Codigo 'http://localhost:8080/api/cuentas/103' $tokenCajero) + '  (tiene cuentas.read)')
Escribir ('cajero GET /api/movimientos/103 -> ' + (Codigo 'http://localhost:8080/api/movimientos/103' $tokenCajero) + '  (no tiene movimientos.read)')

Titulo 'Scope no registrado para el cliente: no se emite token'
Correr 'curl -u cajero-client:*** -d grant_type=client_credentials -d scope=movimientos.read' {
    curl.exe -s -u "cajero-client:cajero-secret" --data "grant_type=client_credentials" --data "scope=movimientos.read" http://localhost:9000/oauth2/token
}

# ---------------------------------------------------------------------------
# 04 - APIs y mensajeria asincrona
# ---------------------------------------------------------------------------
Abrir-Log '04_apis_y_mensajeria.log'
Esperar-Ruta 'http://localhost:8080/api/cuentas/110' $tokenWeb | Out-Null

Titulo 'Carga del dataset legacy en cada microservicio'
Buscar-EnLogs 'cuentas-service' @('filas leidas', 'cuentas distintas')
Buscar-EnLogs 'movimientos-service' @('filas leidas', 'historial cargado')

Titulo 'GET /api/cuentas/110 por el gateway'
Escribir (Json (curl.exe -s -H "Authorization: Bearer $tokenWeb" http://localhost:8080/api/cuentas/110))

Titulo 'GET /api/movimientos/110/resumen antes del retiro'
Escribir (Json (curl.exe -s -H "Authorization: Bearer $tokenWeb" http://localhost:8080/api/movimientos/110/resumen))

Titulo 'POST /api/cuentas/110/retiro con 500'
Escribir 'El retiro se aprueba en cuentas-service, que publica un evento en la cola.'
Escribir 'movimientos-service no recibe ninguna llamada HTTP por esto.'
Escribir (Json (Postear 'http://localhost:8080/api/cuentas/110/retiro' $tokenCajero '{"monto":500,"canal":"cajero"}'))
Start-Sleep -Seconds 4

Titulo 'El saldo bajo en cuentas-service'
Escribir (Json (curl.exe -s -H "Authorization: Bearer $tokenWeb" http://localhost:8080/api/cuentas/110))

Titulo 'Y el movimiento aparecio en movimientos-service, llegado por la cola'
Escribir (Json (curl.exe -s -H "Authorization: Bearer $tokenWeb" "http://localhost:8080/api/movimientos/110?limite=1"))
Escribir (Json (curl.exe -s -H "Authorization: Bearer $tokenWeb" http://localhost:8080/api/movimientos/110/resumen))

Titulo 'Los dos extremos de la cola, en los logs de los contenedores'
Buscar-EnLogs 'cuentas-service' @('publicado en la cola')
Buscar-EnLogs 'movimientos-service' @('consumido')

Titulo 'El broker: la cola declarada y la autenticacion activa'
Buscar-EnLogs 'broker-artemis' @('banco.retiros', 'acceptor TCP')

Titulo 'Rechazos de negocio: sobre el limite por operacion y sin fondos'
Escribir (Json (Postear 'http://localhost:8080/api/cuentas/110/retiro' $tokenCajero '{"monto":500001,"canal":"cajero"}'))
Escribir (Json (Postear 'http://localhost:8080/api/cuentas/110/retiro' $tokenCajero '{"monto":400000,"canal":"cajero"}'))

Titulo 'Un retiro rechazado no genera evento: el historial no cambio'
Escribir (Json (curl.exe -s -H "Authorization: Bearer $tokenWeb" http://localhost:8080/api/movimientos/110/resumen))

# ---------------------------------------------------------------------------
# 05 - Tolerancia a fallos
# ---------------------------------------------------------------------------
Abrir-Log '05_tolerancia_a_fallos.log'
Titulo 'Ficha completa, con cuentas-service arriba'
Escribir (Json (curl.exe -s -H "Authorization: Bearer $tokenWeb" http://localhost:8080/api/movimientos/110/ficha))

Titulo 'Estado inicial del circuito'
Escribir (Json (curl.exe -s -H "Authorization: Bearer $tokenWeb" http://localhost:8082/actuator/circuitbreakers))

Titulo 'Se detiene el contenedor de cuentas-service'
Correr 'docker compose stop cuentas-service' { docker compose stop cuentas-service }
Correr 'docker ps --all (estado de cada contenedor)' {
    docker ps --all --filter "label=com.docker.compose.project=$script:proyecto" `
        --format "{{.Names}}  {{.Status}}"
}
Start-Sleep -Seconds 3

Titulo 'Siete consultas a la ficha con la dependencia caida'
Escribir 'Se entrega el historial completo con los datos de cuenta vacios y el campo'
Escribir 'origenDatosCuenta en DEGRADADO: el cliente puede notar que la respuesta no'
Escribir 'trae datos reales de cuenta, en vez de recibir un error o un dato inventado.'
for ($i = 1; $i -le 7; $i++) {
    $ficha = curl.exe -s -H "Authorization: Bearer $tokenWeb" http://localhost:8080/api/movimientos/110/ficha
    try {
        $f = $ficha | ConvertFrom-Json
        Escribir ("intento $i : origen = " + $f.origenDatosCuenta + " | movimientos en el historial = " + $f.resumen.totalMovimientos)
    } catch {
        Escribir ("intento $i : " + $ficha)
    }
}

Titulo 'El circuito quedo abierto y las llamadas ya no salen a la red'
Escribir (Json (curl.exe -s -H "Authorization: Bearer $tokenWeb" http://localhost:8082/actuator/circuitbreakers))

Titulo 'El historial propio sigue respondiendo: la degradacion es parcial'
Escribir ('GET /api/movimientos/110?limite=2 -> ' + (Codigo 'http://localhost:8080/api/movimientos/110?limite=2' $tokenWeb))

Titulo 'Reintentos y transiciones, en el log del contenedor'
Buscar-EnLogs 'movimientos-service' @('resiliencia:')

Titulo 'Vuelve el contenedor de cuentas-service'
Correr 'docker compose start cuentas-service' { docker compose start cuentas-service }
Esperar-Saludables 240 | Out-Null
Write-Host '  esperando el paso a HALF_OPEN (wait-duration-in-open-state = 10 s)...'
Start-Sleep -Seconds 14
Esperar-Ruta 'http://localhost:8080/api/cuentas/110' $tokenWeb | Out-Null

Titulo 'El circuito prueba, le resulta y se cierra solo'
for ($i = 1; $i -le 4; $i++) {
    $ficha = curl.exe -s -H "Authorization: Bearer $tokenWeb" http://localhost:8080/api/movimientos/110/ficha
    try { Escribir ("intento $i : origen = " + ($ficha | ConvertFrom-Json).origenDatosCuenta) }
    catch { Escribir ("intento $i : " + $ficha) }
}
Escribir (Json (curl.exe -s -H "Authorization: Bearer $tokenWeb" http://localhost:8082/actuator/circuitbreakers))

Titulo 'Ciclo completo de transiciones del circuito'
Buscar-EnLogs 'movimientos-service' @('CIRCUITO')

# ---------------------------------------------------------------------------
# 06 - Estado final
# ---------------------------------------------------------------------------
Abrir-Log '06_estado_final.log'
Titulo 'Salud de los siete contenedores'
Correr 'docker compose ps' { docker compose ps }

Titulo 'Registro final de Eureka'
Correr 'curl -u *** http://localhost:8761/eureka/apps' {
    curl.exe -s -u banco-eureka:banco-eureka-secret -H "Accept: application/json" http://localhost:8761/eureka/apps
}

Titulo 'Bajando la orquestacion'
Correr 'docker compose down' { docker compose down }

Write-Host ''
Write-Host "Evidencia generada en $dirEvidencia"
Write-Host ''
Write-Host 'Conviene acompanarla con capturas de pantalla de:'
Write-Host '  - docker compose ps con los 7 contenedores en healthy'
Write-Host '  - docker images con las 7 imagenes banco-xyz/*'
Write-Host '  - la consola de Eureka en http://localhost:8761'
Write-Host '  - la ficha respondiendo DEGRADADO con cuentas-service detenido'
Write-Host ''
