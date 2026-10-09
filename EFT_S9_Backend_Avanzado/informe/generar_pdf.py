#!/usr/bin/env python3
"""Genera informe_tecnico.pdf a partir de informe/informe_tecnico.md.

Markdown -> HTML con estilos de impresion -> PDF con Chromium (Playwright).
Se usa Chromium porque es el unico que rinde igual las tablas anchas, los
bloques de codigo y los diagramas PNG sin pelear con cada motor por separado.

    python3 informe/generar_pdf.py

Deja el resultado en informe_tecnico.pdf, en la raiz del proyecto, que es donde
lo busca el readme.
"""
import re
import sys
from pathlib import Path

import markdown
from playwright.sync_api import sync_playwright

RAIZ = Path(__file__).resolve().parent.parent
FUENTE = RAIZ / "informe" / "informe_tecnico.md"
SALIDA = RAIZ / "informe_tecnico.pdf"

ESTILOS = """
@page { size: A4; margin: 18mm 16mm 18mm 16mm; }
* { box-sizing: border-box; }
body {
  font-family: "DejaVu Serif", Georgia, serif;
  font-size: 10.5pt; line-height: 1.5; color: #1a1a1a; margin: 0;
}
h1 { font-size: 20pt; margin: 0 0 4pt; line-height: 1.2; }
h2 {
  font-size: 14pt; margin: 20pt 0 6pt; padding-bottom: 3pt;
  border-bottom: 1.5px solid #333; page-break-after: avoid;
}
h3 { font-size: 11.5pt; margin: 14pt 0 4pt; page-break-after: avoid; }
h4 { font-size: 10.5pt; margin: 10pt 0 3pt; page-break-after: avoid; }
p { margin: 0 0 7pt; text-align: justify; }
ul, ol { margin: 0 0 7pt; padding-left: 18pt; }
li { margin-bottom: 2pt; }
code {
  font-family: "DejaVu Sans Mono", Consolas, monospace;
  font-size: 8.6pt; background: #f2f2f2; padding: 0.5pt 2.5pt;
  border-radius: 2px;
}
pre {
  background: #f7f7f7; border: 1px solid #ddd; border-left: 3px solid #666;
  padding: 7pt 9pt; overflow-x: hidden; page-break-inside: avoid;
  margin: 0 0 9pt;
}
pre code {
  background: none; padding: 0; font-size: 8.2pt; line-height: 1.38;
  white-space: pre-wrap; word-break: break-word;
}
table {
  border-collapse: collapse; width: 100%; font-size: 8.8pt;
  margin: 0 0 10pt; page-break-inside: avoid;
}
th, td { border: 1px solid #bbb; padding: 3.5pt 5pt; text-align: left; vertical-align: top; }
th { background: #ececec; font-weight: bold; }
tr:nth-child(even) td { background: #fafafa; }
blockquote {
  margin: 0 0 9pt; padding: 5pt 10pt; border-left: 3px solid #999;
  background: #f7f7f7; font-size: 9.8pt;
}
/* max-height mantiene cada diagrama dentro de una pagina: sin esto, los dos
   diagramas verticales se parten en dos y quedan ilegibles. */
img {
  max-width: 100%; max-height: 250mm; width: auto; height: auto;
  display: block; margin: 10pt auto; page-break-inside: avoid;
}
hr { border: 0; border-top: 1px solid #ccc; margin: 14pt 0; }
a { color: #10418a; text-decoration: none; }
strong { color: #000; }

/* Portada: el bloque de autoria bajo el titulo, centrado y sin justificar. */
.portada { text-align: center; margin: 26pt 0 0; }
.portada p { text-align: center; margin: 0 0 9pt; font-size: 11pt; }

/* Salto de pagina explicito: separa la portada del cuerpo del informe. */
.salto { page-break-after: always; }

/* El titulo y el subtitulo de la portada no llevan la regla de las secciones. */
body > h1 { text-align: center; margin-top: 46pt; font-size: 24pt; }
body > h1 + h2 {
  text-align: center; border-bottom: 0; font-size: 14pt;
  font-weight: normal; color: #444; margin: 2pt 0 0;
}
"""


def main() -> int:
    if not FUENTE.exists():
        print(f"no se encontro {FUENTE}", file=sys.stderr)
        return 1

    texto = FUENTE.read_text(encoding="utf-8")

    cuerpo = markdown.markdown(
        texto,
        extensions=["tables", "fenced_code", "toc", "sane_lists",
                    "attr_list", "md_in_html"],
    )

    # Las rutas de los diagramas son relativas a informe/; el HTML temporal se
    # escribe en esa misma carpeta para que resuelvan sin tocar el markdown.
    html = (
        "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\">"
        "<title>Informe tecnico</title>"
        f"<style>{ESTILOS}</style></head><body>{cuerpo}</body></html>"
    )

    temporal = RAIZ / "informe" / "_informe_tecnico.html"
    temporal.write_text(html, encoding="utf-8")

    try:
        with sync_playwright() as pw:
            navegador = pw.chromium.launch()
            pagina = navegador.new_page()
            pagina.goto(temporal.as_uri(), wait_until="load")
            pagina.pdf(
                path=str(SALIDA),
                format="A4",
                print_background=True,
                display_header_footer=True,
                header_template="<div></div>",
                footer_template=(
                    '<div style="width:100%;font-size:8pt;color:#777;'
                    'font-family:Georgia,serif;padding:0 16mm;">'
                    '<span style="float:left">Informe tecnico &middot; '
                    'EFT Desarrollo Backend III (PBY2203) &middot; '
                    'Ignacio Mi&ntilde;o Astorga</span>'
                    '<span style="float:right">'
                    '<span class="pageNumber"></span> / '
                    '<span class="totalPages"></span></span></div>'
                ),
                margin={"top": "18mm", "bottom": "18mm", "left": "16mm", "right": "16mm"},
            )
            navegador.close()
    finally:
        temporal.unlink(missing_ok=True)

    print(f"listo: {SALIDA} ({SALIDA.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
