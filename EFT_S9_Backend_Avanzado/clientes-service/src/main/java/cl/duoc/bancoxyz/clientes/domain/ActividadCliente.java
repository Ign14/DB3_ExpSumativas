package cl.duoc.bancoxyz.clientes.domain;

/**
 * Lo que este servicio sabe de la actividad de un cliente por haber escuchado
 * los topicos de Kafka: cuantas operaciones se le aprobaron, cuantas alertas
 * de seguridad acumula y cual fue la ultima operacion.
 *
 * Es una vista derivada de eventos, no la verdad del dominio. La verdad del
 * saldo vive en cuentas-service y la del historial en pagos-service; esto es
 * una proyeccion local que existe para que consultar un perfil no obligue a
 * llamar a otros dos servicios y quedar a merced de que esten arriba.
 */
public record ActividadCliente(int operaciones, int alertas, String ultimaOperacion) {

    public static final ActividadCliente VACIA = new ActividadCliente(0, 0, null);

    ActividadCliente conOperacion(String descripcion) {
        return new ActividadCliente(operaciones + 1, alertas, descripcion);
    }

    ActividadCliente conAlerta() {
        return new ActividadCliente(operaciones, alertas + 1, ultimaOperacion);
    }
}
