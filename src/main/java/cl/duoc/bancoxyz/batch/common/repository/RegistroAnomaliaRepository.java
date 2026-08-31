package cl.duoc.bancoxyz.batch.common.repository;

import cl.duoc.bancoxyz.batch.common.model.RegistroAnomalia;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistroAnomaliaRepository extends JpaRepository<RegistroAnomalia, Long> {

    long countByProceso(String proceso);
}
