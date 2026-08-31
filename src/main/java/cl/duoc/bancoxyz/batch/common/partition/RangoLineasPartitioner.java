package cl.duoc.bancoxyz.batch.common.partition;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.core.io.Resource;
import org.springframework.batch.item.ExecutionContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Particionador reutilizado por los tres Jobs. Cuenta las líneas de datos
 * del archivo CSV de entrada (sin contar el encabezado) y las reparte en
 * {@code gridSize} rangos [startLine, endLine) de tamaño equilibrado.
 * <p>
 * Cada partición se entrega como un {@link ExecutionContext} con las claves
 * {@code startLine} / {@code endLine}, que el reader step-scoped de cada
 * proceso usa para leer únicamente su porción del archivo (ver
 * {@link RangoLineasFlatFileItemReader}). Este es el mismo mecanismo descrito
 * en la guía de la semana ("el Partitioner crea un conjunto de
 * ExecutionContext que define rangos de datos mediante start y end").
 */
@Slf4j
public class RangoLineasPartitioner implements Partitioner {

    private final Resource resource;

    public RangoLineasPartitioner(Resource resource) {
        this.resource = resource;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        int totalLineasDatos = contarLineasDeDatos();
        int tamanoParticion = (int) Math.ceil((double) totalLineasDatos / gridSize);

        Map<String, ExecutionContext> particiones = new LinkedHashMap<>();
        int inicio = 0;
        int numero = 0;
        while (inicio < totalLineasDatos) {
            int fin = Math.min(inicio + tamanoParticion, totalLineasDatos);
            ExecutionContext contexto = new ExecutionContext();
            contexto.putInt("startLine", inicio);
            contexto.putInt("endLine", fin);
            contexto.putString("nombreParticion", "particion" + numero);
            particiones.put("particion" + numero, contexto);
            log.info("Partición {} -> filas [{}, {})", numero, inicio, fin);
            inicio = fin;
            numero++;
        }
        return particiones;
    }

    private int contarLineasDeDatos() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            long lineas = reader.lines().count();
            // se descuenta 1 por el encabezado del CSV
            return (int) Math.max(0, lineas - 1);
        } catch (IOException e) {
            throw new IllegalStateException("No fue posible leer el archivo de entrada: " + resource, e);
        }
    }
}
