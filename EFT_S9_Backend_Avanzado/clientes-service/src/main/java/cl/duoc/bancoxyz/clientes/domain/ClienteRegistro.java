package cl.duoc.bancoxyz.clientes.domain;

import java.math.BigDecimal;

/**
 * Datos personales del titular, tal como quedaron tras la migracion.
 *
 * Inmutable: una actualizacion de contacto produce una instancia nueva, igual
 * que un debito en el dominio de cuentas. La actividad del cliente no vive
 * aqui, sino en {@link ActividadCliente}, porque cambia por eventos y no por
 * peticiones, y mezclarlas obligaria a reescribir el perfil completo cada vez
 * que llega un evento.
 */
public record ClienteRegistro(
        Long clienteId,
        String nombre,
        int edad,
        String tipoCuentaPrincipal,
        BigDecimal saldoReferencial
) {

    ClienteRegistro conNombre(String nuevoNombre) {
        return new ClienteRegistro(clienteId, nuevoNombre, edad, tipoCuentaPrincipal, saldoReferencial);
    }

    /**
     * Segmento comercial del cliente, derivado del saldo de referencia y del
     * producto principal.
     *
     * Se calcula y no se almacena: es una funcion de datos que ya estan en el
     * registro, y guardarlo abriria la posibilidad de que quede contradiciendo
     * al saldo del que salio.
     */
    public String segmento() {
        if ("hipoteca".equals(tipoCuentaPrincipal)) {
            return "hipotecario";
        }
        if (saldoReferencial.compareTo(new BigDecimal("7000")) >= 0) {
            return "preferente";
        }
        if (saldoReferencial.compareTo(new BigDecimal("4000")) >= 0) {
            return "establecido";
        }
        return "basico";
    }
}
