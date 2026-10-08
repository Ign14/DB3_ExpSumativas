# Genera la evidencia de ejecucion sobre Docker: construye las imagenes, levanta
# la orquestacion completa y ejercita el ecosistema, incluidas las caidas y el
# escalado horizontal, dejando la salida en evidencia/docker/.
#
# Es el equivalente de generar_evidencia.sh, que hace lo mismo con los jar
# directamente. Los dos existen porque demuestran cosas distintas: aquel
# demuestra la logica del sistema sin depender de Docker, y este demuestra que
# las imagenes y la orquestacion funcionan.
#
# Uso, desde la carpeta EFT_S9_Backend_Avanzado y con Docker Desktop corriendo:
#     powershell -ExecutionPolicy Bypass -File evidencia\generar_evidencia_docker.ps1
#
# Escrito para PowerShell 5.1, que es el que viene con Windows.

$ErrorActionPreference = 'Continue'

$Raiz   = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Salida = Join-Path $Raiz 'evidencia\docker'
New-Item -ItemType Directory -Force -Path $Salida | Out-Null
Set-Location $Raiz

$Gw   = 'http://localhost:8080'
$Auth = 'http://localhost:9000'
$EurekaUser = 'banco-eureka'
$EurekaPass = 'banco-eureka-secret'

# --------------------------------------------------------------------------
# Utilidades
# --------------------------------------------------------------------------

function Titulo($texto) {
    ''
    '=============================================================='
    "  $texto"
    '=============================================================='
}

function Paso($texto) {
    ''
    "--- $texto ---"
}

# Se usa curl.exe y no Invoke-WebRequest a proposito: Invoke-WebRequest lanza una
# excepcion ante cualquier codigo que no sea 2xx, y en esta evidencia los 401 y
# los 403 son justamente lo que hay que demostrar.
function Codigo($url, $token) {
    if ($token) {
        curl.exe -s -o NUL -w "%{http_code}" -H "Authorization: Bearer $token" --max-time 15 $url
    } else {
        curl.exe -s -o NUL -w "%{http_code}" --max-time 15 $url
    }
}

function Json($url, $token) {
    $r = curl.exe -s -H "Authorization: Bearer $token" --max-time 15 $url
    if ($r) { $r | python -m json.tool } else { '(sin respuesta)' }
}

function PostJson($url, $token, $cuerpo) {
    $r = curl.exe -s -X POST -H "Authorization: Bearer $token" `
        -H "Content-Type: application/json" -d $cuerpo --max-time 25 $url
    if ($r) { $r | python -m json.tool } else { '(sin respuesta)' }
}

function Token($clientId, $secreto, $scopes) {
    $r = curl.exe -s -u "${clientId}:${secreto}" -d "grant_type=client_credentials" `
        --data-urlencode "scope=$scopes" "$Auth/oauth2/token"
    try { ($r | ConvertFrom-Json).access_token } catch { '' }
}

function EsperarSanos($intentos = 40) {
    for ($i = 1; $i -le $intentos; $i++) {
        $ps = docker compose ps --format json 2>$null
        if ($ps) {
            $estados = @()
            foreach ($linea in $ps) {
                if ($linea.Trim()) {
                    try { $estados += ($linea | ConvertFrom-Json) } catch { }
                }
            }
            $servicios = $estados | Where-Object { $_.Service -ne 'kafka-init' }
            $sanos = ($servicios | Where-Object { $_.Health -eq 'healthy' }).Count
            $total = $servicios.Count
            Write-Host "   intento $i : $sanos de $total contenedores healthy"
            if ($total -gt 0 -and $sanos -eq $total) { return $true }
        }
        Start-Sleep -Seconds 15
    }
    return $false
}

# --------------------------------------------------------------------------
# 01 - Construccion de las imagenes
# --------------------------------------------------------------------------
Write-Host '>> 01 construyendo las imagenes (puede tardar varios minutos)'
$log = Join-Path $Salida '01_construccion_de_imagenes.log'
# Se invoca por cmd y no por tuberia de PowerShell porque Docker escribe el
# progreso de la construccion en la salida de error, y PowerShell envuelve esas
# lineas en registros de error que ensucian el log sin que nada haya fallado.
cmd /c "docker compose build > `"$log`" 2>&1"
Add-Content $log ''
Add-Content $log '--- Imagenes construidas ---'
cmd /c "docker image ls --filter reference=banco-xyz/* >> `"$log`" 2>&1"

# --------------------------------------------------------------------------
# 02 - Orquestacion
# --------------------------------------------------------------------------
Write-Host '>> 02 levantando la orquestacion'
& {
    Titulo '02 - ORQUESTACION CON DOCKER COMPOSE'
    "Fecha: $(Get-Date -Format o)"

    Paso 'Levantando los contenedores'
    cmd /c "docker compose up -d 2>&1"

    Paso 'Esperando a que todos reporten healthy'
    $ok = EsperarSanos
    if (-not $ok) { '   !! no todos los contenedores llegaron a healthy' }

    Paso 'Estado de los contenedores'
    cmd /c "docker compose ps 2>&1"

    Paso 'El contenedor de un solo uso que crea los topicos'
    cmd /c "docker compose logs kafka-init 2>&1"

    Paso 'La red propia del sistema'
    cmd /c "docker network ls --filter name=eft-banco-xyz 2>&1"
    cmd /c "docker network inspect eft-banco-xyz_banco --format ""{{range .Containers}}{{.Name}} {{.IPv4Address}}{{println}}{{end}}"" 2>&1"

    Paso 'Resolucion por nombre dentro de la red'
    'Desde api-gateway, los otros componentes se alcanzan por su nombre de servicio:'
    foreach ($par in @('cuentas-service:8081', 'pagos-service:8082', 'clientes-service:8083',
                       'config-server:8888', 'discovery-server:8761', 'auth-server:9000')) {
        $n = $par.Split(':')[0]; $p = $par.Split(':')[1]
        $r = cmd /c "docker compose exec -T api-gateway curl -s -o /dev/null -w ""%{http_code}"" http://${n}:${p}/actuator/health 2>&1"
        "   http://${n}:${p}/actuator/health -> HTTP $r"
    }

    Paso 'Servicios registrados en Eureka'
    cmd /c "docker compose exec -T api-gateway curl -s -u ${EurekaUser}:${EurekaPass} -H ""Accept: application/json"" http://discovery-server:8761/eureka/apps 2>&1" |
        python -c "import sys,json;
d=json.loads(sys.stdin.read());
apps=d['applications'].get('application',[]);
apps=[apps] if isinstance(apps,dict) else apps;
[print('  ',a['name'],':',len(a['instance'] if isinstance(a['instance'],list) else [a['instance']]),'instancia(s)') for a in sorted(apps,key=lambda x:x['name'])]"
} *>&1 | Out-File -FilePath (Join-Path $Salida '02_orquestacion.log') -Encoding utf8

# --------------------------------------------------------------------------
# 03 - OAuth 2.0 y control de acceso
# --------------------------------------------------------------------------
Write-Host '>> 03 OAuth2 y control de acceso'
$TkWeb    = Token 'banco-web-client'   'banco-web-secret'   'cuentas.read cuentas.write movimientos.read movimientos.write clientes.read clientes.write'
$TkMovil  = Token 'banco-movil-client' 'banco-movil-secret' 'cuentas.read movimientos.read clientes.read'
$TkCajero = Token 'cajero-client'      'cajero-secret'      'cuentas.read cuentas.write'

& {
    Titulo '03 - OAUTH 2.0 Y CONTROL DE ACCESO POR CANAL'

    Paso 'Emision de token por client_credentials (canal web)'
    curl.exe -s -u "banco-web-client:banco-web-secret" -d "grant_type=client_credentials" `
        --data-urlencode "scope=cuentas.read cuentas.write movimientos.read movimientos.write clientes.read clientes.write" `
        "$Auth/oauth2/token" | python -m json.tool

    Paso 'Contenido del token del canal web'
    $TkWeb | python -c "import sys,base64,json;
s=sys.stdin.read().strip().split('.')[1].replace('_','/').replace('-','+');
s+='='*(-len(s)%4);
print(json.dumps(json.loads(base64.b64decode(s)),indent=2,sort_keys=True))"

    Paso 'Clave publica con la que los Resource Servers validan la firma'
    curl.exe -s "$Auth/oauth2/jwks" | python -m json.tool

    Paso 'Un secreto incorrecto no obtiene token'
    "   cajero-client con secreto erroneo -> HTTP $(curl.exe -s -o NUL -w '%{http_code}' -u 'cajero-client:equivocado' -d 'grant_type=client_credentials' --data-urlencode 'scope=cuentas.read' "$Auth/oauth2/token")"

    Paso 'Un cliente no puede pedir un scope que no tiene registrado'
    curl.exe -s -u "cajero-client:cajero-secret" -d "grant_type=client_credentials" `
        --data-urlencode "scope=clientes.read" "$Auth/oauth2/token" | python -m json.tool

    Paso 'Sin token, el gateway rechaza antes de enrutar'
    foreach ($r in @('/api/cuentas/101', '/api/movimientos/101', '/api/clientes/101')) {
        "   GET $r sin token -> HTTP $(Codigo "$Gw$r" $null)"
    }

    Paso 'Un token alterado tampoco pasa'
    "   GET /api/cuentas/101 con token manipulado -> HTTP $(Codigo "$Gw/api/cuentas/101" ($TkWeb + 'x'))"

    Paso 'Minimo privilegio por canal: el mismo token no sirve para todo'
    '   CANAL      RECURSO                     HTTP'
    foreach ($par in @(@('web', $TkWeb), @('movil', $TkMovil), @('cajero', $TkCajero))) {
        foreach ($r in @('/api/cuentas/101', '/api/movimientos/101', '/api/clientes/101')) {
            '   {0,-10} {1,-27} {2}' -f $par[0], "GET $r", (Codigo "$Gw$r" $par[1])
        }
    }
    ''
    '   El cajero recibe 403 en movimientos y clientes con un token de firma'
    '   perfectamente valida: la autorizacion por scope la aplica cada'
    '   microservicio, no solo el gateway.'

    Paso 'El canal movil no puede escribir aunque el endpoint exista'
    $r = curl.exe -s -o NUL -w "%{http_code}" -X PUT -H "Authorization: Bearer $TkMovil" `
        -H "Content-Type: application/json" -d '{\"nombre\":\"Intento\"}' "$Gw/api/clientes/101/nombre"
    "   PUT /api/clientes/101/nombre con token movil -> HTTP $r"

    Paso 'Las primitivas de liquidacion exigen un scope que ningun canal tiene'
    $r = curl.exe -s -o NUL -w "%{http_code}" -X POST -H "Authorization: Bearer $TkWeb" `
        -H "Content-Type: application/json" -d '{\"monto\":100}' "$Gw/api/cuentas/101/abono"
    "   POST /api/cuentas/101/abono con token web -> HTTP $r"
} *>&1 | Out-File -FilePath (Join-Path $Salida '03_oauth2.log') -Encoding utf8

# --------------------------------------------------------------------------
# 04 - APIs, JMS y Kafka
# --------------------------------------------------------------------------
Write-Host '>> 04 APIs, mensajeria JMS y Kafka'
& {
    Titulo '04 - LOS TRES MICROSERVICIOS, JMS Y KAFKA SOBRE CONTENEDORES'

    Paso 'Gestion de cuentas'
    Json "$Gw/api/cuentas/101" $TkWeb

    Paso 'Gestion de clientes (antes de operar)'
    Json "$Gw/api/clientes/101" $TkWeb

    Paso 'Reporte de transacciones diarias (salida del proceso batch 1)'
    Json "$Gw/api/transacciones-diarias/resumen" $TkWeb

    Paso 'Estado antes del retiro'
    $saldoAntes = (curl.exe -s -H "Authorization: Bearer $TkWeb" "$Gw/api/cuentas/101" | ConvertFrom-Json).saldo
    $movsAntes  = (curl.exe -s -H "Authorization: Bearer $TkWeb" "$Gw/api/movimientos/101/resumen" | ConvertFrom-Json).totalMovimientos
    "   saldo de la cuenta 101: $saldoAntes"
    "   movimientos en el historial: $movsAntes"

    Paso 'Retiro de 250 por el canal cajero (viaja por la cola JMS)'
    PostJson "$Gw/api/cuentas/101/retiro" $TkCajero '{\"monto\":250,\"canal\":\"cajero\"}'

    Start-Sleep -Seconds 6

    Paso 'Estado despues: el saldo bajo en un contenedor y el historial subio en otro'
    $saldoDespues = (curl.exe -s -H "Authorization: Bearer $TkWeb" "$Gw/api/cuentas/101" | ConvertFrom-Json).saldo
    $movsDespues  = (curl.exe -s -H "Authorization: Bearer $TkWeb" "$Gw/api/movimientos/101/resumen" | ConvertFrom-Json).totalMovimientos
    "   saldo:       $saldoAntes -> $saldoDespues"
    "   movimientos: $movsAntes -> $movsDespues"

    Paso 'Deposito por el canal web (Kafka)'
    PostJson "$Gw/api/pagos/deposito/101" $TkWeb '{\"monto\":1200,\"canal\":\"web\",\"descripcion\":\"Abono de sueldo\"}'

    Paso 'Transferencia de la cuenta 101 a la 102 (Kafka)'
    PostJson "$Gw/api/pagos/transferencia/101" $TkWeb '{\"cuentaDestino\":102,\"monto\":300,\"canal\":\"web\",\"descripcion\":\"Pago de arriendo\"}'

    Paso 'Intento de retiro sobre el limite: genera alerta, no transaccion'
    PostJson "$Gw/api/cuentas/101/retiro" $TkCajero '{\"monto\":900000,\"canal\":\"cajero\"}'

    Start-Sleep -Seconds 10

    Paso 'Perfil del cliente 101: la actividad la trajo Kafka'
    Json "$Gw/api/clientes/101" $TkWeb

    Paso 'Perfil del cliente 102: recibio el abono de la transferencia'
    Json "$Gw/api/clientes/102" $TkWeb

    Paso 'Topicos de Kafka en el broker'
    cmd /c "docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe 2>&1"

    Paso 'Lo que publicaron y consumieron los contenedores'
    '-- cuentas-service (productor)'
    cmd /c "docker compose logs --no-log-prefix cuentas-service 2>&1" | Select-String 'publicado en' | Select-Object -Last 4
    '-- pagos-service (consumidor JMS y productor Kafka)'
    cmd /c "docker compose logs --no-log-prefix pagos-service 2>&1" | Select-String 'consumido, retiro de|publicado en banco' | Select-Object -Last 4
    '-- clientes-service (consumidor Kafka)'
    cmd /c "docker compose logs --no-log-prefix clientes-service 2>&1" | Select-String 'ConsumidorEventos' | Select-Object -Last 6
} *>&1 | Out-File -FilePath (Join-Path $Salida '04_apis_y_mensajeria.log') -Encoding utf8

# --------------------------------------------------------------------------
# 05 - Tolerancia a fallos sobre contenedores
# --------------------------------------------------------------------------
Write-Host '>> 05 tolerancia a fallos (detiene y levanta un contenedor)'
& {
    Titulo '05 - TOLERANCIA A FALLOS CON RESILIENCE4J SOBRE CONTENEDORES'

    Paso 'La ficha con cuentas-service arriba'
    curl.exe -s -H "Authorization: Bearer $TkWeb" "$Gw/api/movimientos/101/ficha" |
        python -c "import sys,json; d=json.load(sys.stdin); print('   origenDatosCuenta:', d['origenDatosCuenta']); print('   cuenta:', json.dumps(d.get('cuenta')))"

    Paso 'Se detiene el contenedor de cuentas-service'
    cmd /c "docker compose stop cuentas-service 2>&1"
    Start-Sleep -Seconds 5

    Paso 'Seis peticiones: el circuito se abre y la respuesta se degrada'
    for ($i = 1; $i -le 6; $i++) {
        $origen = curl.exe -s -H "Authorization: Bearer $TkWeb" --max-time 25 "$Gw/api/movimientos/101/ficha" |
            python -c "import sys,json; print(json.load(sys.stdin).get('origenDatosCuenta','sin-respuesta'))"
        $estado = cmd /c "docker compose exec -T pagos-service curl -s -H ""Authorization: Bearer $TkWeb"" http://localhost:8082/actuator/circuitbreakers 2>&1" |
            python -c "import sys,json; print(json.load(sys.stdin)['circuitBreakers']['cuentas']['state'])"
        "   peticion {0}: origenDatosCuenta={1,-10} circuito={2}" -f $i, $origen, $estado
    }

    Paso 'La respuesta degradada sigue sirviendo el historial'
    curl.exe -s -H "Authorization: Bearer $TkWeb" "$Gw/api/movimientos/101/ficha" |
        python -c "import sys,json; d=json.load(sys.stdin);
print('   origenDatosCuenta:', d['origenDatosCuenta']);
print('   cuenta:', d.get('cuenta'));
print('   movimientos entregados:', len(d.get('movimientos') or []))"

    Paso 'Una transferencia con la dependencia caida se rechaza, no se degrada'
    $r = curl.exe -s -o NUL -w "%{http_code}" -X POST -H "Authorization: Bearer $TkWeb" `
        -H "Content-Type: application/json" --max-time 30 `
        -d '{\"cuentaDestino\":102,\"monto\":100,\"canal\":\"web\"}' "$Gw/api/pagos/transferencia/101"
    "   POST /api/pagos/transferencia/101 -> HTTP $r"
    '   No hay version degradada de mover dinero: o se mueve o se informa el fallo.'

    Paso 'La apertura del circuito salio al topico de alertas'
    cmd /c "docker compose logs --no-log-prefix clientes-service 2>&1" | Select-String 'DEPENDENCIA_DEGRADADA' | Select-Object -Last 3

    Paso 'Se vuelve a levantar el contenedor'
    cmd /c "docker compose start cuentas-service 2>&1"
    Start-Sleep -Seconds 50

    Paso 'El circuito pasa a HALF_OPEN y se cierra solo'
    for ($i = 1; $i -le 8; $i++) {
        $origen = curl.exe -s -H "Authorization: Bearer $TkWeb" --max-time 25 "$Gw/api/movimientos/101/ficha" |
            python -c "import sys,json; print(json.load(sys.stdin).get('origenDatosCuenta','sin-respuesta'))"
        $estado = cmd /c "docker compose exec -T pagos-service curl -s -H ""Authorization: Bearer $TkWeb"" http://localhost:8082/actuator/circuitbreakers 2>&1" |
            python -c "import sys,json; print(json.load(sys.stdin)['circuitBreakers']['cuentas']['state'])"
        "   peticion {0}: origenDatosCuenta={1,-10} circuito={2}" -f $i, $origen, $estado
        Start-Sleep -Seconds 3
    }

    Paso 'Transiciones registradas por Resilience4j'
    cmd /c "docker compose exec -T pagos-service curl -s -H ""Authorization: Bearer $TkWeb"" http://localhost:8082/actuator/circuitbreakerevents 2>&1" |
        python -c "import sys,json;
[print('  ', e.get('creationTime','')[:19], e.get('stateTransition')) for e in json.load(sys.stdin).get('circuitBreakerEvents',[]) if e.get('type')=='STATE_TRANSITION']"
} *>&1 | Out-File -FilePath (Join-Path $Salida '05_tolerancia_a_fallos.log') -Encoding utf8

# --------------------------------------------------------------------------
# 06 - BFF por canal
# --------------------------------------------------------------------------
Write-Host '>> 06 los tres BFF'
& {
    Titulo '06 - PATRON BFF: UN BACKEND POR CANAL, EN CONTENEDORES'

    Paso 'Canal WEB'
    curl.exe -s "http://localhost:8091/web/cuentas/101" | python -m json.tool

    Paso 'Canal MOVIL'
    curl.exe -s "http://localhost:8092/movil/cuentas/101?limite=3" | python -m json.tool

    Paso 'Canal CAJERO'
    curl.exe -s "http://localhost:8093/cajero/cuentas/101/saldo" | python -m json.tool

    Paso 'Tamano de la respuesta por canal, para la misma cuenta'
    $tWeb    = (curl.exe -s "http://localhost:8091/web/cuentas/101").Length
    $tMovil  = (curl.exe -s "http://localhost:8092/movil/cuentas/101?limite=3").Length
    $tCajero = (curl.exe -s "http://localhost:8093/cajero/cuentas/101/saldo").Length
    '   {0,-10} {1,8} caracteres' -f 'web', $tWeb
    '   {0,-10} {1,8} caracteres' -f 'movil', $tMovil
    '   {0,-10} {1,8} caracteres' -f 'cajero', $tCajero

    Paso 'Retiro por el canal cajero: una sola llamada'
    curl.exe -s -X POST -H "Content-Type: application/json" -d '{\"monto\":150}' `
        "http://localhost:8093/cajero/cuentas/101/retiro" | python -m json.tool

    Paso 'Panel administrativo, exclusivo del canal web'
    curl.exe -s "http://localhost:8091/web/banco/resumen-diario" | python -m json.tool
} *>&1 | Out-File -FilePath (Join-Path $Salida '06_bff_por_canal.log') -Encoding utf8

# --------------------------------------------------------------------------
# 07 - Escalabilidad horizontal
# --------------------------------------------------------------------------
Write-Host '>> 07 escalado horizontal (replica cuentas-service y pagos-service)'
& {
    Titulo '07 - ESCALABILIDAD HORIZONTAL EN CONTENEDORES'

    Paso 'Instancias antes de escalar'
    cmd /c "docker compose ps cuentas-service pagos-service 2>&1"

    Paso 'Escalando a dos replicas de cada uno'
    cmd /c "docker compose up -d --scale cuentas-service=2 --scale pagos-service=2 2>&1"
    Write-Host '   esperando a que las replicas nuevas esten healthy'
    Start-Sleep -Seconds 90

    Paso 'Contenedores despues de escalar'
    cmd /c "docker compose ps 2>&1"

    Paso 'Instancias registradas en Eureka'
    cmd /c "docker compose exec -T api-gateway curl -s -u ${EurekaUser}:${EurekaPass} -H ""Accept: application/json"" http://discovery-server:8761/eureka/apps 2>&1" |
        python -c "import sys,json;
d=json.loads(sys.stdin.read());
apps=d['applications'].get('application',[]);
apps=[apps] if isinstance(apps,dict) else apps;
[print('  ',a['name'],':',len(a['instance'] if isinstance(a['instance'],list) else [a['instance']]),'instancia(s)') for a in sorted(apps,key=lambda x:x['name'])]"

    Paso 'Treinta peticiones por el gateway, repartidas entre las replicas'
    for ($i = 1; $i -le 30; $i++) {
        Codigo "$Gw/api/cuentas/101" $TkWeb | Out-Null
    }
    Start-Sleep -Seconds 3
    'Peticiones atendidas por cada replica, contadas en su propio log de acceso:'
    cmd /c "docker compose logs --no-log-prefix cuentas-service 2>&1" |
        Select-String 'GET /cuentas/101' | Measure-Object | ForEach-Object { "   total en las dos replicas: $($_.Count) lineas de acceso" }
    ''
    'El detalle por replica se ve con:'
    '   docker compose logs cuentas-service | Select-String "GET /cuentas/101"'
    'donde el prefijo de cada linea es el nombre del contenedor que la atendio.'

    Paso 'Volviendo a una replica de cada uno'
    cmd /c "docker compose up -d --scale cuentas-service=1 --scale pagos-service=1 2>&1"
} *>&1 | Out-File -FilePath (Join-Path $Salida '07_escalabilidad_horizontal.log') -Encoding utf8

# --------------------------------------------------------------------------
# 08 - Estado final y bajada
# --------------------------------------------------------------------------
Write-Host '>> 08 estado final'
& {
    Titulo '08 - ESTADO FINAL DE LA ORQUESTACION'
    "Fecha: $(Get-Date -Format o)"

    Paso 'Contenedores'
    cmd /c "docker compose ps 2>&1"

    Paso 'Salud de cada componente, consultada dentro de la red'
    foreach ($par in @('config-server:8888', 'discovery-server:8761', 'auth-server:9000',
                       'api-gateway:8080', 'cuentas-service:8081', 'pagos-service:8082',
                       'clientes-service:8083', 'broker-artemis:61617',
                       'bff-web:8091', 'bff-movil:8092', 'bff-cajero:8093')) {
        $n = $par.Split(':')[0]; $p = $par.Split(':')[1]
        $r = cmd /c "docker compose exec -T api-gateway curl -s http://${n}:${p}/actuator/health 2>&1"
        "   {0,-20} {1}" -f $n, $r
    }

    Paso 'Registro final de Eureka'
    cmd /c "docker compose exec -T api-gateway curl -s -u ${EurekaUser}:${EurekaPass} -H ""Accept: application/json"" http://discovery-server:8761/eureka/apps 2>&1" |
        python -c "import sys,json;
d=json.loads(sys.stdin.read());
apps=d['applications'].get('application',[]);
apps=[apps] if isinstance(apps,dict) else apps;
[print('  ',a['name'],':',len(a['instance'] if isinstance(a['instance'],list) else [a['instance']]),'instancia(s)') for a in sorted(apps,key=lambda x:x['name'])]"

    Paso 'Imagenes del sistema'
    cmd /c "docker image ls --filter reference=banco-xyz/* 2>&1"
} *>&1 | Out-File -FilePath (Join-Path $Salida '08_estado_final.log') -Encoding utf8

Write-Host '>> bajando la orquestacion'
cmd /c "docker compose down 2>&1" | Out-Null

Write-Host ''
Write-Host ">> evidencia generada en $Salida"
Get-ChildItem $Salida -Filter *.log | Select-Object Name, Length | Format-Table -AutoSize
