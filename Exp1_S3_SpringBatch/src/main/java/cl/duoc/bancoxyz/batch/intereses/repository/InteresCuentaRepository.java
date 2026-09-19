package cl.duoc.bancoxyz.batch.intereses.repository;

import cl.duoc.bancoxyz.batch.intereses.model.InteresCuenta;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InteresCuentaRepository extends JpaRepository<InteresCuenta, Long> {
    long countByTipoCuenta(String tipoCuenta);
}
