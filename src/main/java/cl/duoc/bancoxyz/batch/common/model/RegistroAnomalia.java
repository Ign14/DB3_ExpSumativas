package cl.duoc.bancoxyz.batch.common.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Registro genérico de anomalía de datos, compartido por los tres procesos
 * batch. Cada vez que un {@code ItemProcessor} descarta una fila por no
 * cumplir las reglas de consistencia, el {@code SkipListener}
 * correspondiente persiste aquí la evidencia (job de origen, referencia del
 * registro, motivo y detalle), lo que sirve como bitácora de auditoría de
 * calidad de datos.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RegistroAnomalia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nombre del job/proceso que detectó la anomalía. */
    private String proceso;

    /** Identificador o clave del registro origen (id, cuenta_id, etc.). */
    private String referencia;

    /** Motivo de la anomalía (regla de negocio incumplida). */
    private String motivo;

    /** Línea/valor original que provocó el descarte, para trazabilidad. */
    private String detalle;

    private LocalDateTime fechaDeteccion;

    public static RegistroAnomalia de(String proceso, String referencia, String motivo, String detalle) {
        return new RegistroAnomalia(null, proceso, referencia, motivo, detalle, LocalDateTime.now());
    }
}
