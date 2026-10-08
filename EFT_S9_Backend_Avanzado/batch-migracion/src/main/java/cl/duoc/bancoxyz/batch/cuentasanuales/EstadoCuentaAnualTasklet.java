package cl.duoc.bancoxyz.batch.cuentasanuales;

import cl.duoc.bancoxyz.batch.cuentasanuales.model.EstadoCuentaAnual;
import cl.duoc.bancoxyz.batch.cuentasanuales.model.MovimientoCuentaAnual;
import cl.duoc.bancoxyz.batch.cuentasanuales.repository.EstadoCuentaAnualRepository;
import cl.duoc.bancoxyz.batch.cuentasanuales.repository.MovimientoCuentaAnualRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Segundo Step del Job 3: compila, para cada cuenta y año, el estado de
 * cuenta anual (totales por tipo de movimiento y saldo neto), cumpliendo
 * el requerimiento "Generación de Estados de Cuenta Anuales: compilar
 * datos anuales para cada cuenta y generar un informe detallado para
 * auditorías".
 */
@Slf4j
@RequiredArgsConstructor
public class EstadoCuentaAnualTasklet implements Tasklet {

    private final MovimientoCuentaAnualRepository movimientoRepository;
    private final EstadoCuentaAnualRepository estadoRepository;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        List<Long> cuentas = movimientoRepository.buscarCuentasDistintas();
        List<Integer> anios = movimientoRepository.buscarAniosDistintos();

        int generados = 0;
        for (Long cuentaId : cuentas) {
            for (Integer anio : anios) {
                List<MovimientoCuentaAnual> movimientos = movimientoRepository.findByCuentaIdAndAnio(cuentaId, anio);
                if (movimientos.isEmpty()) {
                    continue;
                }

                BigDecimal totalDepositos = sumar(movimientos, "deposito");
                BigDecimal totalRetiros = sumar(movimientos, "retiro");
                BigDecimal totalCompras = sumar(movimientos, "compra");
                BigDecimal totalPagos = sumar(movimientos, "pago");
                BigDecimal saldoNeto = totalDepositos.subtract(totalRetiros).subtract(totalCompras).subtract(totalPagos);

                EstadoCuentaAnual estado = new EstadoCuentaAnual(null, cuentaId, anio, totalDepositos, totalRetiros,
                        totalCompras, totalPagos, saldoNeto, movimientos.size(), LocalDateTime.now());
                estadoRepository.save(estado);
                generados++;
            }
        }

        log.info("RESUMEN estados de cuenta anuales -> generados={} (cuentas={}, años={})",
                generados, cuentas.size(), anios.size());
        return RepeatStatus.FINISHED;
    }

    private BigDecimal sumar(List<MovimientoCuentaAnual> movimientos, String tipo) {
        return movimientos.stream()
                .filter(m -> tipo.equals(m.getTipoMovimiento()))
                .map(MovimientoCuentaAnual::getMonto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
