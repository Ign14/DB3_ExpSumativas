package cl.duoc.bancoxyz.bff.movimientos.web;

import cl.duoc.bancoxyz.bff.common.dto.TransaccionDiariaResumenDTO;
import cl.duoc.bancoxyz.bff.movimientos.domain.TransaccionDiariaRepositoryEnMemoria;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/banco/transacciones-diarias")
public class TransaccionDiariaController {

    private final TransaccionDiariaRepositoryEnMemoria repositorio;

    public TransaccionDiariaController(TransaccionDiariaRepositoryEnMemoria repositorio) {
        this.repositorio = repositorio;
    }

    @GetMapping("/resumen")
    public TransaccionDiariaResumenDTO resumen() {
        return repositorio.resumen();
    }
}
