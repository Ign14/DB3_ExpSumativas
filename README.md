# Desarrollo Backend III (PBY2203) — Actividades sumativas

Repositorio con las entregas individuales sumativas del curso, organizadas
en una carpeta por experiencia de aprendizaje:

| Carpeta | Experiencia | Semana(s) | Tema |
|---|---|---|---|
| [`Exp1_S3_SpringBatch/`](./Exp1_S3_SpringBatch) | Exp1 | Semana 3 | Migración de procesos batch legacy con Spring Batch (particionamiento, tolerancia a fallos) |
| [`Exp2_S4_S5_BFF/`](./Exp2_S4_S5_BFF) | Exp2 | Semanas 4 y 5 | Patrón arquitectónico Backend for Frontend (BFF): un backend dedicado por canal (web, móvil, cajero) |
| [`Exp3_S6_S8_Microservicios/`](./Exp3_S6_S8_Microservicios) | Exp3 | Semanas 6, 7 y 8 | Microservicios en la nube con Spring Cloud: configuración centralizada, service discovery, OAuth 2.0 con JWT, Resilience4j, mensajería asíncrona JMS y despliegue con Docker |

Las tres experiencias usan el mismo dataset legacy del Banco XYZ
([`KariVillagran/bank_legacy_data`](https://github.com/KariVillagran/bank_legacy_data)),
así que se pueden comparar entre sí: lo que cambia de una a otra es la
arquitectura con la que se resuelve el mismo problema de negocio, no el
problema.

Cada carpeta es un proyecto Maven independiente y autocontenido, con su
propio código fuente, `README.md` (objetivo, estructura, cómo ejecutar) y
carpeta `evidencia/` con la evidencia de ejecución correspondiente. No
comparten dependencias entre sí ni necesitan ejecutarse juntos.

El documento
[`Exp3_S6_S8_Microservicios/RESUMEN_APRENDIZAJES_S1_S8.md`](./Exp3_S6_S8_Microservicios/RESUMEN_APRENDIZAJES_S1_S8.md)
recorre lo aprendido en las ocho semanas del curso y cómo cada experiencia se
apoya en la anterior.
