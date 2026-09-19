package cl.duoc.bancoxyz.batch.transacciones.repository;

import cl.duoc.bancoxyz.batch.transacciones.model.ResumenTransaccionesDiarias;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumenTransaccionesDiariasRepository extends JpaRepository<ResumenTransaccionesDiarias, Long> {
}
