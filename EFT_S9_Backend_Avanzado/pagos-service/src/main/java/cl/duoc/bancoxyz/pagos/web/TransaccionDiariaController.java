package cl.duoc.bancoxyz.pagos.web;

import cl.duoc.bancoxyz.common.dto.TransaccionDiariaResumenDTO;
import cl.duoc.bancoxyz.pagos.domain.TransaccionDiariaRepositoryEnMemoria;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transacciones-diarias")
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
