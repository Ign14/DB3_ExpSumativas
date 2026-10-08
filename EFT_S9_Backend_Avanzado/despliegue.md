# Despliegue del sistema en un entorno de nube

**Evaluación Final Transversal — Desarrollo Backend III (PBY2203)**
Banco XYZ · Ignacio Miño Astorga

Este documento describe cómo llevar el sistema a AWS. Está escrito como una guía
ejecutable: cada paso trae los comandos concretos y lo que hay que ver para saber
que funcionó.

---

## 0. Alcance honesto de este documento

Lo que está probado y se puede reproducir hoy: la construcción de las once
imágenes del `docker-compose`, la orquestación completa con `docker-compose` y el escalado horizontal
con varias instancias por servicio balanceadas por Eureka y el gateway. Eso está
en `evidencia/docker/` y en `evidencia/10_escalabilidad_horizontal.log`.

Lo que este documento describe sin haberlo ejecutado: el despliegue en una cuenta
AWS real. Los comandos están completos y los valores de ejemplo son los del
proyecto, pero levantar la infraestructura implica una cuenta con medio de pago y
costos que esta entrega no justifica. Donde una decisión sea discutible, está
dicho por qué, y donde haga falta un paso manual, está marcado.

Preferí que esto quedara claro en la primera sección antes que escribir una guía
que pareciera un registro de algo que no pasó.

---

## 1. La arquitectura objetivo en AWS

```
                            Internet
                               │
                    ┌──────────▼──────────┐
                    │  Application Load   │  HTTPS (ACM)
                    │     Balancer        │
                    └──────────┬──────────┘
                               │
     ┌─────────────────────────┼─────────────────────────┐
     │          Subredes públicas (2 zonas)              │
     └─────────────────────────┼─────────────────────────┘
                               │
  ┌────────────────────────────▼────────────────────────────────┐
  │        Subredes privadas (2 zonas de disponibilidad)        │
  │                                                             │
  │   ECS Fargate — un servicio por componente                  │
  │   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐      │
  │   │ bff-web  │ │bff-movil │ │bff-cajero│ │ gateway  │      │
  │   └──────────┘ └──────────┘ └──────────┘ └────┬─────┘      │
  │   ┌──────────┐ ┌──────────┐ ┌──────────┐      │            │
  │   │ cuentas  │ │  pagos   │ │ clientes │◀─────┘            │
  │   │  (2..N)  │ │  (2..N)  │ │  (2..N)  │                   │
  │   └──────────┘ └──────────┘ └──────────┘                   │
  │   ┌──────────┐ ┌──────────┐ ┌──────────┐                   │
  │   │  config  │ │ discovery│ │   auth   │                   │
  │   └──────────┘ └──────────┘ └──────────┘                   │
  │                                                             │
  │   ┌─────────────────┐  ┌──────────────────┐                │
  │   │  Amazon MSK     │  │  Amazon MQ       │                │
  │   │  (Kafka)        │  │  (ActiveMQ)      │                │
  │   └─────────────────┘  └──────────────────┘                │
  └─────────────────────────────────────────────────────────────┘
          │                          │
  ┌───────▼────────┐        ┌────────▼────────┐
  │ Secrets Manager│        │  CloudWatch     │
  │  (credenciales)│        │  (logs/métricas)│
  └────────────────┘        └─────────────────┘
```

### Por qué Fargate y no EC2 ni EKS

**Fargate** cobra por tarea y no por instancia, y no hay servidores que parchar.
Para once servicios que escalan por separado, eso es exactamente lo que se
quiere: una tarea más de `cuentas-service` no obliga a razonar sobre si cabe en
la instancia.

**EC2** con `docker compose` sería el camino más corto y el más barato para una
demostración, pero devuelve el problema que los contenedores vinieron a resolver:
alguien tiene que mantener el sistema operativo y el escalado deja de ser una
bandera. Queda descrito en la sección 8 como alternativa de bajo costo.

**EKS** es la respuesta correcta para una plataforma con decenas de equipos.
Aquí sería pagar el costo operativo de Kubernetes por un sistema de once
servicios que no lo necesita.

### Qué reemplaza a qué

| En local | En AWS | Por qué |
|---|---|---|
| `broker-kafka` (módulo propio) | **Amazon MSK** | Un broker Kafka de verdad, con réplicas y respaldo. El módulo propio existe sólo para poder ejecutar sin Docker |
| `broker-artemis` (módulo propio) | **Amazon MQ** (ActiveMQ) | Lo mismo: el broker embebido no es para producción |
| `discovery-server` (Eureka) | **Eureka, igual** | Ver la sección 4: se mantiene a propósito |
| `config-server` (perfil `native`) | **Config Server + S3 o CodeCommit** | La configuración deja de viajar en la imagen |
| Secretos en variables con valor por defecto | **AWS Secrets Manager** | Ningún secreto en la definición de tarea |
| Estado en memoria | **RDS PostgreSQL** | Ver la sección 7: es el cambio pendiente más importante |

---

## 2. Preparación

### 2.1 Herramientas

```bash
aws --version          # AWS CLI v2
docker --version
```

Configurar credenciales y región:

```bash
aws configure
# Región sugerida: us-east-1 (la más barata y con MSK disponible)
```

### 2.2 Variables de trabajo

Los comandos siguientes las usan. Conviene dejarlas en un archivo y cargarlo.

```bash
export AWS_REGION=us-east-1
export AWS_ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
export ECR=$AWS_ACCOUNT.dkr.ecr.$AWS_REGION.amazonaws.com
export PROYECTO=banco-xyz
export CLUSTER=$PROYECTO-cluster
export VERSION=1.0.0
```

### 2.3 Red

El sistema necesita dos subredes públicas (para el balanceador) y dos privadas
(para las tareas), en dos zonas de disponibilidad distintas. Dos zonas no es un
lujo: con una sola, la caída de esa zona baja el sistema completo, y toda la
tolerancia a fallos del código no sirve de nada.

```bash
# VPC
VPC=$(aws ec2 create-vpc --cidr-block 10.0.0.0/16 \
  --tag-specifications "ResourceType=vpc,Tags=[{Key=Name,Value=$PROYECTO-vpc}]" \
  --query Vpc.VpcId --output text)

# Subredes
SUB_PUB_A=$(aws ec2 create-subnet --vpc-id $VPC --cidr-block 10.0.1.0/24 \
  --availability-zone ${AWS_REGION}a --query Subnet.SubnetId --output text)
SUB_PUB_B=$(aws ec2 create-subnet --vpc-id $VPC --cidr-block 10.0.2.0/24 \
  --availability-zone ${AWS_REGION}b --query Subnet.SubnetId --output text)
SUB_PRIV_A=$(aws ec2 create-subnet --vpc-id $VPC --cidr-block 10.0.11.0/24 \
  --availability-zone ${AWS_REGION}a --query Subnet.SubnetId --output text)
SUB_PRIV_B=$(aws ec2 create-subnet --vpc-id $VPC --cidr-block 10.0.12.0/24 \
  --availability-zone ${AWS_REGION}b --query Subnet.SubnetId --output text)

# Internet Gateway y ruta para las públicas
IGW=$(aws ec2 create-internet-gateway --query InternetGateway.InternetGatewayId --output text)
aws ec2 attach-internet-gateway --vpc-id $VPC --internet-gateway-id $IGW
RT_PUB=$(aws ec2 create-route-table --vpc-id $VPC --query RouteTable.RouteTableId --output text)
aws ec2 create-route --route-table-id $RT_PUB --destination-cidr-block 0.0.0.0/0 --gateway-id $IGW
aws ec2 associate-route-table --route-table-id $RT_PUB --subnet-id $SUB_PUB_A
aws ec2 associate-route-table --route-table-id $RT_PUB --subnet-id $SUB_PUB_B
```

Las subredes privadas necesitan salida a internet para bajar imágenes de ECR y
hablar con Secrets Manager. Hay dos opciones y la diferencia es de costo:

**NAT Gateway** es lo simple y cuesta del orden de 32 USD al mes por zona.

**VPC Endpoints** (`ecr.api`, `ecr.dkr`, `s3`, `secretsmanager`, `logs`) cuestan
menos si el tráfico es poco, y además el tráfico no sale de la red de AWS. Para
este sistema es la opción razonable.

Las subredes privadas necesitan su propia tabla de rutas, separada de la
pública. Sin ella heredan la tabla principal de la VPC y el endpoint de S3 se
asociaría a la tabla equivocada, que es la clase de error que no da ningún
mensaje: las tareas simplemente no pueden bajar las capas de sus imágenes.

```bash
# Tabla de rutas de las subredes privadas: sin ruta a internet, a propósito
RT_PRIV=$(aws ec2 create-route-table --vpc-id $VPC \
  --tag-specifications "ResourceType=route-table,Tags=[{Key=Name,Value=$PROYECTO-privada}]" \
  --query RouteTable.RouteTableId --output text)
aws ec2 associate-route-table --route-table-id $RT_PRIV --subnet-id $SUB_PRIV_A
aws ec2 associate-route-table --route-table-id $RT_PRIV --subnet-id $SUB_PRIV_B

# Grupo de seguridad de los endpoints: aceptan HTTPS desde las tareas
SG_ENDPOINTS=$(aws ec2 create-security-group --group-name $PROYECTO-endpoints \
  --description "VPC endpoints" --vpc-id $VPC --query GroupId --output text)

# Endpoints mínimos para que Fargate pueda bajar imágenes y leer secretos
for servicio in ecr.api ecr.dkr secretsmanager logs; do
  aws ec2 create-vpc-endpoint --vpc-id $VPC \
    --vpc-endpoint-type Interface \
    --service-name com.amazonaws.$AWS_REGION.$servicio \
    --subnet-ids $SUB_PRIV_A $SUB_PRIV_B \
    --security-group-ids $SG_ENDPOINTS \
    --private-dns-enabled
done

# S3 va como Gateway endpoint, que es gratis, y se asocia a la tabla PRIVADA:
# es la que usan las tareas. ECR guarda las capas de las imágenes en S3, así
# que sin este endpoint las tareas descargan los manifiestos y se quedan sin
# poder bajar el contenido.
aws ec2 create-vpc-endpoint --vpc-id $VPC --vpc-endpoint-type Gateway \
  --service-name com.amazonaws.$AWS_REGION.s3 --route-table-ids $RT_PRIV
```

El grupo de seguridad de los endpoints necesita aceptar HTTPS desde las tareas,
y eso se completa en la sección siguiente, cuando exista `$SG_TAREAS`.

### 2.4 Grupos de seguridad

La regla es una sola: cada componente acepta tráfico sólo de quien lo necesita.

```bash
SG_ALB=$(aws ec2 create-security-group --group-name $PROYECTO-alb \
  --description "Entrada HTTPS desde internet" --vpc-id $VPC --query GroupId --output text)
SG_TAREAS=$(aws ec2 create-security-group --group-name $PROYECTO-tareas \
  --description "Tareas de ECS" --vpc-id $VPC --query GroupId --output text)
SG_BROKERS=$(aws ec2 create-security-group --group-name $PROYECTO-brokers \
  --description "MSK y Amazon MQ" --vpc-id $VPC --query GroupId --output text)

# Internet -> ALB, sólo 443
aws ec2 authorize-security-group-ingress --group-id $SG_ALB \
  --protocol tcp --port 443 --cidr 0.0.0.0/0

# ALB -> tareas (los BFF y el gateway)
aws ec2 authorize-security-group-ingress --group-id $SG_TAREAS \
  --protocol tcp --port 8080-8093 --source-group $SG_ALB

# Tareas -> tareas (tráfico entre servicios y Eureka)
aws ec2 authorize-security-group-ingress --group-id $SG_TAREAS \
  --protocol tcp --port 8080-9000 --source-group $SG_TAREAS

# Tareas -> brokers
aws ec2 authorize-security-group-ingress --group-id $SG_BROKERS \
  --protocol tcp --port 9092-9098 --source-group $SG_TAREAS
aws ec2 authorize-security-group-ingress --group-id $SG_BROKERS \
  --protocol tcp --port 61617 --source-group $SG_TAREAS

# Tareas -> base de datos (la sección 7). Va en su propio grupo y no en el de
# los brokers: no hay razón para que el broker de mensajería y la base compartan
# superficie, y separarlos permite quitarle acceso a uno sin tocar al otro.
SG_BASE=$(aws ec2 create-security-group --group-name $PROYECTO-base \
  --description "RDS PostgreSQL" --vpc-id $VPC --query GroupId --output text)
aws ec2 authorize-security-group-ingress --group-id $SG_BASE \
  --protocol tcp --port 5432 --source-group $SG_TAREAS

# Tareas -> VPC endpoints, que es lo que quedó pendiente de la sección anterior
aws ec2 authorize-security-group-ingress --group-id $SG_ENDPOINTS \
  --protocol tcp --port 443 --source-group $SG_TAREAS
```

Nótese que **no se abre ningún puerto de los microservicios de negocio al
balanceador**. Sólo el gateway y los tres BFF reciben tráfico de afuera;
`cuentas-service`, `pagos-service` y `clientes-service` sólo son alcanzables
desde dentro del grupo de tareas. Es la misma decisión que en `docker-compose.yml`,
donde esos tres no publican puertos al host.

---

## 3. Los secretos

Ningún secreto va en la definición de tarea. Los valores por defecto del
repositorio sirven para levantar el sistema en local sin preparar nada, y no
deben llegar a la nube.

```bash
aws secretsmanager create-secret --name $PROYECTO/config-password \
  --secret-string "$(openssl rand -base64 24)"
aws secretsmanager create-secret --name $PROYECTO/eureka-password \
  --secret-string "$(openssl rand -base64 24)"
aws secretsmanager create-secret --name $PROYECTO/artemis-password \
  --secret-string "$(openssl rand -base64 24)"
aws secretsmanager create-secret --name $PROYECTO/web-client-secret \
  --secret-string "$(openssl rand -base64 32)"
aws secretsmanager create-secret --name $PROYECTO/movil-client-secret \
  --secret-string "$(openssl rand -base64 32)"
aws secretsmanager create-secret --name $PROYECTO/cajero-client-secret \
  --secret-string "$(openssl rand -base64 32)"
aws secretsmanager create-secret --name $PROYECTO/pagos-client-secret \
  --secret-string "$(openssl rand -base64 32)"
```

En la definición de tarea se referencian por ARN en el bloque `secrets`, y ECS
los inyecta como variables de entorno al arrancar el contenedor. El código no
cambia: ya lee `WEB_CLIENT_SECRET`, `EUREKA_PASSWORD` y las demás desde el
entorno, con un valor por defecto para la ejecución local.

**Un pendiente que conviene nombrar.** La clave RSA con la que el `auth-server`
firma los JWT se genera al arrancar. En la nube eso significa que cada despliegue
invalida los tokens vigentes y que dos tareas del `auth-server` firman con claves
distintas, de modo que un token emitido por una no valida en la otra. Antes de
correr más de una tarea de `auth-server`, la clave tiene que venir de Secrets
Manager o de KMS, compartida entre tareas y con rotación solapada. Hasta
entonces, `auth-server` corre con `desiredCount: 1`, y eso lo convierte en el
único punto único de falla del sistema.

---

## 4. Service discovery: por qué Eureka se queda

AWS ofrece **ECS Service Connect** y **Cloud Map**, que resuelven el
descubrimiento por DNS sin que el código participe. Es la opción idiomática en
AWS y haría innecesario el `discovery-server`.

Aun así, aquí se mantiene Eureka, por tres razones concretas:

El sistema ya **balancea del lado del cliente** con Spring Cloud LoadBalancer
sobre el registro de Eureka, tanto en el gateway (`lb://cuentas-service`) como en
la llamada de `pagos-service` hacia `cuentas-service`. Con DNS, el balanceo
vuelve a depender de la resolución y del caché de DNS de la JVM, que por defecto
cachea y tiende a fijar la conexión a una instancia.

El registro de Eureka trae **estado de la instancia**, no sólo su dirección. Una
instancia que se marca `DOWN` deja de recibir tráfico de inmediato; con DNS hay
que esperar al TTL.

Y mantener Eureka hace el sistema **portable**: el mismo artefacto corre en
`docker compose`, en Fargate y en otro proveedor sin cambiar de mecanismo de
descubrimiento.

Lo que sí hace falta es que las tareas se encuentren entre sí para registrarse.
Para eso se usa Cloud Map sólo para los componentes de infraestructura, con
nombres estables:

```bash
# create-private-dns-namespace es asíncrono: devuelve el id de la operación, no
# el del namespace. Hay que esperar a que termine y recién ahí pedir el id real.
OPERACION=$(aws servicediscovery create-private-dns-namespace \
  --name banco.local --vpc $VPC --query OperationId --output text)

until [ "$(aws servicediscovery get-operation --operation-id $OPERACION \
          --query Operation.Status --output text)" = "SUCCESS" ]; do
  echo "esperando a que el namespace exista..."; sleep 10
done

NAMESPACE=$(aws servicediscovery list-namespaces \
  --query "Namespaces[?Name=='banco.local'].Id" --output text)
echo "namespace: $NAMESPACE"
```

Con eso, `config-server.banco.local`, `discovery-server.banco.local` y
`auth-server.banco.local` son nombres resolubles, y son los únicos que las
variables de entorno necesitan.

---

## 5. Publicar las imágenes en ECR

### 5.1 Crear los repositorios

```bash
for m in config-server discovery-server auth-server api-gateway \
         cuentas-service pagos-service clientes-service \
         bff-web bff-movil bff-cajero broker-artemis batch-migracion; do
  aws ecr create-repository --repository-name $PROYECTO/$m \
    --image-scanning-configuration scanOnPush=true \
    --region $AWS_REGION || true
done
```

`scanOnPush` deja el escaneo de vulnerabilidades activado desde el primer día.
No cuesta nada y es la clase de cosa que nadie activa después.

El módulo `broker-kafka` no se publica: en la nube el broker es MSK.

### 5.2 Construir, etiquetar y subir

```bash
aws ecr get-login-password --region $AWS_REGION \
  | docker login --username AWS --password-stdin $ECR

for m in config-server discovery-server auth-server api-gateway \
         cuentas-service pagos-service clientes-service \
         bff-web bff-movil bff-cajero broker-artemis batch-migracion; do
  echo ">> $m"
  docker build --build-arg MODULE=$m -t $ECR/$PROYECTO/$m:$VERSION .
  docker push $ECR/$PROYECTO/$m:$VERSION
done
```

Qué esperar: la primera imagen tarda varios minutos porque compila el reactor
completo; las once siguientes reutilizan la etapa de compilación desde la caché
de Docker y tardan menos de un minuto cada una.

**La etiqueta es `1.0.0`, no `latest`.** Con `latest`, dos despliegues del mismo
día pueden quedar corriendo imágenes distintas sin que nada lo diga, y volver
atrás deja de ser posible. En un flujo real la etiqueta sería el hash del commit.

Verificar:

```bash
aws ecr describe-images --repository-name $PROYECTO/cuentas-service \
  --query 'imageDetails[].{tags:imageTags,subida:imagePushedAt,mb:imageSizeInBytes}' --output table
```

---

## 6. Los brokers administrados

### 6.1 Kafka con Amazon MSK

```bash
cat > msk.json <<JSON
{
  "ClusterName": "$PROYECTO-kafka",
  "KafkaVersion": "3.6.0",
  "NumberOfBrokerNodes": 2,
  "BrokerNodeGroupInfo": {
    "InstanceType": "kafka.t3.small",
    "ClientSubnets": ["$SUB_PRIV_A", "$SUB_PRIV_B"],
    "SecurityGroups": ["$SG_BROKERS"],
    "StorageInfo": { "EBSStorageInfo": { "VolumeSize": 20 } }
  },
  "EncryptionInfo": {
    "EncryptionInTransit": { "ClientBroker": "TLS", "InCluster": true }
  },
  "ClientAuthentication": { "Sasl": { "Iam": { "Enabled": true } } }
}
JSON

aws kafka create-cluster --cli-input-json file://msk.json
```

Dos brokers en dos zonas, no uno. Con un solo broker, `acks=all` del productor
—que el proyecto ya configura— no garantiza nada, porque no hay dónde replicar.
Con dos y factor de replicación 2, esa línea pasa a significar lo que dice.

Obtener la dirección de conexión:

```bash
ARN_MSK=$(aws kafka list-clusters \
  --query "ClusterInfoList[?ClusterName=='$PROYECTO-kafka'].ClusterArn" --output text)

# El clúster tarda entre 15 y 30 minutos en quedar ACTIVE. Antes de eso no hay
# direcciones que pedir.
until [ "$(aws kafka describe-cluster --cluster-arn $ARN_MSK \
          --query ClusterInfo.State --output text)" = "ACTIVE" ]; do
  echo "esperando a que MSK esté ACTIVE..."; sleep 60
done

export KAFKA_SERVERS=$(aws kafka get-bootstrap-brokers --cluster-arn $ARN_MSK \
  --query BootstrapBrokerStringSaslIam --output text)
echo "KAFKA_SERVERS=$KAFKA_SERVERS"
```

Crear los dos tópicos, con el factor de replicación que el clúster permite:

```bash
# Desde una tarea o instancia dentro de la VPC
kafka-topics.sh --bootstrap-server $KAFKA_SERVERS \
  --command-config cliente-iam.properties \
  --create --topic banco.transacciones-completadas \
  --partitions 3 --replication-factor 2

kafka-topics.sh --bootstrap-server $KAFKA_SERVERS \
  --command-config cliente-iam.properties \
  --create --topic banco.alertas-seguridad \
  --partitions 3 --replication-factor 2
```

**Un cambio que el código sí necesita.** MSK con autenticación IAM exige
`SASL_SSL` y el `AwsMskIamClientCallbackHandler`, que vienen en la librería
`aws-msk-iam-auth`. Son tres propiedades más en la configuración central, no
código:

```yaml
# configuracion-central/application.yml, perfil aws
spring:
  kafka:
    bootstrap-servers: ${KAFKA_SERVERS}
    properties:
      security.protocol: SASL_SSL
      sasl.mechanism: AWS_MSK_IAM
      sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
      sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler
```

Y la dependencia `software.amazon.msk:aws-msk-iam-auth` en los tres
microservicios que hablan con Kafka. La alternativa, dejar MSK con autenticación
en texto plano dentro de la VPC, ahorra ese cambio y es exactamente la clase de
atajo que la sección 5.6 del `readme.md` cuenta haber tenido que corregir en este
mismo proyecto.

### 6.2 JMS con Amazon MQ

```bash
aws mq create-broker \
  --broker-name $PROYECTO-artemis \
  --engine-type ACTIVEMQ \
  --engine-version 5.18.4 \
  --host-instance-type mq.t3.micro \
  --deployment-mode ACTIVE_STANDBY_MULTI_AZ \
  --subnet-ids $SUB_PRIV_A $SUB_PRIV_B \
  --security-groups $SG_BROKERS \
  --users Username=artemis,Password=$(aws secretsmanager get-secret-value \
      --secret-id $PROYECTO/artemis-password --query SecretString --output text) \
  --publicly-accessible false \
  --auto-minor-version-upgrade
```

`ACTIVE_STANDBY_MULTI_AZ` y no `SINGLE_INSTANCE`: la cola es la que garantiza
que un retiro llegue al historial, y una cola en una sola zona es un punto único
de falla sobre el dato más sensible del sistema.

La dirección de conexión sale de:

```bash
ID_MQ=$(aws mq list-brokers --query "BrokerSummaries[?BrokerName=='$PROYECTO-artemis'].BrokerId" --output text)

until [ "$(aws mq describe-broker --broker-id $ID_MQ \
          --query BrokerState --output text)" = "RUNNING" ]; do
  echo "esperando a que Amazon MQ esté RUNNING..."; sleep 30
done

# El endpoint OpenWire, que es el que usa el cliente JMS
export URL_MQ=$(aws mq describe-broker --broker-id $ID_MQ \
  --query "BrokerInstances[0].Endpoints[?starts_with(@, 'ssl://')] | [0]" --output text)
echo "URL_MQ=$URL_MQ"
```

**Aquí hay una trampa que conviene evitar.** La variable que los microservicios
usan en local y en Docker es `ARTEMIS_HOST`, y se interpola **dentro** de una URL
ya formada (`configuracion-central/cuentas-service.yml` y `pagos-service.yml`):

```yaml
broker-url: tcp://${ARTEMIS_HOST:localhost}:61616?callTimeout=4000&connectionTTL=30000
```

Poner ahí el endpoint de Amazon MQ produciría
`tcp://ssl://b-xxx.mq.us-east-1.amazonaws.com:61617:61616?...`, que no es una URL
de nada. En AWS hay que sobrescribir la propiedad completa, no el host:

```json
{ "name": "SPRING_ARTEMIS_BROKER_URL",
  "value": "ssl://b-xxx-1.mq.us-east-1.amazonaws.com:61617?callTimeout=4000&connectionTTL=30000" }
```

Spring Boot relaja los nombres de las propiedades, así que esa variable de
entorno reemplaza a `spring.artemis.broker-url` entera y el valor por defecto de
`ARTEMIS_HOST` deja de usarse. El cambio es de configuración; el código no se
toca.

---

## 7. El estado: de memoria a RDS

Esto es el pendiente más importante antes de considerar el sistema listo para
tráfico real, y conviene decirlo antes de los comandos.

Los tres microservicios cargan los CSV al arrancar y mantienen el estado **sólo
en memoria**. En local eso funciona y hasta ayuda a demostrar la arquitectura.
En la nube tiene dos consecuencias que no se pueden ignorar:

**El escalado deja de ser transparente.** Dos tareas de `cuentas-service` tienen
cada una su propia copia del saldo. Un retiro atendido por la tarea A no se ve
desde la tarea B, y el balanceador reparte sin saberlo. El escalado horizontal
que el sistema demuestra es real en el reparto de tráfico, pero sólo es correcto
mientras los servicios no tengan estado propio que divergir.

**La idempotencia de la sección 5.5 del `readme.md` no se puede sostener.** La
clave de idempotencia por operación que falta para cerrar la compensación de una
transferencia necesita un registro durable y compartido entre instancias.

Por eso, el orden del despliegue real es: primero RDS, después escalar.

```bash
# El subnet group le dice a RDS en qué subredes puede poner la instancia y su
# réplica. Hay que crearlo antes: sin él, create-db-instance falla.
aws rds create-db-subnet-group \
  --db-subnet-group-name $PROYECTO-subnets \
  --db-subnet-group-description "Subredes privadas del $PROYECTO" \
  --subnet-ids $SUB_PRIV_A $SUB_PRIV_B

aws rds create-db-instance \
  --db-instance-identifier $PROYECTO-db \
  --db-instance-class db.t4g.micro \
  --engine postgres \
  --engine-version 16 \
  --allocated-storage 20 \
  --db-name bancoxyz \
  --master-username banco \
  --manage-master-user-password \
  --vpc-security-group-ids $SG_BASE \
  --db-subnet-group-name $PROYECTO-subnets \
  --multi-az \
  --backup-retention-period 7 \
  --no-publicly-accessible

aws rds wait db-instance-available --db-instance-identifier $PROYECTO-db

export DB_HOST=$(aws rds describe-db-instances --db-instance-identifier $PROYECTO-db \
  --query 'DBInstances[0].Endpoint.Address' --output text)
# El ARN del secreto que RDS creó con la clave del usuario maestro
export ARN_SECRETO_DB=$(aws rds describe-db-instances --db-instance-identifier $PROYECTO-db \
  --query 'DBInstances[0].MasterUserSecret.SecretArn' --output text)
echo "DB_HOST=$DB_HOST"
```

`--manage-master-user-password` deja que RDS genere la clave y la guarde en
Secrets Manager, de modo que nunca pasa por la línea de comandos ni por el
historial del shell.

El módulo `batch-migracion` ya trae el perfil `postgres` y no necesita cambios
para apuntar a RDS. Los tres microservicios sí: hay que reemplazar los
repositorios en memoria por repositorios JPA, que es el trabajo que queda
descrito en el informe técnico como siguiente paso.

---

## 8. ECS Fargate: cluster, tareas y servicios

### 8.1 Cluster y rol de ejecución

```bash
aws ecs create-cluster --cluster-name $CLUSTER \
  --capacity-providers FARGATE --settings name=containerInsights,value=enabled
```

`containerInsights` activado desde el principio: sin métricas, el autoescalado de
la sección 8.4 no tiene en qué basarse.

El rol de ejecución necesita permiso para bajar imágenes de ECR, escribir en
CloudWatch y leer los secretos:

```bash
aws iam create-role --role-name $PROYECTO-ecs-exec \
  --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},
                  "Action":"sts:AssumeRole"}]}'

aws iam attach-role-policy --role-name $PROYECTO-ecs-exec \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy

aws iam put-role-policy --role-name $PROYECTO-ecs-exec \
  --policy-name leer-secretos --policy-document "{
    \"Version\":\"2012-10-17\",
    \"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\"secretsmanager:GetSecretValue\"],
      \"Resource\":\"arn:aws:secretsmanager:$AWS_REGION:$AWS_ACCOUNT:secret:$PROYECTO/*\"}]}"
```

### 8.2 Una definición de tarea, como ejemplo

Esta es la de `cuentas-service`, que es la más completa porque habla con los dos
brokers. Las demás siguen el mismo patrón con otras variables.

```bash
cat > tarea-cuentas.json <<JSON
{
  "family": "$PROYECTO-cuentas-service",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "executionRoleArn": "arn:aws:iam::$AWS_ACCOUNT:role/$PROYECTO-ecs-exec",
  "containerDefinitions": [{
    "name": "cuentas-service",
    "image": "$ECR/$PROYECTO/cuentas-service:$VERSION",
    "essential": true,
    "portMappings": [{ "containerPort": 8081, "protocol": "tcp" }],
    "environment": [
      { "name": "CONFIG_HOST",      "value": "config-server.banco.local" },
      { "name": "EUREKA_HOST",      "value": "discovery-server.banco.local" },
      { "name": "AUTH_ISSUER_URI",  "value": "http://auth-server.banco.local:9000" },
      { "name": "SPRING_ARTEMIS_BROKER_URL", "value": "$URL_MQ?callTimeout=4000&connectionTTL=30000" },
      { "name": "KAFKA_SERVERS",    "value": "$KAFKA_SERVERS" },
      { "name": "SPRING_PROFILES_ACTIVE", "value": "aws" }
    ],
    "secrets": [
      { "name": "CONFIG_PASSWORD",
        "valueFrom": "arn:aws:secretsmanager:$AWS_REGION:$AWS_ACCOUNT:secret:$PROYECTO/config-password" },
      { "name": "EUREKA_PASSWORD",
        "valueFrom": "arn:aws:secretsmanager:$AWS_REGION:$AWS_ACCOUNT:secret:$PROYECTO/eureka-password" },
      { "name": "ARTEMIS_PASSWORD",
        "valueFrom": "arn:aws:secretsmanager:$AWS_REGION:$AWS_ACCOUNT:secret:$PROYECTO/artemis-password" }
    ],
    "healthCheck": {
      "command": ["CMD-SHELL", "curl -fsS http://localhost:8081/actuator/health || exit 1"],
      "interval": 30, "timeout": 5, "retries": 3, "startPeriod": 90
    },
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {
        "awslogs-group": "/ecs/$PROYECTO/cuentas-service",
        "awslogs-region": "$AWS_REGION",
        "awslogs-stream-prefix": "ecs",
        "awslogs-create-group": "true"
      }
    }
  }]
}
JSON

aws ecs register-task-definition --cli-input-json file://tarea-cuentas.json
```

Dos valores que importan y suelen quedar mal:

**`startPeriod: 90`.** Un servicio de este proyecto tarda entre veinte y cuarenta
segundos en estar listo: baja su configuración del Config Server, se registra en
Eureka y arranca los contenedores de Kafka. Con el valor por defecto, ECS lo
declara insano y lo mata antes de que termine de arrancar, en un bucle que desde
afuera parece un error de la aplicación.

**`cpu: 512` y `memory: 1024`.** La imagen arranca con
`-XX:MaxRAMPercentage=75.0`, así que la JVM se ajusta sola al límite del
contenedor sin repetir el número en dos lugares.

### 8.3 Orden de arranque

ECS no tiene `depends_on` entre servicios distintos: `dependsOn` sólo funciona
entre contenedores de la misma tarea. Como el sistema sí tiene un orden
obligatorio, hay que crear los servicios por grupos y esperar a que cada uno
estabilice.

```bash
crear_servicio() {
  local nombre=$1 puerto=$2 replicas=${3:-1}
  aws ecs create-service \
    --cluster $CLUSTER \
    --service-name $nombre \
    --task-definition $PROYECTO-$nombre \
    --desired-count $replicas \
    --launch-type FARGATE \
    --network-configuration "awsvpcConfiguration={subnets=[$SUB_PRIV_A,$SUB_PRIV_B],securityGroups=[$SG_TAREAS],assignPublicIp=DISABLED}" \
    --health-check-grace-period-seconds 120
  aws ecs wait services-stable --cluster $CLUSTER --services $nombre
  echo ">> $nombre estable"
}

# Grupo 1: configuración y descubrimiento
crear_servicio config-server 8888
crear_servicio discovery-server 8761

# Grupo 2: autorización y gateway
crear_servicio auth-server 9000          # desiredCount 1: ver la nota de la sección 3
crear_servicio api-gateway 8080 2

# Grupo 3: los microservicios de negocio
crear_servicio cuentas-service 8081 2
crear_servicio pagos-service 8082 2
crear_servicio clientes-service 8083 2

# Grupo 4: los canales
crear_servicio bff-web 8091 2
crear_servicio bff-movil 8092 2
crear_servicio bff-cajero 8093 2
```

`aws ecs wait services-stable` es lo que reemplaza al
`condition: service_healthy` de `docker-compose`.

### 8.4 Autoescalado

Aquí el escalado horizontal deja de ser una bandera manual y pasa a responder a
la carga:

```bash
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/$CLUSTER/cuentas-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 --max-capacity 10

aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/$CLUSTER/cuentas-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name cpu-70 \
  --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration '{
    "TargetValue": 70.0,
    "PredefinedMetricSpecification": { "PredefinedMetricType": "ECSServiceAverageCPUUtilization" },
    "ScaleOutCooldown": 60,
    "ScaleInCooldown": 300
  }'
```

El `ScaleInCooldown` es cinco veces el de salida a propósito: bajar instancias
rápido ante una caída momentánea de la carga es la forma habitual de quedarse
corto justo cuando la carga vuelve.

**`min-capacity 2`, no 1.** Con una sola tarea, cada despliegue tiene una ventana
sin servicio y la caída de una zona baja el componente entero.

Para `clientes-service` hay un límite que conviene conocer: es consumidor de
Kafka, y los tópicos tienen tres particiones. Más de tres tareas no aumentan el
consumo, porque Kafka asigna como máximo una partición por consumidor dentro de
un grupo; las tareas extra quedarían sin particiones asignadas. Si hiciera falta
escalar más allá de tres, primero hay que aumentar las particiones del tópico.

### 8.5 El balanceador

Sólo el gateway y los tres BFF se publican. Los microservicios de negocio no
tienen grupo de destino: se llegan desde dentro.

```bash
ALB=$(aws elbv2 create-load-balancer --name $PROYECTO-alb \
  --subnets $SUB_PUB_A $SUB_PUB_B --security-groups $SG_ALB \
  --scheme internet-facing --type application --query 'LoadBalancers[0].LoadBalancerArn' --output text)

crear_grupo_destino() {
  local nombre=$1 puerto=$2
  aws elbv2 create-target-group --name $PROYECTO-$nombre \
    --protocol HTTP --port $puerto --vpc-id $VPC --target-type ip \
    --health-check-path /actuator/health \
    --health-check-interval-seconds 30 \
    --healthy-threshold-count 2 --unhealthy-threshold-count 3 \
    --query 'TargetGroups[0].TargetGroupArn' --output text
}

TG_GATEWAY=$(crear_grupo_destino gateway 8080)
TG_WEB=$(crear_grupo_destino bff-web 8091)
TG_MOVIL=$(crear_grupo_destino bff-movil 8092)
TG_CAJERO=$(crear_grupo_destino bff-cajero 8093)

# El certificado de ACM, para el dominio por el que se va a publicar la API.
# La validación por DNS exige agregar un registro CNAME en la zona del dominio,
# que es un paso manual fuera de AWS: el comando pide el certificado y queda
# PENDING_VALIDATION hasta que ese registro exista.
export DOMINIO=api.bancoxyz.cl
export ARN_CERTIFICADO=$(aws acm request-certificate \
  --domain-name $DOMINIO --validation-method DNS \
  --query CertificateArn --output text)

aws acm describe-certificate --certificate-arn $ARN_CERTIFICADO \
  --query 'Certificate.DomainValidationOptions[0].ResourceRecord'
# Crear ese CNAME en el DNS del dominio y esperar:
aws acm wait certificate-validated --certificate-arn $ARN_CERTIFICADO

# HTTPS con el certificado ya validado
aws elbv2 create-listener --load-balancer-arn $ALB \
  --protocol HTTPS --port 443 \
  --certificates CertificateArn=$ARN_CERTIFICADO \
  --ssl-policy ELBSecurityPolicy-TLS13-1-2-2021-06 \
  --default-actions Type=forward,TargetGroupArn=$TG_GATEWAY
```

Reglas por ruta, para que cada canal entre por la suya:

```bash
LISTENER=$(aws elbv2 describe-listeners --load-balancer-arn $ALB \
  --query 'Listeners[0].ListenerArn' --output text)

aws elbv2 create-rule --listener-arn $LISTENER --priority 10 \
  --conditions Field=path-pattern,Values='/web/*' \
  --actions Type=forward,TargetGroupArn=$TG_WEB
aws elbv2 create-rule --listener-arn $LISTENER --priority 20 \
  --conditions Field=path-pattern,Values='/movil/*' \
  --actions Type=forward,TargetGroupArn=$TG_MOVIL
aws elbv2 create-rule --listener-arn $LISTENER --priority 30 \
  --conditions Field=path-pattern,Values='/cajero/*' \
  --actions Type=forward,TargetGroupArn=$TG_CAJERO
```

El tráfico llega siempre por **HTTPS**, con el certificado en el balanceador y la
política TLS 1.3. El requerimiento de comunicaciones seguras de la actividad se
cumple aquí y no en el código: terminar TLS en el balanceador es lo correcto,
porque es un punto único donde rotar el certificado y porque las tareas no
necesitan conocerlo.

**Lo que falta para cerrar el ciclo del issuer.** Los microservicios exigen que
el claim `iss` del token coincida con `AUTH_ISSUER_URI`. Si los canales piden el
token a través del balanceador (`https://api.bancoxyz.cl`) pero la variable dice
`http://auth-server.banco.local:9000`, la validación falla. La forma correcta es
publicar el `auth-server` por el balanceador y poner ese mismo nombre público en
`AUTH_ISSUER_URI` para todos los servicios, de modo que el issuer sea uno solo
visto desde dentro y desde fuera.

---

## 9. Los procesos batch en la nube

El batch no es un servicio: se ejecuta y termina. Corresponde una tarea
programada, no un servicio de ECS.

```bash
aws events put-rule --name $PROYECTO-batch-diario \
  --schedule-expression "cron(0 6 * * ? *)" \
  --description "Reporte de transacciones diarias, 06:00 UTC"

aws events put-targets --rule $PROYECTO-batch-diario --targets "[{
  \"Id\": \"batch-transacciones\",
  \"Arn\": \"arn:aws:ecs:$AWS_REGION:$AWS_ACCOUNT:cluster/$CLUSTER\",
  \"RoleArn\": \"arn:aws:iam::$AWS_ACCOUNT:role/$PROYECTO-events\",
  \"EcsParameters\": {
    \"TaskDefinitionArn\": \"arn:aws:ecs:$AWS_REGION:$AWS_ACCOUNT:task-definition/$PROYECTO-batch-migracion\",
    \"LaunchType\": \"FARGATE\",
    \"NetworkConfiguration\": {
      \"awsvpcConfiguration\": {
        \"Subnets\": [\"$SUB_PRIV_A\"],
        \"SecurityGroups\": [\"$SG_TAREAS\"],
        \"AssignPublicIp\": \"DISABLED\"
      }
    }
  },
  \"Input\": \"{\\\"containerOverrides\\\":[{\\\"name\\\":\\\"batch-migracion\\\",\\\"command\\\":[\\\"--job=transacciones\\\"]}]}\"
}]"
```

Los otros dos jobs van en reglas separadas, con su propia frecuencia: el cálculo
de intereses mensual y los estados de cuenta anuales no tienen por qué correr
todos los días.

**Aquí la política de finalización del batch deja de ser un detalle.** El proceso
termina con código 1 si algún job quedó fallido tras agotar los reintentos, y ese
código es lo que ECS registra como `exitCode` de la tarea. Una alarma sobre eso
es lo que hace que un batch fallido se sepa el mismo día:

```bash
aws logs put-metric-filter \
  --log-group-name /ecs/$PROYECTO/batch-migracion \
  --filter-name batch-fallido \
  --filter-pattern '"job(s) terminaron fallidos"' \
  --metric-transformations \
      metricName=BatchFallido,metricNamespace=$PROYECTO,metricValue=1

# El destino de las alarmas. Una alarma sin destinatario es un grafico bonito
# que nadie mira: SNS es lo que la convierte en un correo o un mensaje.
export ARN_TEMA_SNS=$(aws sns create-topic --name $PROYECTO-alertas \
  --query TopicArn --output text)
aws sns subscribe --topic-arn $ARN_TEMA_SNS --protocol email \
  --notification-endpoint operaciones@bancoxyz.cl
# La suscripcion queda pendiente hasta que alguien confirme desde ese correo.

aws cloudwatch put-metric-alarm \
  --alarm-name $PROYECTO-batch-fallido \
  --metric-name BatchFallido --namespace $PROYECTO \
  --statistic Sum --period 300 --threshold 1 \
  --comparison-operator GreaterThanOrEqualToThreshold \
  --evaluation-periods 1 \
  --alarm-actions $ARN_TEMA_SNS
```

---

## 10. Observabilidad

Los logs de las tareas van a CloudWatch Logs por el driver `awslogs` que ya está
en la definición de tarea. Vale la pena agregar filtros sobre las líneas que el
sistema ya emite a propósito, en vez de inventar métricas nuevas:

```bash
# Circuito abierto
aws logs put-metric-filter --log-group-name /ecs/$PROYECTO/pagos-service \
  --filter-name circuito-abierto \
  --filter-pattern '"CIRCUITO cuentas paso de CLOSED a OPEN"' \
  --metric-transformations metricName=CircuitoAbierto,metricNamespace=$PROYECTO,metricValue=1

# Descalce contable: la alerta más grave del sistema
aws logs put-metric-filter --log-group-name /ecs/$PROYECTO/pagos-service \
  --filter-name descalce-transferencia \
  --filter-pattern '"DESCALCE en la transferencia"' \
  --metric-transformations metricName=DescalceContable,metricNamespace=$PROYECTO,metricValue=1

# Evento que no se pudo publicar
aws logs put-metric-filter --log-group-name /ecs/$PROYECTO/cuentas-service \
  --filter-name evento-no-publicado \
  --filter-pattern '"NO se pudo publicar el evento"' \
  --metric-transformations metricName=EventoNoPublicado,metricNamespace=$PROYECTO,metricValue=1
```

La alarma sobre `DescalceContable` debe tener umbral **1** y notificar de
inmediato. Es el único caso del sistema que describe dinero descontado de una
cuenta que no llegó a ninguna, y no se arregla solo.

---

## 11. Alternativa de bajo costo: una instancia EC2

Para una demostración o un ambiente de pruebas, el sistema completo corre en una
sola instancia con `docker compose`, sin cambiar nada:

```bash
# La AMI mas reciente de Amazon Linux 2023, el par de llaves y un grupo de
# seguridad que abra SSH y los puertos publicados.
export AMI_AMAZON_LINUX_2023=$(aws ssm get-parameter \
  --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
  --query 'Parameter.Value' --output text)
export MI_LLAVE=mi-par-de-llaves    # el nombre de un key pair que ya exista
export SG_DEMO=$(aws ec2 create-security-group --group-name $PROYECTO-demo \
  --description "Demo en una instancia" --vpc-id $VPC --query GroupId --output text)
aws ec2 authorize-security-group-ingress --group-id $SG_DEMO \
  --protocol tcp --port 22 --cidr $(curl -s ifconfig.me)/32
for puerto in 8080 8091 8092 8093 8761 9000; do
  aws ec2 authorize-security-group-ingress --group-id $SG_DEMO \
    --protocol tcp --port $puerto --cidr $(curl -s ifconfig.me)/32
done

# t3.large: 2 vCPU y 8 GB. Con menos memoria, trece JVM no caben.
aws ec2 run-instances \
  --image-id $AMI_AMAZON_LINUX_2023 \
  --instance-type t3.large \
  --key-name $MI_LLAVE \
  --security-group-ids $SG_DEMO \
  --subnet-id $SUB_PUB_A \
  --associate-public-ip-address \
  --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=40}' \
  --user-data '#!/bin/bash
dnf update -y
dnf install -y docker git
systemctl enable --now docker
usermod -aG docker ec2-user
curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/bin/docker-compose
chmod +x /usr/local/bin/docker-compose'
```

Después, por SSH:

```bash
git clone https://github.com/Ign14/DB3_ExpSumativas.git
cd DB3_ExpSumativas/EFT_S9_Backend_Avanzado
docker compose build
docker compose up -d
docker compose ps
```

Es el camino más corto y el más barato, del orden de 60 USD al mes. Lo que no da:
alta disponibilidad —todo vive en una zona y en una máquina—, escalado
automático, y brokers administrados con respaldo. Sirve para mostrar el sistema
funcionando, no para sostenerlo.

---

## 12. Costo estimado

Precios de referencia de `us-east-1`, mensuales y aproximados. Están para
dimensionar, no para cotizar.

| Componente | Configuración | USD/mes |
|---|---|---|
| Fargate | 12 servicios, ~20 tareas de 0,5 vCPU y 1 GB | 290 |
| Amazon MSK | 2 × kafka.t3.small + 40 GB | 130 |
| Amazon MQ | mq.t3.micro en activo/standby | 55 |
| RDS PostgreSQL | db.t4g.micro Multi-AZ, 20 GB | 50 |
| ALB | 1 balanceador + tráfico bajo | 25 |
| VPC Endpoints | 4 interface endpoints | 30 |
| ECR | 12 imágenes, ~5 GB | 1 |
| CloudWatch | logs y métricas, volumen bajo | 15 |
| **Total aproximado** | | **~600** |

La alternativa de la sección 11 cuesta del orden de 60 USD al mes. La diferencia
—un orden de magnitud— es lo que se paga por alta disponibilidad, escalado
automático y servicios administrados, y es una decisión de negocio antes que
técnica.

Lo que más baja la cuenta, si hiciera falta: Fargate Spot para los servicios que
toleran interrupción, un solo broker de MSK en un ambiente que no sea producción,
y RDS en una sola zona fuera de producción.

---

## 13. Verificación posterior al despliegue

Las mismas pruebas de `instrucciones.md` sirven, cambiando `localhost:8080` por
el nombre público del balanceador. El orden que conviene seguir:

```bash
export API=https://$DOMINIO

# 1. Los servicios están estables
aws ecs list-services --cluster $CLUSTER --query 'serviceArns' --output table
aws ecs describe-services --cluster $CLUSTER \
  --services cuentas-service pagos-service clientes-service \
  --query 'services[].{nombre:serviceName,deseadas:desiredCount,activas:runningCount}' --output table

# 2. El balanceador ve sus destinos sanos
aws elbv2 describe-target-health --target-group-arn $TG_GATEWAY \
  --query 'TargetHealthDescriptions[].{destino:Target.Id,estado:TargetHealth.State}' --output table

# 3. Hay token. Los secretos salen de Secrets Manager, no de ningun archivo.
export SECRETO_WEB=$(aws secretsmanager get-secret-value \
  --secret-id $PROYECTO/web-client-secret --query SecretString --output text)
export SECRETO_CAJERO=$(aws secretsmanager get-secret-value \
  --secret-id $PROYECTO/cajero-client-secret --query SecretString --output text)

TOKEN=$(curl -s -u banco-web-client:$SECRETO_WEB -d grant_type=client_credentials \
  --data-urlencode "scope=cuentas.read movimientos.read clientes.read" \
  $API/oauth2/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

# 4. Los tres microservicios responden por el gateway
for r in /api/cuentas/101 /api/movimientos/101 /api/clientes/101; do
  echo "$r -> $(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" $API$r)"
done

# 5. El control de acceso sigue vigente (debe dar 403)
TOKEN_CAJERO=$(curl -s -u cajero-client:$SECRETO_CAJERO -d grant_type=client_credentials \
  --data-urlencode "scope=cuentas.read cuentas.write" \
  $API/oauth2/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
curl -s -o /dev/null -w "cajero en clientes: %{http_code}\n" \
  -H "Authorization: Bearer $TOKEN_CAJERO" $API/api/clientes/101

# 6. Los tópicos existen y tienen las particiones esperadas
kafka-topics.sh --bootstrap-server $KAFKA_SERVERS \
  --command-config cliente-iam.properties --describe

# 7. El escalado funciona de verdad
aws ecs update-service --cluster $CLUSTER --service cuentas-service --desired-count 4
aws ecs wait services-stable --cluster $CLUSTER --services cuentas-service
# y las cuatro instancias deben aparecer en Eureka
```

El paso 5 es el que más vale la pena: un despliegue que funciona pero perdió el
control de acceso es peor que uno que no funciona, porque nadie se da cuenta.

---

## 14. Resumen de lo que falta

En orden de prioridad, lo que este sistema necesita antes de ver tráfico real:

**Estado en una base de datos.** Sin eso, el escalado horizontal reparte tráfico
correctamente pero las instancias divergen (sección 7).

**La clave de firma del `auth-server` fuera del proceso.** Hasta entonces,
`auth-server` corre con una sola tarea y es el punto único de falla del sistema
(sección 3).

**Clave de idempotencia por operación de pago.** Es lo que permitiría reintentar
una transferencia sin duplicarla y retomar una compensación interrumpida
(sección 5.5 del `readme.md`).

**Autenticación del usuario final en los BFF.** Hoy los canales se autentican a
sí mismos contra el backend, pero nada verifica a la persona detrás del canal.

**Un único issuer, visto igual desde dentro y desde fuera** (sección 8.5).

Ninguna de las cinco es un hallazgo tardío: las cinco están nombradas en el
código, donde corresponde, con el motivo de por qué no se resolvieron.
