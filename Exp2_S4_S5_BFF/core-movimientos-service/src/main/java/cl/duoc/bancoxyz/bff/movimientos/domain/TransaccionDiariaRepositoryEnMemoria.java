package cl.duoc.bancoxyz.bff.movimientos.domain;

import cl.duoc.bancoxyz.bff.common.dto.TransaccionDiariaResumenDTO;
import cl.duoc.bancoxyz.bff.movimientos.util.FechaLegacyParser;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Repository;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Carga transacciones.csv: el log de transacciones diarias del banco, que a
 * diferencia de cuentas_anuales.csv no esta asociado a una cuenta especifica.
 * Se expone como un agregado unico (no por cuenta) que alimenta, por
 * ejemplo, un panel administrativo del BFF Web.
 */
@Repository
public class TransaccionDiariaRepositoryEnMemoria {

    private static final Logger log = LoggerFactory.getLogger(TransaccionDiariaRepositoryEnMemoria.class);
    private static final Set<String> TIPOS_VALIDOS = Set.of("credito", "debito");

    private long cantidadTotal;
    private BigDecimal montoTotal = BigDecimal.ZERO;
    private long cantidadCreditos;
    private long cantidadDebitos;
    private BigDecimal montoCreditos = BigDecimal.ZERO;
    private BigDecimal montoDebitos = BigDecimal.ZERO;

    @PostConstruct
    void cargarDatos() {
        int leidas = 0;
        int omitidas = 0;
        Resource recurso = new PathMatchingResourcePatternResolver().getResource("classpath:data/transacciones.csv");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(recurso.getInputStream(), StandardCharsets.UTF_8))) {
            String linea = reader.readLine(); // encabezado
            while ((linea = reader.readLine()) != null) {
                if (linea.isBlank()) {
                    continue;
                }
                leidas++;
                if (procesar(linea)) {
                    cantidadTotal++;
                } else {
                    omitidas++;
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo cargar transacciones.csv", ex);
        }
        log.info(">> core-movimientos-service: {} transacciones diarias leidas = {} validas + {} omitidas por datos inconsistentes",
                leidas, cantidadTotal, omitidas);
    }

    private boolean procesar(String linea) {
        String[] campos = linea.split(",", -1);
        if (campos.length < 4) {
            return false;
        }
        try {
            if (FechaLegacyParser.parsear(campos[1]).isEmpty()) {
                return false;
            }
            String montoTexto = campos[2].trim();
            if (montoTexto.isBlank()) {
                return false;
            }
            BigDecimal monto = new BigDecimal(montoTexto);
            if (monto.signum() <= 0) {
                return false;
            }
            String tipo = campos[3].trim().toLowerCase();
            if (!TIPOS_VALIDOS.contains(tipo)) {
                return false;
            }
            montoTotal = montoTotal.add(monto);
            if ("credito".equals(tipo)) {
                cantidadCreditos++;
                montoCreditos = montoCreditos.add(monto);
            } else {
                cantidadDebitos++;
                montoDebitos = montoDebitos.add(monto);
            }
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    public TransaccionDiariaResumenDTO resumen() {
        return new TransaccionDiariaResumenDTO(
                cantidadTotal, montoTotal, cantidadCreditos, cantidadDebitos, montoCreditos, montoDebitos);
    }
}
