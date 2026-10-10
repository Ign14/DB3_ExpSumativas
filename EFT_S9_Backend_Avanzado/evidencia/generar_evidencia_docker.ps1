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
# Se borran los logs de la corrida anterior, no las capturas ni el LEEME. Sin
# esto, una corrida que se detiene a la mitad deja mezclados logs nuevos con
# viejos y no hay forma de saber cuales son de cuando.
Remove-Item (Join-Path $Salida '*.log') -ErrorAction SilentlyContinue
Set-Location $Raiz

$Gw   = 'http://localhost:8080'
$Auth = 'http://localhost:9000'
$EurekaUser = 'banco-eureka'
$EurekaPass = 'banco-eureka-secret'

# --------------------------------------------------------------------------
# Utilidades
# --------------------------------------------------------------------------

# Los once servicios de este compose fijan container_name, asi que sus nombres
# son unicos en todo el demonio de Docker: un contenedor con el mismo nombre
# dejado por otro proyecto hace fallar el 'docker compose up' entero.
$Nombres = @('config-server','discovery-server','broker-artemis','kafka',
             'kafka-init','auth-server','api-gateway',
             'bff-web','bff-movil','bff-cajero')

# Los doce servicios que tienen que quedar corriendo y sanos. kafka-init no
# esta: es de un solo uso y su exito se verifica aparte, por su codigo de
# salida.
$Servicios = @('config-server','discovery-server','broker-artemis','kafka',
               'auth-server','api-gateway','cuentas-service','pagos-service',
               'clientes-service','bff-web','bff-movil','bff-cajero')

$Repos = @('banco-xyz/config-server','banco-xyz/discovery-server',
           'banco-xyz/auth-server','banco-xyz/api-gateway',
           'banco-xyz/cuentas-service','banco-xyz/pagos-service',
           'banco-xyz/clientes-service','banco-xyz/bff-web',
           'banco-xyz/bff-movil','banco-xyz/bff-cajero',
           'banco-xyz/broker-artemis')

function ImagenesDelProyecto() {
    # Se listan por nombre exacto y no con --filter reference=banco-xyz/*,
    # porque ese comodin tambien trae imagenes de proyectos anteriores que
    # comparten el prefijo y haria parecer que este compose construye mas de
    # las once que declara.
    '{0,-32} {1}' -f 'REPOSITORIO', 'ID / TAMANO'
    $n = 0
    foreach ($r in $Repos) {
        $linea = @(docker image ls "${r}:1.0.0" --format "{{.ID}}  {{.Size}}" 2>$null)[0]
        if ($linea) { '{0,-32} {1}' -f $r, $linea; $n++ }
        else        { '{0,-32} (no construida)' -f $r }
    }
    ''
    "   $n de $($Repos.Count) imagenes del proyecto presentes"
}

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

# Que un contenedor este healthy no basta para pedirle algo POR EL GATEWAY. El
# gateway enruta con la copia del registro de Eureka que tiene en memoria, y
# hasta que la refresca responde 503. Dormir un numero fijo de segundos funciona
# hasta que la maquina esta mas cargada; por eso se pregunta hasta que conteste.
function EsperarGateway($url, $token, $intentos = 60) {
    for ($i = 1; $i -le $intentos; $i++) {
        if ((Codigo $url $token) -eq '200') { return $i }
        Start-Sleep -Seconds 1
    }
    return -1
}

function Token($clientId, $secreto, $scopes) {
    $r = curl.exe -s -u "${clientId}:${secreto}" -d "grant_type=client_credentials" `
        --data-urlencode "scope=$scopes" "$Auth/oauth2/token"
    try { ($r | ConvertFrom-Json).access_token } catch { '' }
}

# Devuelve los servicios de $Servicios que NO estan healthy en este momento.
# Se compara contra la lista esperada y no contra los contenedores que existen,
# que es como una corrida anterior se dio por buena con seis de doce: kafka-init
# habia fallado, los seis servicios que dependen de el nunca se crearon, y los
# seis que si existian estaban todos healthy.
function FaltanSanos() {
    $estados = @()
    foreach ($linea in @(docker compose ps -a --format json 2>$null)) {
        if ("$linea".Trim()) {
            try { $estados += ($linea | ConvertFrom-Json) } catch { }
        }
    }
    $faltan = @()
    foreach ($s in $Servicios) {
        $c = @($estados | Where-Object { $_.Service -eq $s })
        if ($c.Count -eq 0) {
            $faltan += "$s (no existe)"
        } elseif (-not ($c | Where-Object { $_.Health -eq 'healthy' })) {
            $estado = ($c[0].Health, $c[0].State | Where-Object { $_ }) -join '/'
            $faltan += "$s ($estado)"
        }
    }
    return $faltan
}

function EsperarSanos($intentos = 40) {
    for ($i = 1; $i -le $intentos; $i++) {
        $faltan = @(FaltanSanos)
        $sanos = $Servicios.Count - $faltan.Count
        Write-Host "   intento $i : $sanos de $($Servicios.Count) servicios healthy"
        if ($faltan.Count -eq 0) { return $true }
        Start-Sleep -Seconds 15
    }
    ''
    '   Servicios que no llegaron a healthy:'
    foreach ($f in @(FaltanSanos)) { "     - $f" }
    return $false
}

# --------------------------------------------------------------------------
# 00 - Limpieza previa
# --------------------------------------------------------------------------
# Este compose fija container_name en once servicios, para que los nombres de
# los contenedores sean legibles en la evidencia. El precio es que un
# contenedor con el mismo nombre dejado por OTRO proyecto -la Exp3, por
# ejemplo- hace fallar el 'docker compose up' entero con un conflicto de
# nombre, y el resto de la evidencia sale vacia sin que se vea por que.
# Esto lo detecta y lo resuelve antes de empezar, y deja dicho que lo hizo.
Write-Host '>> 00 limpiando contenedores de corridas anteriores'
$log = Join-Path $Salida '00_limpieza_previa.log'
& {
    Titulo '00 - LIMPIEZA PREVIA'
    "Fecha: $(Get-Date -Format o)"

    Paso 'Contenedores presentes antes de empezar'
    docker ps -a --format "{{.Names}}  |  {{.Image}}  |  {{.Status}}" 2>$null

    Paso 'Bajando cualquier orquestacion previa de este proyecto'
    cmd /c "docker compose down --remove-orphans 2>&1"

    Paso 'Nombres en conflicto dejados por otros proyectos'
    $existentes = @(docker ps -a --format "{{.Names}}" 2>$null)
    $conflictos = @($Nombres | Where-Object { $existentes -contains $_ })
    if ($conflictos.Count -eq 0) {
        '   ninguno: los once nombres fijos de este compose estan libres'
    } else {
        foreach ($n in $conflictos) { "   $n" }
        ''
        "   Se eliminan $($conflictos.Count) contenedor(es) para liberar los nombres."
        foreach ($n in $conflictos) { docker rm -f $n 2>&1 }
    }

    Paso 'Estado despues de la limpieza'
    docker ps -a --format "{{.Names}}  |  {{.Image}}  |  {{.Status}}" 2>$null
} *>&1 | Out-File -FilePath $log -Encoding utf8

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
ImagenesDelProyecto | Out-File -FilePath $log -Encoding utf8 -Append

# --------------------------------------------------------------------------
# 02 - Orquestacion
# --------------------------------------------------------------------------
Write-Host '>> 02 levantando la orquestacion'
& {
    Titulo '02 - ORQUESTACION CON DOCKER COMPOSE'
    "Fecha: $(Get-Date -Format o)"

    Paso 'Levantando los contenedores'
    cmd /c "docker compose up -d 2>&1"
    $script:CodigoUp = $LASTEXITCODE
    "   codigo de salida de 'docker compose up': $script:CodigoUp"

    Paso 'Esperando a que todos reporten healthy'
    if ($script:CodigoUp -ne 0) {
        # Un 'up' que falla casi siempre es una dependencia que no arranco, y
        # entonces los servicios que la esperan no existen y nunca existiran.
        # Esperar diez minutos por ellos no cambia nada.
        "   !! 'docker compose up' termino con codigo $script:CodigoUp; no se espera"
        $ok = $false
    } else {
        $ok = EsperarSanos
    }
    $script:Orquestado = $ok
    if (-not $ok) {
        ''
        "   !! la orquestacion no quedo completa ($($Servicios.Count) servicios esperados)"
        ''
        '   Servicios que faltan o no estan healthy:'
        foreach ($f in @(FaltanSanos)) { "     - $f" }
        ''
        '   Estado completo, incluidos los que terminaron:'
        cmd /c "docker compose ps -a 2>&1"
        ''
        '   Ultimas lineas de kafka-init, que es de quien dependen seis servicios:'
        cmd /c "docker compose logs --tail 30 kafka-init 2>&1"
        foreach ($s in $Servicios) {
            ''
            "   --- ultimas lineas de $s ---"
            cmd /c "docker compose logs --tail 20 $s 2>&1"
        }
    }

    Paso 'Estado de los contenedores'
    cmd /c "docker compose ps 2>&1"

    Paso 'El contenedor de un solo uso que crea los topicos'
    $salidaInit = @(docker inspect --format "{{.State.ExitCode}}" kafka-init 2>$null)[0]
    "   codigo de salida de kafka-init: $salidaInit  (0 = los dos topicos quedaron creados)"
    ''
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

    Paso 'El gateway enrutando hacia cada microservicio'
    'Estar healthy no es lo mismo que ser alcanzable por el gateway: el gateway'
    'enruta con su copia del registro de Eureka, y hasta refrescarla responde'
    '503. Se pregunta por una ruta de cada microservicio hasta que conteste 200.'
    ''
    $tkSonda = Token 'banco-web-client' 'banco-web-secret' `
        'cuentas.read movimientos.read clientes.read'
    foreach ($par in @(
            @('cuentas-service',  "$Gw/api/cuentas/101"),
            @('pagos-service',    "$Gw/api/movimientos/101/resumen"),
            @('clientes-service', "$Gw/api/clientes/101"))) {
        $s = EsperarGateway $par[1] $tkSonda
        if ($s -ge 0) { '   {0,-18} enrutando tras {1}s' -f $par[0], $s }
        else {
            '   {0,-18} !! el gateway sigue sin enrutar' -f $par[0]
            $script:Orquestado = $false
        }
    }

    Paso 'Servicios registrados en Eureka'
    cmd /c "docker compose exec -T api-gateway curl -s -u ${EurekaUser}:${EurekaPass} -H ""Accept: application/json"" http://discovery-server:8761/eureka/apps 2>&1" |
        python -c "import sys,json;
d=json.loads(sys.stdin.read());
apps=d['applications'].get('application',[]);
apps=[apps] if isinstance(apps,dict) else apps;
[print('  ',a['name'],':',len(a['instance'] if isinstance(a['instance'],list) else [a['instance']]),'instancia(s)') for a in sorted(apps,key=lambda x:x['name'])]"
} *>&1 | Out-File -FilePath (Join-Path $Salida '02_orquestacion.log') -Encoding utf8

# Sin orquestacion arriba, los seis logs que siguen saldrian vacios y haria
# falta leerlos uno por uno para darse cuenta. Mejor detenerse aca y decir
# donde mirar.
if (-not $Orquestado) {
    Write-Host ''
    Write-Host '!! La orquestacion no quedo utilizable. Se detiene aca.' -ForegroundColor Red
    Write-Host "   El diagnostico esta en $Salida\02_orquestacion.log"
    Write-Host '   Lo mas comun: un contenedor muerto por falta de memoria'
    Write-Host '   (Docker Desktop -> Settings -> Resources, al menos 8 GB).'
    Write-Host '   Los contenedores quedan arriba para que puedas inspeccionarlos;'
    Write-Host '   cuando termines: docker compose down --remove-orphans'
    exit 1
}

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

    # Se cuenta el DELTA y no el acumulado. El log de acceso de cada contenedor
    # arranca con el contenedor, y la replica original ya venia atendiendo
    # peticiones de los pasos anteriores: sumar los acumulados daria un total
    # mayor que las treinta que se acaban de enviar, y el log se leeria como si
    # las cuentas no cuadraran.
    function ContarAccesos() {
        $c = @{}
        foreach ($id in (cmd /c "docker compose ps -q cuentas-service 2>&1")) {
            if ($id) {
                $c[$id] = (cmd /c "docker logs $id 2>&1" |
                    Select-String 'GET /cuentas/101' | Measure-Object).Count
            }
        }
        return $c
    }

    $antes = ContarAccesos

    Paso 'Treinta peticiones por el gateway, repartidas entre las replicas'
    for ($i = 1; $i -le 30; $i++) {
        Codigo "$Gw/api/cuentas/101" $TkWeb | Out-Null
    }
    Start-Sleep -Seconds 3
    $despues = ContarAccesos

    'De esas treinta, cuantas atendio cada replica, segun su propio log de acceso:'
    $total = 0
    foreach ($id in $despues.Keys) {
        $nombre = cmd /c "docker inspect --format `"{{.Name}}`" $id 2>&1"
        $nombre = "$nombre".TrimStart('/')
        $n = $despues[$id] - $(if ($antes.ContainsKey($id)) { $antes[$id] } else { 0 })
        $total += $n
        '   {0,-45} {1} peticiones' -f $nombre, $n
    }
    "   total repartido entre las replicas: $total  (de 30 enviadas)"
    ''
    'El reparto lo hace el balanceador del gateway sobre el registro de Eureka.'
    'No hubo que tocar la configuracion de ningun componente: basto levantar la'
    'replica. El log de acceso que permite este conteo viene de las variables'
    'SERVER_TOMCAT_ACCESSLOG_* que docker-compose.yml le pasa a cuentas-service.'

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
    ImagenesDelProyecto
} *>&1 | Out-File -FilePath (Join-Path $Salida '08_estado_final.log') -Encoding utf8

Write-Host '>> bajando la orquestacion'
cmd /c "docker compose down 2>&1" | Out-Null

Write-Host ''
Write-Host ">> evidencia generada en $Salida"
Get-ChildItem $Salida -Filter *.log | Select-Object Name, Length | Format-Table -AutoSize
