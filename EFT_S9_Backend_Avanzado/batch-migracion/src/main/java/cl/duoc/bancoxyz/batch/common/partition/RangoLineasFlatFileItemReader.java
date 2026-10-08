package cl.duoc.bancoxyz.batch.common.partition;

import org.springframework.batch.item.file.FlatFileItemReader;

/**
 * Extiende {@link FlatFileItemReader} para que cada Step "trabajador"
 * (worker/minion) de una partición lea únicamente el rango de líneas de
 * datos que le fue asignado por {@link RangoLineasPartitioner}.
 * <p>
 * Se le indica cuántas líneas saltar desde el inicio del archivo
 * (encabezado + filas de particiones anteriores) y se corta la lectura una
 * vez alcanzada la cantidad de ítems correspondiente a su rango, de modo
 * que todas las particiones puedan compartir el mismo archivo físico sin
 * solaparse ni repetir registros.
 */
public class RangoLineasFlatFileItemReader<T> extends FlatFileItemReader<T> {

    private final int cantidadItemsAProcesar;
    private int itemsLeidos = 0;

    public RangoLineasFlatFileItemReader(int startLine, int endLine, int lineasEncabezado) {
        this.cantidadItemsAProcesar = endLine - startLine;
        // se saltan las líneas de encabezado más las filas de las particiones anteriores
        setLinesToSkip(lineasEncabezado + startLine);
    }

    @Override
    protected T doRead() throws Exception {
        if (itemsLeidos >= cantidadItemsAProcesar) {
            return null;
        }
        T item = super.doRead();
        if (item != null) {
            itemsLeidos++;
        }
        return item;
    }
}
