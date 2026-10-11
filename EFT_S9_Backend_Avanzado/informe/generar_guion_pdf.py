#!/usr/bin/env python3
"""Genera guion_video_resumido.pdf: la chuleta de grabacion, para imprimir.

Estilo distinto al del informe a proposito. Esto no se lee de corrido en un
escritorio: se mira de reojo mientras se graba, asi que la letra es mas grande,
los tramos se separan con bandas de tiempo visibles y cada punto entra en una
o dos lineas.

    python3 informe/generar_guion_pdf.py
"""
import sys
from pathlib import Path

import markdown
from playwright.sync_api import sync_playwright

RAIZ = Path(__file__).resolve().parent.parent
FUENTE = RAIZ / "informe" / "guion_video_resumido.md"
SALIDA = RAIZ / "informe" / "guion_video_resumido.pdf"

ESTILOS = """
@page { size: A4; margin: 14mm 14mm 14mm 14mm; }
* { box-sizing: border-box; }
body {
  font-family: "DejaVu Sans", Verdana, sans-serif;
  font-size: 11.5pt; line-height: 1.45; color: #111; margin: 0;
}

h1 { font-size: 19pt; margin: 0 0 2pt; letter-spacing: -0.2pt; }
h1 + p { margin: 0 0 3pt; font-size: 10.5pt; color: #444; }

/* Cada tramo abre con su banda de tiempo: es lo que se busca de un vistazo. */
h2 {
  font-size: 13pt; margin: 16pt 0 7pt; padding: 5pt 9pt;
  background: #1f1f1f; color: #fff; border-radius: 3px;
  page-break-after: avoid; page-break-inside: avoid;
}

h3 { font-size: 11.5pt; margin: 10pt 0 4pt; page-break-after: avoid; }

p { margin: 0 0 6pt; }

/* "Pantalla:" y "Cambiar a:" son instrucciones de camara, no texto hablado. */
p > strong:first-child { color: #000; }

ul { margin: 0 0 8pt; padding-left: 16pt; }
li { margin-bottom: 4.5pt; page-break-inside: avoid; }
li > strong { background: #ffeeb0; padding: 0 2px; }

ol { margin: 0 0 8pt; padding-left: 18pt; }
ol li { margin-bottom: 2.5pt; }

code {
  font-family: "DejaVu Sans Mono", Consolas, monospace;
  font-size: 9.5pt; background: #eee; padding: 0.5pt 3pt; border-radius: 2px;
}
pre {
  background: #f6f6f6; border-left: 3px solid #999; padding: 6pt 8pt;
  margin: 0 0 8pt; page-break-inside: avoid;
}
pre code { background: none; padding: 0; font-size: 8pt; line-height: 1.35; }

blockquote {
  margin: 0 0 10pt; padding: 6pt 10pt; border-left: 3px solid #c00;
  background: #fff4f4; font-size: 10.5pt;
}

hr { border: 0; border-top: 1px dashed #bbb; margin: 10pt 0; }
em { color: #666; }
"""


def main() -> int:
    if not FUENTE.exists():
        print(f"no se encontro {FUENTE}", file=sys.stderr)
        return 1

    cuerpo = markdown.markdown(
        FUENTE.read_text(encoding="utf-8"),
        extensions=["tables", "fenced_code", "sane_lists"],
    )
    html = (
        '<!doctype html><html lang="es"><head><meta charset="utf-8">'
        "<title>Guion de grabacion</title>"
        f"<style>{ESTILOS}</style></head><body>{cuerpo}</body></html>"
    )

    temporal = RAIZ / "informe" / "_guion.html"
    temporal.write_text(html, encoding="utf-8")
    try:
        with sync_playwright() as pw:
            nav = pw.chromium.launch()
            pagina = nav.new_page()
            pagina.goto(temporal.as_uri(), wait_until="load")
            pagina.pdf(
                path=str(SALIDA), format="A4", print_background=True,
                display_header_footer=True, header_template="<div></div>",
                footer_template=(
                    '<div style="width:100%;font-size:8pt;color:#888;'
                    'font-family:Verdana,sans-serif;padding:0 14mm;">'
                    '<span style="float:left">Guion de grabaci&oacute;n &middot; '
                    'EFT Backend III</span>'
                    '<span style="float:right"><span class="pageNumber"></span>'
                    ' / <span class="totalPages"></span></span></div>'
                ),
                margin={"top": "14mm", "bottom": "14mm", "left": "14mm", "right": "14mm"},
            )
            nav.close()
    finally:
        temporal.unlink(missing_ok=True)

    print(f"listo: {SALIDA} ({SALIDA.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
