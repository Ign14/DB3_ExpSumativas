package cl.duoc.bancoxyz.batch.transacciones.repository;

import cl.duoc.bancoxyz.batch.transacciones.model.Transaccion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;

public interface TransaccionRepository extends JpaRepository<Transaccion, Long> {

    long countByTipo(String tipo);

    @Query("select coalesce(sum(case when t.tipo = 'credito' then t.monto else -t.monto end), 0) from Transaccion t")
    BigDecimal calcularMontoNeto();
}
