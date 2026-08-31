package cl.duoc.bancoxyz.batch.cuentasanuales.repository;

import cl.duoc.bancoxyz.batch.cuentasanuales.model.MovimientoCuentaAnual;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MovimientoCuentaAnualRepository extends JpaRepository<MovimientoCuentaAnual, Long> {

    @Query("select distinct m.cuentaId from MovimientoCuentaAnual m")
    List<Long> buscarCuentasDistintas();

    @Query("select distinct m.anio from MovimientoCuentaAnual m")
    List<Integer> buscarAniosDistintos();

    List<MovimientoCuentaAnual> findByCuentaIdAndAnio(Long cuentaId, Integer anio);
}
