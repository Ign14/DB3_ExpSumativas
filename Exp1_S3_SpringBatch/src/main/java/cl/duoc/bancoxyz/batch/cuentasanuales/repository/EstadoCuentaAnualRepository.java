package cl.duoc.bancoxyz.batch.cuentasanuales.repository;

import cl.duoc.bancoxyz.batch.cuentasanuales.model.EstadoCuentaAnual;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EstadoCuentaAnualRepository extends JpaRepository<EstadoCuentaAnual, Long> {
}
